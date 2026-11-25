package com.dallas.cabletv.ui.companion

import com.dallas.cabletv.data.atvremote.AtvRemoteException
import com.dallas.cabletv.data.companion.client.AtvController
import com.dallas.cabletv.data.companion.client.AtvPairingFlow
import com.dallas.cabletv.data.companion.client.CompanionException
import com.dallas.cabletv.data.companion.client.CompanionRequests
import com.dallas.cabletv.data.companion.client.Favorite
import com.dallas.cabletv.data.companion.client.KeyRoute
import com.dallas.cabletv.data.companion.client.PairedTv
import com.dallas.cabletv.data.companion.client.PinCancelledException
import com.dallas.cabletv.data.companion.client.RemoteKey
import com.dallas.cabletv.data.companion.client.SavedReply
import com.dallas.cabletv.data.companion.client.SyncChoice
import com.dallas.cabletv.data.companion.client.SessionStatus
import com.dallas.cabletv.data.companion.client.GridPage
import com.dallas.cabletv.data.companion.client.GuideChannel
import com.dallas.cabletv.data.companion.client.LibraryPage
import com.dallas.cabletv.data.companion.client.LineupSummary
import com.dallas.cabletv.data.companion.client.SearchResults
import com.dallas.cabletv.data.companion.client.TitleItem
import com.dallas.cabletv.data.companion.client.TvCopy
import com.dallas.cabletv.data.companion.client.TvDebugLog
import com.dallas.cabletv.data.companion.client.objects
import com.dallas.cabletv.data.companion.client.stringList
import com.dallas.cabletv.data.companion.client.TvSession
import com.dallas.cabletv.data.companion.client.TvSettings
import com.dallas.cabletv.data.companion.client.routeKey
import com.dallas.cabletv.data.companion.client.withPin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Settings as the Settings tab sees them. */
sealed interface SettingsLoad {
    data object Idle : SettingsLoad
    data object Loading : SettingsLoad
    data class Loaded(val settings: TvSettings) : SettingsLoad
    data class Failed(val message: String) : SettingsLoad
}

/** What one section's Save button last did. */
/** The lineup summary last read from the TV, or the reason it could not be read. */
data class LineupLoad(val summary: LineupSummary? = null, val error: String? = null)

data class SectionResult(val busy: Boolean = false, val messages: List<String> = emptyList(), val error: String? = null)

/** The Debug tab's state: the last log the TV sent, and whether a request is running. */
data class DebugLogLoad(val busy: Boolean = false, val log: TvDebugLog? = null, val error: String? = null)

/** A browse tab's state: what the TV last sent, and whether a request is running. */
data class BrowseLoad<T>(val busy: Boolean = false, val data: T? = null, val error: String? = null)

/** The dialog for pairing the Android TV remote (power and volume). */
sealed interface AtvPairUi {
    data object Connecting : AtvPairUi
    data class EnterCode(val error: String? = null, val busy: Boolean = false) : AtvPairUi
    data class Failed(val message: String) : AtvPairUi
}

/**
 * Everything one TV's screen needs while it is open: the connection to the TV, the Android TV
 * remote, favorites, settings and the remote's key presses. [start] opens the connections and
 * [stop] closes them when the screen goes away.
 */
