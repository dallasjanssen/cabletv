package com.dallas.cabletv.data.atvremote

/** What the TV's remote service reports, as far as the phone can tell. A null means not known yet. */
data class AtvState(
    val connected: Boolean = false,
    val poweredOn: Boolean? = null,
    val currentApp: String? = null,
    val volumeLevel: Int? = null,
    val volumeMax: Int? = null,
    val muted: Boolean? = null
)

/** Why talking to the TV's remote service failed. [reason] is what the phone chooses its message by. */
class AtvRemoteException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { UNREACHABLE, NOT_PAIRED, BAD_CODE, PROTOCOL }
}

/** Android key codes the phone uses. The remote service takes the same numbers. */
object AtvKeys {
    const val HOME = 3; const val BACK = 4
    const val DPAD_UP = 19; const val DPAD_DOWN = 20; const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22; const val DPAD_CENTER = 23
    const val VOLUME_UP = 24; const val VOLUME_DOWN = 25; const val POWER = 26
    const val MEDIA_PLAY_PAUSE = 85; const val VOLUME_MUTE = 164
    const val INFO = 165; const val CHANNEL_UP = 166; const val CHANNEL_DOWN = 167
    const val GUIDE = 172; const val LAST_CHANNEL = 229

    /** KEYCODE_0 to KEYCODE_9 are 7 to 16. */
    fun digit(d: Int): Int = 7 + d
}
