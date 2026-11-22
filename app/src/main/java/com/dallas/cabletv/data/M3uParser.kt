package com.dallas.cabletv.data

/**
 * Parses an extended M3U playlist into live channels. Pure Kotlin, so it is unit tested on the JVM.
 *
 * Channel ids are the stream URL, made unique with a counter when a playlist repeats a URL.
 * The tvg-id is kept separately as the guide key, because many playlists reuse it across
 * HD and SD copies of the same channel.
 */
object M3uParser {
    private val attr = Regex("(\\w[\\w-]*)=\"([^\"]*)\"")

    fun parse(text: String): List<LiveChannel> {
        val out = mutableListOf<LiveChannel>()
        val seenIds = HashSet<String>()
        var attrs: Map<String, String> = emptyMap()
        var title = ""

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    attrs = attr.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                    // Attributes can contain commas, so drop them before looking for the title.
                    title = line.replace(attr, "").substringAfter(',', "").trim()
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val n = out.size + 1
                    var id = line
                    var suffix = 2
                    while (!seenIds.add(id)) {
                        id = "$line#${suffix++}"
                    }
                    out += LiveChannel(
                        id = id,
                        number = attrs["tvg-chno"]?.toIntOrNull() ?: n,
                        name = title.ifBlank { attrs.nz("tvg-name") ?: "Channel $n" },
                        logo = attrs.nz("tvg-logo"),
                        group = attrs.nz("group-title") ?: "Other",
                        streamUrl = line,
                        epgId = attrs.nz("tvg-id")
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
}
