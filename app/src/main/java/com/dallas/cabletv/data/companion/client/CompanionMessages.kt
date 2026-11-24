package com.dallas.cabletv.data.companion.client

import com.dallas.cabletv.data.companion.CompanionProtocol
import com.dallas.cabletv.data.companion.CompanionProtocol.Types
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** The messages the phone sends. Each is built without an id, because the connection numbers them. */
object CompanionRequests {
    fun hello(phoneName: String, appBuild: Int): JSONObject =
        JSONObject().put("type", Types.HELLO).put("phone_name", phoneName).put("app_build", appBuild)

    fun pairStart(phoneName: String): JSONObject =
        JSONObject().put("type", Types.PAIR_START).put("phone_name", phoneName)

    fun pairFinish(proof: String): JSONObject = JSONObject().put("type", Types.PAIR_FINISH).put("proof", proof)

    fun ping(): JSONObject = JSONObject().put("type", Types.PING)

    fun getState(): JSONObject = JSONObject().put("type", Types.GET_STATE)

    fun key(name: String): JSONObject = JSONObject().put("type", Types.KEY).put("key", name)

    fun open(link: String): JSONObject = JSONObject().put("type", Types.OPEN).put("link", link)

    fun getFavorites(): JSONObject = JSONObject().put("type", Types.GET_FAVORITES)

    fun getSettings(pin: String?): JSONObject = withPin(JSONObject().put("type", Types.GET_SETTINGS), pin)

    fun putSettings(pin: String?, settings: JSONObject): JSONObject =
        withPin(JSONObject().put("type", Types.PUT_SETTINGS).put("settings", settings), pin)

    fun exportSync(pin: String?, sections: List<String>): JSONObject =
        withPin(JSONObject().put("type", Types.EXPORT_SYNC).put("sections", JSONArray(sections)), pin)

    fun importSync(pin: String?, data: JSONObject): JSONObject =
        withPin(JSONObject().put("type", Types.IMPORT_SYNC).put("data", data), pin)

    private fun withPin(message: JSONObject, pin: String?): JSONObject {
        if (!pin.isNullOrEmpty()) message.put("pin", pin)
        return message
    }

    /** The link that opens a channel by its provider id, with the id made safe for a link. */
    fun channelLink(id: String): String = "cabletv://channel?id=" + URLEncoder.encode(id, "UTF-8")

    /** The link that opens live TV on the last channel. */
    const val LIVE_LINK = "cabletv://live"
}

/** The TV's answer to hello. */
data class Welcome(
    val paired: Boolean,
    val tvName: String,
    val tvId: String,
    val appBuild: Int,
    val pinSet: Boolean,
    val protocol: Int
) {
    companion object {
        fun fromJson(o: JSONObject) = Welcome(
            paired = o.optBoolean("paired", false),
            tvName = o.optString("tv_name"),
            tvId = o.optString("tv_id"),
            appBuild = o.optInt("app_build", 0),
            pinSet = o.optBoolean("pin_set", false),
            protocol = o.optInt("protocol", 1)
        )
    }
}

/** What the TV says is on screen and playing. A TV with nothing playing sends state "idle". */
data class TvState(
    val screen: String,
    val state: String,
    val kind: String?,
    val title: String?,
    val channel: String?,
    val channelNumber: Int?,
    val channelId: String?,
    val image: String?
) {
    val isIdle: Boolean get() = state == "idle" || title.isNullOrBlank()
    val isPaused: Boolean get() = state == "paused"

    companion object {
        fun fromJson(o: JSONObject) = TvState(
            screen = o.optString("screen"),
            state = o.optString("state", "idle"),
            kind = o.text("kind"),
            title = o.text("title"),
            channel = o.text("channel"),
            channelNumber = if (o.has("channel_number") && !o.isNull("channel_number")) o.optInt("channel_number") else null,
            channelId = o.text("channel_id"),
            image = o.text("image")
        )
    }
}

