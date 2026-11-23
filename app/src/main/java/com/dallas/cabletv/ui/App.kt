package com.dallas.cabletv.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.dallas.cabletv.BuildConfig
import com.dallas.cabletv.data.AppUpdater
import com.dallas.cabletv.data.ChannelSource
import com.dallas.cabletv.data.CrashLog
import com.dallas.cabletv.data.EpgCache
import com.dallas.cabletv.data.epgAddresses
import com.dallas.cabletv.data.guideRefreshMessage
import com.dallas.cabletv.data.JellyfinClient
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.Profile
import com.dallas.cabletv.data.Program
import com.dallas.cabletv.data.ProfileStore
import com.dallas.cabletv.data.Recording
import com.dallas.cabletv.data.RecordingService
import com.dallas.cabletv.data.RecordingStore
import com.dallas.cabletv.data.Reminder
import com.dallas.cabletv.data.ScheduledRecording
import com.dallas.cabletv.data.SettingsStore
import com.dallas.cabletv.data.StalkerClient
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

enum class Screen { Live, Home, Guide, OnDemand, Search, Settings, Welcome, Multi }

/** Which PIN pad, if any, is on screen. */
private enum class PinPurpose { NONE, UNLOCK, SET_NEW, SET_CONFIRM, REMOVE, CONTENT }

private fun Tab.toScreen(): Screen = when (this) {
    Tab.Home -> Screen.Home
    Tab.Guide -> Screen.Guide
    Tab.OnDemand -> Screen.OnDemand
    Tab.Search -> Screen.Search
    Tab.Settings -> Screen.Settings
}

