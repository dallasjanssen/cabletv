package com.dallas.cabletv.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** True when a playlist lists other playlists rather than media segments. */
fun isHlsMaster(text: String): Boolean = text.contains("#EXT-X-STREAM-INF")

/**
 * The highest-bandwidth variant in a master playlist, resolved against [baseUrl].
 * Returns null when the text is not a master playlist.
 */
fun hlsVariantUrl(text: String, baseUrl: String): String? {
    if (!isHlsMaster(text)) return null
    var bestBandwidth = -1
    var best: String? = null
    val lines = text.lines()
    for (i in lines.indices) {
        val line = lines[i].trim()
        if (!line.startsWith("#EXT-X-STREAM-INF")) continue
        val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim() ?: continue
        if (bandwidth >= bestBandwidth) {
            bestBandwidth = bandwidth
            best = resolveHlsUrl(baseUrl, next)
        }
    }
    return best
}

/** Media segment addresses in a media playlist, in order. */
fun hlsSegments(text: String, baseUrl: String): List<String> =
    text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { resolveHlsUrl(baseUrl, it) }

/** Resolves a playlist reference against the playlist's own address. The base query is ignored. */
fun resolveHlsUrl(baseUrl: String, ref: String): String {
    val r = ref.trim()
    if (r.startsWith("http://", ignoreCase = true) || r.startsWith("https://", ignoreCase = true)) return r
    val resolved = baseUrl.toHttpUrlOrNull()?.resolve(r)?.toString()
    if (resolved != null) return resolved
    val clean = baseUrl.substringBefore('?').substringBefore('#')
    val scheme = clean.substringBefore("://", "http")
    return when {
        r.startsWith("//") -> "$scheme:$r"
        r.startsWith("/") -> {
            val afterScheme = clean.substringAfter("://", clean)
            "$scheme://" + afterScheme.substringBefore('/') + r
        }
        else -> clean.substringBeforeLast('/') + "/" + r
    }
}