/** A favorite channel from get_favorites. [number] is 0 when the TV does not know it. */
data class Favorite(val id: String, val name: String, val number: Int, val logo: String?) {
    companion object {
        /** The favorites in a `favorites` reply. Entries without an id are skipped. */
        fun listFrom(reply: JSONObject): List<Favorite> {
            val array = reply.optJSONArray("favorites") ?: return emptyList()
            val out = ArrayList<Favorite>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                out.add(Favorite(id, o.optString("name").ifBlank { id }, o.optInt("number", 0), o.text("logo")))
            }
            return out
        }
    }
}

/** The strings in a JSON array, such as the messages in a `saved` reply. */
fun JSONObject.stringList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
}

private fun JSONObject.text(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

/** Why a call to a TV failed. [message] is plain text that can be shown as it is. */
class CompanionException(
    val kind: Kind,
    message: String,
    /** The error code from the TV, for [Kind.REMOTE]. */
    val code: String? = null,
    /** Tries left, when the TV said so (a wrong pairing code). */
    val attemptsLeft: Int? = null
) : Exception(message) {
    enum class Kind {
        /** Could not connect, or the connection dropped. */
        UNREACHABLE,

        /** The TV showed a different certificate than the one pairing stored. */
        IDENTITY_CHANGED,

        /** The TV did not answer in time. */
        TIMEOUT,

        /** The connection was closed on purpose. */
        CLOSED,

        /** The TV answered with an error message. */
        REMOTE,

        /** The TV sent something this app did not expect. */
        PROTOCOL
    }

    companion object {
        const val IDENTITY_CHANGED_TEXT = "This TV's identity changed. Pair it again."

        fun unreachable(tvName: String) =
            CompanionException(Kind.UNREACHABLE, "Can't reach $tvName. Is Cable TV open on it?")

        fun timeout(tvName: String) =
            CompanionException(Kind.TIMEOUT, "$tvName did not answer. Is Cable TV open on it?")

        fun lost(tvName: String) =
            CompanionException(Kind.UNREACHABLE, "Lost the connection to $tvName. Is Cable TV open on it?")

        fun identityChanged() = CompanionException(Kind.IDENTITY_CHANGED, IDENTITY_CHANGED_TEXT)

        /** The exception for an `error` message from the TV. */
        fun fromError(o: JSONObject): CompanionException {
            val code = o.optString("code")
            return CompanionException(
                Kind.REMOTE,
                remoteText(code, o.optString("message")),
                code,
                if (o.has("attempts_left")) o.optInt("attempts_left") else null
            )
        }

        /** The TV's own sentence when it sent one, and a plain fallback otherwise. */
        fun remoteText(code: String, message: String): String {
            if (message.isNotBlank()) return message
            return when (code) {
                CompanionProtocol.Errors.NOT_PAIRED -> "This TV does not know this phone. Pair it again."
                CompanionProtocol.Errors.PIN_REQUIRED -> "This TV needs its PIN."
                CompanionProtocol.Errors.WRONG_PIN -> "That PIN did not match."
                CompanionProtocol.Errors.PIN_WAIT -> "Too many wrong PINs. Wait a little, then try again."
                CompanionProtocol.Errors.BUSY -> "The TV is busy. Try again in a moment."
                CompanionProtocol.Errors.UNSUPPORTED -> "This TV's app is too old for that. Update Cable TV on it."
                CompanionProtocol.Errors.INVALID -> "The TV did not accept that."
                else -> "Something went wrong on the TV."
            }
        }
    }
}

/** What to tell the person when pairing with the code they typed did not finish. */
fun pairingErrorText(e: CompanionException): String = when (e.code) {
    CompanionProtocol.Errors.WRONG_CODE -> {
        val left = e.attemptsLeft
        when {
            left == null -> "That code did not match. Check the TV and try again."
            left <= 0 -> "That code did not match, and there are no tries left."
            left == 1 -> "That code did not match. 1 try left."
            else -> "That code did not match. $left tries left."
        }
    }
    CompanionProtocol.Errors.CODE_EXPIRED -> "That code has expired. Start again to get a new one."
    CompanionProtocol.Errors.LOCKED -> "Too many wrong codes. Start again to get a new one."
    else -> e.message ?: "Pairing did not finish."
}
