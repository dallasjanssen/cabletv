package com.dallas.cabletv.data

/** Parses an extended M3U playlist into live channels. Pure Kotlin, so it is unit tested on the JVM. */
object M3uParser {
    private val attr = Regex("(\\w[\\w-]*)=\"([^\"]*)\"")

    fun parse(text: String): List<LiveChannel> {
        val out = mutableListOf<LiveChannel>()
        var attrs: Map<String, String> = emptyMap()
        var title = ""

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    attrs = attr.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                    title = line.substringAfter(',', "").trim()
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val n = out.size + 1
                    out += LiveChannel(
                        id = attrs.nz("tvg-id") ?: line,
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
