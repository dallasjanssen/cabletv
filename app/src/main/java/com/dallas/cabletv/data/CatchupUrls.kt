package com.dallas.cabletv.data

/**
 * Catch-up address for a playlist channel.
 * Templates use the placeholders providers actually ship: ${start}, ${end}, ${duration},
 * ${timestamp}, ${offset}, {utc} and {lutc}. Start and end are unix seconds.
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
        return fillTemplate(template, streamUrl, startSec, endSec, durationSec, offsetSec, startMs)
    }
    val type = catchupType?.trim()?.lowercase().orEmpty()
    return when (type) {
        "shift" -> withQuery(streamUrl, "shift" to offsetSec.toString())
        "flussonic", "fs" -> flussonicArchive(streamUrl, startSec, durationSec)
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
    startMs: Long
): String {
    val values = mapOf(
        "start" to startSec.toString(),
        "end" to endSec.toString(),
        "duration" to durationSec.toString(),
        "offset" to offsetSec.toString(),
        "timestamp" to startSec.toString(),
        "utc" to startSec.toString(),
        "lutc" to endSec.toString(),
        "startms" to startMs.toString()
    )
    var out = template
    values.forEach { (key, value) ->
        out = out.replace("\${$key}", value, ignoreCase = true)
        out = out.replace("{$key}", value, ignoreCase = true)
    }
    if (out.contains("{url}", ignoreCase = true)) {
        out = out.replace("{url}", streamUrl, ignoreCase = true)
    }
    return out
}

private fun flussonicArchive(streamUrl: String, startSec: Long, durationSec: Long): String {
    val noQuery = streamUrl.substringBefore("?")
    val archive = noQuery
        .removeSuffix("/")
        .replace(Regex("""/(?:index|mono|video)\.m3u8$""", RegexOption.IGNORE_CASE), "/")
        .trimEnd('/') + "/archive-$startSec-$durationSec.m3u8"
    return archive
}

private fun withQuery(url: String, vararg pairs: Pair<String, String>): String {
    val joiner = if (url.contains("?")) "&" else "?"
    return url + joiner + pairs.joinToString("&") { (k, v) -> "$k=$v" }
}
