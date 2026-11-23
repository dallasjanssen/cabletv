package com.dallas.cabletv.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dallas.cabletv.BuildConfig
import com.dallas.cabletv.data.AppUpdater
import com.dallas.cabletv.data.ChannelSource
import com.dallas.cabletv.data.EpgCache
import com.dallas.cabletv.data.JellyfinClient
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.Profile
import com.dallas.cabletv.data.ProfileStore
import com.dallas.cabletv.data.Settings
import com.dallas.cabletv.data.SettingsStore
import com.dallas.cabletv.data.UserStateStore
import com.dallas.cabletv.data.VodItem
import com.dallas.cabletv.data.XtreamClient
import com.dallas.cabletv.data.channelFilters
import com.dallas.cabletv.data.displayName
import com.dallas.cabletv.data.isHidden
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

enum class Screen { Home, LiveTv, Guide, OnDemand, Search, Settings }

/** Which PIN pad, if any, is on screen. */
private enum class PinPurpose { NONE, UNLOCK, SET_NEW, SET_CONFIRM, REMOVE }

private fun titleOf(screen: Screen): String = when (screen) {
    Screen.Home -> "CABLE"
    Screen.LiveTv -> "LIVE TV"
    Screen.Guide -> "PROGRAM GUIDE"
    Screen.OnDemand -> "ON DEMAND"
    Screen.Search -> "SEARCH"
    Screen.Settings -> "SETTINGS"
}

