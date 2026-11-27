package com.dallas.cabletv.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Channels that loaded, plus one short message for each source that did not. */
data class ChannelLoad(val channels: List<LiveChannel>, val problems: List<String>)

/** One short plain sentence for a common failure. Falls back to the exception message. */
fun friendlyError(t: Throwable): String {
    var cur: Throwable? = t
    var depth = 0
    while (cur != null && depth < 6) {
        val msg = cur.message.orEmpty()
        val lower = msg.lowercase()
        when {
            cur is java.net.UnknownHostException -> return "The server name could not be found."
            cur is java.net.SocketTimeoutException -> return "The server did not answer."
            cur is java.net.ConnectException -> return "Could not connect to the server."
            cur is javax.net.ssl.SSLException -> return "A secure connection could not be made."
            cur is org.json.JSONException || lower.contains("<html") || lower.contains("<!doctype") ||
                lower.contains("cannot be converted to json") ->
                return "The server sent a web page instead of channel data. Check the address."
            Regex("""\b401\b""").containsMatchIn(msg) && lower.contains("http") ||
                Regex("""\b403\b""").containsMatchIn(msg) && lower.contains("http") ->
                return "The server refused the login."
            Regex("""\b404\b""").containsMatchIn(msg) && lower.contains("http") ->
                return "The server address was not found."
            Regex("""\bhttp 5\d\d\b""").containsMatchIn(lower) -> return "The server had a problem. Try again later."
        }
        cur = cur.cause?.takeIf { it !== cur }
        depth++
    }
    return t.message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
}

/**
 * Chooses where live channels come from. Xtream Codes is preferred because it
 * carries categories and guide data. A plain M3U URL is the fallback.
 */
object ChannelSource {
    /**
     * Every channel the sources provide. Hiding is applied in the app, so ticking a box never re-downloads.
     * Throws, with the problems joined, only when every configured source failed.
     */
    fun load(s: Settings): List<LiveChannel> {
        val report = loadReport(s)
        if (report.channels.isEmpty() && report.problems.isNotEmpty() && report.problems.size >= configuredSources(s)) {
            throw IllegalStateException(report.problems.joinToString(" "))
        }
        return report.channels
    }

    private fun configuredSources(s: Settings): Int =
        (if (s.hasXtream) 1 else 0) + (if (s.m3uUrl.isNotBlank()) 1 else 0) + (if (s.hasStalker) 1 else 0)

    /** Loads each configured source on its own. A failed source adds a short message and the rest still load. */
    fun loadReport(s: Settings): ChannelLoad {
        val all = mutableListOf<LiveChannel>()
        val problems = mutableListOf<String>()
        fun attempt(label: String, block: () -> List<LiveChannel>) {
            try {
                all += block()
            } catch (e: Exception) {
                problems += "$label: ${friendlyError(e)}"
            }
        }
        if (s.hasXtream) attempt("Xtream") {
            XtreamClient(s.xtreamServer, s.xtreamUser, s.xtreamPass).liveChannels()
        }
        if (s.m3uUrl.isNotBlank()) attempt("Playlist") {
            M3uParser.parse(fetchText(s.m3uUrl)).map { it.copy(origin = "playlist") }
        }
        if (s.hasStalker) attempt("Stalker") {
            StalkerClient(s.stalkerUrl, s.stalkerMac).channels()
        }
        return ChannelLoad(all.distinctBy { it.origin + ":" + it.id }, problems)
    }

    /**
     * The XMLTV guide link named in an M3U playlist's first line (url-tvg), or null.
     * Only the first line is read, so this stays cheap even for large playlists.
     */
    fun guideUrl(s: Settings): String? = guideUrls(s).firstOrNull()

