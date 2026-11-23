package com.dallas.cabletv.data

import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.URLEncoder
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

/**
 * The full programme schedule from an XMLTV feed. The feed comes from the Xtream
 * login, from the guide link inside an M3U playlist, or from a Guide URL. Programmes
 * from about two days behind to two days ahead are kept, so catch-up has something
 * to show. Channels are matched by their epg_channel_id or the playlist's tvg-id,
 * ignoring case and punctuation. A channel name can also match the guide's display name.
 */
object EpgCache {

    private const val DAY_MS = 24 * 3_600_000L
    /** Two days ahead, so tomorrow evening is on the guide. */
    const val AHEAD_MS = 48 * 3_600_000L
    /** Two days behind, so catch-up has listings to play. */
    const val BEHIND_MS = 48 * 3_600_000L

    private val lock = Any()
    private var programs: Map<String, List<Program>> = emptyMap()
    private var aliases: Map<String, String> = emptyMap()
    private val refreshGeneration = AtomicInteger()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /** Downloads the feed from an Xtream login and replaces the cache. Runs on a background thread. */
    fun refresh(server: String, user: String, pass: String) {
        val base = normalizeXtreamServerUrl(server)
        refreshUrl("$base/xmltv.php?username=${enc(user)}&password=${enc(pass)}")
    }

