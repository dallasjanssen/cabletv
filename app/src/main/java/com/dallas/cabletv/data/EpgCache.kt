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
    private const val AHEAD_MS = 12 * 3_600_000L

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

    /** Downloads any XMLTV feed by URL and replaces the cache. Runs on a background thread. */
    fun refreshUrl(url: String) {
        val req = Request.Builder().url(url).header("User-Agent", PLAYER_USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("XMLTV returned HTTP ${resp.code}")
            val body = resp.body ?: error("Empty XMLTV response")
            val parsed = parse(body.byteStream())
            synchronized(lock) { programs = parsed }
        }
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
        val from = now - DAY_MS
        val to = now + AHEAD_MS
        val stamp = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val out = HashMap<String, MutableList<Program>>()

        val parser = Xml.newPullParser()
        parser.setInput(input, null)

        var channel: String? = null
        var start = 0L
        var stop = 0L
        var title = ""
        var inTitle = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> {
                        channel = parser.getAttributeValue(null, "channel")
                        start = parseTime(stamp, parser.getAttributeValue(null, "start"))
                        stop = parseTime(stamp, parser.getAttributeValue(null, "stop"))
                        title = ""
                    }
                    // Only the first title is used when a programme has several languages.
                    "title" -> inTitle = title.isEmpty()
                }
                XmlPullParser.TEXT -> if (inTitle) title += parser.text
                XmlPullParser.END_TAG -> when (parser.name) {
                    "title" -> inTitle = false
                    "programme" -> {
                        val ch = channel
                        if (ch != null && start > 0L && stop > from && start < to) {
                            out.getOrPut(ch) { mutableListOf() }
                                .add(Program(title.trim(), start, stop))
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
