package com.dallas.cabletv.data.companion.client

/** What the Now playing card shows. */
data class NowPlaying(
    val title: String,
    val detail: String?,
    val paused: Boolean,
    val image: String?
)

/** The words for the TV app's screens. */
fun screenLabel(screen: String): String = when (screen) {
    "live" -> "Live TV"
    "home" -> "Home"
    "guide" -> "Guide"
    "on_demand" -> "Movies & Shows"
    "search" -> "Search"
    "settings" -> "Settings"
    "welcome" -> "Welcome"
    "multi" -> "Multi view"
    else -> ""
}

/**
 * The Now playing card's words. [state] is null while the phone is not connected to the TV.
 * With nothing playing the card says so and names the screen the TV is on.
 */
fun nowPlaying(state: TvState?): NowPlaying {
    if (state == null) return NowPlaying("Not connected", null, false, null)
    if (state.isIdle) {
        return NowPlaying("Nothing is on", screenLabel(state.screen).takeIf { it.isNotEmpty() }, false, null)
    }
    val channel = state.channel
    val number = state.channelNumber
    val detail = when {
        channel != null && number != null -> "$channel, channel $number"
        channel != null -> channel
        number != null -> "Channel $number"
        else -> null
    }
    return NowPlaying(state.title.orEmpty(), detail, state.isPaused, state.image)
}

/** True when a press of [key] can be sent now. Companion keys need the TV's connection to be up. */
fun keyAvailable(key: RemoteKey, atvConnected: Boolean, companionOnline: Boolean): Boolean =
    when (routeKey(key, atvConnected)) {
        is KeyRoute.Atv -> true
        is KeyRoute.Cable -> companionOnline
        KeyRoute.Unavailable -> false
    }
