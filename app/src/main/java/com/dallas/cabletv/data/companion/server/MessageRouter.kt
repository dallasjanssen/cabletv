package com.dallas.cabletv.data.companion.server

import com.dallas.cabletv.data.companion.CompanionProtocol
import com.dallas.cabletv.data.companion.CompanionProtocol.Errors
import com.dallas.cabletv.data.companion.CompanionProtocol.Types
import com.dallas.cabletv.data.DebugLog
import com.dallas.cabletv.data.parseChannelLink
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the router knows about one connection: the certificates it presented in the TLS
 * handshake. [phoneKey] is the phone's fingerprint in hex, which is what pairing stores.
 */
class ConnectionInfo(val tvFingerprint: ByteArray, val phoneFingerprint: ByteArray) {
    val phoneKey: String = CompanionProtocol.toHex(phoneFingerprint)
}

/**
 * Answers one message from a phone. It knows the rules of the protocol: who may send what before
 * and after pairing, the PIN rules, and the shape of every reply. Everything it does to the TV goes
 * through [backend]. Whether a phone is paired is looked up on every message, so removing a phone
 * takes effect at once. Safe to call from several connection threads.
 */
class MessageRouter(
    private val backend: CompanionBackend,
    private val pairing: PairingManager,
    private val pins: PinGate,
    private val now: () -> Long = System::currentTimeMillis
) {
    /** The reply to [msg]. Every message gets one. */
    fun handle(conn: ConnectionInfo, msg: JSONObject): JSONObject {
        val re: Int? = if (msg.has("id")) msg.optInt("id") else null
        val type = msg.optString("type")
        val paired = backend.isPaired(conn.phoneKey)
        if (!paired && type !in Types.BEFORE_PAIRING) {
            return fail(re, Errors.NOT_PAIRED, "Pair this phone with the TV first.")
        }
        return try {
            when (type) {
                Types.HELLO -> hello(re, paired)
                Types.PAIR_START -> pairStart(re, conn, msg)
                Types.PAIR_FINISH -> pairFinish(re, conn, msg)
                Types.PING -> CompanionProtocol.reply(re, Types.PONG)
                Types.GET_STATE -> backend.stateMessage().also { reTag(it, re) }
                Types.KEY -> key(re, msg)
                Types.OPEN -> open(re, msg)
                Types.GET_FAVORITES -> CompanionProtocol.reply(re, Types.FAVORITES).put("favorites", backend.favorites())
                Types.GET_SETTINGS -> guarded(re, msg) {
                    CompanionProtocol.reply(re, Types.SETTINGS).put("settings", backend.settings())
                }
                Types.PUT_SETTINGS -> guarded(re, msg) { putSettings(re, msg) }
                Types.EXPORT_SYNC -> guarded(re, msg) { exportSync(re, msg) }
                Types.IMPORT_SYNC -> guarded(re, msg) { importSync(re, msg) }
                Types.GET_DEBUG_LOG -> guarded(re, msg) { debugLogReply(re, backend.debugLog()) }
                Types.SET_DEBUG_LOG -> guarded(re, msg) { setDebugLog(re, msg) }
                Types.GET_GRID -> grid(re, msg)
                Types.SEARCH -> search(re, msg)
                Types.GET_LIBRARY -> library(re, msg)
                Types.OPEN_TITLE -> openTitle(re, msg)
                Types.GET_LINEUP -> guarded(re, msg) {
                    CompanionProtocol.reply(re, Types.LINEUP).put("lineup", backend.lineup())
                }
                Types.PUT_LINEUP -> guarded(re, msg) { putLineup(re, msg) }
                Types.PUT_LINEUP_KEY -> guarded(re, msg) { putLineupKey(re, msg) }
                Types.BUILD_LINEUP -> guarded(re, msg) { buildLineup(re) }
                else -> fail(re, Errors.BAD_REQUEST, "The TV does not know that request.")
            }
        } catch (e: CompanionException) {
            fail(re, e.code, e.message ?: "That did not work.")
        } catch (e: Exception) {
            DebugLog.log("companion", "Request $type failed", e)
            fail(re, Errors.BUSY, "The TV could not do that just now.")
        }
    }

    private fun hello(re: Int?, paired: Boolean): JSONObject {
        val reply = CompanionProtocol.reply(re, Types.WELCOME)
            .put("paired", paired)
            .put("tv_name", backend.tvName)
            .put("tv_id", backend.tvId)
            .put("app_build", backend.appBuild)
            .put("protocol", CompanionProtocol.PROTOCOL_VERSION)
        if (paired) reply.put("pin_set", backend.pinSet())
        return reply
    }

    private fun pairStart(re: Int?, conn: ConnectionInfo, msg: JSONObject): JSONObject {
        val name = cleanName(msg.optString("phone_name"))
        if (name.isEmpty()) return fail(re, Errors.BAD_REQUEST, "The phone needs a name.")
        pairing.start(conn.phoneKey, name)
        DebugLog.log("companion", "A phone started pairing.")
        return CompanionProtocol.reply(re, Types.OK)
    }

    private fun pairFinish(re: Int?, conn: ConnectionInfo, msg: JSONObject): JSONObject {
        val proof = msg.optString("proof").takeIf { it.isNotEmpty() }
            ?: return fail(re, Errors.BAD_REQUEST, "The code is missing.")
        return when (val result = pairing.finish(conn.phoneKey, proof, conn.tvFingerprint, conn.phoneFingerprint)) {
            is PairResult.Success -> {
                backend.addPaired(PairedPhone(conn.phoneKey, result.phoneName, now()))
                DebugLog.log("companion", "A phone paired.")
                CompanionProtocol.reply(re, Types.PAIRED).put("tv_name", backend.tvName).put("tv_id", backend.tvId)
            }
            is PairResult.WrongCode ->
                fail(re, Errors.WRONG_CODE, "That code did not match.").put("attempts_left", result.attemptsLeft)
            PairResult.Expired -> fail(re, Errors.CODE_EXPIRED, "That code has run out. Start again from the phone.")
            PairResult.Locked -> fail(re, Errors.LOCKED, "Too many wrong codes. Start again from the phone.")
        }
    }

    private fun key(re: Int?, msg: JSONObject): JSONObject {
        val code = CompanionProtocol.REMOTE_KEYS[msg.optString("key")]
            ?: return fail(re, Errors.BAD_REQUEST, "The TV does not know that key.")
        backend.pressKey(code)
        return CompanionProtocol.reply(re, Types.OK)
    }

    private fun open(re: Int?, msg: JSONObject): JSONObject {
        val link = msg.optString("link").trim()
        if (parseChannelLink(link) == null) return fail(re, Errors.BAD_REQUEST, "That is not a Cable TV link.")
        backend.openLink(link)
        return CompanionProtocol.reply(re, Types.OK)
    }

    private fun putSettings(re: Int?, msg: JSONObject): JSONObject {
        val settings = msg.optJSONObject("settings") ?: return fail(re, Errors.BAD_REQUEST, "The settings are missing.")
        val result = backend.putSettings(settings)
        return CompanionProtocol.reply(re, Types.SAVED)
            .put("messages", JSONArray(result.messages))
            .put("settings", result.settings)
    }

    private fun lineupSaved(re: Int?, messages: List<String>): JSONObject =
        CompanionProtocol.reply(re, Types.SAVED)
            .put("messages", JSONArray(messages))
            .put("lineup", backend.lineup())

    private fun putLineup(re: Int?, msg: JSONObject): JSONObject {
        val builtin = msg.optBoolean("builtin", false)
        val csv = msg.optString("csv")
        if (!builtin && csv.isBlank()) return fail(re, Errors.BAD_REQUEST, "Paste the lineup or choose the built-in one.")
        return lineupSaved(re, backend.putLineup(if (builtin) null else csv, builtin))
    }

    private fun putLineupKey(re: Int?, msg: JSONObject): JSONObject {
        val key = if (msg.has("openrouter_key")) msg.optString("openrouter_key").trim() else null
        val model = if (msg.has("openrouter_model")) msg.optString("openrouter_model").trim() else null
        return lineupSaved(re, backend.putLineupKey(key, model))
    }

    private fun buildLineup(re: Int?): JSONObject = lineupSaved(re, backend.buildLineup())

    private fun exportSync(re: Int?, msg: JSONObject): JSONObject {
        val arr = msg.optJSONArray("sections") ?: return fail(re, Errors.BAD_REQUEST, "Choose what to copy.")
        val sections = (0 until arr.length()).map { arr.optString(it) }
        if (sections.isEmpty() || sections.any { it !in CompanionProtocol.Sections.ALL }) {
            return fail(re, Errors.BAD_REQUEST, "Choose what to copy.")
        }
        return CompanionProtocol.reply(re, Types.SYNC_DATA).put("data", backend.exportSync(sections.distinct()))
    }

    private fun importSync(re: Int?, msg: JSONObject): JSONObject {
        val data = msg.optJSONObject("data") ?: return fail(re, Errors.BAD_REQUEST, "There is nothing to copy.")
        if (data.optInt("format", -1) != SyncData.FORMAT) {
            return fail(re, Errors.UNSUPPORTED, "This TV cannot read that copy. Update Cable TV on both TVs.")
        }
        if (data.optJSONObject("sections") == null) return fail(re, Errors.BAD_REQUEST, "There is nothing to copy.")
        return CompanionProtocol.reply(re, Types.SAVED).put("messages", JSONArray(backend.importSync(data)))
    }

    /** Runs [block] only when the TV has no PIN or the request carries the right one. */
    private inline fun guarded(re: Int?, msg: JSONObject, block: () -> JSONObject): JSONObject =
        when (val check = pins.check(if (msg.has("pin") && !msg.isNull("pin")) msg.optString("pin") else null)) {
            PinResult.Allowed -> block()
            PinResult.Required -> fail(re, Errors.PIN_REQUIRED, "Enter the PIN to continue.")
            PinResult.Wrong -> fail(re, Errors.WRONG_PIN, "That PIN did not match.")
            is PinResult.Wait -> fail(re, Errors.PIN_WAIT, "Too many tries. Wait ${check.seconds} seconds.")
        }

    private fun grid(re: Int?, msg: JSONObject): JSONObject {
        val group = msg.optString("group").trim()
        if (group.isEmpty()) return fail(re, Errors.BAD_REQUEST, "Choose a category.")
        val start = msg.optLong("start_ms", 0L)
        if (start <= 0L) return fail(re, Errors.BAD_REQUEST, "Choose a time for the guide to start from.")
        val view = backend.grid(group, start, msg.optInt("offset", 0), msg.optInt("limit", BrowseJson.GRID_PAGE_MAX))
        return CompanionProtocol.reply(re, Types.GRID)
            .put("groups", view.groups)
            .put("group", view.group)
            .put("start_ms", view.startMs)
            .put("end_ms", view.endMs)
            .put("total", view.total)
            .put("channels", view.channels)
    }

    private fun search(re: Int?, msg: JSONObject): JSONObject {
        val query = msg.optString("query").trim()
        if (query.isEmpty() || query.length > MAX_QUERY) return fail(re, Errors.BAD_REQUEST, "Type a name to search for.")
        val view = backend.search(query)
        return CompanionProtocol.reply(re, Types.RESULTS)
            .put("channels", view.channels)
            .put("titles", view.titles)
    }

    private fun library(re: Int?, msg: JSONObject): JSONObject {
        val movies = when (msg.optString("kind")) {
            "movies" -> true
            "series" -> false
            else -> return fail(re, Errors.BAD_REQUEST, "Choose movies or shows.")
        }
        val view = backend.library(movies, msg.optInt("offset", 0), msg.optInt("limit", BrowseJson.LIBRARY_PAGE_MAX))
        return CompanionProtocol.reply(re, Types.LIBRARY)
            .put("items", view.items)
            .put("total", view.total)
    }

    private fun openTitle(re: Int?, msg: JSONObject): JSONObject {
        val id = msg.optString("id")
        if (id.isEmpty()) return fail(re, Errors.BAD_REQUEST, "That title is not known.")
        val found = backend.openTitle(msg.optString("source"), msg.optString("media_type"), id)
        if (!found) return fail(re, Errors.BAD_REQUEST, "That title is not on the TV any more.")
        return CompanionProtocol.reply(re, Types.OK)
    }

    private fun debugLogReply(re: Int?, state: DebugLogState): JSONObject =
        CompanionProtocol.reply(re, Types.DEBUG_LOG).put("enabled", state.enabled).put("log", state.log)

    private fun setDebugLog(re: Int?, msg: JSONObject): JSONObject {
        val enabled = if (msg.has("enabled") && !msg.isNull("enabled")) msg.optBoolean("enabled") else null
        return debugLogReply(re, backend.changeDebugLog(enabled, msg.optBoolean("clear", false)))
    }

    private fun fail(re: Int?, code: String, message: String): JSONObject {
        DebugLog.log("companion", "Refused with $code")
        return CompanionProtocol.error(re, code, message)
    }

    private fun reTag(json: JSONObject, re: Int?) {
        if (re != null) json.put("re", re)
    }

    /** A phone name without control characters, short enough to fit the popup. */
    private fun cleanName(raw: String): String =
        raw.filter { !it.isISOControl() }.trim().take(MAX_NAME)

    companion object {
        const val MAX_NAME = 40
        const val MAX_QUERY = 100
    }
}