@Composable
fun CableApp(store: SettingsStore) {
    var settings by remember { mutableStateOf(store.load()) }
    // Read once per launch. The app opens straight into live TV on the last channel.
    val lastChannelId = remember { store.lastChannelId() }
    var screen by remember {
        mutableStateOf(
            if (settings.hasLiveSource && lastChannelId != null) Screen.LiveTv else Screen.Home
        )
    }
    // The screen the user came from, so Back returns there instead of always going home.
    var previous by remember { mutableStateOf(Screen.Home) }
    // Every channel the source sent, and the list the screens show after hidden channels are removed.
    var loadedChannels by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    val channels = remember(loadedChannels, settings) {
        loadedChannels
            .filterNot { isHidden(it, settings.hiddenTerms) }
            .map { if (settings.stripPrefixes) it.copy(name = displayName(it.name)) else it }
    }
    var channelsLoading by remember { mutableStateOf(false) }
    var channelError by remember { mutableStateOf<String?>(null) }
    var tuneIndex by remember { mutableIntStateOf(0) }
    var restoredChannel by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val updateScope = rememberCoroutineScope()
    var update by remember { mutableStateOf<AppUpdater.Release?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updateBusy by remember { mutableStateOf(false) }

    // Profiles: each has its own favorites, recent channels, and resume points.
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

    // The parental lock: Settings needs the PIN, and it stays open only until Settings is left.
    var pinSet by remember { mutableStateOf(store.hasPin()) }
    var settingsUnlocked by remember { mutableStateOf(false) }
    var pinPurpose by remember { mutableStateOf(PinPurpose.NONE) }
    var pinFirst by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var pinNote by remember { mutableStateOf<String?>(null) }

    // A title chosen in search opens in On Demand.
    var pendingVod by remember { mutableStateOf<VodItem?>(null) }

    val xtream = remember(settings) {
        if (settings.hasXtream) {
            XtreamClient(settings.xtreamServer, settings.xtreamUser, settings.xtreamPass)
        } else {
            null
        }
    }

    // Reload the channel list only when the live source login changes. Hiding channels does not reload.
    LaunchedEffect(settings.xtreamServer, settings.xtreamUser, settings.xtreamPass, settings.m3uUrl) {
        loadedChannels = emptyList()
        channelError = null
        if (!settings.hasLiveSource) return@LaunchedEffect
        channelsLoading = true
        withContext(Dispatchers.IO) { runCatching { ChannelSource.load(settings) } }
            .onSuccess { loadedChannels = it }
            .onFailure { channelError = it.message ?: "Could not load channels" }
        channelsLoading = false
    }

    // The full schedule is downloaded now and then hourly: from the Xtream login, or the guide link in an M3U playlist.
    LaunchedEffect(settings.xtreamServer, settings.xtreamUser, settings.xtreamPass, settings.m3uUrl) {
        while (settings.hasLiveSource) {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (settings.hasXtream) {
                        EpgCache.refresh(settings.xtreamServer, settings.xtreamUser, settings.xtreamPass)
                    } else {
                        ChannelSource.guideUrl(settings)?.let { EpgCache.refreshUrl(it) }
                    }
                }
            }
            delay(60 * 60 * 1000L)
        }
    }

    // Until the saved channel is found, live TV starts on it directly, so channel 1 never plays first.
    // The index is always kept inside the list, so a shorter list after a settings change cannot go blank.
    val startIndex = if (!restoredChannel && channels.isNotEmpty()) {
        (lastChannelId?.let { id -> channels.indexOfFirst { it.id == id } } ?: -1).coerceAtLeast(0)
    } else {
        tuneIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
    }

    LaunchedEffect(channels) {
        if (!restoredChannel && channels.isNotEmpty()) {
            restoredChannel = true
            tuneIndex = startIndex
        }
    }

    // Ticking a checkbox saves straight away, so there is no SAVE step to forget on a remote.
    val toggleFilter: (String) -> Unit = { term ->
        val set = settings.hiddenTerms.toMutableSet()
        if (!set.remove(term)) set.add(term)
        settings = settings.copy(hiddenFilters = set.joinToString(","))
        store.save(settings)
    }

    val toggleStrip: () -> Unit = {
        settings = settings.copy(stripPrefixes = !settings.stripPrefixes)
        store.save(settings)
    }

    // Asks GitHub for a newer build. A manual check always reports back, an automatic one stays quiet.
    fun checkForUpdate(manual: Boolean) {
        updateBusy = true
        updateScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AppUpdater.latest(BuildConfig.VERSION_CODE) }
            }
            updateBusy = false
            result
                .onSuccess { found ->
                    update = found
                    updateMessage = when {
                        found != null -> "Build ${found.versionCode} is ready to install."
                        manual -> "You have the latest build."
                        else -> null
                    }
                }
                .onFailure { if (manual) updateMessage = "Could not check for updates. ${it.message}" }
        }
    }

    // Downloads the build and opens the system installer. Android asks the user to confirm.
    fun installUpdate(release: AppUpdater.Release) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            updateMessage = "Allow installs from CableTV, then press Install again."
            context.startActivity(
                Intent(
                    AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        updateBusy = true
        updateMessage = "Downloading build ${release.versionCode}..."
        updateScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AppUpdater.download(release, File(context.cacheDir, "updates")) }
            }
            updateBusy = false
            result
                .onSuccess { apk ->
                    updateMessage = null
                    AppUpdater.install(context, apk)
                }
                .onFailure { updateMessage = "Download failed. ${it.message}" }
        }
    }

    LaunchedEffect(Unit) { checkForUpdate(manual = false) }

    val navigate: (Screen) -> Unit = { target ->
        if (target == Screen.Settings && pinSet && !settingsUnlocked) {
            pinError = null
            pinPurpose = PinPurpose.UNLOCK
        } else {
            previous = screen
            screen = target
        }
    }
    val goBack: () -> Unit = {
        if (screen == Screen.Settings) settingsUnlocked = false
        screen = previous
        previous = Screen.Home
    }

    BackHandler(enabled = screen != Screen.Home) { goBack() }

    // Cycles to the next profile and loads its favorites and recent channels.
    val cycleProfile: () -> Unit = {
        if (profiles.size > 1) {
            val at = profiles.indexOfFirst { it.id == profileId }
            val next = profiles[(at + 1) % profiles.size]
            profileStore.setActive(next.id)
            profileId = next.id
        }
    }

    // Scan lines are part of the menu look. Live video and playback are shown clean.
    val scanlineModifier = if (screen == Screen.LiveTv || screen == Screen.OnDemand) {
        Modifier
    } else {
        Modifier.scanlines()
    }

    Box(Modifier.fillMaxSize().background(Cable.Canvas).then(scanlineModifier)) {
        when (screen) {
            Screen.Home -> HomeMenu(
                channelCount = channels.size,
                liveReady = settings.hasLiveSource,
                onDemandReady = settings.hasJellyfin || settings.hasXtream,
                updateAvailable = update != null,
                profileName = profileName,
                canCycleProfile = profiles.size > 1,
                onCycleProfile = cycleProfile,
                onSearch = { navigate(Screen.Search) },
                onOpen = navigate
            )
            // Full-bleed screens draw their own scan lines, so playback can stay clean.
            Screen.OnDemand -> OnDemandScreen(
                settings = settings,
                profileId = profileId,
                pendingVod = pendingVod,
                onPendingConsumed = { pendingVod = null },
                onExit = goBack
            )
            Screen.LiveTv -> LiveTvScreen(
                channels = channels,
                loading = channelsLoading,
                error = channelError,
                startIndex = startIndex,
                epg = xtream,
                favorites = favorites,
                onToggleFavorite = toggleFavorite,
                onExit = goBack,
                onChannelChanged = { ch ->
                    store.saveLastChannelId(ch.id)
                    userState.addRecent(ch.id)
                    recents = userState.recents()
                    tuneIndex = channels.indexOfFirst { it.id == ch.id }.coerceAtLeast(0)
                }
            )
            else -> Column(Modifier.fillMaxSize()) {
                CableHeader(title = titleOf(screen), onBack = goBack)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (screen) {
                        Screen.Guide -> GuideScreen(
                            channels = channels,
                            epg = xtream,
                            favorites = favorites,
                            recents = recents,
                            onToggleFavorite = toggleFavorite,
                            onWatch = { idx ->
                                tuneIndex = idx
                                previous = Screen.Guide
                                screen = Screen.LiveTv
                            }
                        )
                        Screen.Search -> SearchScreen(
                            channels = channels,
                            settings = settings,
                            // Names are already shown without prefixes when that setting is on.
                            stripPrefixes = false,
                            onPlayChannel = { ch ->
                                tuneIndex = channels.indexOfFirst { it.id == ch.id }.coerceAtLeast(0)
                                previous = Screen.Search
                                screen = Screen.LiveTv
                            },
                            onOpenVod = { vod ->
                                pendingVod = vod
                                previous = Screen.Search
                                screen = Screen.OnDemand
                            }
                        )
                        Screen.Settings -> SettingsScreen(
                            current = settings,
                            onSave = { updated ->
                                store.save(updated)
                                settings = updated
                                // Leaving Settings locks it again.
                                settingsUnlocked = false
                                previous = Screen.Home
                                screen = Screen.Home
                            },
                            onExit = goBack,
                            update = update,
                            updateMessage = updateMessage,
                            updateBusy = updateBusy,
                            onCheckUpdate = { checkForUpdate(manual = true) },
                            onInstallUpdate = { installUpdate(it) },
                            allChannels = loadedChannels,
                            onToggleFilter = toggleFilter,
                            onToggleStrip = toggleStrip,
                            profiles = profiles,
                            activeProfileId = profileId,
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
                            pinNote = pinNote,
                            onSetPin = {
                                pinError = null
                                pinPurpose = PinPurpose.SET_NEW
                            },
                            onRemovePin = {
                                pinError = null
                                pinPurpose = PinPurpose.REMOVE
                            }
                        )
                        else -> Unit
                    }
                }
            }
        }

        // The PIN pad sits over whatever is showing.
        when (pinPurpose) {
            PinPurpose.NONE -> Unit
            PinPurpose.UNLOCK -> PinPad(
                title = "PARENTAL LOCK",
                subtitle = pinError ?: "Enter the PIN to open Settings.",
                onComplete = { entered ->
                    if (store.checkPin(entered)) {
                        settingsUnlocked = true
                        pinPurpose = PinPurpose.NONE
                        navigate(Screen.Settings)
                    } else {
                        pinError = "Wrong PIN. Try again."
                    }
                },
                onCancel = { pinPurpose = PinPurpose.NONE }
            )
            PinPurpose.SET_NEW -> PinPad(
                title = "NEW PIN",
                subtitle = pinError ?: "Enter four digits.",
                onComplete = { entered ->
                    pinFirst = entered
                    pinError = null
                    pinPurpose = PinPurpose.SET_CONFIRM
                },
                onCancel = { pinPurpose = PinPurpose.NONE }
            )
            PinPurpose.SET_CONFIRM -> PinPad(
                title = "CONFIRM PIN",
                subtitle = pinError ?: "Enter the same four digits again.",
                onComplete = { entered ->
                    if (entered == pinFirst) {
                        store.setPin(entered)
                        pinSet = true
                        pinNote = "PIN set. Settings now need it to open."
                        pinPurpose = PinPurpose.NONE
                    } else {
                        pinError = "The PINs did not match. Start again."
                        pinPurpose = PinPurpose.SET_NEW
                    }
                },
                onCancel = { pinPurpose = PinPurpose.NONE }
            )
            PinPurpose.REMOVE -> PinPad(
                title = "REMOVE PIN",
                subtitle = pinError ?: "Enter the current PIN to remove the lock.",
                onComplete = { entered ->
                    if (store.checkPin(entered)) {
                        store.setPin("")
                        pinSet = false
                        pinNote = "PIN removed."
                        pinPurpose = PinPurpose.NONE
                    } else {
                        pinError = "Wrong PIN. Try again."
                    }
                },
                onCancel = { pinPurpose = PinPurpose.NONE }
            )
        }
    }
}

