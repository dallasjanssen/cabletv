package com.dallas.cabletv.data

import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The full programme schedule from an XMLTV feed. The feed comes from the Xtream
 * login, or from the guide link inside an M3U playlist. Programmes from the last day
 * to twelve hours ahead are kept, so catch-up has something to show. Channels are
 * matched by their epg_channel_id, or the playlist's tvg-id.
 */
object EpgCache {

    private const val DAY_MS = 24 * 3_600_000L
    /** Two days ahead, so tomorrow evening is on the guide. */
    const val AHEAD_MS = 48 * 3_600_000L
    /** Two days behind, so catch-up has listings to play. */
    const val BEHIND_MS = 48 * 3_600_000L

    private val lock = Any()
    private var programs: Map<String, List<Program>> = emptyMap()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /** Downloads the feed from an Xtream login and replaces the cache. Runs on a background thread. */
    fun refresh(server: String, user: String, pass: String) {
        val base = normalizeXtreamServerUrl(server)
        refreshUrl("$base/xmltv.php?username=${enc(user)}&password=${enc(pass)}")
    }

    /** Downloads every guide and keeps them together. One feed failing leaves the others in place. */
    fun refreshAll(urls: List<String>) {
        val merged = LinkedHashMap<String, MutableList<Program>>()
        for (url in urls.distinct()) {
            runCatching {
                val req = Request.Builder().url(url).header("User-Agent", PLAYER_USER_AGENT).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("XMLTV returned HTTP ${resp.code}")
                    val body = resp.body ?: error("Empty XMLTV response")
                    parse(body.byteStream()).forEach { (id, list) ->
                        merged.getOrPut(id) { mutableListOf() }.addAll(list)
                    }
                }
            }
        }
        if (merged.isNotEmpty()) {
            synchronized(lock) {
                programs = merged.mapValues { (_, list) -> list.sortedBy { it.start } }
            }
        }
    }

    /** Downloads any XMLTV feed by URL and replaces the cache. Runs on a background thread. */
    fun refreshUrl(url: String) {
        val req = Request.Builder().url(url).header("User-Agent", PLAYER_USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("XMLTV returned HTTP ${resp.code}")
            val body = resp.body ?: error("Empty XMLTV response")
            load(body.byteStream())
        }
    }

    /** Reads an XMLTV feed and replaces the cache with it. */
    fun load(input: InputStream) {
        val parsed = parse(input)
        synchronized(lock) { programs = parsed }
    }

    /** Now and next for a channel from the full schedule, or null when the feed has nothing for it. */
    fun nowNext(epgId: String?): NowNext? {
        if (epgId.isNullOrBlank()) return null
        val list = synchronized(lock) { programs[epgId] } ?: return null
        val now = System.currentTimeMillis()
        val current = list.firstOrNull { now >= it.start && now < it.end }
        val next = list.firstOrNull { it.start > now }
        if (current == null && next == null) return null
        return NowNext(current, next)
    }

    /** Every programme kept for a channel, oldest first. Empty when the feed has nothing for it. */
    fun schedule(epgId: String?): List<Program> {
        if (epgId.isNullOrBlank()) return emptyList()
        return synchronized(lock) { programs[epgId] }.orEmpty()
    }

    /** True once a feed has been loaded, so screens can tell "no data yet" from "no data for this channel". */
    fun hasData(): Boolean = synchronized(lock) { programs.isNotEmpty() }

    /** Shows on now or later whose titles contain [query]. Soonest first. */
    fun programmesMatching(query: String, nowMs: Long = System.currentTimeMillis(), limit: Int = 30): List<Pair<String, Program>> {
        val schedule = synchronized(lock) { programs }
        return matchProgrammes(schedule, query, nowMs, limit)
    }

    /**
     * Programmes that already aired on a channel inside [withinMs], newest first.
     * The feed itself only keeps one day, so a longer archive still shows that day.
     */
    fun pastPrograms(epgId: String?, withinMs: Long = DAY_MS): List<Program> {
        if (epgId.isNullOrBlank()) return emptyList()
        val list = synchronized(lock) { programs[epgId] } ?: return emptyList()
        return finishedWithin(list, System.currentTimeMillis(), withinMs)
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** Reads XMLTV with a streaming parser, keeping only the window around now. */
    internal fun parse(input: InputStream): Map<String, List<Program>> {
        val now = System.currentTimeMillis()
        val from = now - BEHIND_MS
        val to = now + AHEAD_MS
        val stamp = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val out = HashMap<String, MutableList<Program>>()

        val parser = Xml.newPullParser()
        parser.setInput(input, null)

        var channel: String? = null
        var start = 0L
        var stop = 0L
        var title = ""
        var desc = ""
        var inTitle = false
        var inDesc = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> {
                        channel = parser.getAttributeValue(null, "channel")
                        start = parseTime(stamp, parser.getAttributeValue(null, "start"))
                        stop = parseTime(stamp, parser.getAttributeValue(null, "stop"))
                        title = ""
                        desc = ""
                    }
                    // Only the first title and description are used when a programme has several languages.
                    "title" -> inTitle = title.isEmpty()
                    "desc" -> inDesc = desc.isEmpty()
                }
                XmlPullParser.TEXT -> {
                    if (inTitle) title += parser.text
                    if (inDesc) desc += parser.text
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "title" -> inTitle = false
                    "desc" -> inDesc = false
                    "programme" -> {
                        val ch = channel
                        if (ch != null && start > 0L && stop > from && start < to) {
                            out.getOrPut(ch) { mutableListOf() }
                                .add(Program(title.trim(), start, stop, desc.trim()))
                        }
                        channel = null
                    }
                }
            }
            event = parser.next()
        }
        return out.mapValues { (_, list) -> list.sortedBy { it.start } }
    }

    private fun parseTime(stamp: SimpleDateFormat, raw: String?): Long =
        try {
            stamp.parse(raw.orEmpty())?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
}
