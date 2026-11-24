package com.dallas.cabletv.data.atvremote

/**
 * The remote messages (remotemessage.proto). Each is a RemoteMessage with one field set.
 */
object RemoteWire {
    /** Feature flags the remote service and a client agree on. */
    const val FEATURE_PING = 1
    const val FEATURE_KEY = 2
    const val FEATURE_IME = 4
    const val FEATURE_POWER = 32
    const val FEATURE_VOLUME = 64
    const val FEATURE_APP_LINK = 512

    /**
     * What this client asks for: pings, keys, power, volume and app links, plus the on screen
     * keyboard channel, because that is how the TV tells which app is in front. Voice is not asked for.
     */
    const val WANTED_FEATURES =
        FEATURE_PING or FEATURE_KEY or FEATURE_IME or FEATURE_POWER or FEATURE_VOLUME or FEATURE_APP_LINK

    /** A key press and release, rather than the start or end of a long press. */
    const val DIRECTION_SHORT = 3

    private const val F_CONFIGURE = 1
    private const val F_SET_ACTIVE = 2
    private const val F_ERROR = 3
    private const val F_PING_REQUEST = 8
    private const val F_PING_RESPONSE = 9
    private const val F_KEY_INJECT = 10
    private const val F_IME_KEY_INJECT = 20
    private const val F_SET_VOLUME = 50
    private const val F_START = 40
    private const val F_APP_LINK = 90

    /** The reply to the TV's configure message: the features both sides support, and who we are. */
    fun configureReply(activeFeatures: Int): ByteArray = ProtoWriter()
        .message(F_CONFIGURE) {
            int(1, activeFeatures)
            message(2) {
                int(3, 1)
                string(4, "1")
                string(5, "atvremote")
                string(6, "1.0.0")
            }
        }.toByteArray()

    /** The reply to the TV's set active message. */
    fun setActiveReply(activeFeatures: Int): ByteArray =
        ProtoWriter().message(F_SET_ACTIVE) { int(1, activeFeatures) }.toByteArray()

    /**
     * The reply to a ping: the same number the TV sent. These messages use proto3 rules, where a
     * number that is zero is left out, so a ping of 0 is answered with an empty response.
     */
    fun pingResponse(value: Int): ByteArray =
        ProtoWriter().message(F_PING_RESPONSE) { if (value != 0) int(1, value) }.toByteArray()

    /** A short press of an Android key code. */
    fun key(keyCode: Int): ByteArray = ProtoWriter().message(F_KEY_INJECT) {
        int(1, keyCode)
        int(2, DIRECTION_SHORT)
    }.toByteArray()

    /** A request to open an app link on the TV. */
    fun appLink(link: String): ByteArray =
        ProtoWriter().message(F_APP_LINK) { string(1, link) }.toByteArray()

    /** What the TV sent, in the cases the phone cares about. */
    sealed class Incoming {
        /** The TV's first message. [features] is what the service supports. */
        class Configure(val features: Int) : Incoming()

        class SetActive : Incoming()
        class Ping(val value: Int) : Incoming()

        /** The TV started, so it is ready for keys. [started] is whether it is switched on. */
        class Start(val started: Boolean) : Incoming()

        class Volume(val level: Int, val max: Int, val muted: Boolean) : Incoming()

        /** The app in front, sent with the on screen keyboard information. */
        class CurrentApp(val appPackage: String) : Incoming()

        class Error : Incoming()
        object Other : Incoming()
    }

    /** Reads a message from the TV. Throws [ProtoException] when it is not valid. */
    fun parse(bytes: ByteArray): Incoming {
        var result: Incoming = Incoming.Other
        ProtoReader(bytes).forEachField { f ->
            if (result !== Incoming.Other) return@forEachField
            val body = f.asBytes() ?: return@forEachField
            result = when (f.number) {
                F_CONFIGURE -> Incoming.Configure(firstInt(body, 1))
                F_SET_ACTIVE -> Incoming.SetActive()
                F_ERROR -> Incoming.Error()
                F_PING_REQUEST -> Incoming.Ping(firstInt(body, 1))
                F_IME_KEY_INJECT -> Incoming.CurrentApp(appPackage(body))
                F_START -> Incoming.Start(firstInt(body, 1) != 0)
                F_SET_VOLUME -> volume(body)
                else -> Incoming.Other
            }
        }
        return result
    }

    private fun firstInt(body: ByteArray, number: Int): Int {
        var value = 0
        ProtoReader(body).forEachField { f -> if (f.number == number) f.asInt()?.let { value = it } }
        return value
    }

    private fun volume(body: ByteArray): Incoming.Volume {
        var max = 0
        var level = 0
        var muted = false
        ProtoReader(body).forEachField { f ->
            when (f.number) {
                6 -> f.asInt()?.let { max = it }
                7 -> f.asInt()?.let { level = it }
                8 -> f.asBool()?.let { muted = it }
            }
        }
        return Incoming.Volume(level, max, muted)
    }

    /** The package name inside the key inject message's app info (field 1, package is its field 12). */
    private fun appPackage(body: ByteArray): String {
        var pkg = ""
        ProtoReader(body).forEachField { f ->
            if (f.number == 1) {
                f.asBytes()?.let { info ->
                    ProtoReader(info).forEachField { g -> if (g.number == 12) g.asString()?.let { pkg = it } }
                }
            }
        }
        return pkg
    }

    /** The features this client turns on, given what the TV says it supports. */
    fun activeFeatures(supported: Int): Int = WANTED_FEATURES and supported

    /** The state after a message from the TV. Messages that do not change it return [state] unchanged. */
    fun apply(state: AtvState, message: Incoming): AtvState = when (message) {
        is Incoming.Start -> state.copy(poweredOn = message.started)
        is Incoming.Volume -> state.copy(volumeLevel = message.level, volumeMax = message.max, muted = message.muted)
        is Incoming.CurrentApp -> state.copy(currentApp = message.appPackage.ifEmpty { null })
        else -> state
    }
}