@Composable
fun CableApp(store: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(store.load()) }
    // Read once per launch. The app opens straight into live TV on the last channel.
    val lastChannelId = remember { store.lastChannelId() }
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
        if (CrashLog.takeNotice(context)) showToast("The app closed unexpectedly last time. Details are in Settings, Updates.")
    }

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
    var tuneIndex by remember { mutableIntStateOf(0) }
    var restoredChannel by remember { mutableStateOf(false) }
    var priorChannelId by remember { mutableStateOf<String?>(null) }

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
    LaunchedEffect(settings.xtreamServer, settings.xtreamUser, settings.xtreamPass, settings.m3uUrl, settings.stalkerUrl, settings.stalkerMac, channelReload) {
        loadedChannels = emptyList()
        channelError = null
        if (!settings.hasLiveSource) return@LaunchedEffect
        channelsLoading = true
        withContext(Dispatchers.IO) { runCatching { ChannelSource.load(settings) } }
            .onSuccess { loadedChannels = it }
            .onFailure { channelError = it.message ?: "The channel list did not load" }
        channelsLoading = false
    }

    // The full schedule is downloaded now, again when asked, and then hourly.
    var guideReload by remember { mutableIntStateOf(0) }
    var guideRefreshing by remember { mutableStateOf(false) }
    fun refreshGuide() {
        if (!settings.hasLiveSource) {
            showToast("Add a live TV login before refreshing the guide")
            return
        }
        if (!guideRefreshing) guideReload++
    }
    LaunchedEffect(settings.xtreamServer, settings.xtreamUser, settings.xtreamPass, settings.m3uUrl, settings.epgUrl, settings.stalkerUrl, guideReload) {
        val s = settings
        val manual = guideReload > 0
        var announced = false
        if (!s.hasLiveSource) {
            guideRefreshing = false
            return@LaunchedEffect
        }
        while (s.hasLiveSource) {
            guideRefreshing = true
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val urls = ChannelSource.guideUrls(s)
                    val failed = if (urls.isEmpty()) emptyList() else EpgCache.refreshAll(urls)
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
                    showToast("The guide did not load")
                }
            }
            delay(60 * 60 * 1000L)
        }
    }

    // Until the saved channel is found, live TV starts on it directly, so channel 1 never plays first.
    val index = if (!restoredChannel && channels.isNotEmpty()) {
        (lastChannelId?.let { id -> channels.indexOfFirst { it.id == id } } ?: -1).coerceAtLeast(0)
    } else {
        tuneIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
    }
    LaunchedEffect(channels) {
        if (!restoredChannel && channels.isNotEmpty()) {
            restoredChannel = true
            tuneIndex = index
        }
    }
    val current = channels.getOrNull(index)
    val currentId by rememberUpdatedState(current?.id)

    // ------------------------------------------------------------ Profiles
    val profileStore = remember { ProfileStore(context) }
    var profiles by remember { mutableStateOf(profileStore.profiles()) }
    var profileId by remember { mutableStateOf(profileStore.active()) }
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
    var replayUrl by remember { mutableStateOf<String?>(null) }
    var previewBounds by remember { mutableStateOf<Rect?>(null) }
    // The channel keeps playing behind Home and in the guide's preview. Elsewhere it stops, so a
    // provider that allows one stream at a time can play movies.
    val liveActive = settings.hasLiveSource && screen in setOf(Screen.Live, Screen.Home, Screen.Guide)
    val channelLocked = current != null && isLocked(current.name, current.group, settings.lockedTerms, pinSet) && !contentUnlocked
    LaunchedEffect(channelLocked, current?.id) {
        if (channelLocked && pinPurpose == PinPurpose.NONE) {
            pinError = null
            pinPurpose = PinPurpose.CONTENT
        }
    }
    val playUrl = if (channelLocked) null else replayUrl ?: current?.streamUrl
    LaunchedEffect(playUrl, liveActive, current?.origin) {
        if (!liveActive || playUrl == null) {
            live.stop()
            return@LaunchedEffect
        }
        // A short wait means holding a key only loads the channel you stop on.
        delay(250)
        val url = if (replayUrl == null && current?.origin == "stalker") {
            val cmd = current.streamUrl
            val portal = stalker
            if (portal == null) null
            else withContext(Dispatchers.IO) { runCatching { portal.resolveLink(cmd) }.getOrNull() }
        } else {
            playUrl
        }
        if (url == null) showToast("That channel did not start")
        else live.play(url, if (replayUrl == null) current?.fallbackStreamUrl else null)
    }
    // Remember the channel on screen, so the next launch returns to it and it joins Recently watched.
    LaunchedEffect(current?.id, profileId) {
        val ch = current ?: return@LaunchedEffect
        delay(600)
        store.saveLastChannelId(ch.id)
        userState.addRecent(ch.id)
        recents = userState.recents()
    }
    lateinit var open: (Screen) -> Unit

    fun applyTune(next: Int) {
        val nowId = channels.getOrNull(tuneIndex)?.id
        val nextId = channels[next].id
        if (restoredChannel && nowId != null && nowId != nextId) priorChannelId = nowId
        tuneIndex = next
        restoredChannel = true
        replayUrl = null
    }

    fun tuneToIndex(i: Int) {
        if (channels.isEmpty()) return
        val next = ((i % channels.size) + channels.size) % channels.size
        val ch = channels[next]
        if (isLocked(ch.name, ch.group, settings.lockedTerms, pinSet) && !contentUnlocked) {
            afterUnlock = { applyTune(next) }
            pinError = null
            pinPurpose = PinPurpose.CONTENT
            return
        }
        applyTune(next)
    }

    fun tuneTo(ch: LiveChannel) {
        val i = channels.indexOfFirst { it.id == ch.id }
        if (i >= 0) tuneToIndex(i) else replayUrl = null
    }

    fun tuneToPrevious() {
        val id = priorChannelId
        val i = id?.let { wanted -> channels.indexOfFirst { it.id == wanted } } ?: -1
        if (i < 0) showToast("No previous channel") else tuneToIndex(i)
    }

    fun startReplay(ch: LiveChannel, program: Program) {
        if (!ch.catchup || ch.origin == "stalker") {
            showToast("${program.title} is not available to replay")
            return
        }
        if (isLocked(ch.name, ch.group, settings.lockedTerms, pinSet) && !contentUnlocked) {
            afterUnlock = { startReplay(ch, program) }
            pinError = null
            pinPurpose = PinPurpose.CONTENT
            return
        }
        fun begin(url: String) {
            if (channels.getOrNull(tuneIndex)?.id != ch.id) tuneTo(ch)
            replayUrl = url
            open(Screen.Live)
            showToast("Playing ${program.title} from the start")
        }
        val client = xtream
        if (ch.origin != "xtream" || client == null) {
            val local = m3uCatchupUrl(ch.streamUrl, ch.catchupType, ch.catchupSource, program.start, program.end)
            if (local == null) showToast("${program.title} is not available to replay") else begin(local)
            return
        }
        if (channels.getOrNull(tuneIndex)?.id != ch.id) tuneTo(ch)
        open(Screen.Live)
        scope.launch {
            val url = withContext(Dispatchers.IO) {
                runCatching { client.resolveTimeshiftUrl(ch.id, program.start, program.end) }.getOrNull()
            }
            when {
                url == null -> showToast("${program.title} is not available to replay")
                currentId == ch.id -> {
                    replayUrl = url
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
        if (isLocked(ch.name, ch.group, settings.lockedTerms, pinSet) && !contentUnlocked) {
            afterUnlock = { record(ch, program) }
            pinError = null
            pinPurpose = PinPurpose.CONTENT
            return
        }
        if (ch.origin == "stalker") {
            showToast("Recording is not available for this portal")
            return
        }
        if (RecordingService.isBusy()) {
            showToast("A recording is already running")
            return
        }
        val id = "${ch.id.hashCode()}-${program.start}"
        val storeFiles = RecordingStore(context.filesDir)
        fun startWith(url: String) {
            val nowMs = System.currentTimeMillis()
            if (program.start > nowMs + 20_000) {
                storeFiles.addSchedule(ScheduledRecording(id, ch.id, ch.name, program.title, url, program.start, program.end))
                showToast("Will record ${program.title}. Leave the app open until it starts. This uses another connection.")
            } else {
                RecordingService.start(context, id, url, program.title, ch.name, program.end)
                showToast("Recording ${program.title}. This uses another connection.")
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
        val xt = xtream ?: return@LaunchedEffect
        providerLoading = true
        withContext(Dispatchers.IO) { runCatching { xt.vodMovies(1500) to xt.vodSeries(800) } }
            .onSuccess { providerVod = it }
            .onFailure { providerError = it.message ?: "Your provider did not answer" }
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
            .onFailure { jellyfinError = it.message ?: "Jellyfin did not answer" }
        jellyfinLoading = false
    }
    val localResume = remember(userState, shelvesTick) { userState.continueWatching().map { it.first } }
    val shelves = VodShelves(
        continueWatching = (localResume + jellyfinShelves.first).distinctBy { it.source + ":" + it.id },
        recent = jellyfinShelves.second,
        movies = (providerVod.first + jellyfinBrowse.first).distinctBy { it.source + ":" + it.id },
        series = (providerVod.second + jellyfinBrowse.second).distinctBy { it.source + ":" + it.id },
        loading = providerLoading || jellyfinLoading,
        error = jellyfinError ?: providerError
    )
    var pendingVod by remember { mutableStateOf<VodItem?>(null) }

    // ------------------------------------------------------------ Updates
    var update by remember { mutableStateOf<AppUpdater.Release?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updateBusy by remember { mutableStateOf(false) }

    // Asks GitHub for a newer build. A manual check always reports back, an automatic one stays quiet.
    fun checkForUpdate(manual: Boolean) {
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
                .onFailure { if (manual) updateMessage = "The update check did not work. ${it.message ?: ""}".trim() }
        }
    }

    // Downloads the build and opens the system installer. Android asks the person to confirm.
    fun installUpdate(release: AppUpdater.Release) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            updateMessage = "Allow installs from this app on the next screen, then press Install again."
            context.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
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
                    AppUpdater.install(context, apk)
                }
                .onFailure { updateMessage = "The download did not finish. ${it.message ?: ""}".trim() }
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
    LaunchedEffect(Unit) {
        val finished = mutableSetOf<String>()
        while (true) {
            val item = dueSchedule(RecordingStore(context.filesDir).schedule(), System.currentTimeMillis(), finished).firstOrNull()
            if (item != null && !RecordingService.isBusy()) {
                finished += item.id
                RecordingService.start(context, item.id, item.url, item.title, item.channelName, item.end)
                showToast("Recording ${item.title}")
            }
            delay(20_000)
        }
    }

    LaunchedEffect(Unit) { checkForUpdate(manual = false) }

    // ------------------------------------------------------------ Parental lock
    var settingsUnlocked by remember { mutableStateOf(false) }
    var pinFirst by remember { mutableStateOf("") }

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

        Box(Modifier.fillMaxSize().zIndex(1f)) {
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
                    replaying = replayUrl != null,
                    onTune = { tuneToIndex(it) },
                    onToggleFavorite = toggleFavorite,
                    onReplay = { ch, p -> startReplay(ch, p) },
                    onReturnToLive = {
                        replayUrl = null
                        showToast("Back to live")
                    },
                    onOpenGuide = { go(Screen.Guide) },
                    onOpenHome = { go(Screen.Home) },
                    onOpenSearch = { go(Screen.Search) },
                    onLastChannel = { tuneToPrevious() },
                    onExit = { go(Screen.Home) },
                    showToast = showToast
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
                        if (isLocked(item.name, item.category, settings.lockedTerms, pinSet) && !contentUnlocked) {
                            afterUnlock = { openItem() }
                            pinError = null
                            pinPurpose = PinPurpose.CONTENT
                        } else {
                            openItem()
                        }
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
                    }
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
                    }
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
                        if (isLocked(item.name, item.category, settings.lockedTerms, pinSet) && !contentUnlocked) {
                            afterUnlock = { openItem() }
                            pinError = null
                            pinPurpose = PinPurpose.CONTENT
                        } else {
                            openItem()
                        }
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
                        profileStore.remove(id)
                        profiles = profileStore.profiles()
                        profileId = profileStore.active()
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
                        if (updated.jellyfinToken.isBlank()) {
                            store.clearProfileJellyfin(profileId)
                            jfOverride = null
                            if (profileId == ProfileStore.DEFAULT_ID) {
                                store.save(updated)
                                settings = updated
                            }
                        } else if (profileId == ProfileStore.DEFAULT_ID) {
                            store.clearProfileJellyfin(profileId)
                            jfOverride = null
                            store.save(updated)
                            settings = updated
                        } else {
                            if (settings.jellyfinServer != updated.jellyfinServer) {
                                val serverOnly = settings.copy(jellyfinServer = updated.jellyfinServer)
                                store.save(serverOnly)
                                settings = serverOnly
                            }
                            store.saveProfileJellyfin(profileId, updated.jellyfinToken, updated.jellyfinUserId)
                            jfOverride = updated.jellyfinToken to updated.jellyfinUserId
                        }
                    }
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
                    subtitle = pinError ?: "Settings are locked.",
                    onComplete = { entered ->
                        if (store.checkPin(entered)) {
                            settingsUnlocked = true
                            pinPurpose = PinPurpose.NONE
                            go(Screen.Settings)
                        } else {
                            pinError = "That PIN did not match. Try again."
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
                    subtitle = pinError ?: "This is locked.",
                    onComplete = { entered ->
                        if (store.checkPin(entered)) {
                            contentUnlocked = true
                            pinPurpose = PinPurpose.NONE
                            val next = afterUnlock
                            afterUnlock = null
                            next?.invoke()
                        } else {
                            pinError = "That PIN did not match. Try again."
                        }
                    },
                    onCancel = {
                        pinPurpose = PinPurpose.NONE
                        afterUnlock = null
                    }
                )
                PinPurpose.REMOVE -> PinPad(
                    title = "Turn off the lock",
                    subtitle = pinError ?: "Enter the current PIN.",
                    onComplete = { entered ->
                        if (store.checkPin(entered)) {
                            store.setPin("")
                            pinSet = false
                            pinPurpose = PinPurpose.NONE
                            showToast("Parental lock is off")
                        } else {
                            pinError = "That PIN did not match. Try again."
                        }
                    },
                    onCancel = { pinPurpose = PinPurpose.NONE }
                )
            }
        }

        dueReminder?.let { reminder ->
            Column(
                Modifier.align(Alignment.TopCenter).padding(top = 24.dp).zIndex(5f)
                    .clip(RoundedCornerShape(16.dp)).background(Tv.Surface3).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("${reminder.title} is starting on ${reminder.channelName}", style = TvType.BodyStrong)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Watch", {
                        firedReminders = firedReminders + reminderKey(reminder)
                        dueReminder = null
                        channels.firstOrNull { it.id == reminder.channelId }?.let { ch ->
                            tuneTo(ch)
                            go(Screen.Live)
                        }
                    })
                    PillButton("Dismiss", {
                        firedReminders = firedReminders + reminderKey(reminder)
                        dueReminder = null
                    })
                }
            }
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
