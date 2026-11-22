package com.dallas.cabletv.data

data class LiveChannel(
    val id: String,
    val number: Int,
    val name: String,
    val logo: String?,
    val group: String,
    val streamUrl: String,
    val epgId: String?
)

data class Program(
    val title: String,
    val start: Long,
    val end: Long,
    val description: String? = null,
    val rating: String? = null,
    val category: String? = null
) {
    fun contains(timeMs: Long): Boolean = timeMs in start until end
    val durationMs: Long get() = (end - start).coerceAtLeast(0L)
}

data class NowNext(
    val now: Program?,
    val next: Program?
)

data class VodItem(
    val id: String,
    val name: String,
    val year: Int?,
    val overview: String?,
    val posterUrl: String,
    val streamUrl: String,
    val type: String,
    val seriesId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val resumeTicks: Long = 0L,
    val runtimeTicks: Long = 0L,
    val mediaSourceId: String? = null,
    val genres: List<String> = emptyList()
)

/** Ticks are Jellyfin's 100-nanosecond units. */
const val TICKS_PER_MS = 10_000L

data class Settings(
    val xtreamServer: String = "",
    val xtreamUser: String = "",
    val xtreamPass: String = "",
    val m3uUrl: String = "",
    val epgUrl: String = "",
    val jellyfinServer: String = "",
    val jellyfinToken: String = "",
    val jellyfinUserId: String = "",
    val jellyfinTranscodeFallback: Boolean = true
) {
    val hasXtream: Boolean
        get() = xtreamServer.isNotBlank() && xtreamUser.isNotBlank() && xtreamPass.isNotBlank()

    val hasLiveSource: Boolean
        get() = hasXtream || m3uUrl.isNotBlank()

    val hasJellyfin: Boolean
        get() = jellyfinServer.isNotBlank() && jellyfinToken.isNotBlank() && jellyfinUserId.isNotBlank()

    /** The key that decides when the live channel list must be reloaded. */
    val liveSourceKey: String
        get() = listOf(xtreamServer, xtreamUser, xtreamPass, m3uUrl).joinToString("|")

    /** The key that decides when the guide must be reloaded. */
    val epgKey: String
        get() = listOf(xtreamServer, xtreamUser, xtreamPass, epgUrl).joinToString("|")
}

/** One row of the Jellyfin library browser. Tabs map to these. */
enum class VodTab(val label: String, val types: String) {
    Movies("MOVIES", "Movie"),
    Shows("SHOWS", "Series"),
    Search("SEARCH", "Movie,Series")
}
