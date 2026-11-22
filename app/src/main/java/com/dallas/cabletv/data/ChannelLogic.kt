package com.dallas.cabletv.data

/** Pure helpers for channel lists and guide time math. Kept free of Android types so they can be tested. */
object ChannelLogic {

    const val SLOT_MS = 30L * 60 * 1000
    const val FAVORITES_GROUP = "Favorites"
    const val ALL_GROUP = "All"

    /** Group names shown in the filter bar, with Favorites and All first. */
    fun groups(channels: List<LiveChannel>, favorites: Set<String>): List<String> {
        val names = channels.map { it.group }.distinct().sorted()
        return buildList {
            add(ALL_GROUP)
            if (favorites.isNotEmpty()) add(FAVORITES_GROUP)
            addAll(names)
        }
    }

    /** Applies the selected group and the search text. */
    fun filter(
        channels: List<LiveChannel>,
        group: String,
        favorites: Set<String>,
        search: String
    ): List<LiveChannel> {
        val needle = search.trim().lowercase()
        return channels.filter { ch ->
            val inGroup = when (group) {
                ALL_GROUP -> true
                FAVORITES_GROUP -> ch.id in favorites
                else -> ch.group == group
            }
            val matches = needle.isEmpty() ||
                ch.name.lowercase().contains(needle) ||
                ch.number.toString() == needle
            inGroup && matches
        }
    }

    /** Index of the next channel, wrapping at both ends. */
    fun step(current: Int, delta: Int, size: Int): Int {
        if (size <= 0) return 0
        return ((current + delta) % size + size) % size
    }

    /** Index of the channel with this number, or -1. */
    fun indexOfNumber(channels: List<LiveChannel>, number: Int): Int =
        channels.indexOfFirst { it.number == number }

    /** Rounds a time down to the start of its half hour. */
    fun floorToSlot(timeMs: Long): Long = timeMs - Math.floorMod(timeMs, SLOT_MS)

    /** Formats a number typed on a remote as a channel number, or null if it is empty or too long. */
    fun parseTypedNumber(digits: String): Int? =
        if (digits.isEmpty() || digits.length > 4) null else digits.toIntOrNull()

    /** Progress through a program, 0.0 to 1.0. */
    fun progress(p: Program, nowMs: Long): Float {
        val total = (p.end - p.start).coerceAtLeast(1L)
        return ((nowMs - p.start).toFloat() / total).coerceIn(0f, 1f)
    }
}