class TvScreenModel(private val tvId: String, private val app: CompanionModel) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val initial: PairedTv = app.store.find(tvId) ?: error("Unknown TV")

    private val session = TvSession(initial, app.connector, app.phoneName, app.appBuild, scope)
    private val atv = AtvController(scope) { app.store.setAtvPaired(tvId, false) }

    val status: StateFlow<SessionStatus> = session.status
    val state = session.state
    val atvState = atv.state

    private val _favorites = MutableStateFlow<List<Favorite>?>(null)
    val favorites: StateFlow<List<Favorite>?> = _favorites

    private val _message = MutableStateFlow<String?>(null)

    /** The last problem with a key press or a channel, until the next one works. */
    val message: StateFlow<String?> = _message

    private val _atvPairUi = MutableStateFlow<AtvPairUi?>(null)
    val atvPairUi: StateFlow<AtvPairUi?> = _atvPairUi
    private var atvPairing: AtvPairingFlow? = null
    private var atvPairJob: Job? = null

    private val _settings = MutableStateFlow<SettingsLoad>(SettingsLoad.Idle)
    val settings: StateFlow<SettingsLoad> = _settings
    private val _sections = MutableStateFlow<Map<String, SectionResult>>(emptyMap())
    val sections: StateFlow<Map<String, SectionResult>> = _sections

    /** The TV as stored now, which has the newest address and the Android TV remote flag. */
    private fun tvNow(): PairedTv = app.store.find(tvId) ?: initial

    fun start() {
        session.start()
        scope.launch {
            status.collect { s ->
                if (s is SessionStatus.Online) {
                    loadFavorites()
                    _message.value = null
                }
            }
        }
        scope.launch {
            // Connect the Android TV remote while the person has set it up for this TV.
            app.tvs.collect { list ->
                val tv = list.firstOrNull { it.id == tvId } ?: return@collect
                if (tv.atvPaired) atv.start(tv.host) else atv.stop()
            }
        }
    }

    fun stop() {
        atvPairing?.close()
        session.stop()
        atv.stop()
        scope.cancel()
    }

    // Remote

    /** Sends a key by the route the spec gives: the Android TV remote when connected, else the app. */
    fun press(key: RemoteKey) {
        when (val route = routeKey(key, atvState.value.connected)) {
            is KeyRoute.Atv -> atv.sendKey(route.code)
            is KeyRoute.Cable -> call { session.request(CompanionRequests.key(route.name)) }
            KeyRoute.Unavailable -> Unit
        }
    }

    /** Opens a favorite channel. Through the app when it is open, otherwise through the Android TV remote. */
    fun openChannel(favorite: Favorite) {
        val link = CompanionRequests.channelLink(favorite.id)
        if (status.value is SessionStatus.Online) {
            call { session.request(CompanionRequests.open(link)) }
        } else if (atvState.value.connected) {
            atv.openLink(link)
        }
    }

    /** Starts Cable TV on the TV through the Android TV remote. */
    fun openCableTv() {
        atv.openLink(CompanionRequests.LIVE_LINK)
    }

    private fun call(block: suspend () -> Any?) {
        scope.launch {
            try {
                block()
                _message.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = app.errorText(e)
            }
        }
    }

    private fun loadFavorites() {
        scope.launch {
            try {
                _favorites.value = Favorite.listFrom(session.request(CompanionRequests.getFavorites()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The list stays as it was. Pressing a favorite shows the problem if it matters.
            }
        }
    }

    // Android TV remote pairing

    fun startAtvPairing() {
        atvPairJob?.cancel()
        atvPairing?.close()
        val flow = AtvPairingFlow(tvNow().host)
        atvPairing = flow
        _atvPairUi.value = AtvPairUi.Connecting
        atvPairJob = scope.launch {
            try {
                flow.start(app.phoneName)
                _atvPairUi.value = AtvPairUi.EnterCode()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                flow.close()
                _atvPairUi.value = AtvPairUi.Failed(atvPairText(e))
            }
        }
    }

    fun submitAtvCode(code: String) {
        val flow = atvPairing ?: return
        _atvPairUi.value = AtvPairUi.EnterCode(busy = true)
        atvPairJob = scope.launch {
            try {
                flow.finish(code)
                app.store.setAtvPaired(tvId, true)
                _atvPairUi.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: AtvRemoteException) {
                if (e.reason == AtvRemoteException.Reason.BAD_CODE) {
                    _atvPairUi.value = AtvPairUi.EnterCode(error = atvPairText(e))
                } else {
                    flow.close()
                    _atvPairUi.value = AtvPairUi.Failed(atvPairText(e))
                }
            } catch (e: Exception) {
                flow.close()
                _atvPairUi.value = AtvPairUi.Failed(atvPairText(e))
            }
        }
    }

    fun cancelAtvPairing() {
        atvPairJob?.cancel()
        atvPairing?.close()
        atvPairing = null
        _atvPairUi.value = null
    }

    private fun atvPairText(e: Exception): String {
        val name = tvNow().name
        return when ((e as? AtvRemoteException)?.reason) {
            AtvRemoteException.Reason.BAD_CODE -> "That code did not match. Check the TV and try again."
            AtvRemoteException.Reason.UNREACHABLE -> "Can't reach $name. Is the TV on?"
            else -> "Pairing the TV's remote did not finish. Try again."
        }
    }

    // Channel lineup

    private val _lineup = MutableStateFlow(LineupLoad())
    val lineup: StateFlow<LineupLoad> = _lineup

    /** Reads the lineup summary and the build's progress from the TV. */
    fun loadLineup() {
        scope.launch {
            try {
                val reply = withPin(tvId, app.pins, { app.askPin(tvNow().name, it) }) { pin ->
                    session.request(CompanionRequests.getLineup(pin))
                }
                reply.optJSONObject("lineup")?.let { _lineup.value = LineupLoad(summary = LineupSummary.fromJson(it)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PinCancelledException) {
                _lineup.value = _lineup.value.copy(error = "The lineup needs the TV's PIN.")
            } catch (e: Exception) {
                _lineup.value = _lineup.value.copy(error = app.errorText(e))
            }
        }
    }

    /** Loads the lineup from pasted CSV text. */
    fun putLineupCsv(csv: String) = lineupAction { pin -> session.request(CompanionRequests.putLineupCsv(pin, csv)) }

    /** Loads the lineup built into the TV app. */
    fun useBuiltInLineup() = lineupAction { pin -> session.request(CompanionRequests.putLineupBuiltIn(pin)) }

    /** Saves the OpenRouter key and model. A null key keeps the saved one. */
    fun saveOpenRouter(key: String?, model: String?) = lineupAction { pin ->
        session.request(CompanionRequests.putLineupKey(pin, key, model))
    }

    /** Starts the model pass on the TV. Progress comes back through [loadLineup]. */
    fun buildLineup() = lineupAction { pin -> session.request(CompanionRequests.buildLineup(pin)) }

    /** Keeps the lineup polling while the TV builds, and stops when the build ends. */
    suspend fun pollWhileBuilding() {
        while (_lineup.value.summary?.running == true) {
            delay(2_500)
            loadLineup()
        }
    }

    private fun lineupAction(request: suspend (String?) -> JSONObject) {
        setSection("lineup", SectionResult(busy = true))
        scope.launch {
            try {
                val reply = withPin(tvId, app.pins, { app.askPin(tvNow().name, it) }) { pin -> request(pin) }
                reply.optJSONObject("lineup")?.let { _lineup.value = LineupLoad(summary = LineupSummary.fromJson(it)) }
                val messages = reply.stringList("messages").ifEmpty { listOf("Saved.") }
                setSection("lineup", SectionResult(messages = messages))
            } catch (e: CancellationException) {
                throw e
            } catch (e: PinCancelledException) {
                setSection("lineup", SectionResult(error = "Nothing was saved. The TV needs its PIN."))
            } catch (e: Exception) {
                setSection("lineup", SectionResult(error = app.errorText(e)))
            }
        }
    }

    // Copy on this phone

    private val _copy = MutableStateFlow<TvCopy?>(null)
    val copy: StateFlow<TvCopy?> = _copy

    /** Reads the copy this phone has saved for this TV. */
    fun loadCopy() {
        _copy.value = app.copies.saved(tvId)
    }

    /** Exports every section from the TV and keeps it on this phone, replacing any older copy. */
    fun saveCopyToPhone() {
        setSection("copy", SectionResult(busy = true))
        scope.launch {
            try {
                val sections = SyncChoice.ALL.map { it.key }
                val reply = withPin(tvId, app.pins, { app.askPin(tvNow().name, it) }) { pin ->
                    session.request(CompanionRequests.exportSync(pin, sections))
                }
                val data = reply.optJSONObject("data") ?: throw CompanionException(
                    CompanionException.Kind.PROTOCOL, "The TV sent no setup to copy."
                )
                val copy = TvCopy(tvId, tvNow().name, System.currentTimeMillis(), data)
                app.copies.save(copy)
                _copy.value = copy
                setSection("copy", SectionResult(messages = listOf("Copy saved on this phone.")))
            } catch (e: CancellationException) {
                throw e
            } catch (e: PinCancelledException) {
                setSection("copy", SectionResult(error = "Nothing was saved. The TV needs its PIN."))
            } catch (e: Exception) {
                setSection("copy", SectionResult(error = app.errorText(e)))
            }
        }
    }

    /** Sends the saved copy back to the TV. Only the sections in the copy are changed. */
    fun restoreCopyToTv() {
        val saved = _copy.value ?: return setSection("copy", SectionResult(error = "No copy is saved on this phone."))
        setSection("copy", SectionResult(busy = true))
        scope.launch {
            try {
                val sections = saved.data.optJSONObject("sections")?.keys()?.asSequence()?.toList().orEmpty()
                val target = tvNow()
                val messages = app.startSync(saved.data, target, sections, "Restore copy to ${target.name}")
                // Logins and channel settings may have changed, so the Settings tab reloads them.
                _settings.value = SettingsLoad.Idle
                setSection("copy", SectionResult(messages = messages))
            } catch (e: CancellationException) {
                throw e
            } catch (e: PinCancelledException) {
                setSection("copy", SectionResult(error = "Nothing was restored. The TV needs its PIN."))
            } catch (e: Exception) {
                setSection("copy", SectionResult(error = app.errorText(e)))
            }
        }
    }

    /** Removes the copy from this phone. The TV is not changed. */
    fun deleteCopy() {
        app.copies.remove(tvId)
        _copy.value = null
        setSection("copy", SectionResult(messages = listOf("Copy removed from this phone.")))
    }

    // Settings

    fun loadSettings() {
        if (_settings.value == SettingsLoad.Loading) return
        _settings.value = SettingsLoad.Loading
        scope.launch {
            _settings.value = try {
                val reply = withPin(tvId, app.pins, { app.askPin(tvNow().name, it) }) { pin ->
                    session.request(CompanionRequests.getSettings(pin))
                }
                val json = reply.optJSONObject("settings") ?: throw CompanionException(
                    CompanionException.Kind.PROTOCOL, "The TV sent no settings."
                )
                SettingsLoad.Loaded(TvSettings.fromJson(json))
            } catch (e: CancellationException) {
                throw e
            } catch (e: PinCancelledException) {
                SettingsLoad.Failed("Settings need the TV's PIN.")
            } catch (e: Exception) {
                SettingsLoad.Failed(app.errorText(e).orEmpty())
            }
        }
    }

    /** Saves one section. [change] is the `settings` object from SettingsChanges. */
    fun save(section: String, change: JSONObject) {
        setSection(section, SectionResult(busy = true))
        scope.launch {
            try {
                val reply = withPin(tvId, app.pins, { app.askPin(tvNow().name, it) }) { pin ->
                    session.request(CompanionRequests.putSettings(pin, change))
                }
                val saved = SavedReply.fromJson(reply)
                saved.settings?.let { _settings.value = SettingsLoad.Loaded(it) }
                val messages = saved.messages.ifEmpty { listOf("Saved.") }
                setSection(section, SectionResult(messages = messages))
            } catch (e: CancellationException) {
                throw e
            } catch (e: PinCancelledException) {
                setSection(section, SectionResult(error = "Nothing was saved. The TV needs its PIN."))
            } catch (e: Exception) {
                setSection(section, SectionResult(error = app.errorText(e)))
            }
        }
    }

    private val _debugLog = MutableStateFlow(DebugLogLoad())
    val debugLog: StateFlow<DebugLogLoad> = _debugLog

    /** Reads the TV's debug log. */
    fun loadDebugLog() = debugRequest { pin -> session.request(CompanionRequests.getDebugLog(pin)) }

    /** Turns the TV's debug logging on or off. */
    fun setDebugLogging(enabled: Boolean) =
        debugRequest { pin -> session.request(CompanionRequests.setDebugLog(pin, enabled, clear = false)) }

    /** Empties the TV's debug log. */
    fun clearDebugLog() = debugRequest { pin -> session.request(CompanionRequests.setDebugLog(pin, null, clear = true)) }

    private fun debugRequest(block: suspend (String?) -> JSONObject) {
        _debugLog.value = _debugLog.value.copy(busy = true, error = null)
        scope.launch {
            _debugLog.value = try {
                val reply = withPin(tvId, app.pins, { app.askPin(tvNow().name, it) }) { pin -> block(pin) }
                DebugLogLoad(log = TvDebugLog.fromJson(reply))
            } catch (e: CancellationException) {
                throw e
            } catch (e: PinCancelledException) {
                DebugLogLoad(log = _debugLog.value.log, error = "The debug log needs the TV's PIN.")
            } catch (e: Exception) {
                DebugLogLoad(log = _debugLog.value.log, error = app.errorText(e))
            }
        }
    }

    // Guide, search and library. Browsing only reads; nothing here changes what the TV shows.
    private val pageSize = 50

    private val gridPage = 60
    private val _grid = MutableStateFlow(BrowseLoad<GridPage>())
    val grid: StateFlow<BrowseLoad<GridPage>> = _grid
    private val _searchResults = MutableStateFlow(BrowseLoad<SearchResults>())
    val searchResults: StateFlow<BrowseLoad<SearchResults>> = _searchResults
    private val _libraryPage = MutableStateFlow(BrowseLoad<LibraryPage>())
    val libraryPage: StateFlow<BrowseLoad<LibraryPage>> = _libraryPage
    private val _libraryMovies = MutableStateFlow(true)
    val libraryMovies: StateFlow<Boolean> = _libraryMovies

    /** Shows a guide category from [startMs] on: the first page of its channels, with the programmes for four hours. */
    fun loadGrid(group: String, startMs: Long) =
        browse(_grid, { session.request(CompanionRequests.getGrid(group, startMs, 0, gridPage)) }) { r ->
            GridPage.fromJson(r)
        }

    /** Adds the next page of channels to the guide, when the TV has more. */
    fun loadMoreGrid() {
        val current = _grid.value.data ?: return
        if (_grid.value.busy || current.channels.size >= current.total) return
        browse(_grid, { session.request(CompanionRequests.getGrid(current.group, current.startMs, current.channels.size, gridPage)) }) { r ->
            val next = GridPage.fromJson(r)
            current.copy(channels = current.channels + next.channels, total = next.total)
        }
    }

    fun search(query: String) = browse(_searchResults, { session.request(CompanionRequests.search(query.trim())) }) { r ->
        SearchResults(
            channels = r.optJSONArray("channels").objects { GuideChannel.fromJson(it) },
            titles = r.optJSONArray("titles").objects { TitleItem.fromJson(it) }
        )
    }

    /** Shows the first page of movies or of shows. */
    fun loadLibrary(movies: Boolean) {
        _libraryMovies.value = movies
        _libraryPage.value = BrowseLoad()
        browse(_libraryPage, { session.request(CompanionRequests.getLibrary(movies, 0, pageSize)) }) { r ->
            LibraryPage(r.optJSONArray("items").objects { TitleItem.fromJson(it) }, r.optInt("total"))
        }
    }

    /** Adds the next page to the library list, when the TV has more. */
    fun loadMoreLibrary() {
        val current = _libraryPage.value.data ?: return
        if (_libraryPage.value.busy || current.items.size >= current.total) return
        browse(_libraryPage, { session.request(CompanionRequests.getLibrary(_libraryMovies.value, current.items.size, pageSize)) }) { r ->
            LibraryPage(current.items + r.optJSONArray("items").objects { TitleItem.fromJson(it) }, r.optInt("total"))
        }
    }

    /** Tunes the TV to a channel, the same as choosing it in the guide. */
    fun watchChannel(channelId: String) = sendToTv("Tuning the TV to this channel.") {
        session.request(CompanionRequests.open(CompanionRequests.channelLink(channelId)))
    }

    /** Opens a movie or show on the TV. The TV asks for its PIN there when the title is locked. */
    fun openTitleOnTv(item: TitleItem) = sendToTv("Opening it on the TV.") {
        session.request(CompanionRequests.openTitle(item))
    }

    private fun <T> browse(flow: MutableStateFlow<BrowseLoad<T>>, request: suspend () -> JSONObject, parse: (JSONObject) -> T) {
        flow.value = flow.value.copy(busy = true, error = null)
        scope.launch {
            flow.value = try {
                BrowseLoad(data = parse(request()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BrowseLoad(data = flow.value.data, error = app.errorText(e))
            }
        }
    }

    private fun sendToTv(done: String, request: suspend () -> JSONObject) {
        if (status.value !is SessionStatus.Online) {
            _message.value = "The TV is not connected."
            return
        }
        scope.launch {
            try {
                request()
                _message.value = done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = app.errorText(e)
            }
        }
    }

    private fun setSection(section: String, result: SectionResult) {
        _sections.value = _sections.value + (section to result)
    }
}
