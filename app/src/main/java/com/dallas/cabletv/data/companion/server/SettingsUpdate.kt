package com.dallas.cabletv.data.companion.server

import com.dallas.cabletv.data.Settings
import com.dallas.cabletv.data.companion.CompanionProtocol
import com.dallas.cabletv.data.isWebhookUrl
import com.dallas.cabletv.data.normalizeWebhookUrl
import org.json.JSONObject

/**
 * What a `put_settings` request changes, ready to apply. Nothing is applied until every check has
 * passed, so a failed check leaves the TV exactly as it was.
 */
class SettingsChange(
    /** The settings to save. Its Jellyfin fields are the ones already saved. */
    val settings: Settings,
    /** The active profile's hidden filters, when the request set them. */
    val hiddenFilters: String?,
    /** The settings after a Jellyfin sign in, to save the way Settings, Movies and Shows does. Null when not signing in. */
    val jellyfinSignIn: Settings?,
    /** True to sign the active profile out of Jellyfin. */
    val jellyfinSignOut: Boolean,
    /** The messages the TV would show. */
    val messages: List<String>
)

/**
 * Works out a `put_settings` request. The live login check and the Jellyfin sign in are the same
 * ones Settings uses, passed in as [checkLive] and [signIn], so the phone and the TV agree on what
 * is valid and what the messages say. Sections are all optional.
 */
class SettingsUpdater(
    private val checkLive: suspend (current: Settings, server: String, user: String, pass: String, m3u: String, stalkerUrl: String, stalkerMac: String, epgUrl: String) -> Settings,
    private val signIn: suspend (current: Settings, server: String, user: String, pass: String) -> Settings
) {
    /** Throws [CompanionException] with the invalid code and the TV's own message when a check fails. */
    suspend fun plan(current: Settings, request: JSONObject): SettingsChange {
        val messages = mutableListOf<String>()
        var s = current
        var hidden: String? = null

        request.optJSONObject("channels")?.let { channels ->
            if (channels.has("hidden")) hidden = channels.optString("hidden").trim()
            if (channels.has("locked")) s = s.copy(lockedFilters = channels.optString("locked").trim())
            if (channels.has("strip_prefixes")) s = s.copy(stripPrefixes = channels.optBoolean("strip_prefixes"))
            messages += "Customizations saved."
        }

        request.optJSONObject("home_assistant")?.let { ha ->
            if (ha.has("webhook")) {
                val webhook = normalizeWebhookUrl(ha.optString("webhook"))
                if (!isWebhookUrl(webhook)) invalid("The Home Assistant webhook URL needs to start with http:// or https://.")
                s = s.copy(homeAssistantWebhook = webhook)
                if ("Customizations saved." !in messages) messages += "Customizations saved."
            }
        }

        request.optJSONObject("live")?.let { live ->
            fun field(key: String, saved: String) = if (live.has(key)) (if (live.isNull(key)) "" else live.optString(key)) else saved
            s = try {
                checkLive(
                    s,
                    field("server", s.xtreamServer),
                    field("username", s.xtreamUser),
                    field("password", s.xtreamPass),
                    field("playlist", s.m3uUrl),
                    field("stalker_portal", s.stalkerUrl),
                    field("stalker_mac", s.stalkerMac),
                    field("guide", s.epgUrl)
                )
            } catch (e: Exception) {
                invalid("${(e.message ?: "That did not work").trimEnd('.')}.")
            }
            messages += "Live TV is connected."
        }

        var signedIn: Settings? = null
        var signOut = false
        request.optJSONObject("library")?.let { library ->
            if (library.optBoolean("sign_out")) {
                signOut = true
                messages += "Signed out of Jellyfin for this profile."
            } else {
                signedIn = try {
                    signIn(s, library.optString("server"), library.optString("username"), library.optString("password"))
                } catch (e: Exception) {
                    invalid("Sign in failed. ${e.message ?: ""}".trim())
                }
                messages += "Jellyfin is signed in."
            }
        }

        // Jellyfin is saved by its own rules, so the settings here keep the Jellyfin fields as they are.
        val keep = signedIn?.let {
            s.copy(jellyfinServer = current.jellyfinServer, jellyfinToken = current.jellyfinToken, jellyfinUserId = current.jellyfinUserId)
        } ?: s
        return SettingsChange(keep, hidden, signedIn, signOut, messages)
    }

    private fun invalid(message: String): Nothing = throw CompanionException(CompanionProtocol.Errors.INVALID, message)
}
