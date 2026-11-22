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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns everything the screens share: settings, the channel list, favorites,
 * last channel, and the program guide. Screens read state from here instead of
 * reloading it, so switching between screens is instant.
 */
class CableViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)
    private val appState = AppStateStore(app)
    val epg = EpgRepository(app)
    val playback = Playback(app, viewModelScope)

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
    }

    /** Saves new settings and reloads only the sources that actually changed. */
    fun save(updated: Settings) {
        val old = _settings.value
        store.save(updated)
        _settings.value = updated
        if (old.liveSourceKey != updated.liveSourceKey) reloadChannels()
        if (old.epgKey != updated.epgKey) reloadGuide()
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

    fun reloadGuide(force: Boolean = false) {
        val s = _settings.value
        guideJob?.cancel()
        if (!s.hasXtream && s.epgUrl.isBlank()) {
            _guideStatus.value = "NO GUIDE SOURCE"
            return
        }
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

    /** Tunes a live channel and remembers the one that was on before it. */
    fun tune(channel: LiveChannel) {
        val prev = playback.liveChannel
        if (prev?.id != channel.id) rememberChannel(prev?.id)
        playback.tuneLive(channel)
    }

    fun rememberChannel(channelId: String?) {
        _lastChannelId.value = channelId
        appState.setLastChannelId(channelId)
    }

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
                        val count = EpgRepository(getApplication()).let { repo ->
                            repo.refresh(s, force = true).getOrThrow()
                        }
                        "Guide OK, $count channels with listings"
                    }
                    Source.Jellyfin -> {
                        check(s.jellyfinServer.isNotBlank()) { "Enter the Jellyfin server URL first." }
                        check(s.hasJellyfin) { "Sign in to Jellyfin first." }
                        val count = JellyfinClient(s.jellyfinServer, s.jellyfinToken, s.jellyfinUserId)
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
}
