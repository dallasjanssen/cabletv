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
    val catchup: Boolean = false,
    /** Days of archive the provider keeps. Zero when it did not say; replay then uses one day. */
    val archiveDays: Int = 0,
    /** MPEG-TS address tried once when [streamUrl] is refused before playback starts. */
    val fallbackStreamUrl: String? = null,
    /** How a playlist asks for catch-up: append, shift, flussonic, or a template in [catchupSource]. */
    val catchupType: String? = null,
    /** Playlist catch-up template, when the channel has one. */
    val catchupSource: String? = null,
    /** Where the channel was loaded from: xtream, playlist, or stalker. */
    val origin: String = "xtream"
)

data class Program(
    val title: String,
    val start: Long,
    val end: Long,
    /** The guide's description of the programme, when the feed has one. */
    val desc: String = ""
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
    val played: Boolean = false,
    /** A wide image for the details page, when the source has one. */
    val backdropUrl: String? = null,
    /** Season and episode numbers for episodes. */
    val season: Int? = null,
    val episode: Int? = null,
    /** The show an episode belongs to. */
    val seriesName: String? = null,
    val runtimeMinutes: Int? = null,
    /** How much has been watched, from 0 to 1, when known. */
    val progress: Float? = null,
    /** Provider or library category, used to browse and to apply the parental lock. */
    val category: String = ""
)

data class Settings(
    val xtreamServer: String = "",
    val xtreamUser: String = "",
    val xtreamPass: String = "",
    val m3uUrl: String = "",
    /** XMLTV guide address from the provider, used as well as any guide the login already includes. */
    val epgUrl: String = "",
    val jellyfinServer: String = "",
    val jellyfinToken: String = "",
    val jellyfinUserId: String = "",
    /** Comma-separated prefixes and category names to hide from the guide and live TV. */
    val hiddenFilters: String = "",
    /** Shows channel names without their country prefix, such as "UK| BBC One" as "BBC One". */
    val stripPrefixes: Boolean = false,
    /** Groups and categories that ask for the PIN before they play. Same matching rules as [hiddenFilters]. */
    val lockedFilters: String = "",
    /** Stalker / Ministra portal address. */
    val stalkerUrl: String = "",
    /** MAC address for the Stalker portal. */
    val stalkerMac: String = "",
    /** Provider expiry, unix milliseconds. Zero when the login did not say. */
    val subscriptionExpiryMs: Long = 0L
) {
    /** The hidden filters, trimmed and upper-cased. */
    val hiddenTerms: List<String>
        get() = hiddenFilters.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }

    val lockedTerms: List<String>
        get() = lockedFilters.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }

    val hasXtream: Boolean
        get() = xtreamServer.isNotBlank() && xtreamUser.isNotBlank() && xtreamPass.isNotBlank()

    val hasStalker: Boolean
        get() = stalkerUrl.isNotBlank() && stalkerMac.isNotBlank()

    val hasLiveSource: Boolean
        get() = hasXtream || m3uUrl.isNotBlank() || hasStalker

    val hasJellyfin: Boolean
        get() = jellyfinServer.isNotBlank() && jellyfinToken.isNotBlank() && jellyfinUserId.isNotBlank()
}

/** A channel or title needs the PIN when a lock term matches and a PIN is set. */
fun isLocked(name: String, group: String, terms: List<String>, pinSet: Boolean): Boolean =
    pinSet && terms.isNotEmpty() && isHidden(
        LiveChannel(id = "", number = 0, name = name, logo = null, group = group, streamUrl = "", epgId = null),
        terms
    )
