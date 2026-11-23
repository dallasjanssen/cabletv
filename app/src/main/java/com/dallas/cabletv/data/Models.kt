package com.dallas.cabletv.data

data class LiveChannel(
    val id: String,
    val number: Int,
    val name: String,
    val logo: String?,
    val group: String,
    val streamUrl: String,
    val epgId: String?,
    /** True when the provider keeps a replay archive for this channel. */
    val catchup: Boolean = false
)

data class Program(
    val title: String,
    val start: Long,
    val end: Long
)

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
    /** Where the item comes from: "jellyfin" or "xtream". Decides how episodes load. */
    val source: String = "jellyfin",
    /** Date added, used to sort provider titles newest first. */
    val addedAt: Long = 0L,
    /** True when the viewer has finished the title (Jellyfin only). */
    val played: Boolean = false
)

data class Settings(
    val xtreamServer: String = "",
    val xtreamUser: String = "",
    val xtreamPass: String = "",
    val m3uUrl: String = "",
    val jellyfinServer: String = "",
    val jellyfinToken: String = "",
    val jellyfinUserId: String = "",
    /** Comma-separated prefixes and category names to hide from the guide and live TV. */
    val hiddenFilters: String = "",
    /** Shows channel names without their country prefix, such as "UK| BBC One" as "BBC One". */
    val stripPrefixes: Boolean = false
) {
    /** The hidden filters, trimmed and upper-cased. */
    val hiddenTerms: List<String>
        get() = hiddenFilters.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }

    val hasXtream: Boolean
        get() = xtreamServer.isNotBlank() && xtreamUser.isNotBlank() && xtreamPass.isNotBlank()

    val hasLiveSource: Boolean
        get() = hasXtream || m3uUrl.isNotBlank()

    val hasJellyfin: Boolean
        get() = jellyfinServer.isNotBlank() && jellyfinToken.isNotBlank() && jellyfinUserId.isNotBlank()
}
