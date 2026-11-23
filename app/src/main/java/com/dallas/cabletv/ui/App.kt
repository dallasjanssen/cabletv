package com.dallas.cabletv.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dallas.cabletv.BuildConfig
import com.dallas.cabletv.data.AppUpdater
import com.dallas.cabletv.data.BackupStore
import com.dallas.cabletv.data.ChannelSource
import com.dallas.cabletv.data.CrashLog
import com.dallas.cabletv.data.EpgCache
import com.dallas.cabletv.data.epgAddresses
import com.dallas.cabletv.data.friendlyError
import com.dallas.cabletv.data.guideCacheKey
import com.dallas.cabletv.data.guideRefreshMessage
import com.dallas.cabletv.data.JellyfinClient
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.Profile
import com.dallas.cabletv.data.Program
import com.dallas.cabletv.data.ProfileStore
import com.dallas.cabletv.data.RecordingService
import com.dallas.cabletv.data.RecordingStore
import com.dallas.cabletv.data.Reminder
import com.dallas.cabletv.data.ScheduledRecording
import com.dallas.cabletv.data.SettingsStore
import com.dallas.cabletv.data.StalkerClient
import com.dallas.cabletv.data.StreamCredentials
import com.dallas.cabletv.data.UserStateStore
import com.dallas.cabletv.data.VodItem
import com.dallas.cabletv.data.XtreamClient
import com.dallas.cabletv.data.displayName
import com.dallas.cabletv.data.dueReminders
import com.dallas.cabletv.data.dueSchedule
import com.dallas.cabletv.data.isHidden
import com.dallas.cabletv.data.isLocked
import com.dallas.cabletv.data.m3uCatchupUrl
import com.dallas.cabletv.data.reminderKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** How often the guide is downloaded again while the app is open. */
private const val GUIDE_REFRESH_MS = 60 * 60 * 1000L

enum class Screen { Live, Home, Guide, OnDemand, Search, Settings, Welcome, Multi }

/** Which PIN pad, if any, is on screen. */
private enum class PinPurpose { NONE, UNLOCK, SET_NEW, SET_CONFIRM, REMOVE, CONTENT }

private const val PIN_WRONG = "That PIN did not match. Try again. Forgot it? Clearing the app's data in Android settings removes the lock."
private const val PIN_WAIT = "Too many tries. Wait 30 seconds."

