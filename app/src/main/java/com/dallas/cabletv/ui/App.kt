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
import com.dallas.cabletv.data.EpgCache
import com.dallas.cabletv.data.JellyfinClient
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.Profile
import com.dallas.cabletv.data.ProfileStore
import com.dallas.cabletv.data.SettingsStore
import com.dallas.cabletv.data.UserStateStore
import com.dallas.cabletv.data.VodItem
import com.dallas.cabletv.data.XtreamClient
import com.dallas.cabletv.data.displayName
import com.dallas.cabletv.data.isHidden
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

enum class Screen { Live, Home, Guide, OnDemand, Search, Settings, Welcome }

/** Which PIN pad, if any, is on screen. */
private enum class PinPurpose { NONE, UNLOCK, SET_NEW, SET_CONFIRM, REMOVE }

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

    // ------------------------------------------------------------ Channels and guide
    var loadedChannels by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    val channels = remember(loadedChannels, settings.hiddenFilters, settings.stripPrefixes) {
        loadedChannels
            .distinctBy { it.id }
            .filterNot { isHidden(it, settings.hiddenTerms) }
            .map { if (settings.stripPrefixes) it.copy(name = displayName(it.name)) else it }
    }
    var channelsLoading by remember { mutableStateOf(false) }
    var channelError by remember { mutableStateOf<String?>(null) }
    var tuneIndex by remember { mutableIntStateOf(0) }
    var restoredChannel by remember { mutableStateOf(false) }

    val xtream = remember(settings.xtreamServer, settings.xtreamUser, settings.xtreamPass) {
        if (settings.hasXtream) XtreamClient(settings.xtreamServer, settings.xtreamUser, settings.xtreamPass) else null
    }
    val guide = remember { GuideData(scope) }
    SideEffect { guide.epg = xtream }

    // Reload the channel list only when the live login changes. Hiding channels does not reload.
    LaunchedEffect(settings.xtreamServer, settings.xtreamUser, settings.xtreamPass, settings.m3uUrl) {
        loadedChannels = emptyList()
        channelError = null
        if (!settings.hasLiveSource) return@LaunchedEffect
        channelsLoading = true
        withContext(Dispatchers.IO) { runCatching { ChannelSource.load(settings) } }
            .onSuccess { loadedChannels = it }
            .onFailure { channelError = it.message ?: "The channel list did not load" }
        channelsLoading = false
    }

    // The full schedule is downloaded now and then hourly: from the provider login, or the playlist's guide link.
    LaunchedEffect(settings.xtreamServer, settings.xtreamUser, settings.xtreamPass, settings.m3uUrl) {
        val s = settings
        while (s.hasLiveSource) {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (s.hasXtream) EpgCache.refresh(s.xtreamServer, s.xtreamUser, s.xtreamPass)
                    else ChannelSource.guideUrl(s)?.let { EpgCache.refreshUrl(it) }
                }
            }
            guide.scheduleUpdated()
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
    }

    // ------------------------------------------------------------ Live player
    val live = remember { LivePlayer(context) }
    DisposableEffect(Unit) { onDispose { live.release() } }
    var replayUrl by remember { mutableStateOf<String?>(null) }
    var previewBounds by remember { mutableStateOf<Rect?>(null) }
    // The channel keeps playing behind Home and in the guide's preview. Elsewhere it stops, so a
    // provider that allows one stream at a time can play movies.
    val liveActive = settings.hasLiveSource && screen in setOf(Screen.Live, Screen.Home, Screen.Guide)
    val playUrl = replayUrl ?: current?.streamUrl
    LaunchedEffect(playUrl, liveActive) {
        if (!liveActive || playUrl == null) {
            live.stop()
            return@LaunchedEffect
        }
        // A short wait means holding a key only loads the channel you stop on.
        delay(250)
        live.play(playUrl, if (replayUrl == null) current?.fallbackStreamUrl else null)
    }
    // Remember the channel on screen, so the next launch returns to it and it joins Recently watched.
    LaunchedEffect(current?.id, profileId) {
        val ch = current ?: return@LaunchedEffect
        delay(600)
        store.saveLastChannelId(ch.id)
        userState.addRecent(ch.id)
        recents = userState.recents()
    }
    fun tuneTo(ch: LiveChannel) {
        val i = channels.indexOfFirst { it.id == ch.id }
        if (i >= 0) {
            tuneIndex = i
            restoredChannel = true
        }
        replayUrl = null
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
        withContext(Dispatchers.IO) { runCatching { xt.vodMovies() to xt.vodSeries() } }
            .onSuccess { providerVod = it }
            .onFailure { providerError = it.message ?: "Your provider did not answer" }
        providerLoading = false
    }
    var jellyfinShelves by remember { mutableStateOf<Pair<List<VodItem>, List<VodItem>>>(emptyList<VodItem>() to emptyList()) }
    var jellyfinError by remember { mutableStateOf<String?>(null) }
    var jellyfinLoading by remember { mutableStateOf(false) }
    LaunchedEffect(settings.jellyfinServer, settings.jellyfinToken, settings.jellyfinUserId, shelvesTick) {
        jellyfinError = null
        if (!settings.hasJellyfin) {
            jellyfinShelves = emptyList<VodItem>() to emptyList()
            return@LaunchedEffect
        }
        val jf = JellyfinClient(settings.jellyfinServer, settings.jellyfinToken, settings.jellyfinUserId)
        jellyfinLoading = true
        withContext(Dispatchers.IO) { runCatching { jf.continueWatching() to jf.recentlyAdded() } }
            .onSuccess { jellyfinShelves = it }
            .onFailure { jellyfinError = it.message ?: "Jellyfin did not answer" }
        jellyfinLoading = false
    }
    val localResume = remember(userState, shelvesTick) { userState.continueWatching().map { it.first } }
    val shelves = VodShelves(
        continueWatching = (localResume + jellyfinShelves.first).distinctBy { it.source + ":" + it.id },
        recent = jellyfinShelves.second,
        movies = providerVod.first,
        series = providerVod.second,
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

    LaunchedEffect(Unit) { checkForUpdate(manual = false) }

    // ------------------------------------------------------------ Parental lock
    var pinSet by remember { mutableStateOf(store.hasPin()) }
    var settingsUnlocked by remember { mutableStateOf(false) }
    var pinPurpose by remember { mutableStateOf(PinPurpose.NONE) }
    var pinFirst by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

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

    val nav = NavActions(
        select = { tab -> go(tab.toScreen()) },
        back = {
            when {
                screen != Screen.Home -> go(Screen.Home)
                settings.hasLiveSource && channels.isNotEmpty() -> go(Screen.Live)
                else -> (context as? Activity)?.finish()
            }
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
            val wantsLive = listOf("xs", "xu", "xp", "m3u").any { !fields[it].isNullOrBlank() }
            if (wantsLive) {
                runCatching { checkLiveLogin(s, fields["xs"].orEmpty(), fields["xu"].orEmpty(), fields["xp"].orEmpty(), fields["m3u"].orEmpty()) }
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
                    loading = channelsLoading,
                    error = channelError,
                    canReplay = xtream != null,
                    replaying = replayUrl != null,
                    onTune = { i ->
                        tuneIndex = i
                        restoredChannel = true
                        replayUrl = null
                    },
                    onToggleFavorite = toggleFavorite,
                    onReplay = { ch, p ->
                        val client = xtream
                        if (client != null) {
                            scope.launch {
                                // The provider is asked which replay address works, so this runs off the main thread.
                                val url = withContext(Dispatchers.IO) {
                                    runCatching { client.resolveTimeshiftUrl(ch.id, p.start, p.end) }.getOrNull()
                                }
                                when {
                                    url == null -> showToast("${p.title} is not available to replay")
                                    currentId == ch.id -> {
                                        replayUrl = url
                                        showToast("Playing ${p.title} from the start")
                                    }
                                }
                            }
                        }
                    },
                    onReturnToLive = {
                        replayUrl = null
                        showToast("Back to live")
                    },
                    onOpenGuide = { go(Screen.Guide) },
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
                    onWatch = { go(Screen.Live) },
                    onGuide = { go(Screen.Guide) },
                    onTune = { ch ->
                        tuneTo(ch)
                        go(Screen.Live)
                    },
                    onOpenVod = { item ->
                        vodOrigin = Screen.Home
                        pendingVod = item
                        go(Screen.OnDemand)
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
                    onWatch = { ch ->
                        tuneTo(ch)
                        go(Screen.Live)
                    },
                    onToggleFavorite = toggleFavorite,
                    onPreviewBounds = { previewBounds = it },
                    showToast = showToast
                )
                Screen.OnDemand -> OnDemandScreen(
                    nav = nav,
                    settings = settings,
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
                    onOpenVod = { item ->
                        vodOrigin = Screen.Search
                        pendingVod = item
                        go(Screen.OnDemand)
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
                    showToast = showToast
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
