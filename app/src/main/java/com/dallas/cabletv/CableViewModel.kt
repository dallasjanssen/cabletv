package com.dallas.cabletv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dallas.cabletv.data.AppStateStore
import com.dallas.cabletv.data.ChannelSource
import com.dallas.cabletv.data.EpgRepository
import com.dallas.cabletv.data.JellyfinClient
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.Settings
import com.dallas.cabletv.data.SettingsStore
import com.dallas.cabletv.data.XtreamClient
import com.dallas.cabletv.ui.Playback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns everything the screens share: settings, the channel list, favorites, the last and
 * current channel, and the program guide. Screens read state from here instead of
 * reloading it, so switching between screens is instant.
 */
class CableViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)
    private val appState = AppStateStore(app)
    val epg = EpgRepository(app)
    val playback = Playback(app, viewModelScope)

    /** Per-install id sent to Jellyfin, so this TV's sign-in does not replace another device's. */
    val deviceId: String = appState.deviceId()

    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _channels = MutableStateFlow<List<LiveChannel>>(emptyList())
    val channels: StateFlow<List<LiveChannel>> = _channels.asStateFlow()

    private val _channelsLoading = MutableStateFlow(false)
    val channelsLoading: StateFlow<Boolean> = _channelsLoading.asStateFlow()

    private val _channelError = MutableStateFlow<String?>(null)
    val channelError: StateFlow<String?> = _channelError.asStateFlow()

    private val _guideVersion = MutableStateFlow(0)
    /** Bumps each time the guide finishes loading, so guide screens redraw. */
    val guideVersion: StateFlow<Int> = _guideVersion.asStateFlow()

    private val _guideStatus = MutableStateFlow<String?>(null)
    val guideStatus: StateFlow<String?> = _guideStatus.asStateFlow()

    private val _favorites = MutableStateFlow(appState.favorites())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _lastChannelId = MutableStateFlow(appState.lastChannelId())
    val lastChannelId: StateFlow<String?> = _lastChannelId.asStateFlow()

    private var channelJob: Job? = null
    private var guideJob: Job? = null

    init {
        reloadChannels()
        reloadGuide()
        // Keeps the guide fresh while the box stays on for a long time.
        viewModelScope.launch {
            while (true) {
                delay(GUIDE_CHECK_MS)
                reloadGuide()
            }
        }
    }

    /** Saves new settings and reloads only the sources that actually changed. */
    fun save(updated: Settings) {
        val old = _settings.value
        store.save(updated)
        _settings.value = updated
        if (old.liveSourceKey != updated.liveSourceKey) {
            // Channels from the old source must not keep playing under the new one.
            playback.stopLive()
            appState.setCurrentChannelId(null)
            reloadChannels()
        }
        if (old.epgKey != updated.epgKey) reloadGuide(force = true)
    }

    fun reloadChannels() {
        val s = _settings.value
        channelJob?.cancel()
        _channelError.value = null
        if (!s.hasLiveSource) {
            _channels.value = emptyList()
            _channelsLoading.value = false
            return
        }
        _channelsLoading.value = true
        channelJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { ChannelSource.load(s) } }
            result
                .onSuccess {
                    _channels.value = it
                    _channelError.value = null
                }
                .onFailure {
                    _channels.value = emptyList()
                    _channelError.value = it.message ?: "Could not load channels"
                }
            _channelsLoading.value = false
        }
    }

    /** Loads the guide if it is missing or stale. The repository decides; this only schedules it. */
    fun reloadGuide(force: Boolean = false) {
        val s = _settings.value
        if (!s.hasXtream && s.epgUrl.isBlank()) {
            _guideStatus.value = "NO GUIDE SOURCE"
            return
        }
        if (guideJob?.isActive == true && !force) return
        guideJob?.cancel()
        _guideStatus.value = "LOADING GUIDE..."
        guideJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { epg.refresh(s, force) }
            result
                .onSuccess {
                    _guideStatus.value = if (it == 0) "GUIDE IS EMPTY" else null
                    _guideVersion.value = _guideVersion.value + 1
                }
                .onFailure {
                    _guideStatus.value = "GUIDE ERROR: ${it.message ?: "unknown"}"
                }
        }
    }

    fun toggleFavorite(channelId: String) {
        val next = _favorites.value.toMutableSet().apply {
            if (!add(channelId)) remove(channelId)
        }
        _favorites.value = next
        appState.setFavorites(next)
    }

    /** Tunes a live channel and remembers it, and the one that was on before it. */
    fun tune(channel: LiveChannel) {
        val prev = playback.liveChannel
        if (prev?.id != channel.id) rememberChannel(prev?.id)
        playback.tuneLive(channel)
        appState.setCurrentChannelId(channel.id)
    }

    /** The channel to tune when Live TV opens: the one playing last time, or the first channel. */
    fun startChannel(): LiveChannel? {
        val list = _channels.value
        val savedId = appState.currentChannelId()
        return list.firstOrNull { it.id == savedId } ?: list.firstOrNull()
    }

    fun rememberChannel(channelId: String?) {
        _lastChannelId.value = channelId
        appState.setLastChannelId(channelId)
    }

    /** Called when the app is no longer on screen. */
    fun onBackground() = playback.onBackground()

    /** Called when the app is back on screen. */
    fun onForeground() = playback.onForeground()

    /** Runs a connection check for one source and returns a short human message. */
    suspend fun testConnection(which: Source, s: Settings): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                when (which) {
                    Source.Xtream -> {
                        check(s.hasXtream) { "Enter server, user and password first." }
                        check(XtreamClient(s.xtreamServer, s.xtreamUser, s.xtreamPass).login()) {
                            "Login failed. Check server, user and password."
                        }
                        "Live TV login OK"
                    }
                    Source.M3u -> {
                        check(s.m3uUrl.isNotBlank()) { "Enter a playlist URL first." }
                        val count = ChannelSource.load(s.copy(xtreamServer = "", xtreamUser = "", xtreamPass = "")).size
                        "Playlist OK, $count channels"
                    }
                    Source.Guide -> {
                        check(s.hasXtream || s.epgUrl.isNotBlank()) { "Enter a guide URL or an Xtream login first." }
                        // Uses the shared repository so the test cannot disturb the cache another refresh is using.
                        val count = epg.refresh(s, force = true).getOrThrow()
                        "Guide OK, $count channels with listings"
                    }
                    Source.Jellyfin -> {
                        check(s.jellyfinServer.isNotBlank()) { "Enter the Jellyfin server URL first." }
                        check(s.hasJellyfin) { "Sign in to Jellyfin first." }
                        val count = JellyfinClient(s.jellyfinServer, s.jellyfinToken, s.jellyfinUserId, deviceId)
                            .recentlyAdded(limit = 1).size
                        "Jellyfin OK (${if (count > 0) "library has items" else "library empty"})"
                    }
                }
            }
        }

    override fun onCleared() {
        playback.release()
        super.onCleared()
    }

    enum class Source { Xtream, M3u, Guide, Jellyfin }

    private companion object {
        const val GUIDE_CHECK_MS = 30L * 60 * 1000
    }
}