private fun Tab.toScreen(): Screen = when (this) {
    Tab.Home -> Screen.Home
    Tab.Guide -> Screen.Guide
    Tab.OnDemand -> Screen.OnDemand
    Tab.Search -> Screen.Search
    Tab.Settings -> Screen.Settings
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CableApp(store: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(store.load()) }

    // ------------------------------------------------------------ Profiles
    val profileStore = remember { ProfileStore(context) }
    val backupStore = remember { BackupStore(context, store, profileStore) }
    var profiles by remember { mutableStateOf(profileStore.profiles()) }
    var profileId by remember { mutableStateOf(profileStore.active()) }

    // Read once per launch. The app opens straight into live TV on this profile's last channel.
    val lastChannelId = remember { store.lastChannelId(profileStore.active()) }
    var screen by remember {
        mutableStateOf(
            when {
                !settings.hasLiveSource && !settings.hasJellyfin -> Screen.Welcome
                settings.hasLiveSource && lastChannelId != null -> Screen.Live
                else -> Screen.Home
            }
        )
    }
    // Where a movie page was opened from, so Back returns there.
    var vodOrigin by remember { mutableStateOf(Screen.OnDemand) }
    var settingsPane by remember { mutableStateOf(SettingsPane.Phone) }
    // The category picked in the guide or the channel list. Both show the same one.
    var channelGroup by remember { mutableStateOf(GROUP_ALL) }

    // ------------------------------------------------------------ Toasts
    var toast by remember { mutableStateOf<String?>(null) }
    var toastTick by remember { mutableIntStateOf(0) }
    val showToast: (String) -> Unit = {
        toast = it
        toastTick++
    }
    LaunchedEffect(toastTick) {
        if (toast != null) {
            delay(2800)
            toast = null
        }
    }
    var lastCrash by remember { mutableStateOf(CrashLog.read(context)) }
    LaunchedEffect(Unit) {
        if (store.wasReset) {
            showToast("Saved logins could not be read, so they were cleared. Please set up again.")
            delay(3000)
        }
        if (CrashLog.takeNotice(context)) showToast("The app closed unexpectedly last time. Details are in Settings, Updates.")
    }

    // ------------------------------------------------------------ On screen or not
    // Pressing Home on the remote stops the picture and lets go of the provider connection.
    val lifecycle = (context as? ComponentActivity)?.lifecycle
    var foreground by remember { mutableStateOf(lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false) }

    // ------------------------------------------------------------ Channels and guide
    var loadedChannels by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    var hiddenTerms by remember { mutableStateOf(settings.hiddenTerms) }
    var contentUnlocked by remember { mutableStateOf(false) }
    var pinSet by remember { mutableStateOf(store.hasPin()) }
    var pinPurpose by remember { mutableStateOf(PinPurpose.NONE) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var afterUnlock by remember { mutableStateOf<(() -> Unit)?>(null) }
    val channels = remember(loadedChannels, hiddenTerms, settings.stripPrefixes) {
        loadedChannels
            .distinctBy { it.id }
            .filterNot { isHidden(it, hiddenTerms) }
            .map { if (settings.stripPrefixes) it.copy(name = displayName(it.name)) else it }
    }
    var channelsLoading by remember { mutableStateOf(false) }
    var channelError by remember { mutableStateOf<String?>(null) }
    var channelReload by remember { mutableIntStateOf(0) }
    // The channel the viewer chose, by id, so a change to the list never swaps it for another one.
    var currentChannelId by remember { mutableStateOf(lastChannelId) }
    // The channel watched before the current one, and the current one once it has been on a moment.
    var priorChannelId by remember { mutableStateOf<String?>(null) }
    var watchedId by remember { mutableStateOf<String?>(null) }

    val xtream = remember(settings.xtreamServer, settings.xtreamUser, settings.xtreamPass) {
        if (settings.hasXtream) XtreamClient(settings.xtreamServer, settings.xtreamUser, settings.xtreamPass) else null
    }
    val stalker = remember(settings.stalkerUrl, settings.stalkerMac) {
        if (settings.hasStalker) StalkerClient(settings.stalkerUrl, settings.stalkerMac) else null
    }
    val guide = remember { GuideData(scope) }
    SideEffect {
        guide.epg = xtream
        guide.stalker = stalker
    }

    // Reload the channel list only when the live login changes. Hiding channels does not reload.
    // Each source loads on its own, so one that fails does not hide the channels of the others.
    LaunchedEffect(settings.xtreamServer, settings.xtreamUser, settings.xtreamPass, settings.m3uUrl, settings.stalkerUrl, settings.stalkerMac, channelReload) {
        loadedChannels = emptyList()
        channelError = null
        if (!settings.hasLiveSource) {
            channelsLoading = false
            return@LaunchedEffect
        }
        channelsLoading = true
        val s = settings
        withContext(Dispatchers.IO) { runCatching { ChannelSource.loadReport(s) } }
            .onSuccess { report ->
                loadedChannels = report.channels
                val problems = report.problems.joinToString(" ")
                if (report.channels.isEmpty()) {
                    if (problems.isNotBlank()) channelError = problems
                } else if (problems.isNotBlank()) {
                    showToast(problems)
                }
            }
            .onFailure { channelError = friendlyError(it) }
        channelsLoading = false
    }

    // The full schedule is downloaded now, again when asked, and then hourly. A copy is kept on the
    // device, so the next start shows listings straight away.
    val guideFile = remember { File(context.cacheDir, "guide/listings.bin") }
    var guideReload by remember { mutableIntStateOf(0) }
    var guideReloadSeen by remember { mutableIntStateOf(0) }
    var guideRefreshing by remember { mutableStateOf(false) }
    fun refreshGuide() {
        if (!settings.hasLiveSource) {
            showToast("Add a live TV login before refreshing the guide")
            return
        }
        if (!guideRefreshing) guideReload++
    }
    LaunchedEffect(settings.xtreamServer, settings.xtreamUser, settings.xtreamPass, settings.m3uUrl, settings.epgUrl, settings.stalkerUrl, settings.stalkerMac, guideReload) {
        val s = settings
        // Only the refresh the viewer asked for reports back. A settings change reloads quietly.
        var manual = guideReload != guideReloadSeen
        guideReloadSeen = guideReload
        var announced = false
        if (!s.hasLiveSource) {
            guideRefreshing = false
            // No live login, so the saved listings belong to nobody.
            withContext(Dispatchers.IO) { runCatching { guideFile.delete() } }
            return@LaunchedEffect
        }
        val key = guideCacheKey(s)
        // The saved copy shows straight away. When it is less than an hour old, the next download
        // waits until it is an hour old, as if the app had stayed open.
        if (!manual) {
            val savedAt = withContext(Dispatchers.IO) { runCatching { EpgCache.restoreFrom(guideFile, key) }.getOrNull() }
            if (savedAt != null) {
                guide.scheduleUpdated()
                val age = System.currentTimeMillis() - savedAt
                if (age in 0 until GUIDE_REFRESH_MS) delay(GUIDE_REFRESH_MS - age)
            }
        }
        while (s.hasLiveSource) {
            guideRefreshing = true
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val urls = ChannelSource.guideUrls(s)
                    val failed = if (urls.isEmpty()) emptyList() else EpgCache.refreshAll(urls)
                    // Saved only when at least one guide loaded in full, so an old copy never looks new.
                    if (failed.size < urls.size) runCatching { EpgCache.saveTo(guideFile, key) }
                    urls.size to failed
                }
            }
            guideRefreshing = false
            outcome.onSuccess { (attempted, failed) ->
                guide.clearShort()
                guide.scheduleUpdated()
                val typed = epgAddresses(s.epgUrl)
                if (!announced && typed.isNotEmpty() && failed.any { it in typed }) {
                    announced = true
                    showToast("The guide link did not load. Check Guide URL in Settings, Live TV.")
                }
                if (!announced && manual) {
                    announced = true
                    showToast(guideRefreshMessage(attempted, failed.size, s.hasStalker))
                }
            }.onFailure {
                if (!announced && manual) {
                    announced = true
                    showToast("The guide did not load. ${friendlyError(it)}")
                }
            }
            manual = false
            delay(GUIDE_REFRESH_MS)
        }
    }

    // The list can change under the viewer: a hidden group, another profile, a reload. The channel is
    // followed by id. When it has left the list, the one now in its place plays, but it is not saved
    // as the last channel until it has played for a while.
    val seenIndex = remember { intArrayOf(0) }
    val foundIndex = remember(channels, currentChannelId) {
        currentChannelId?.let { id -> channels.indexOfFirst { it.id == id } } ?: -1
    }
    val chosen = foundIndex >= 0
    val index = if (chosen) foundIndex else seenIndex[0].coerceIn(0, (channels.size - 1).coerceAtLeast(0))
    SideEffect { if (foundIndex >= 0) seenIndex[0] = foundIndex }
    val current = channels.getOrNull(index)
    val currentId by rememberUpdatedState(current?.id)

    // A channel counts as watched after about a second and a half, like a cable box, so Last
    // returns to the one you were watching and not to one you flipped past.
    LaunchedEffect(current?.id) {
        val id = current?.id ?: return@LaunchedEffect
        delay(1500)
        if (id != watchedId) {
            priorChannelId = watchedId
            watchedId = id
        }
    }

    // ------------------------------------------------------------ Profile state
    val userState = remember(profileId) { UserStateStore(context, profileId) }
    var favorites by remember(userState) { mutableStateOf(userState.favorites()) }
    var recents by remember(userState) { mutableStateOf(userState.recents()) }
    val toggleFavorite: (LiveChannel) -> Unit = { ch ->
        userState.toggleFavorite(ch.id)
        favorites = userState.favorites()
    }
    val profileName = profiles.firstOrNull { it.id == profileId }?.name ?: "Main"
    var showProfiles by remember { mutableStateOf(false) }
    fun switchProfile(id: String) {
        profileStore.setActive(id)
        if (id != profileId) {
            // Each profile returns to its own last channel, and does not take over the one playing now.
            store.lastChannelId(id)?.let { currentChannelId = it }
            priorChannelId = null
            watchedId = null
        }
        profileId = id
        contentUnlocked = false
    }
    LaunchedEffect(userState, settings.hiddenFilters) {
        val raw = userState.hiddenFilters() ?: settings.hiddenFilters
        hiddenTerms = raw.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
    }
    var jfOverride by remember(profileId) { mutableStateOf(store.profileJellyfin(profileId)) }
    val jellyfinToken = jfOverride?.first ?: settings.jellyfinToken
    val jellyfinUserId = jfOverride?.second ?: settings.jellyfinUserId

    // ------------------------------------------------------------ Live player
    val live = remember { LivePlayer(context) }
    DisposableEffect(Unit) { onDispose { live.release() } }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { foreground = true }
                Lifecycle.Event.ON_STOP -> {
                    foreground = false
                    live.stop()
                }
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    var replayUrl by remember { mutableStateOf<String?>(null) }
    var replayChannelId by remember { mutableStateOf<String?>(null) }
    // A replay belongs to its channel. If the list moves to another channel, the replay is dropped.
    val replay = replayUrl?.takeIf { replayChannelId == current?.id }
    var previewBounds by remember { mutableStateOf<Rect?>(null) }
    // The channel keeps playing behind Home and in the guide's preview. Elsewhere it stops, so a
    // provider that allows one stream at a time can play movies.
    val liveActive = settings.hasLiveSource && screen in setOf(Screen.Live, Screen.Home, Screen.Guide)

    // True when a channel needs the PIN and it has not been entered yet.
    fun lockedNow(ch: LiveChannel): Boolean =
        isLocked(ch.name, ch.group, settings.lockedTerms, pinSet) && !contentUnlocked

    // A title is locked by its own name, its category, or the show it belongs to.
    fun vodLocked(item: VodItem): Boolean {
        if (contentUnlocked) return false
        val terms = settings.lockedTerms
        return isLocked(item.name, item.category, terms, pinSet) ||
            item.seriesName?.let { isLocked(it, item.category, terms, pinSet) } == true
    }

    fun askContentPin(then: (() -> Unit)?) {
        afterUnlock = then
        pinError = null
        pinPurpose = PinPurpose.CONTENT
    }

    // A locked channel does not play. Live TV says so and OK asks for the PIN; other screens stay as they are.
    val channelLocked = current != null && lockedNow(current)
    val playUrl = if (channelLocked) null else replay ?: current?.streamUrl
    // The Stalker channel whose failure was already announced, so retries do not repeat the toast.
    var stalkerToastFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playUrl, liveActive, foreground, current?.origin, live.reloadRequests) {
        if (!liveActive || !foreground || playUrl == null) {
            live.stop()
            return@LaunchedEffect
        }
        // A short wait means holding a key only loads the channel you stop on.
        delay(250)
        val ch = current
        if (replay == null && ch?.origin == "stalker") {
            // The portal gives each channel a fresh address. The old channel stops meanwhile, so it
            // never plays under this channel's name.
            val portal = stalker
            live.resolving()
            val url = if (portal == null) null
            else withContext(Dispatchers.IO) { runCatching { portal.resolveLink(ch.streamUrl) }.getOrNull() }
            if (url == null) {
                live.fail()
                // The no signal screen tries again every so often. The toast is only shown once per channel.
                if (stalkerToastFor != ch.id) {
                    stalkerToastFor = ch.id
                    showToast("That channel did not start")
                }
            } else {
                stalkerToastFor = null
                live.play(url)
            }
        } else {
            live.play(playUrl, if (replay == null) ch?.fallbackStreamUrl else null, replay = replay != null)
        }
    }

    fun returnToLive(message: String) {
        replayUrl = null
        showToast(message)
    }

    // A replay that plays to its end returns to the live channel.
    LaunchedEffect(live.replayEnds) {
        if (live.replayEnds > 0 && replayUrl != null) returnToLive("Replay finished")
    }

    // Remember the channel on screen, so the next launch returns to it and it joins Recently watched.
    LaunchedEffect(current?.id, chosen, profileId) {
        val ch = current ?: return@LaunchedEffect
        if (!chosen) return@LaunchedEffect
        delay(600)
        store.saveLastChannelId(profileId, ch.id)
        userState.addRecent(ch.id)
        recents = userState.recents()
    }
    // The channel standing in for one that left the list becomes the chosen one once it has played a while.
    LaunchedEffect(current?.id, chosen, liveActive, foreground, channelLocked) {
        val ch = current ?: return@LaunchedEffect
        if (chosen || !liveActive || !foreground || channelLocked) return@LaunchedEffect
        delay(10_000)
        currentChannelId = ch.id
    }
    lateinit var open: (Screen) -> Unit

    fun applyTune(ch: LiveChannel) {
        currentChannelId = ch.id
        replayUrl = null
    }

    // A direct choice: a number, the channel list, the guide, search or Home. A locked channel asks for the PIN.
    fun tuneTo(ch: LiveChannel) {
        if (channels.none { it.id == ch.id }) {
            replayUrl = null
            return
        }
        if (lockedNow(ch)) {
            askContentPin { applyTune(ch) }
            return
        }
        applyTune(ch)
    }

    // Up and down, and CH+ and CH-, skip locked channels until the PIN has been entered.
    fun stepChannel(delta: Int) {
        if (channels.isEmpty()) return
        val size = channels.size
        for (step in 1..size) {
            val ch = channels[(index + delta * step).mod(size)]
            if (!lockedNow(ch)) {
                if (ch.id != current?.id) applyTune(ch)
                return
            }
        }
        // Every channel is locked.
        tuneTo(channels[(index + delta).mod(size)])
    }

    fun tuneToPrevious() {
        val now = current?.id
        val target = if (watchedId != null && watchedId != now) watchedId else priorChannelId
        val ch = target?.let { id -> channels.firstOrNull { it.id == id } }
        if (ch == null || ch.id == now) showToast("No previous channel") else tuneTo(ch)
    }

    fun startReplay(ch: LiveChannel, program: Program) {
        if (!ch.catchup || ch.origin == "stalker") {
            showToast("${program.title} is not available to replay")
            return
        }
        if (lockedNow(ch)) {
            askContentPin { startReplay(ch, program) }
            return
        }
        fun begin(url: String) {
            if (current?.id != ch.id) tuneTo(ch)
            replayUrl = url
            replayChannelId = ch.id
            open(Screen.Live)
            showToast("Playing ${program.title} from the start")
        }
        val client = xtream
        if (ch.origin != "xtream" || client == null) {
            val local = m3uCatchupUrl(ch.streamUrl, ch.catchupType, ch.catchupSource, program.start, program.end)
            if (local == null) showToast("${program.title} is not available to replay") else begin(local)
            return
        }
        if (current?.id != ch.id) tuneTo(ch)
        open(Screen.Live)
        scope.launch {
            val url = withContext(Dispatchers.IO) {
                runCatching { client.resolveTimeshiftUrl(ch.id, program.start, program.end) }.getOrNull()
            }
            when {
                url == null -> showToast("${program.title} is not available to replay")
                currentId == ch.id -> {
                    replayUrl = url
                    replayChannelId = ch.id
                    showToast("Playing ${program.title} from the start")
                }
            }
        }
    }

    fun remind(ch: LiveChannel, program: Program) {
        val added = userState.toggleReminder(Reminder(ch.id, ch.name, program.title, program.start))
        showToast(if (added) "Reminder set for ${program.title}" else "Reminder removed")
    }

    fun record(ch: LiveChannel, program: Program) {
        if (lockedNow(ch)) {
            askContentPin { record(ch, program) }
            return
        }
        if (ch.origin == "stalker") {
            showToast("Recording is not available for this portal")
            return
        }
        if (RecordingService.isBusy()) {
            showToast("A recording is already running. Stop it in Settings, Recordings.")
            return
        }
        val id = "${ch.id.hashCode()}-${program.start}"
        val storeFiles = RecordingStore(context.filesDir)
        fun startWith(url: String) {
            val nowMs = System.currentTimeMillis()
            if (program.start > nowMs + 20_000) {
                storeFiles.addSchedule(ScheduledRecording(id, ch.id, ch.name, program.title, url, program.start, program.end))
                showToast("Will record ${program.title}. Leave the app open until it starts. This uses another connection.")
            } else if (RecordingService.start(context, id, url, program.title, ch.name, program.end)) {
                showToast("Recording ${program.title}. This uses another connection.")
            } else {
                showToast("The recording could not start. Check free space.")
            }
        }
        val client = xtream
        if (ch.origin == "xtream" && client != null && ch.catchup) {
            scope.launch {
                val url = withContext(Dispatchers.IO) {
                    runCatching { client.resolveTimeshiftUrl(ch.id, program.start, program.end) }.getOrNull()
                } ?: ch.streamUrl
                startWith(url)
            }
            return
        }
        val local = if (ch.catchup) m3uCatchupUrl(ch.streamUrl, ch.catchupType, ch.catchupSource, program.start, program.end) else null
        startWith(local ?: ch.streamUrl)
    }

    // ------------------------------------------------------------ Movies and shows
    var shelvesTick by remember { mutableIntStateOf(0) }
    var providerVod by remember { mutableStateOf<Pair<List<VodItem>, List<VodItem>>>(emptyList<VodItem>() to emptyList()) }
    var providerError by remember { mutableStateOf<String?>(null) }
    var providerLoading by remember { mutableStateOf(false) }
    LaunchedEffect(settings.xtreamServer, settings.xtreamUser, settings.xtreamPass) {
        providerVod = emptyList<VodItem>() to emptyList()
        providerError = null
        val xt = xtream
        if (xt == null) {
            providerLoading = false
            return@LaunchedEffect
        }
        providerLoading = true
        // After the channel list, so a login that allows one connection never gets two big requests at once.
        snapshotFlow { channelsLoading }.first { !it }
        // Movies and shows load separately, so a panel without shows still lists its movies.
        val movies = withContext(Dispatchers.IO) { runCatching { xt.vodMovies(1500) } }
        val series = withContext(Dispatchers.IO) { runCatching { xt.vodSeries(800) } }
        providerVod = movies.getOrDefault(emptyList()) to series.getOrDefault(emptyList())
        val failure = movies.exceptionOrNull()
        if (failure != null && series.isFailure) providerError = friendlyError(failure)
        providerLoading = false
    }
    var jellyfinShelves by remember { mutableStateOf<Pair<List<VodItem>, List<VodItem>>>(emptyList<VodItem>() to emptyList()) }
    var jellyfinBrowse by remember { mutableStateOf<Pair<List<VodItem>, List<VodItem>>>(emptyList<VodItem>() to emptyList()) }
    var jellyfinError by remember { mutableStateOf<String?>(null) }
    var jellyfinLoading by remember { mutableStateOf(false) }
    LaunchedEffect(settings.jellyfinServer, jellyfinToken, jellyfinUserId, shelvesTick) {
        jellyfinError = null
        if (settings.jellyfinServer.isBlank() || jellyfinToken.isBlank() || jellyfinUserId.isBlank()) {
            jellyfinShelves = emptyList<VodItem>() to emptyList()
            jellyfinBrowse = emptyList<VodItem>() to emptyList()
            return@LaunchedEffect
        }
        val jf = JellyfinClient(settings.jellyfinServer, jellyfinToken, jellyfinUserId)
        jellyfinLoading = true
        withContext(Dispatchers.IO) {
            runCatching {
                val cont = jf.continueWatching()
                val recent = jf.recentlyAdded(40)
                val movies = runCatching { jf.browse("Movie") }.getOrDefault(emptyList())
                val shows = runCatching { jf.browse("Series") }.getOrDefault(emptyList())
                (cont to recent) to (movies to shows)
            }
        }
            .onSuccess {
                jellyfinShelves = it.first
                jellyfinBrowse = it.second
            }
            .onFailure { jellyfinError = friendlyError(it) }
        jellyfinLoading = false
    }
    // Resume entries keep no logins. They get them back from here, so this is set before they are read.
    val credentials = StreamCredentials(settings.xtreamUser, settings.xtreamPass, jellyfinToken)
    userState.credentials = credentials
    val localResume = remember(userState, shelvesTick, credentials) { userState.continueWatching().map { it.first } }
    val shelves = VodShelves(
        continueWatching = (localResume + jellyfinShelves.first).distinctBy { it.source + ":" + it.id },
        recent = jellyfinShelves.second,
        movies = (providerVod.first + jellyfinBrowse.first).distinctBy { it.source + ":" + it.id },
        series = (providerVod.second + jellyfinBrowse.second).distinctBy { it.source + ":" + it.id },
        loading = providerLoading || jellyfinLoading,
        error = jellyfinError ?: providerError
    )
    // Movie and show categories, offered in the parental lock next to the live groups.
    val vodCategories by remember {
        derivedStateOf {
            (providerVod.first + providerVod.second + jellyfinBrowse.first + jellyfinBrowse.second)
                .map { it.category.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sortedBy { it.lowercase() }
        }
    }
    val jellyfinSignedIn = settings.jellyfinServer.isNotBlank() && jellyfinToken.isNotBlank() && jellyfinUserId.isNotBlank()
    var pendingVod by remember { mutableStateOf<VodItem?>(null) }

    // ------------------------------------------------------------ Updates
    var update by remember { mutableStateOf<AppUpdater.Release?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updateBusy by remember { mutableStateOf(false) }

    // Asks GitHub for a newer build. A manual check always reports back, an automatic one stays quiet.
    fun checkForUpdate(manual: Boolean) {
        // A debug build is signed with another key, so Android would refuse a release over it.
        if (BuildConfig.DEBUG) {
            if (manual) updateMessage = "This is a debug build. Install the release build once to get updates."
            return
        }
        updateBusy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AppUpdater.latest(BuildConfig.VERSION_CODE) } }
            updateBusy = false
            result
                .onSuccess { found ->
                    update = found
                    if (found != null && !manual) showToast("Build ${found.versionCode} is ready. Install it from Settings, Updates.")
                    updateMessage = when {
                        found != null -> null
                        manual -> "You have the latest build."
                        else -> null
                    }
                }
                .onFailure { if (manual) updateMessage = "The update check did not work. ${friendlyError(it)}" }
        }
    }

    // Downloads the build and opens the system installer. Android asks the person to confirm.
    fun installUpdate(release: AppUpdater.Release) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            updateMessage = try {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                "Allow installs from this app on the next screen, then press Install again."
            } catch (e: Exception) {
                "This TV did not open the setting. In Android settings, allow Cable TV to install unknown apps, then press Install again."
            }
            return
        }
        updateBusy = true
        updateMessage = "Downloading build ${release.versionCode}"
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AppUpdater.download(release, File(context.cacheDir, "updates")) } }
            updateBusy = false
            result
                .onSuccess { apk ->
                    updateMessage = null
                    runCatching { AppUpdater.install(context, apk) }
                        .onFailure { updateMessage = it.message ?: "The installer did not open." }
                }
                .onFailure { updateMessage = "The download did not finish. ${friendlyError(it)}" }
        }
    }

    var dueReminder by remember { mutableStateOf<Reminder?>(null) }
    var firedReminders by remember { mutableStateOf(setOf<String>()) }
    val reminderStore by rememberUpdatedState(userState)
    LaunchedEffect(profileId) {
        firedReminders = emptySet()
        while (true) {
            dueReminder = dueReminders(reminderStore.reminders(), System.currentTimeMillis(), firedReminders).firstOrNull()
            delay(15_000)
        }
    }
    // Scheduled recordings. Android refuses to start the recording service while the app is in the
    // background, so a due recording waits until the app is on screen.
    LaunchedEffect(Unit) {
        val finished = mutableSetOf<String>()
        val warned = mutableSetOf<String>()
        while (true) {
            if (foreground && !RecordingService.isBusy()) {
                val schedule = withContext(Dispatchers.IO) {
                    runCatching { RecordingStore(context.filesDir).schedule() }.getOrDefault(emptyList())
                }
                val item = dueSchedule(schedule, System.currentTimeMillis(), finished).firstOrNull()
                if (item != null) {
                    if (RecordingService.start(context, item.id, item.url, item.title, item.channelName, item.end)) {
                        finished += item.id
                        showToast("Recording ${item.title}")
                    } else if (warned.add(item.id)) {
                        showToast("The recording could not start.")
                    }
                }
            }
            delay(20_000)
        }
    }

    LaunchedEffect(Unit) { checkForUpdate(manual = false) }

    // ------------------------------------------------------------ Parental lock
    var settingsUnlocked by remember { mutableStateOf(false) }
    var pinFirst by remember { mutableStateOf("") }
    // Five wrong PINs in a row, for any purpose, pause the pad for 30 seconds.
    var pinFails by remember { mutableIntStateOf(0) }
    var pinBlocked by remember { mutableStateOf(false) }
    LaunchedEffect(pinBlocked) {
        if (!pinBlocked) return@LaunchedEffect
        delay(30_000)
        pinBlocked = false
        pinError = null
    }

    fun pinMatches(entered: String): Boolean {
        if (pinBlocked) {
            pinError = PIN_WAIT
            return false
        }
        if (store.checkPin(entered)) {
            pinFails = 0
            pinError = null
            return true
        }
        pinFails++
        if (pinFails >= 5) {
            pinFails = 0
            pinBlocked = true
            pinError = PIN_WAIT
        } else {
            pinError = PIN_WRONG
        }
        return false
    }

    fun pinSubtitle(normal: String): String = if (pinBlocked) PIN_WAIT else pinError ?: normal

    // ------------------------------------------------------------ Navigation
    fun go(target: Screen) {
        if (target == Screen.Settings && pinSet && !settingsUnlocked) {
            pinError = null
            pinPurpose = PinPurpose.UNLOCK
            return
        }
        // Leaving Settings locks it again.
        if (screen == Screen.Settings && target != Screen.Settings) settingsUnlocked = false
        if (target != Screen.Guide) previewBounds = null
        screen = target
    }
    open = ::go

    val nav = NavActions(
        select = { tab -> go(tab.toScreen()) },
        back = {
            // Live TV returns here. A second Back leaves the app instead of bouncing back to the picture.
            if (screen != Screen.Home) go(Screen.Home) else (context as? Activity)?.finish()
        },
        profileName = profileName,
        onProfile = { showProfiles = true }
    )

    // ------------------------------------------------------------ First launch
    var welcomeBusy by remember { mutableStateOf(false) }
    var welcomeMessage by remember { mutableStateOf<String?>(null) }
    fun welcomeReceived(fields: Map<String, String>) {
        welcomeBusy = true
        welcomeMessage = "Details received. Checking them now."
        scope.launch {
            var s = settings
            val problems = mutableListOf<String>()
            val wantsLive = listOf("xs", "xu", "xp", "m3u", "su", "sm", "epg").any { !fields[it].isNullOrBlank() }
            if (wantsLive) {
                runCatching {
                    checkLiveLogin(
                        s, fields["xs"].orEmpty(), fields["xu"].orEmpty(), fields["xp"].orEmpty(), fields["m3u"].orEmpty(),
                        fields["su"].orEmpty(), fields["sm"].orEmpty(), fields["epg"].orEmpty()
                    )
                }
                    .onSuccess { s = it }
                    .onFailure { problems += "Live TV: ${it.message}." }
            }
            if (!fields["js"].isNullOrBlank() && !fields["ju"].isNullOrBlank()) {
                runCatching { signInJellyfin(s, fields["js"].orEmpty(), fields["ju"].orEmpty(), fields["jp"].orEmpty()) }
                    .onSuccess { s = it }
                    .onFailure { problems += "Jellyfin: ${it.message ?: "sign in failed"}." }
            }
            welcomeBusy = false
            if (s != settings) {
                store.save(s)
                settings = s
            }
            if (problems.isEmpty() && (s.hasLiveSource || s.hasJellyfin)) {
                showToast("You are all set")
                go(if (s.hasLiveSource) Screen.Live else Screen.Home)
            } else {
                welcomeMessage = (problems.ifEmpty { listOf("Nothing was filled in on the phone.") } + "Scan the code again to fix it.").joinToString(" ")
            }
        }
    }

    // ------------------------------------------------------------ Overlays
    // A reminder shows over the live picture, Home and the guide. It waits while the PIN pad is up,
    // and does not take the remote from a movie, search typing or Settings.
    val reminderShown = dueReminder?.takeIf {
        pinPurpose == PinPurpose.NONE && !showProfiles && screen in setOf(Screen.Live, Screen.Home, Screen.Guide)
    }
    // Live TV takes focus back when these close, so the next key press works.
    val overlayOpen = pinPurpose != PinPurpose.NONE || reminderShown != null

    // The other screens get focus back too. When an overlay closes and nothing on the screen has
    // focus, the item that had it before is focused again, or the screen's first item.
    val screenFocus = remember { FocusRequester() }
    var screenHasFocus by remember { mutableStateOf(false) }
    var overlayWasOpen by remember { mutableStateOf(false) }
    val anyOverlay = overlayOpen || showProfiles
    LaunchedEffect(anyOverlay) {
        if (anyOverlay) {
            overlayWasOpen = true
            return@LaunchedEffect
        }
        if (!overlayWasOpen) return@LaunchedEffect
        overlayWasOpen = false
        if (screen == Screen.Live) return@LaunchedEffect
        // Give the screen a moment to place its own focus first.
        withFrameNanos { }
        withFrameNanos { }
        if (!screenHasFocus) runCatching { screenFocus.requestFocus() }
    }

    // ------------------------------------------------------------ Layout
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().background(Tv.Bg)) {
        val bounds = previewBounds
        val pip = screen == Screen.Guide
        if (liveActive && channels.isNotEmpty()) {
            // The guide shows the picture in its corner preview. Until that spot is measured, or on a
            // phone where there is none, the view stays attached at no size so it does not restart.
            val placement = when {
                !pip -> Modifier.fillMaxSize()
                bounds != null -> with(density) {
                    Modifier
                        .offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                        .size(bounds.width.toDp(), bounds.height.toDp())
                        .clip(RoundedCornerShape(10.dp))
                }
                else -> Modifier.size(0.dp)
            }
            LiveVideo(live, placement.zIndex(if (pip) 2f else 0f))
        }

        Box(
            Modifier
                .fillMaxSize()
                .zIndex(1f)
                .onFocusChanged { screenHasFocus = it.hasFocus }
                .focusRequester(screenFocus)
                .focusRestorer()
                .focusGroup()
        ) {
            when (screen) {
                Screen.Welcome -> WelcomeScreen(
                    busy = welcomeBusy,
                    message = welcomeMessage,
                    onReceived = { welcomeReceived(it) },
                    onUseRemote = {
                        settingsPane = SettingsPane.Live
                        go(Screen.Settings)
                    }
                )
                Screen.Live -> LiveTvScreen(
                    channels = channels,
                    index = index,
                    player = live,
                    guide = guide,
                    favorites = favorites,
                    recents = recents,
                    group = channelGroup,
                    onGroupChange = { channelGroup = it },
                    loading = channelsLoading,
                    error = channelError,
                    canReplay = true,
                    onMultiView = { go(Screen.Multi) },
                    onRecord = { ch, program -> record(ch, program) },
                    replaying = replay != null,
                    onTune = { i -> channels.getOrNull(i)?.let { tuneTo(it) } },
                    onStep = { stepChannel(it) },
                    onToggleFavorite = toggleFavorite,
                    onReplay = { ch, p -> startReplay(ch, p) },
                    onReturnToLive = { returnToLive("Back to live") },
                    onOpenGuide = { go(Screen.Guide) },
                    onOpenHome = { go(Screen.Home) },
                    onOpenSearch = { go(Screen.Search) },
                    onLastChannel = { tuneToPrevious() },
                    onExit = { go(Screen.Home) },
                    showToast = showToast,
                    locked = channelLocked,
                    onUnlock = { askContentPin(null) },
                    overlayOpen = overlayOpen,
                    onReload = { channelReload++ },
                    onOpenSettings = {
                        settingsPane = SettingsPane.Live
                        go(Screen.Settings)
                    }
                )
                Screen.Home -> HomeScreen(
                    nav = nav,
                    channels = channels,
                    current = current,
                    guide = guide,
                    favorites = favorites,
                    recents = recents,
                    shelves = shelves,
                    hasLive = settings.hasLiveSource,
                    loadingChannels = channelsLoading,
                    channelError = channelError,
                    onRetry = { channelReload++ },
                    onWatch = { go(Screen.Live) },
                    onGuide = { go(Screen.Guide) },
                    onTune = { ch ->
                        tuneTo(ch)
                        go(Screen.Live)
                    },
                    onOpenVod = { item ->
                        fun openItem() {
                            vodOrigin = Screen.Home
                            pendingVod = item
                            go(Screen.OnDemand)
                        }
                        if (vodLocked(item)) askContentPin { openItem() } else openItem()
                    },
                    onSetUp = {
                        settingsPane = SettingsPane.Live
                        go(Screen.Settings)
                    }
                )
                Screen.Guide -> GuideScreen(
                    nav = nav,
                    channels = channels,
                    currentId = current?.id,
                    favorites = favorites,
                    recents = recents,
                    guide = guide,
                    loading = channelsLoading,
                    group = channelGroup,
                    onGroupChange = { channelGroup = it },
                    onWatch = { ch ->
                        tuneTo(ch)
                        go(Screen.Live)
                    },
                    onReplay = { ch, program -> startReplay(ch, program) },
                    onRemind = { ch, program -> remind(ch, program) },
                    onRecord = { ch, program -> record(ch, program) },
                    onToggleFavorite = toggleFavorite,
                    onPreviewBounds = { previewBounds = it },
                    showToast = showToast,
                    guideRefreshing = guideRefreshing,
                    onRefreshGuide = { refreshGuide() }
                )
                Screen.Multi -> MultiViewScreen(
                    channels = channels,
                    start = index,
                    onExit = { ch ->
                        tuneTo(ch)
                        go(Screen.Live)
                    },
                    isLocked = { lockedNow(it) },
                    resolve = { ch ->
                        val portal = stalker
                        when {
                            ch.origin != "stalker" -> ch.streamUrl
                            portal == null -> null
                            else -> withContext(Dispatchers.IO) { runCatching { portal.resolveLink(ch.streamUrl) }.getOrNull() }
                        }
                    },
                    onClose = { go(Screen.Live) }
                )
                Screen.OnDemand -> OnDemandScreen(
                    nav = nav,
                    settings = settings,
                    jellyfinToken = jellyfinToken,
                    jellyfinUserId = jellyfinUserId,
                    shelves = shelves,
                    userState = userState,
                    pendingVod = pendingVod,
                    onPendingConsumed = { pendingVod = null },
                    onPlaybackEnded = { shelvesTick++ },
                    onLeaveDetail = {
                        val back = vodOrigin
                        vodOrigin = Screen.OnDemand
                        if (back != Screen.OnDemand) go(back)
                    },
                    requireUnlock = { item, proceed -> if (vodLocked(item)) askContentPin(proceed) else proceed() }
                )
                Screen.Search -> SearchScreen(
                    nav = nav,
                    channels = channels,
                    favorites = favorites,
                    guide = guide,
                    settings = settings,
                    onPlayChannel = { ch ->
                        tuneTo(ch)
                        go(Screen.Live)
                    },
                    onOpenProgramme = { ch, program -> startReplay(ch, program) },
                    onOpenVod = { item ->
                        fun openItem() {
                            vodOrigin = Screen.Search
                            pendingVod = item
                            go(Screen.OnDemand)
                        }
                        if (vodLocked(item)) askContentPin { openItem() } else openItem()
                    }
                )
                Screen.Settings -> SettingsScreen(
                    nav = nav,
                    settings = settings,
                    onSave = { updated ->
                        store.save(updated)
                        settings = updated
                    },
                    startPane = settingsPane,
                    channelCount = channels.size,
                    allChannels = loadedChannels,
                    profiles = profiles,
                    activeProfileId = profileId,
                    onSwitchProfile = { switchProfile(it) },
                    onAddProfile = { name ->
                        profileStore.add(name)
                        profiles = profileStore.profiles()
                    },
                    onRemoveProfile = { id ->
                        val wasActive = id == profileId
                        profileStore.remove(id)
                        store.clearProfileJellyfin(id)
                        profiles = profileStore.profiles()
                        profileId = profileStore.active()
                        if (wasActive) contentUnlocked = false
                    },
                    pinSet = pinSet,
                    onSetPin = {
                        pinError = null
                        pinPurpose = PinPurpose.SET_NEW
                    },
                    onRemovePin = {
                        pinError = null
                        pinPurpose = PinPurpose.REMOVE
                    },
                    update = update,
                    updateMessage = updateMessage,
                    updateBusy = updateBusy,
                    onCheckUpdate = { checkForUpdate(manual = true) },
                    onInstallUpdate = { installUpdate(it) },
                    lastCrash = lastCrash,
                    onClearCrash = {
                        CrashLog.clear(context)
                        lastCrash = null
                    },
                    showToast = showToast,
                    hiddenFilters = hiddenTerms.joinToString(","),
                    onHiddenFilters = { value ->
                        userState.setHiddenFilters(value)
                        hiddenTerms = value.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
                    },
                    guideRefreshing = guideRefreshing,
                    onRefreshGuide = { refreshGuide() },
                    onJellyfinSave = { updated ->
                        // Only the Jellyfin fields come from the pane. The rest is taken from what is saved
                        // now, so a live login saved a moment earlier from the same form is kept.
                        val merged = settings.copy(
                            jellyfinServer = updated.jellyfinServer,
                            jellyfinToken = updated.jellyfinToken,
                            jellyfinUserId = updated.jellyfinUserId
                        )
                        if (merged.jellyfinToken.isBlank()) {
                            store.clearProfileJellyfin(profileId)
                            jfOverride = null
                            if (profileId == ProfileStore.DEFAULT_ID) {
                                store.save(merged)
                                settings = merged
                            }
                        } else if (profileId == ProfileStore.DEFAULT_ID) {
                            store.clearProfileJellyfin(profileId)
                            jfOverride = null
                            store.save(merged)
                            settings = merged
                        } else {
                            if (settings.jellyfinServer != merged.jellyfinServer) {
                                val serverOnly = settings.copy(jellyfinServer = merged.jellyfinServer)
                                store.save(serverOnly)
                                settings = serverOnly
                            }
                            store.saveProfileJellyfin(profileId, merged.jellyfinToken, merged.jellyfinUserId)
                            jfOverride = merged.jellyfinToken to merged.jellyfinUserId
                        }
                    },
                    onBackupExportJson = { backupStore.backupJson() },
                    onBackupRestoreJson = { json -> backupStore.restoreBackup(json) },
                    onBackupExport = { uri -> backupStore.writeBackup(uri) },
                    onBackupRestore = { uri -> backupStore.restoreBackup(uri) },
                    vodCategories = vodCategories,
                    jellyfinSignedIn = jellyfinSignedIn
                )
            }
        }

        if (showProfiles) {
            ProfilePicker(
                profiles = profiles,
                activeId = profileId,
                onPick = { id ->
                    switchProfile(id)
                    showProfiles = false
                    showToast("Switched to ${profiles.firstOrNull { it.id == id }?.name ?: "profile"}")
                },
                onManage = {
                    showProfiles = false
                    settingsPane = SettingsPane.Profiles
                    go(Screen.Settings)
                },
                onClose = { showProfiles = false },
                modifier = Modifier.zIndex(3f)
            )
        }

        // The PIN pad sits over whatever is showing.
        Box(Modifier.zIndex(4f)) {
            when (pinPurpose) {
                PinPurpose.NONE -> Unit
                PinPurpose.UNLOCK -> PinPad(
                    title = "Enter your PIN",
                    subtitle = pinSubtitle("Settings are locked."),
                    onComplete = { entered ->
                        if (pinMatches(entered)) {
                            settingsUnlocked = true
                            pinPurpose = PinPurpose.NONE
                            go(Screen.Settings)
                        }
                    },
                    onCancel = { pinPurpose = PinPurpose.NONE }
                )
                PinPurpose.SET_NEW -> PinPad(
                    title = "Choose a PIN",
                    subtitle = pinError ?: "Four numbers you will remember.",
                    onComplete = { entered ->
                        pinFirst = entered
                        pinError = null
                        pinPurpose = PinPurpose.SET_CONFIRM
                    },
                    onCancel = { pinPurpose = PinPurpose.NONE }
                )
                PinPurpose.SET_CONFIRM -> PinPad(
                    title = "Enter it again",
                    subtitle = "To make sure.",
                    onComplete = { entered ->
                        if (entered == pinFirst) {
                            store.setPin(entered)
                            pinSet = true
                            pinPurpose = PinPurpose.NONE
                            showToast("PIN set. Settings now need it.")
                        } else {
                            pinError = "Those did not match. Start again."
                            pinPurpose = PinPurpose.SET_NEW
                        }
                    },
                    onCancel = { pinPurpose = PinPurpose.NONE }
                )
                PinPurpose.CONTENT -> PinPad(
                    title = "Enter your PIN",
                    subtitle = pinSubtitle("This is locked."),
                    onComplete = { entered ->
                        if (pinMatches(entered)) {
                            contentUnlocked = true
                            pinPurpose = PinPurpose.NONE
                            val next = afterUnlock
                            afterUnlock = null
                            next?.invoke()
                        }
                    },
                    onCancel = {
                        pinPurpose = PinPurpose.NONE
                        afterUnlock = null
                    }
                )
                PinPurpose.REMOVE -> PinPad(
                    title = "Turn off the lock",
                    subtitle = pinSubtitle("Enter the current PIN."),
                    onComplete = { entered ->
                        if (pinMatches(entered)) {
                            store.setPin("")
                            pinSet = false
                            pinPurpose = PinPurpose.NONE
                            showToast("Parental lock is off")
                        }
                    },
                    onCancel = { pinPurpose = PinPurpose.NONE }
                )
            }
        }

        reminderShown?.let { reminder ->
            ReminderPopup(
                reminder = reminder,
                onWatch = {
                    firedReminders = firedReminders + reminderKey(reminder)
                    dueReminder = null
                    val ch = channels.firstOrNull { it.id == reminder.channelId }
                    if (ch == null) {
                        showToast("${reminder.channelName} is not in your channel list")
                    } else {
                        tuneTo(ch)
                        go(Screen.Live)
                    }
                },
                onDismiss = {
                    firedReminders = firedReminders + reminderKey(reminder)
                    dueReminder = null
                },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp).zIndex(5f)
            )
        }

        AnimatedVisibility(
            visible = toast != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp).zIndex(5f)
        ) {
            Text(
                toast.orEmpty(),
                style = TvType.SmallStrong,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Tv.Surface3)
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }
    }
}

