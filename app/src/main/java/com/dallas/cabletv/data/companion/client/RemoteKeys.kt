package com.dallas.cabletv.data.companion.client

/**
 * A key on the phone's remote. [companionName] is its name in the companion protocol, or null for
 * keys the TV app cannot press itself (Home, power and volume). [atvCode] is its Android key code,
 * which the Android TV remote service takes as it is.
 */
data class RemoteKey(val companionName: String?, val atvCode: Int) {
    companion object {
        val UP = RemoteKey("DPAD_UP", 19)
        val DOWN = RemoteKey("DPAD_DOWN", 20)
        val LEFT = RemoteKey("DPAD_LEFT", 21)
        val RIGHT = RemoteKey("DPAD_RIGHT", 22)
        val OK = RemoteKey("DPAD_CENTER", 23)
        val BACK = RemoteKey("BACK", 4)
        val GUIDE = RemoteKey("GUIDE", 172)
        val INFO = RemoteKey("INFO", 165)
        val LAST = RemoteKey("LAST_CHANNEL", 229)
        val CHANNEL_UP = RemoteKey("CHANNEL_UP", 166)
        val CHANNEL_DOWN = RemoteKey("CHANNEL_DOWN", 167)
        val PLAY_PAUSE = RemoteKey("MEDIA_PLAY_PAUSE", 85)

        /** Only the Android TV remote can press these. */
        val HOME = RemoteKey(null, 3)
        val POWER = RemoteKey(null, 26)
        val VOLUME_UP = RemoteKey(null, 24)
        val VOLUME_DOWN = RemoteKey(null, 25)
        val MUTE = RemoteKey(null, 164)

        /** The number key for [d], 0 to 9. KEYCODE_0 to KEYCODE_9 are 7 to 16. */
        fun digit(d: Int): RemoteKey {
            require(d in 0..9) { "A digit is 0 to 9" }
            return RemoteKey(d.toString(), 7 + d)
        }
    }
}

/** Where a key press goes. */
sealed interface KeyRoute {
    /** Through the Android TV remote, which works in every app. */
    data class Atv(val code: Int) : KeyRoute

    /** Through the companion connection to the Cable TV app. */
    data class Cable(val name: String) : KeyRoute

    /** Nothing can press this key right now. */
    data object Unavailable : KeyRoute
}

/**
 * Keys go through the Android TV remote when it is connected, because that works in every app, and
 * through the companion connection otherwise. Keys only the Android TV remote can press are
 * unavailable without it.
 */
fun routeKey(key: RemoteKey, atvConnected: Boolean): KeyRoute = when {
    atvConnected -> KeyRoute.Atv(key.atvCode)
    key.companionName != null -> KeyRoute.Cable(key.companionName)
    else -> KeyRoute.Unavailable
}
