package com.dallas.cabletv.data.companion.server

import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.Settings
import com.dallas.cabletv.data.VodItem
import com.dallas.cabletv.data.companion.CompanionProtocol
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The part of the TV app the companion reaches into. CableApp fills these in on every
 * composition, so each one sees the app's current state. Every function here runs on the main
 * thread, because it touches Compose state or the activity; the server gets there through
 * [MainThread]. [screen] is the exception: it is a flow the server can read from any thread.
 */
class CompanionHooks {
    /** The screen the app shows, by the protocol's name. */
    val screen = MutableStateFlow("home")

    /** The saved settings, as the app holds them. */
    @Volatile var settings: () -> Settings = { notReady() }

    /** Saves [Settings] and updates the app, so channels reload after a login change. */
    @Volatile var applySettings: (Settings) -> Unit = { notReady() }

    /** Saves a Jellyfin sign in for the active profile, the way Settings, Movies and Shows does. */
    @Volatile var saveJellyfin: (Settings) -> Unit = { notReady() }

    @Volatile var signOutJellyfin: () -> Unit = { notReady() }

    /** The active profile's hidden filters, as Settings, Channel list shows them. */
    @Volatile var hiddenFilters: () -> String = { notReady() }

    @Volatile var setHiddenFilters: (String) -> Unit = { notReady() }

    @Volatile var jellyfinSignedIn: () -> Boolean = { notReady() }

    /** The channels in the list after hiding, with names as the app shows them. */
    @Volatile var channels: () -> List<LiveChannel> = { notReady() }

    /** Reads profiles, favorites, hidden filters and the PIN again from the stores, after sync changed them. */
    @Volatile var refresh: () -> Unit = { notReady() }

    /** Handles a cabletv: link exactly as one from Home Assistant. */
    @Volatile var openLink: (String) -> Unit = { notReady() }

    /** The movies and the shows the TV has loaded, as On Demand lists them. */
    @Volatile var vodCatalog: () -> Pair<List<VodItem>, List<VodItem>> = { notReady() }

    /** Opens a movie or show the way choosing it in On Demand does, asking for the PIN when it is locked. */
    @Volatile var openTitle: (VodItem) -> Unit = { notReady() }

    /** A short message on the TV screen. */
    @Volatile var showToast: (String) -> Unit = { }

    private fun notReady(): Nothing =
        throw CompanionException(CompanionProtocol.Errors.BUSY, "Cable TV is not ready. Try again in a moment.")
}
