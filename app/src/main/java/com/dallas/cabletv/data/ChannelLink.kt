package com.dallas.cabletv.data

import java.net.URLDecoder

/** The scheme the app answers to. Home Assistant, adb or another app opens these links. */
const val CHANNEL_LINK_SCHEME = "cabletv"

/**
 * What a cabletv:// link asks for.
 *
 *   cabletv://channel/12           channel number 12
 *   cabletv://channel/ESPN         the channel named ESPN
 *   cabletv://channel?number=12    the same, written as a query
 *   cabletv://channel?name=ESPN
 *   cabletv://channel?id=1234      the provider's stream id
 *   cabletv://live                 live TV on the last channel
 *
 * A name in the path keeps a plus sign as it is, so cabletv://channel/Disney+ works. In a query,
 * write a plus sign as %2B.
 */
sealed interface ChannelLink {
    data class Number(val number: Int) : ChannelLink
    data class Name(val name: String) : ChannelLink
    data class Id(val id: String) : ChannelLink
    data object Live : ChannelLink
}

/** Reads a cabletv:// link. Null for any other link, or a channel link with nothing to look for. */
fun parseChannelLink(link: String): ChannelLink? {
    val text = link.trim()
    val schemeEnd = text.indexOf(':')
    if (schemeEnd <= 0 || !text.substring(0, schemeEnd).equals(CHANNEL_LINK_SCHEME, ignoreCase = true)) return null
    val rest = text.substring(schemeEnd + 1).removePrefix("//").substringBefore('#')
    val beforeQuery = rest.substringBefore('?')
    val query = rest.substringAfter('?', "")
    val host = beforeQuery.substringBefore('/').lowercase()
    val path = decodePath(beforeQuery.substringAfter('/', "")).trim().trim('/').trim()
    val params = query.split('&')
        .filter { it.isNotEmpty() }
        .associate { decodeQuery(it.substringBefore('=')).lowercase() to decodeQuery(it.substringAfter('=', "")).trim() }

    return when (host) {
        "live" -> ChannelLink.Live
        "channel", "tune" -> {
            params["number"]?.toIntOrNull()?.let { return ChannelLink.Number(it) }
            params["id"]?.takeIf { it.isNotEmpty() }?.let { return ChannelLink.Id(it) }
            params["name"]?.takeIf { it.isNotEmpty() }?.let { return ChannelLink.Name(it) }
            when {
                path.isEmpty() -> null
                path.length <= 6 && path.all { it.isDigit() } -> ChannelLink.Number(path.toInt())
                else -> ChannelLink.Name(path)
            }
        }
        else -> null
    }
}

/** How a link reads in a message, such as "Channel 12". */
fun describeChannelLink(link: ChannelLink): String = when (link) {
    is ChannelLink.Number -> "Channel ${link.number}"
    is ChannelLink.Name -> link.name
    is ChannelLink.Id -> "That channel"
    ChannelLink.Live -> "Live TV"
}

/**
 * The channel a link points to, from [channels] in list order. A number or id must match exactly.
 * A name ignores case, accents, spaces and punctuation, and the closest match wins:
 * the whole name, then the name as its first words ("ESPN" finds "ESPN HD" before "ESPN2"),
 * then those words anywhere, then any name that starts with it or contains it.
 * Between equally close matches, the shorter name wins, then the one earlier in the list.
 */
fun findLinkedChannel(channels: List<LiveChannel>, link: ChannelLink): LiveChannel? = when (link) {
    is ChannelLink.Number -> channels.firstOrNull { it.number == link.number }
    is ChannelLink.Id -> channels.firstOrNull { it.id == link.id } ?: channels.firstOrNull { it.id.equals(link.id, ignoreCase = true) }
    is ChannelLink.Name -> closestByName(channels, link.name)
    ChannelLink.Live -> null
}

private fun closestByName(channels: List<LiveChannel>, name: String): LiveChannel? {
    val query = foldForSearch(name)
    if (query.isEmpty()) return null
    val queryWords = foldedWords(name)
    var best: LiveChannel? = null
    var bestRank = Int.MAX_VALUE
    var bestLength = Int.MAX_VALUE
    for (ch in channels) {
        // The name as the provider sent it, and without a country prefix such as "US| ".
        var rank = Int.MAX_VALUE
        var length = Int.MAX_VALUE
        for (candidate in listOf(ch.name, displayName(ch.name))) {
            val folded = foldForSearch(candidate)
            val r = nameRank(folded, foldedWords(candidate), query, queryWords)
            if (r < rank || (r == rank && folded.length < length)) {
                rank = r
                length = folded.length
            }
        }
        if (rank < bestRank || (rank == bestRank && length < bestLength)) {
            best = ch
            bestRank = rank
            bestLength = length
        }
    }
    return if (bestRank == Int.MAX_VALUE) null else best
}

private fun nameRank(folded: String, words: List<String>, query: String, queryWords: List<String>): Int = when {
    folded == query -> 0
    queryWords.isNotEmpty() && words.size >= queryWords.size && words.subList(0, queryWords.size) == queryWords -> 1
    queryWords.isNotEmpty() && words.windowed(queryWords.size).any { it == queryWords } -> 2
    folded.startsWith(query) -> 3
    folded.contains(query) -> 4
    else -> Int.MAX_VALUE
}

private val wordBreak = Regex("[^\\p{L}\\p{N}]+")

private fun foldedWords(text: String): List<String> =
    text.split(wordBreak).map { foldForSearch(it) }.filter { it.isNotEmpty() }

/** Percent escapes only. A plus sign in a path is a plus sign. */
private fun decodePath(text: String): String =
    runCatching { URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") }.getOrDefault(text)

/** Form decoding, where a plus sign is a space. */
private fun decodeQuery(text: String): String =
    runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