/**
 * The home screen is a numbered menu, like the front panel of the original
 * box. Keys 1 to 4 on the remote open the matching section.
 */
@Composable
private fun HomeMenu(
    channelCount: Int,
    liveReady: Boolean,
    onDemandReady: Boolean,
    updateAvailable: Boolean,
    profileName: String,
    canCycleProfile: Boolean,
    onCycleProfile: () -> Unit,
    onSearch: () -> Unit,
    onOpen: (Screen) -> Unit
) {
    val firstButton = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstButton.requestFocus() } }

    val entries = listOf(
        Screen.LiveTv to (if (liveReady) "$channelCount CHANNELS" else "NOT SET UP"),
        Screen.Guide to (if (liveReady) "NOW AND NEXT" else "NEEDS A LIVE SOURCE"),
        Screen.OnDemand to (if (onDemandReady) "CONNECTED" else "NOT SET UP"),
        Screen.Settings to "SOURCES AND LOGIN"
    )
    val labels = listOf("LIVE TV", "PROGRAM GUIDE", "ON DEMAND", "SETTINGS")

    Column(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (val code = ev.nativeKeyEvent.keyCode) {
                    in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_4 -> {
                        onOpen(entries[code - KeyEvent.KEYCODE_1].first)
                        true
                    }
                    else -> false
                }
            }
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "CABLE TV",
                style = MaterialTheme.typography.headlineLarge,
                color = Cable.Led,
                modifier = Modifier.weight(1f)
            )
            CableButton(label = "SEARCH", onClick = onSearch)
            Spacer(Modifier.width(12.dp))
            CableButton(
                label = if (canCycleProfile) "PROFILE: ${profileName.uppercase()}" else profileName.uppercase(),
                enabled = canCycleProfile,
                onClick = onCycleProfile
            )
            Spacer(Modifier.width(20.dp))
            Text(
                text = if (updateAvailable) "UPDATE READY IN SETTINGS" else "PRESS 1-4",
                style = MaterialTheme.typography.bodyMedium,
                color = if (updateAvailable) Cable.Accent else Cable.Ink3
            )
        }

        Column(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MenuButton(
                    number = 1,
                    label = labels[0],
                    status = entries[0].second,
                    modifier = Modifier.weight(1f).fillMaxHeight().focusRequester(firstButton)
                ) { onOpen(entries[0].first) }
                MenuButton(
                    number = 2,
                    label = labels[1],
                    status = entries[1].second,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) { onOpen(entries[1].first) }
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MenuButton(
                    number = 3,
                    label = labels[2],
                    status = entries[2].second,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) { onOpen(entries[2].first) }
                MenuButton(
                    number = 4,
                    label = labels[3],
                    status = entries[3].second,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) { onOpen(entries[3].first) }
            }
        }
    }
}