    /** Every XMLTV address the saved logins provide. Xtream and a playlist can both contribute. */
    fun guideUrls(s: Settings): List<String> {
        val urls = mutableListOf<String>()
        if (s.hasXtream) {
            val base = normalizeXtreamServerUrl(s.xtreamServer)
            val user = java.net.URLEncoder.encode(s.xtreamUser, "UTF-8").replace("+", "%20")
            val pass = java.net.URLEncoder.encode(s.xtreamPass, "UTF-8").replace("+", "%20")
            urls += "$base/xmltv.php?username=$user&password=$pass"
        }
        if (s.m3uUrl.isNotBlank()) {
            runCatching {
                val req = Request.Builder().url(s.m3uUrl).header("User-Agent", PLAYER_USER_AGENT).build()
                playlistHttp.newCall(req).execute().use { resp ->
                    resp.body?.source()?.readUtf8Line()?.let { M3uParser.guideUrlFrom(it) }
                }
            }.getOrNull()?.let { urls += it }
        }
        urls += epgAddresses(s.epgUrl)
        return urls.distinct()
    }
}

/**
 * What to tell the person after they ask for the guide again.
 * [attempted] is how many guide links were downloaded. [failed] is how many of those did not load.
 * A Stalker portal has no XMLTV file; its short listings are asked for again instead.
 */
fun guideRefreshMessage(attempted: Int, failed: Int, stalker: Boolean): String = when {
    attempted == 0 && stalker -> "Guide updated"
    attempted == 0 -> "No guide link is saved. Add one in Settings, Live TV."
    failed >= attempted -> "The guide did not load"
    failed > 0 -> "Part of the guide did not load"
    else -> "Guide updated"
}

/** Guide addresses typed by hand. Commas and spaces can separate more than one. */
fun epgAddresses(raw: String): List<String> =
    raw.split(',', '\n', '\r', ' ', '\t')
        .map { it.trim() }
        .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
        .distinct()

/**
 * A channel is hidden when its category matches a term exactly, or when its name
 * starts with a term. A term ending in a letter or digit must stop at a word boundary,
 * so "UK" hides "UK| BBC" but not "UKRAINE NEWS".
 */
fun isHidden(ch: LiveChannel, terms: List<String>): Boolean {
    if (terms.isEmpty()) return false
    val name = ch.name.uppercase().trim()
    val group = ch.group.ifBlank { "Other" }.uppercase().trim()
    return terms.any { term ->
        val boundary = term.last().isLetterOrDigit()
        group == term ||
            (name.startsWith(term) &&
                (!boundary || name.length == term.length || !name[term.length].isLetterOrDigit()))
    }
}

/** One checkbox in the hidden-channels list. [term] is what gets stored and matched. */
data class ChannelFilter(val term: String, val label: String, val count: Int)

private val countryPrefix = Regex("^([A-Za-z]{2})\\s*[|:\\-]")

/** The two-letter country code at the start of a name, such as "UK" in "UK| BBC One", or null. */
fun countryPrefixOf(name: String): String? =
    countryPrefix.find(name.trim())?.groupValues?.get(1)?.uppercase()

/**
 * The channel list the guide and the player show. Hidden groups, channels hidden one by one,
 * renamed titles, a hand-picked guide id, and a saved order are applied here.
 */
fun presentedChannels(
    channels: List<LiveChannel>,
    hiddenTerms: List<String>,
    stripPrefixes: Boolean,
    options: PlayerOptions
): List<LiveChannel> {
    val renamed = channels
        .distinctBy { it.id }
        .filterNot { isHidden(it, hiddenTerms) || it.id in options.hiddenIds }
        .map { ch ->
            val name = tidyChannelName(ch.name, stripPrefixes, options.namePrefixes, options.nameSuffixes)
            val epgId = options.epgIdFor(ch.id, ch.epgId)
            if (name == ch.name && epgId == ch.epgId) ch else ch.copy(name = name, epgId = epgId)
        }
    return orderChannels(renamed, options.channelOrder)
}

/** A channel name without its country prefix, for display. Names without one are returned unchanged. */
fun displayName(name: String): String {
    val match = countryPrefix.find(name.trim()) ?: return name
    return name.trim().substring(match.value.length).trim().ifEmpty { name }
}

/**
 * Builds the checkbox lists from the channels the provider sent.
 * Country prefixes come from names such as "UK| BBC". Only prefixes used by two or more
 * channels are offered, so ordinary names are not mistaken for countries.
 */
