package com.dallas.cabletv.data.companion.server

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import com.dallas.cabletv.BuildConfig
import com.dallas.cabletv.data.DebugLog
import com.dallas.cabletv.data.HomeAssistantReporter
import com.dallas.cabletv.data.Profile
import com.dallas.cabletv.data.ProfileStore
import com.dallas.cabletv.data.Settings
import com.dallas.cabletv.data.SettingsStore
import com.dallas.cabletv.data.UserStateStore
import com.dallas.cabletv.data.companion.CompanionProtocol
import com.dallas.cabletv.data.tvDeviceName
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * The real [CompanionBackend]: it answers a phone by reading and changing the same stores and
 * screen state the TV's own Settings use. Compose state and the activity are reached on the main
 * thread through [main]. Saved stores are read directly, because preferences are safe to read from
 * any thread. After a change that the screen shows, [CompanionHooks.refresh] reads it back in.
 */
class AppCompanionBackend(
    private val context: Context,
    private val hooks: CompanionHooks,
    private val prefs: CompanionPrefs,
    private val settingsStore: SettingsStore,
    private val profileStore: ProfileStore,
    private val updater: SettingsUpdater,
    private val main: MainThread,
    private val activity: () -> Activity?
) : CompanionBackend {

    override val tvName: String get() = tvDeviceName(context)
    override val tvId: String get() = prefs.tvId
    override val appBuild: Int get() = BuildConfig.VERSION_CODE

    override fun isPaired(fingerprint: String): Boolean = prefs.isPaired(fingerprint)

    override fun addPaired(phone: PairedPhone) {
        prefs.addPaired(phone)
        main.run { hooks.showToast("${phone.name} is paired") }
    }

    override fun pinSet(): Boolean = settingsStore.hasPin()

    override fun checkPin(pin: String): Boolean = settingsStore.checkPin(pin)

    override fun stateMessage(): JSONObject {
        val playing = HomeAssistantReporter.nowPlaying.value
        return CompanionJson.stateMessage(hooks.screen.value, playing.state, playing.info)
    }

    override fun pressKey(code: Int) {
        main.call {
            val target = activity() ?: throw CompanionException(CompanionProtocol.Errors.BUSY, "Cable TV is not ready. Try again in a moment.")
            val down = SystemClock.uptimeMillis()
            target.dispatchKeyEvent(KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0))
            val handled = target.dispatchKeyEvent(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
            // Compose's back handler is reached through the activity, so ask it directly if the key found no taker.
            if (code == KeyEvent.KEYCODE_BACK && !handled) (target as? ComponentActivity)?.onBackPressedDispatcher?.onBackPressed()
        }
    }

    override fun openLink(link: String) {
        main.call { hooks.openLink(link) }
    }

    override fun favorites(): JSONArray {
        val channels = main.call { hooks.channels() }
        val ids = profileStore.profiles().flatMap { p ->
            runCatching { UserStateStore(context, p.id).favorites() }.getOrDefault(emptySet())
        }.toSet()
        return CompanionJson.favoritesJson(CompanionJson.favoriteEntries(channels, ids))
    }

    override fun settings(): JSONObject = main.call { CompanionJson.settingsJson(view()) }

    override fun putSettings(settings: JSONObject): PutResult {
        val current = main.call { hooks.settings() }
        val change = runBlocking { updater.plan(current, settings) }
        val json = main.call(APPLY_TIMEOUT_MS) {
            if (change.settings != hooks.settings()) hooks.applySettings(change.settings)
            change.hiddenFilters?.let { hooks.setHiddenFilters(it) }
            change.jellyfinSignIn?.let { hooks.saveJellyfin(it) }
            if (change.jellyfinSignOut) hooks.signOutJellyfin()
            CompanionJson.settingsJson(view())
        }
        return PutResult(change.messages, json)
    }

    override fun debugLog(): DebugLogState = DebugLogState(DebugLog.enabled(), DebugLog.read())

    override fun changeDebugLog(enabled: Boolean?, clear: Boolean): DebugLogState {
        if (clear) DebugLog.clear()
        enabled?.let { DebugLog.setEnabled(it) }
        return debugLog()
    }

    override fun exportSync(sections: List<String>): JSONObject = SyncData(SyncAdapter(), tvName).export(sections)

    override fun importSync(data: JSONObject): List<String> {
        val messages = SyncData(SyncAdapter(), tvName).import(data)
        main.call(APPLY_TIMEOUT_MS) { hooks.refresh() }
        return messages
    }

    /** What `get_settings` reports. Reads Compose state, so it runs on the main thread. */
    private fun view(): SettingsView = SettingsView(
        settings = hooks.settings(),
        channelCount = hooks.channels().size,
        hiddenFilters = hooks.hiddenFilters(),
        jellyfinSignedIn = hooks.jellyfinSignedIn(),
        profiles = profileStore.profiles(),
        activeProfileId = profileStore.active(),
        pinSet = settingsStore.hasPin()
    )

    /** Lets [SyncData] read and write this TV's stores. */
    private inner class SyncAdapter : SyncTarget {
        override fun settings(): Settings = main.call { hooks.settings() }
        override fun saveSettings(settings: Settings) = main.call(APPLY_TIMEOUT_MS) { hooks.applySettings(settings) }
        override fun profiles(): List<Profile> = profileStore.profiles()
        override fun addProfile(name: String): Profile? = profileStore.add(name)
        override fun hiddenFilters(profileId: String): String? = UserStateStore(context, profileId).hiddenFilters()

        override fun setHiddenFilters(profileId: String, value: String?) {
            val store = UserStateStore(context, profileId)
            if (value == null) store.clearHiddenFilters() else store.setHiddenFilters(value)
        }

        override fun favorites(profileId: String): Set<String> = UserStateStore(context, profileId).favorites()
        override fun setFavorites(profileId: String, ids: Set<String>) = UserStateStore(context, profileId).replaceFavorites(ids)
        override fun pinRecord(): String = settingsStore.pinRecord()
        override fun setPinRecord(record: String) = settingsStore.setPinRecord(record)
    }

    private companion object {
        /** Saving can reload the screen, so it is allowed a little longer than a plain read. */
        const val APPLY_TIMEOUT_MS = 10_000L
    }
}