    /**
     * Downloads every guide and keeps them together. One feed failing leaves the others in place.
     * Returns the addresses that did not load.
     */
    fun refreshAll(urls: List<String>): List<String> {
        val generation = refreshGeneration.incrementAndGet()
        val feeds = mutableListOf<Map<String, List<Program>>>()
        val names = mutableListOf<Pair<String, String>>()
        val failed = mutableListOf<String>()
        var cutShort = false
        for (url in urls.distinct()) {
            runCatching {
                val req = Request.Builder().url(url).header("User-Agent", PLAYER_USER_AGENT).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("XMLTV returned HTTP ${resp.code}")
                    val body = resp.body ?: error("Empty XMLTV response")
                    val parsed = parse(body.byteStream())
                    feeds += parsed.programs
                    names += parsed.names
                    // A feed cut off part way is used, but reported, and does not wipe what it did not reach.
                    if (!parsed.complete) {
                        cutShort = true
                        failed += url
                    }
                }
            }.onFailure { failed += url }
        }
        // Two feeds often carry the same listings, so each show is kept once.
        val fresh = mergeSchedules(feeds)
        // After a cut off feed, channels it did not reach keep their earlier listings.
        val stored = if (cutShort) synchronized(lock) { programs } + fresh else fresh
        if (stored.isNotEmpty() && generation == refreshGeneration.get()) {
            val keys = buildEpgAliases(stored.keys, names)
            synchronized(lock) {
                if (generation == refreshGeneration.get()) {
                    // Kept listings keep their display names too. New names win.
                    aliases = if (cutShort) aliases.filterValues { it in stored } + keys else keys
                    programs = stored
                }
            }
        }
        return failed
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
        synchronized(lock) {
            programs = parsed.programs
            aliases = buildEpgAliases(parsed.programs.keys, parsed.names)
        }
    }

    /** Now and next for a channel from the full schedule, or null when the feed has nothing for it. */
    fun nowNext(epgId: String?, name: String? = null): NowNext? {
        val list = schedule(epgId, name)
        if (list.isEmpty()) return null
        val now = System.currentTimeMillis()
        val current = list.firstOrNull { now >= it.start && now < it.end }
        val next = list.firstOrNull { it.start > now }
        if (current == null && next == null) return null
        return NowNext(current, next)
    }

    /**
     * Every programme kept for a channel, oldest first. Empty when the feed has nothing for it.
     * [name] is used when the id is missing, or when the id's listings do not cover now and the
     * guide's display name does.
     */
    fun schedule(epgId: String?, name: String? = null): List<Program> {
        val now = System.currentTimeMillis()
        return synchronized(lock) { chooseListings(listFor(epgId), listFor(name), now) }
    }

    /** True once a feed has been loaded, so screens can tell "no data yet" from "no data for this channel". */
    fun hasData(): Boolean = synchronized(lock) { programs.isNotEmpty() }

    /**
     * Shows on now or later whose titles contain [query], ignoring case, accents and punctuation.
     * Soonest first. [keys], when given, limits the search to those guide channels.
     */
    fun programmesMatching(
        query: String,
        nowMs: Long = System.currentTimeMillis(),
        limit: Int = 30,
        keys: Set<String>? = null
    ): List<Pair<String, Program>> {
        val schedule = synchronized(lock) { programs }
        return matchProgrammes(schedule, query, nowMs, limit, keys)
    }

    /**
     * The guide channel whose listings [schedule] shows for this channel, or null when there is none.
     * Search uses it, so a show is found on the same channels the guide shows it on.
     */
    fun scheduleKey(epgId: String?, name: String? = null, now: Long = System.currentTimeMillis()): String? =
        synchronized(lock) {
            val idKey = keyFor(epgId)
            val nameKey = keyFor(name)
            val byId = idKey?.let { programs[it] }.orEmpty()
            val byName = nameKey?.let { programs[it] }.orEmpty()
            val chosen = chooseListings(byId, byName, now)
            when {
                chosen.isEmpty() -> null
                chosen === byId -> idKey
                else -> nameKey
            }
        }

    /**
     * Programmes that already aired on a channel inside [withinMs], newest first.
     * The feed keeps about two days, so a longer archive still shows those two days.
     */
    fun pastPrograms(epgId: String?, withinMs: Long = DAY_MS, name: String? = null): List<Program> {
        val list = synchronized(lock) { listFor(epgId).ifEmpty { listFor(name) } }
        return finishedWithin(list, System.currentTimeMillis(), withinMs)
    }

    private fun listFor(key: String?): List<Program> = keyFor(key)?.let { programs[it] }.orEmpty()

    /** The id stored in the schedule for an id or display name, or null. Call while holding [lock]. */
    private fun keyFor(key: String?): String? {
        if (key.isNullOrBlank()) return null
        if (programs.containsKey(key)) return key
        val norm = normalizeEpgKey(key)
        if (norm.isEmpty()) return null
        return aliases[norm]
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /**
     * Reads XMLTV with a streaming parser, keeping only the window around now. A gzip body is inflated first.
     * A feed that breaks part way (a cut off download, a bad character) keeps what was read before the break.
     */
    internal fun parse(input: InputStream): ParsedGuide {
        val now = System.currentTimeMillis()
        val from = now - BEHIND_MS
        val to = now + AHEAD_MS
        val out = HashMap<String, MutableList<Program>>()
        val names = mutableListOf<Pair<String, String>>()
        var complete = true
        try {
            readFeed(input, from, to, out, names)
        } catch (e: Exception) {
            if (out.isEmpty()) throw e
            complete = false
        }
        return ParsedGuide(out.mapValues { (_, list) -> tidySchedule(list, from, to) }, names, complete)
    }

    private fun readFeed(
        input: InputStream,
        from: Long,
        to: Long,
        out: HashMap<String, MutableList<Program>>,
        names: MutableList<Pair<String, String>>
    ) {
        val parser = Xml.newPullParser()
        parser.setInput(ungzipIfNeeded(input), null)

        var channel: String? = null
        var start = 0L
        var stop = 0L
        var title = ""
        var desc = ""
        var inTitle = false
        var inDesc = false
        var inChannel = false
        var channelId: String? = null
        var inDisplay = false
        var display = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> {
                        inChannel = true
                        channelId = parser.getAttributeValue(null, "id")
                        display = ""
                    }
                    "display-name" -> if (inChannel) {
                        inDisplay = true
                        display = ""
                    }
                    "programme" -> {
                        channel = parser.getAttributeValue(null, "channel")
                        start = xmltvTimeMs(parser.getAttributeValue(null, "start"))
                        stop = xmltvTimeMs(parser.getAttributeValue(null, "stop") ?: parser.getAttributeValue(null, "end"))
                        title = ""
                        desc = ""
                    }
                    // Only the first title and description are used when a programme has several languages.
                    "title" -> inTitle = title.isEmpty()
                    "desc" -> inDesc = desc.isEmpty()
                }
                XmlPullParser.TEXT -> {
                    if (inTitle) title += parser.text
                    // Long descriptions are cut short, so a big feed does not fill the memory.
                    if (inDesc && desc.length <= MAX_DESC) desc += parser.text
                    if (inDisplay) display += parser.text
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "title" -> inTitle = false
                    "desc" -> inDesc = false
                    "display-name" -> {
                        val id = channelId
                        if (inChannel && id != null && display.isNotBlank()) names += display.trim() to id
                        inDisplay = false
                        display = ""
                    }
                    "channel" -> inChannel = false
                    "programme" -> {
                        val ch = channel
                        // A programme with no stop time is kept for now. tidySchedule gives it one.
                        val open = stop <= 0L && start > from - OPEN_END_SLACK_MS
                        if (ch != null && start > 0L && start < to && (stop > from || open)) {
                            out.getOrPut(ch) { mutableListOf() }
                                .add(Program(title.trim(), start, if (open) 0L else stop, clipDesc(desc)))
                        }
                        channel = null
                    }
                }
            }
            event = parser.next()
        }
    }
}

