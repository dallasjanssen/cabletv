package com.dallas.cabletv.data.companion.server

import org.json.JSONArray
import org.json.JSONObject

/** A request the TV cannot do. [code] is one of CompanionProtocol.Errors and [message] is a plain sentence. */
class CompanionException(val code: String, message: String) : Exception(message)

/** What `put_settings` answers with: the messages the TV would show, and the settings as they are now. */
class PutResult(val messages: List<String>, val settings: JSONObject)

/** The TV's debug log: whether debug logging is on, and the text recorded so far. */
class DebugLogState(val enabled: Boolean, val log: String)

/**
 * What the message router needs from the rest of the TV app. The real one reads Compose state on
 * the main thread and the saved stores. Tests use a fake. Every call may block, so the router
 * calls them from a connection thread. A call that cannot be done throws [CompanionException].
 */
interface CompanionBackend {
    val tvName: String
    val tvId: String
    val appBuild: Int

    /** True when a phone with this certificate fingerprint (hex) is paired. Checked on every message. */
    fun isPaired(fingerprint: String): Boolean

    /** Remembers a phone that finished pairing. */
    fun addPaired(phone: PairedPhone)

    fun pinSet(): Boolean
    fun checkPin(pin: String): Boolean

    /** The `state` message, without `re`. */
    fun stateMessage(): JSONObject

    /** Presses a remote key, given its Android key code, in the TV app. */
    fun pressKey(code: Int)

    /** Hands a cabletv: link to the app, the way a link from Home Assistant is handled. */
    fun openLink(link: String)

    fun favorites(): JSONArray

    fun settings(): JSONObject
    fun putSettings(settings: JSONObject): PutResult
    fun exportSync(sections: List<String>): JSONObject

    /** Applies a sync file and returns the messages to show. */
    fun importSync(data: JSONObject): List<String>

    /** The debug log as it is now. */
    fun debugLog(): DebugLogState

    /** Turns debug logging on or off when [enabled] is given, and empties the log first when [clear] is true. */
    fun changeDebugLog(enabled: Boolean?, clear: Boolean): DebugLogState
}