/**
 * A programme the viewer asked to be reminded about is starting. Watch takes focus, Back dismisses,
 * and it goes by itself after a minute or once the showing is two minutes in.
 */
@Composable
private fun ReminderPopup(reminder: Reminder, onWatch: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val dismiss by rememberUpdatedState(onDismiss)
    val watch = remember { FocusRequester() }
    BackHandler { dismiss() }
    LaunchedEffect(reminder) {
        withFrameNanos { }
        runCatching { watch.requestFocus() }
    }
    LaunchedEffect(reminder) {
        val wait = minOf(60_000L, reminder.start + 2 * 60_000L - System.currentTimeMillis())
        delay(wait.coerceAtLeast(0L))
        dismiss()
    }
    Column(
        modifier
            .trapFocus()
            .clip(RoundedCornerShape(16.dp))
            .background(Tv.Surface3)
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("${reminder.title} is starting on ${reminder.channelName}", style = TvType.BodyStrong)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Watch", onWatch, modifier = Modifier.focusRequester(watch))
            PillButton("Dismiss", { dismiss() })
        }
    }
}

/** Opens from the name at the top right. Picking a profile switches favorites, recents and resume points. */
@Composable
private fun ProfilePicker(
    profiles: List<Profile>,
    activeId: String,
    onPick: (String) -> Unit,
    onManage: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onClose() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Color(0x80000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() }
    ) {
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .trapFocus()
                .padding(top = 52.dp, end = 32.dp)
                .width(250.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Tv.Surface2)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text("Who is watching?", style = TvType.CaptionStrong, color = Tv.Ink3, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            profiles.forEach { p ->
                Focusable(
                    onClick = { onPick(p.id) },
                    modifier = (if (p.id == activeId) Modifier.focusRequester(first) else Modifier).fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(p.name, Modifier.size(26.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(p.name, style = TvType.BodyStrong, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (p.id == activeId) Icon(TvIcons.Check, contentDescription = "Active", tint = if (LocalFocused.current) Tv.FocusInk else Tv.Accent, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Focusable(onClick = onManage, modifier = Modifier.fillMaxWidth().height(40.dp), shape = RoundedCornerShape(10.dp), contentAlignment = Alignment.CenterStart) {
                Text("Manage profiles", style = TvType.SmallStrong, color = ink2, modifier = Modifier.padding(start = 48.dp))
            }
        }
    }
}
