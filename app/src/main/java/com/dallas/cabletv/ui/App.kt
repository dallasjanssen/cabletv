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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dallas.cabletv.BuildConfig
import com.dallas.cabletv.data.AppUpdater
import com.dallas.cabletv.data.ChannelSource
import com.dallas.cabletv.data.JellyfinClient
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.Settings
import com.dallas.cabletv.data.SettingsStore
import com.dallas.cabletv.data.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { Home, LiveTv, Guide, OnDemand, Settings }

private fun titleOf(screen: Screen): String = when (screen) {
    Screen.Home -> "CABLE"
    Screen.LiveTv -> "LIVE TV"
    Screen.Guide -> "PROGRAM GUIDE"
    Screen.OnDemand -> "ON DEMAND"
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
    var channels by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    var channelsLoading by remember { mutableStateOf(false) }
    var channelError by remember { mutableStateOf<String?>(null) }
    var tuneIndex by remember { mutableIntStateOf(0) }
    var restoredChannel by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val updateScope = rememberCoroutineScope()
    var update by remember { mutableStateOf<AppUpdater.Release?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updateBusy by remember { mutableStateOf(false) }

    val xtream = remember(settings) {
        if (settings.hasXtream) {
            XtreamClient(settings.xtreamServer, settings.xtreamUser, settings.xtreamPass)
        } else {
            null
        }
    }

    // Reload the channel list whenever the live source settings change.
    LaunchedEffect(settings) {
        channels = emptyList()
        channelError = null
        if (!settings.hasLiveSource) return@LaunchedEffect
        channelsLoading = true
        withContext(Dispatchers.IO) { runCatching { ChannelSource.load(settings) } }
            .onSuccess { channels = it }
            .onFailure { channelError = it.message ?: "Could not load channels" }
        channelsLoading = false
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
        previous = screen
        screen = target
    }
    val goBack: () -> Unit = {
        screen = previous
        previous = Screen.Home
    }

    BackHandler(enabled = screen != Screen.Home) { goBack() }

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
                onOpen = navigate
            )
            // Full-bleed screens draw their own scan lines, so playback can stay clean.
            Screen.OnDemand -> OnDemandScreen(settings = settings, onExit = goBack)
            Screen.LiveTv -> LiveTvScreen(
                channels = channels,
                loading = channelsLoading,
                error = channelError,
                startIndex = startIndex,
                epg = xtream,
                onExit = goBack,
                onChannelChanged = { ch ->
                    store.saveLastChannelId(ch.id)
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
                            onWatch = { idx ->
                                tuneIndex = idx
                                previous = Screen.Guide
                                screen = Screen.LiveTv
                            }
                        )
                        Screen.Settings -> SettingsScreen(
                            current = settings,
                            onSave = { updated ->
                                store.save(updated)
                                settings = updated
                                previous = Screen.Home
                                screen = Screen.Home
                            },
                            onExit = goBack,
                            update = update,
                            updateMessage = updateMessage,
                            updateBusy = updateBusy,
                            onCheckUpdate = { checkForUpdate(manual = true) },
                            onInstallUpdate = { installUpdate(it) }
                        )
                        else -> Unit
                    }
                }
            }
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "CABLE TV",
                style = MaterialTheme.typography.headlineLarge,
                color = Cable.Led
            )
            Text(
                text = if (updateAvailable) "UPDATE READY IN SETTINGS" else "PRESS 1-4",
                style = MaterialTheme.typography.bodyMedium,
                color = if (updateAvailable) Cable.Accent else Cable.Ink3,
                modifier = Modifier.padding(top = 14.dp)
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
    onInstallUpdate: (AppUpdater.Release) -> Unit
) {
    var xs by remember { mutableStateOf(current.xtreamServer) }
    var xu by remember { mutableStateOf(current.xtreamUser) }
    var xp by remember { mutableStateOf(current.xtreamPass) }
    var m3u by remember { mutableStateOf(current.m3uUrl) }
    var hidden by remember { mutableStateOf(current.hiddenFilters) }
    var js by remember { mutableStateOf(current.jellyfinServer) }
    var ju by remember { mutableStateOf("") }
    var jp by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

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
                        hiddenFilters = hidden.trim()
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
            title = "LIVE TV",
            subtitle = "Xtream Codes login. Used first when it is filled in."
        ) {
            CableField("Server URL", xs) { xs = it }
            CableField("Username", xu) { xu = it }
            CableField("Password", xp, secret = true) { xp = it }
        }

        SettingsCard(
            title = "LIVE TV PLAYLIST",
            subtitle = "M3U URL. Used only when no Xtream login is set."
        ) {
            CableField("Playlist URL", m3u) { m3u = it }
        }

        SettingsCard(
            title = "HIDDEN CHANNELS",
            subtitle = "Comma separated. A name prefix such as UK or US| hides every channel that starts with it. A category name hides that whole category. Hidden channels leave the guide and live TV."
        ) {
            CableField("Hide (for example UK, US|, Adult)", hidden) { hidden = it }
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

@Composable
private fun CableField(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text
        ),
        modifier = Modifier.fillMaxWidth(),
        textStyle = CableFieldStyle,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Cable.Led,
            unfocusedBorderColor = Cable.Track,
            focusedLabelColor = Cable.Led,
            unfocusedLabelColor = Cable.Ink3,
            cursorColor = Cable.Led
        )
    )
}