/** A home menu card. The number sits beside the label so everything fits on a 1080p screen. */
@Composable
private fun MenuButton(
    number: Int,
    label: String,
    status: String,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        focusScale = 1.03f
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .background(Cable.Surface)
                .border(2.dp, Cable.Accent.copy(alpha = 0.45f), shape)
                .padding(horizontal = 28.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = number.toString(),
                style = CableNumber,
                color = Cable.Accent
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleLarge,
                    color = Cable.Led,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Cable.Ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    current: Settings,
    onSave: (Settings) -> Unit,
    onExit: () -> Unit,
    update: AppUpdater.Release?,
    updateMessage: String?,
    updateBusy: Boolean,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: (AppUpdater.Release) -> Unit,
    allChannels: List<LiveChannel>,
    onToggleFilter: (String) -> Unit,
    onToggleStrip: () -> Unit,
    profiles: List<Profile>,
    activeProfileId: String,
    onAddProfile: (String) -> Unit,
    onRemoveProfile: (String) -> Unit,
    pinSet: Boolean,
    pinNote: String?,
    onSetPin: () -> Unit,
    onRemovePin: () -> Unit
) {
    var xs by remember { mutableStateOf(current.xtreamServer) }
    var xu by remember { mutableStateOf(current.xtreamUser) }
    var xp by remember { mutableStateOf(current.xtreamPass) }
    var m3u by remember { mutableStateOf(current.m3uUrl) }
    var js by remember { mutableStateOf(current.jellyfinServer) }
    var ju by remember { mutableStateOf("") }
    var jp by remember { mutableStateOf("") }
    var newProfile by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Fills the fields from the phone. A blank value leaves what the TV already has.
    fun applyPhoneFields(fields: Map<String, String>) {
        fields["xs"]?.takeIf { it.isNotBlank() }?.let { xs = it.trim() }
        fields["xu"]?.takeIf { it.isNotBlank() }?.let { xu = it.trim() }
        fields["xp"]?.takeIf { it.isNotEmpty() }?.let { xp = it }
        fields["m3u"]?.takeIf { it.isNotBlank() }?.let { m3u = it.trim() }
        fields["js"]?.takeIf { it.isNotBlank() }?.let { js = it.trim() }
        fields["ju"]?.takeIf { it.isNotBlank() }?.let { ju = it.trim() }
        fields["jp"]?.takeIf { it.isNotEmpty() }?.let { jp = it }
    }

    // Focus starts on SAVE, so the remote is never stuck inside a text field.
    val saveFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { saveFocus.requestFocus() }
    }

    fun save() {
        busy = true
        message = "Contacting servers..."
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val xServer = xs.trim()
                    if (xServer.isNotBlank() && xu.isNotBlank() && xp.isNotBlank()) {
                        val ok = XtreamClient(xServer, xu.trim(), xp).login()
                        check(ok) { "Live TV login failed. Check server, user and password." }
                    }

                    var token = current.jellyfinToken
                    var userId = current.jellyfinUserId
                    val jServer = js.trim()
                    if (jServer.isBlank() || jServer != current.jellyfinServer) {
                        token = ""
                        userId = ""
                    }
                    if (jServer.isNotBlank() && ju.isNotBlank() && jp.isNotBlank()) {
                        val (t, u) = JellyfinClient.login(jServer, ju.trim(), jp)
                        token = t
                        userId = u
                    }

                    Settings(
                        xtreamServer = xServer,
                        xtreamUser = xu.trim(),
                        xtreamPass = xp,
                        m3uUrl = m3u.trim(),
                        jellyfinServer = jServer,
                        jellyfinToken = token,
                        jellyfinUserId = userId,
                        // These are changed on their own rows, so the save keeps what is current.
                        hiddenFilters = current.hiddenFilters,
                        stripPrefixes = current.stripPrefixes
                    )
                }
            }
            busy = false
            result
                .onSuccess { onSave(it) }
                .onFailure { message = "Save failed: ${it.message}" }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "Credentials are encrypted on this device.",
            style = MaterialTheme.typography.bodyLarge,
            color = Cable.Ink2
        )

        // Actions come first, so they are one press away and not below seven fields.
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CableButton(
                label = "SAVE",
                primary = true,
                enabled = !busy,
                onClick = { save() },
                modifier = Modifier.focusRequester(saveFocus)
            )
            CableButton(label = "CANCEL", onClick = onExit)
        }

        message?.let { StatusLine(it) }

        SettingsCard(
            title = "SIGN IN FROM YOUR PHONE",
            subtitle = "Scan the code and type the details on your phone. They appear here, and nothing is saved until you press SAVE."
        ) {
            PhoneSignInPanel(onReceived = { applyPhoneFields(it) })
        }

        SettingsCard(
            title = "LIVE TV",
            subtitle = "Xtream Codes login. Used first when it is filled in."
        ) {
            CableField("Server URL", xs) { xs = it }
            CableField("Username", xu) { xu = it }
            CableField("Password", xp, secret = true) { xp = it }
        }

        SettingsCard(
            title = "LIVE TV PLAYLIST",
            subtitle = "M3U URL. Used only when no Xtream login is set. A guide link inside the playlist is used automatically."
        ) {
            CableField("Playlist URL", m3u) { m3u = it }
        }

        SettingsCard(
            title = "ON DEMAND",
            subtitle = "Jellyfin server, optional. Provider movies and series use the Live TV login."
        ) {
            CableField("Server URL", js) { js = it }
            CableField("Username (only to sign in again)", ju) { ju = it }
            CableField("Password", jp, secret = true) { jp = it }
        }

        SettingsCard(
            title = "PARENTAL LOCK",
            subtitle = if (pinSet) {
                "Settings need the PIN to open. Hidden channels can only be changed from Settings."
            } else {
                "Set a PIN so children cannot open Settings or change hidden channels."
            }
        ) {
            pinNote?.let { StatusLine(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (pinSet) {
                    CableButton(label = "CHANGE PIN", onClick = onSetPin)
                    CableButton(label = "REMOVE PIN", onClick = onRemovePin)
                } else {
                    CableButton(label = "SET PIN", primary = true, onClick = onSetPin)
                }
            }
        }

        SettingsCard(
            title = "PROFILES",
            subtitle = "Each profile keeps its own favorites, recent channels and resume points. Press PROFILE on the home screen to switch."
        ) {
            profiles.forEach { p ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = if (p.id == activeProfileId) "${p.name}   (in use)" else p.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Cable.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (p.id != com.dallas.cabletv.data.ProfileStore.DEFAULT_ID) {
                        CableButton(label = "REMOVE", onClick = { onRemoveProfile(p.id) })
                    }
                }
            }
            CableField("New profile name", newProfile) { newProfile = it }
            CableButton(
                label = "ADD PROFILE",
                primary = true,
                enabled = newProfile.isNotBlank(),
                onClick = {
                    onAddProfile(newProfile)
                    newProfile = ""
                }
            )
        }

        SettingsCard(
            title = "CHANNEL NAMES",
            subtitle = "Hide the country code at the start of names, so UK| BBC One shows as BBC One."
        ) {
            FilterCheckbox(
                label = "Hide country codes",
                count = null,
                checked = current.stripPrefixes,
                onToggle = onToggleStrip
            )
        }

        SettingsCard(
            title = "HIDDEN CHANNELS",
            subtitle = "Tick a country or category to hide its channels from the guide and live TV. Press OK to tick or untick."
        ) {
            HiddenChannelFilters(
                allChannels = allChannels,
                hiddenTerms = current.hiddenTerms,
                onToggle = onToggleFilter
            )
        }

        SettingsCard(
            title = "APP UPDATES",
            subtitle = "Installed build ${BuildConfig.VERSION_CODE}. Installs always ask you to confirm."
        ) {
            updateMessage?.let { StatusLine(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CableButton(label = "CHECK", enabled = !updateBusy, onClick = onCheckUpdate)
                update?.let { release ->
                    CableButton(
                        label = "INSTALL BUILD ${release.versionCode}",
                        primary = true,
                        enabled = !updateBusy,
                        onClick = { onInstallUpdate(release) }
                    )
                }
            }
        }
    }
}

