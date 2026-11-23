package com.dallas.cabletv.data

/**
 * Catch-up address for a playlist channel.
 * Templates follow Kodi pvr.iptvsimple: {utc} and ${start} are the programme start, {utcend}
 * and ${end} the end (unix seconds), {lutc}, ${now} and ${timestamp} the current time, {duration}
 * the length in seconds, {offset} seconds since the start, and {Y} {m} {d} {H} {M} {S} the
 * start time in UTC. A template that is not a full address is appended to the stream address.
 * Returns null when there is nothing to build from.
 */
fun m3uCatchupUrl(
    streamUrl: String,
    catchupType: String?,
    catchupSource: String?,
    startMs: Long,
    endMs: Long,
    nowMs: Long = System.currentTimeMillis()
): String? {
    if (streamUrl.isBlank() || endMs <= startMs) return null
    val startSec = startMs / 1000
    val endSec = endMs / 1000
    val durationSec = (endSec - startSec).coerceAtLeast(1)
    val offsetSec = ((nowMs - startMs) / 1000).coerceAtLeast(0)
    val template = catchupSource?.trim().orEmpty()
    if (template.isNotEmpty()) {
        return fillTemplate(template, streamUrl, startSec, endSec, durationSec, offsetSec, startMs, nowMs / 1000)
    }
    val type = catchupType?.trim()?.lowercase().orEmpty()
    return when (type) {
        "shift" -> withQuery(streamUrl, "shift" to offsetSec.toString())
        "flussonic", "fs", "flussonic-hls" -> flussonicArchive(streamUrl, startSec, durationSec, "m3u8")
        "flussonic-ts" -> flussonicArchive(streamUrl, startSec, durationSec, "ts")
        "append", "default", "" -> withQuery(streamUrl, "utc" to startSec.toString(), "lutc" to endSec.toString())
        else -> withQuery(streamUrl, "utc" to startSec.toString(), "lutc" to endSec.toString())
    }
}

private fun fillTemplate(
    template: String,
    streamUrl: String,
    startSec: Long,
    endSec: Long,
    durationSec: Long,
    offsetSec: Long,
    startMs: Long,
    nowSec: Long
): String {
    val start = java.time.Instant.ofEpochSecond(startSec).atZone(java.time.ZoneOffset.UTC)
    fun two(n: Int) = String.format(java.util.Locale.US, "%02d", n)
    // Date parts are case sensitive: {m} is the month and {M} the minute.
    val dateParts = mapOf(
        "Y" to String.format(java.util.Locale.US, "%04d", start.year),
        "m" to two(start.monthValue),
        "d" to two(start.dayOfMonth),
        "H" to two(start.hour),
        "M" to two(start.minute),
        "S" to two(start.second)
    )
    val values = mapOf(
        "start" to startSec.toString(),
        "utc" to startSec.toString(),
        "end" to endSec.toString(),
        "utcend" to endSec.toString(),
        "now" to nowSec.toString(),
        "lutc" to nowSec.toString(),
        "timestamp" to nowSec.toString(),
        "duration" to durationSec.toString(),
        "offset" to offsetSec.toString(),
        "startms" to startMs.toString()
    )
    var out = template
    dateParts.forEach { (key, value) ->
        out = out.replace("\${$key}", value).replace("{$key}", value)
    }
    values.forEach { (key, value) ->
        out = out.replace("\${$key}", value, ignoreCase = true)
        out = out.replace("{$key}", value, ignoreCase = true)
    }
    val hasUrl = out.contains("{url}", ignoreCase = true)
    if (hasUrl) {
        out = out.replace("{url}", streamUrl, ignoreCase = true)
    } else if (!out.startsWith("http://", ignoreCase = true) && !out.startsWith("https://", ignoreCase = true)) {
        out = when {
            out.startsWith("?") || out.startsWith("&") -> {
                val joiner = if (streamUrl.contains("?")) "&" else "?"
                streamUrl + joiner + out.substring(1)
            }
            streamUrl.endsWith("/") && out.startsWith("/") -> streamUrl + out.substring(1)
            else -> streamUrl + out
        }
    }
    return out
}

private fun flussonicArchive(streamUrl: String, startSec: Long, durationSec: Long, ext: String): String {
    val noQuery = streamUrl.substringBefore("?")
    return noQuery
        .removeSuffix("/")
        .replace(Regex("""/(?:index|mono|video|mpegts)(?:\.m3u8|\.ts)?$""", RegexOption.IGNORE_CASE), "/")
        .trimEnd('/') + "/archive-$startSec-$durationSec.$ext"
}

private fun withQuery(url: String, vararg pairs: Pair<String, String>): String {
    val joiner = if (url.contains("?")) "&" else "?"
    return url + joiner + pairs.joinToString("&") { (k, v) -> "$k=$v" }
}