/** Longest programme description kept, in characters. */
private const val MAX_DESC = 400
/** A programme with no stop time and no next programme is given half an hour. */
private const val OPEN_END_MS = 30 * 60_000L
/** How far before the kept window a programme with no stop time may start and still be read. */
private const val OPEN_END_SLACK_MS = 12 * 3_600_000L

internal fun clipDesc(raw: String): String {
    val text = raw.trim()
    return if (text.length <= MAX_DESC) text else text.take(MAX_DESC).trimEnd() + "…"
}

/**
 * One programme per start time, sorted. Two programmes on one channel cannot start at the same
 * instant, so a repeat is a duplicate. The one with a description is kept, then the one with a stop time.
 */
internal fun dedupeByStart(list: List<Program>): List<Program> {
    if (list.size < 2) return list
    val out = ArrayList<Program>(list.size)
    for (p in list.sortedBy { it.start }) {
        val last = out.lastOrNull()
        if (last == null || last.start != p.start) {
            out += p
            continue
        }
        val better = (last.desc.isBlank() && p.desc.isNotBlank()) ||
            (last.desc.isBlank() == p.desc.isBlank() && last.end <= 0L && p.end > 0L)
        if (better) out[out.lastIndex] = p
    }
    return out
}

/** Gives a programme with no stop time (end 0) the next programme's start, or half an hour. [sorted] has one programme per start. */
internal fun fillMissingStops(sorted: List<Program>): List<Program> {
    if (sorted.none { it.end <= 0L }) return sorted
    return sorted.mapIndexed { i, p ->
        if (p.end > 0L) p else p.copy(end = sorted.getOrNull(i + 1)?.start ?: (p.start + OPEN_END_MS))
    }
}

/** A channel's programmes from one feed: no duplicates, every stop time filled in, inside [from] to [to]. */
internal fun tidySchedule(list: List<Program>, from: Long, to: Long): List<Program> =
    fillMissingStops(dedupeByStart(list)).filter { it.end > from && it.start < to }

/** Joins several feeds by channel id. A show that two feeds both carry is kept once. */
internal fun mergeSchedules(feeds: List<Map<String, List<Program>>>): Map<String, List<Program>> {
    if (feeds.size == 1) return feeds[0]
    val merged = LinkedHashMap<String, MutableList<Program>>()
    for (feed in feeds) {
        for ((id, list) in feed) merged.getOrPut(id) { mutableListOf() }.addAll(list)
    }
    return merged.mapValues { (_, list) -> dedupeByStart(list) }
}

internal data class ParsedGuide(
    val programs: Map<String, List<Program>>,
    val names: List<Pair<String, String>>,
    /** False when the feed broke part way and only what came before the break was read. */
    val complete: Boolean = true
)