fun channelFilters(channels: List<LiveChannel>): Pair<List<ChannelFilter>, List<ChannelFilter>> {
    val prefixes = channels
        .mapNotNull { countryPrefixOf(it.name) }
        .groupingBy { it }
        .eachCount()
        .filter { it.value >= 2 }
        .map { (prefix, count) -> ChannelFilter(term = prefix, label = prefix, count = count) }
        .sortedBy { it.term }

    val categories = channels
        .groupingBy { it.group.trim().ifBlank { "Other" } }
        .eachCount()
        .map { (group, count) -> ChannelFilter(term = group.uppercase(), label = group, count = count) }
        .sortedBy { it.label }

    return prefixes to categories
}

private val playlistHttp = OkHttpClient.Builder()
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

fun fetchText(url: String): String {
    val req = Request.Builder().url(url).header("User-Agent", PLAYER_USER_AGENT).build()
    playlistHttp.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) error("Playlist returned HTTP ${resp.code}")
        return resp.body?.string() ?: error("Playlist was empty")
    }
}

object M3uParser {
    private val attr = Regex("(\\w[\\w-]*)=(?:\"([^\"]*)\"|'([^']*)')")
    private val guideAttr = Regex("(?:url-tvg|x-tvg-url)=\"([^\"]+)\"")

    /** The first guide link in a playlist header line, such as #EXTM3U url-tvg="...". */
    fun guideUrlFrom(headerLine: String): String? =
        guideAttr.find(headerLine)?.groupValues?.get(1)
            ?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /** Index of the first comma that is not inside a quoted attribute value, or -1. */
    private fun titleComma(line: String): Int {
        var quote = 0.toChar()
        for (i in line.indices) {
            val c = line[i]
            if (quote != 0.toChar()) {
                if (c == quote) quote = 0.toChar()
            } else if ((c == '"' || c == '\'') && i > 0 && line[i - 1] == '=') {
                quote = c
            } else if (c == ',') {
                return i
            }
        }
        return -1
    }

    /** A stable id from the stream address, so list changes do not move saved favorites. */
    private fun idFor(url: String, seen: MutableMap<String, Int>): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { String.format(java.util.Locale.US, "%02x", it) }.take(16)
        val base = "m3u:$hex"
        val count = (seen[base] ?: 0) + 1
        seen[base] = count
        return if (count == 1) base else "$base#$count"
    }

    fun parse(text: String): List<LiveChannel> {
        val out = mutableListOf<LiveChannel>()
        val seen = mutableMapOf<String, Int>()
        var attrs: Map<String, String> = emptyMap()
        var title = ""

        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    val comma = titleComma(line)
                    val head = if (comma >= 0) line.substring(0, comma) else line
                    attrs = attr.findAll(head).associate {
                        it.groupValues[1] to it.groupValues[2].ifEmpty { it.groupValues[3] }
                    }
                    title = if (comma >= 0) line.substring(comma + 1).trim() else ""
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val n = out.size + 1
                    out += LiveChannel(
                        // Playlists often repeat tvg-id (HD and SD copies), so the id comes from the stream address.
                        id = idFor(line, seen),
                        number = attrs["tvg-chno"]?.toIntOrNull() ?: n,
                        name = title.ifBlank { attrs.nz("tvg-name") ?: "Channel $n" },
                        logo = attrs.nz("tvg-logo"),
                        group = attrs.nz("group-title") ?: "Other",
                        streamUrl = line,
                        epgId = attrs.nz("tvg-id"),
                        catchup = catchupOf(attrs),
                        archiveDays = attrs["catchup-days"]?.toIntOrNull()
                            ?: attrs["timeshift"]?.toIntOrNull()
                            ?: 0,
                        catchupType = attrs.nz("catchup"),
                        catchupSource = attrs.nz("catchup-source"),
                        origin = "playlist"
                    )
                    attrs = emptyMap()
                    title = ""
                }
            }
        }
        return out.sortedBy { it.number }
    }

    private fun Map<String, String>.nz(key: String): String? =
        this[key]?.takeIf { it.isNotBlank() }

    private fun catchupOf(attrs: Map<String, String>): Boolean {
        val type = attrs["catchup"]?.trim()?.lowercase().orEmpty()
        if (type.isNotEmpty() && type != "0" && type != "none") return true
        if (!attrs["catchup-source"].isNullOrBlank()) return true
        val days = attrs["catchup-days"]?.toIntOrNull() ?: attrs["timeshift"]?.toIntOrNull() ?: 0
        return days > 0
    }
}