/** Checkbox lists of country prefixes and categories, built from the channels the provider sent. */
@Composable
private fun HiddenChannelFilters(
    allChannels: List<LiveChannel>,
    hiddenTerms: List<String>,
    onToggle: (String) -> Unit
) {
    if (allChannels.isEmpty()) {
        Text(
            text = "Connect a live source to see its countries and categories.",
            style = MaterialTheme.typography.bodyMedium,
            color = Cable.Ink2
        )
        return
    }
    val (prefixes, categories) = remember(allChannels) { channelFilters(allChannels) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (prefixes.isNotEmpty()) {
            Text(
                text = "COUNTRY PREFIXES",
                style = MaterialTheme.typography.labelMedium,
                color = Cable.Accent
            )
            prefixes.forEach { f ->
                FilterCheckbox(
                    label = f.label,
                    count = f.count,
                    checked = f.term in hiddenTerms,
                    onToggle = { onToggle(f.term) }
                )
            }
        }
        if (categories.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "CATEGORIES",
                style = MaterialTheme.typography.labelMedium,
                color = Cable.Accent
            )
            categories.forEach { f ->
                FilterCheckbox(
                    label = f.label,
                    count = f.count,
                    checked = f.term in hiddenTerms,
                    onToggle = { onToggle(f.term) }
                )
            }
        }
    }
}