/** Letters and digits only, so `TNT.us` and `tnt us` can meet. Called for every channel, so it avoids a regex. */
internal fun normalizeEpgKey(raw: String): String {
    val out = StringBuilder(raw.length)
    for (c in raw) {
        val l = c.lowercaseChar()
        if (l in 'a'..'z' || l in '0'..'9') out.append(l)
    }
    return out.toString()
}

internal fun epgIdsMatch(channelId: String?, storedId: String): Boolean {
    if (channelId.isNullOrBlank()) return false
    if (channelId == storedId) return true
    val a = normalizeEpgKey(channelId)
    val b = normalizeEpgKey(storedId)
    return a.isNotEmpty() && a == b
}

/**
 * Maps a normalized id or display name to the channel id stored in the schedule.
 * A key that would point at two channels is left out.
 */
internal fun buildEpgAliases(ids: Set<String>, names: List<Pair<String, String>>): Map<String, String> {
    val aliases = HashMap<String, String>()
    val blocked = HashSet<String>()
    fun add(raw: String, id: String) {
        val key = normalizeEpgKey(raw)
        if (key.isEmpty() || key in blocked) return
        val existing = aliases[key]
        if (existing == null) aliases[key] = id
        else if (existing != id) {
            aliases.remove(key)
            blocked += key
        }
    }
    ids.forEach { add(it, it) }
    names.forEach { (name, id) -> add(name, id) }
    return aliases
}

/** The id's listings, unless they miss the current show and the name's listings have it. */
internal fun chooseListings(byId: List<Program>, byName: List<Program>, now: Long): List<Program> {
    if (byId.any { now >= it.start && now < it.end }) return byId
    if (byName.any { now >= it.start && now < it.end }) return byName
    return byId.ifEmpty { byName }
}

private val COMPACT_XMLTV_TIME = Regex("""^(\d{14}|\d{12})(?:\s*([+-]\d{2}:?\d{2}|Z|UTC|GMT))?$""", RegexOption.IGNORE_CASE)
private val ISO_XMLTV_TIME = Regex("""^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})(?:\s*([+-]\d{2}:?\d{2}|Z|UTC|GMT))?$""", RegexOption.IGNORE_CASE)

private val XMLTV_OFFSET = Regex("""^([+-])(\d{2}):?(\d{2})$""")
private val SECONDS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US)
private val MINUTES_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm", Locale.US)
private val ISO_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

/** XMLTV start and stop, with or without seconds. A missing zone is UTC. A time this cannot read is 0. */
internal fun xmltvTimeMs(raw: String?): Long {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return 0L
    COMPACT_XMLTV_TIME.matchEntire(s)?.let { m ->
        val digits = m.groupValues[1]
        return xmltvStamp(digits, if (digits.length == 12) MINUTES_FORMAT else SECONDS_FORMAT, m.groupValues[2])
    }
    ISO_XMLTV_TIME.matchEntire(s)?.let { m ->
        return xmltvStamp("${m.groupValues[1]}T${m.groupValues[2]}", ISO_FORMAT, m.groupValues[3])
    }
    return 0L
}

private fun xmltvStamp(text: String, format: DateTimeFormatter, zoneRaw: String): Long = try {
    val local = LocalDateTime.parse(text, format)
    local.toInstant(xmltvOffset(zoneRaw)).toEpochMilli()
} catch (e: DateTimeException) {
    0L
}

private fun xmltvOffset(raw: String): ZoneOffset {
    val z = raw.trim()
    if (z.isEmpty() || z.equals("Z", true) || z.equals("UTC", true) || z.equals("GMT", true)) return ZoneOffset.UTC
    val m = XMLTV_OFFSET.matchEntire(z) ?: return ZoneOffset.UTC
    val sign = if (m.groupValues[1] == "-") -1 else 1
    val seconds = sign * (m.groupValues[2].toInt() * 3600 + m.groupValues[3].toInt() * 60)
    return ZoneOffset.ofTotalSeconds(seconds)
}

/** A guide file downloaded without a gzip header is still gzip when it starts with the gzip magic. */
internal fun ungzipIfNeeded(input: InputStream): InputStream {
    val src = input.buffered()
    src.mark(4)
    val b0 = src.read()
    val b1 = src.read()
    src.reset()
    return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(src) else src
}