/**
 * A large checkbox row for the remote. A ticked box is on. The whole row is one focus
 * target, so OK toggles it and the ring shows where you are.
 */
@Composable
private fun FilterCheckbox(label: String, count: Int?, checked: Boolean, onToggle: () -> Unit) {
    FocusSurface(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        focusScale = 1f
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Cable.Surface)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .border(3.dp, if (checked) Cable.Led else Cable.Ink3, RoundedCornerShape(4.dp))
                    .background(if (checked) Cable.Led else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                if (checked) {
                    Text(
                        text = "X",
                        style = MaterialTheme.typography.labelLarge,
                        color = Cable.Canvas
                    )
                }
            }
            Spacer(Modifier.width(24.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = if (checked) Cable.Ink3 else Cable.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (count != null) {
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Cable.Ink2
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Cable.Surface)
            .border(2.dp, Cable.Accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = Cable.Accent
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = Cable.Ink2
        )
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Cable.Led,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Cable.SurfaceHi)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    )
}

/**
 * A settings field. Moving over it with the remote only highlights it, so the keyboard
 * stays closed. OK starts editing and opens the keyboard. Done or Back finishes editing
 * and returns the highlight to the field.
 */
@Composable
internal fun CableField(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val row = remember { FocusRequester() }
    val input = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    fun finish() {
        editing = false
        scope.launch {
            withFrameNanos { }
            runCatching { row.requestFocus() }
        }
    }

    BackHandler(enabled = editing) { finish() }
    LaunchedEffect(editing) {
        if (editing) {
            withFrameNanos { }
            runCatching { input.requestFocus() }
        }
    }

    FocusSurface(
        onClick = { editing = true },
        modifier = Modifier.fillMaxWidth().focusRequester(row),
        shape = RoundedCornerShape(6.dp),
        focusScale = 1f
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Cable.Surface)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = Cable.Ink3
            )
            Spacer(Modifier.height(4.dp))
            if (editing) {
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = CableFieldStyle,
                    cursorBrush = SolidColor(Cable.Led),
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { finish() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(input)
                )
            } else {
                Text(
                    text = when {
                        value.isEmpty() -> "Not set"
                        secret -> "*".repeat(value.length.coerceAtMost(24))
                        else -> value
                    },
                    style = CableFieldStyle,
                    color = if (value.isEmpty()) Cable.Ink3 else Cable.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
