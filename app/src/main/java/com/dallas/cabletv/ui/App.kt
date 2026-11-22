package com.dallas.cabletv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dallas.cabletv.data.ChannelSource
import com.dallas.cabletv.data.JellyfinClient
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.Settings
import com.dallas.cabletv.data.SettingsStore
import com.dallas.cabletv.data.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var screen by remember { mutableStateOf(Screen.Home) }
    var channels by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    var channelsLoading by remember { mutableStateOf(false) }
    var channelError by remember { mutableStateOf<String?>(null) }
    var tuneIndex by remember { mutableIntStateOf(0) }

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

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    val goHome: () -> Unit = { screen = Screen.Home }
    val navigate: (Screen) -> Unit = { screen = it }

    Box(Modifier.fillMaxSize().background(Cable.Canvas).scanlines()) {
        when (screen) {
            Screen.Home -> HomeMenu(
                channelCount = channels.size,
                liveReady = settings.hasLiveSource,
                jellyfinReady = settings.hasJellyfin,
                onOpen = navigate
            )
            // Full-bleed playback has its own header-free layout.
            Screen.LiveTv -> LiveTvScreen(
                channels = channels,
                loading = channelsLoading,
                error = channelError,
                startIndex = tuneIndex,
                epg = xtream,
                onExit = goHome
            )
            else -> Column(Modifier.fillMaxSize()) {
                CableHeader(title = titleOf(screen), onBack = goHome)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (screen) {
                        Screen.Guide -> GuideScreen(
                            channels = channels,
                            epg = xtream,
                            onWatch = { idx ->
                                tuneIndex = idx
                                screen = Screen.LiveTv
                            }
                        )
                        Screen.OnDemand -> OnDemandScreen(settings = settings)
                        Screen.Settings -> SettingsScreen(
                            current = settings,
                            onSave = { updated ->
                                store.save(updated)
                                settings = updated
                                screen = Screen.Home
                            },
                            onExit = goHome
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
    jellyfinReady: Boolean,
    onOpen: (Screen) -> Unit
) {
    val firstButton = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstButton.requestFocus() } }

    val entries = listOf(
        Screen.LiveTv to (if (liveReady) "$channelCount CHANNELS" else "NOT SET UP"),
        Screen.Guide to (if (liveReady) "NOW AND NEXT" else "NEEDS A LIVE SOURCE"),
        Screen.OnDemand to (if (jellyfinReady) "CONNECTED" else "NOT SET UP"),
        Screen.Settings to "SOURCES AND LOGIN"
    )
    val labels = listOf("LIVE TV", "PROGRAM GUIDE", "ON DEMAND", "SETTINGS")

    Column(
        Modifier
            .fillMaxSize()
            .focusable()
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
            .padding(horizontal = 48.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "CABLE TV",
                style = MaterialTheme.typography.headlineLarge,
                color = Cable.Led
            )
            Text(
                text = "PRESS 1-4",
                style = MaterialTheme.typography.labelMedium,
                color = Cable.Ink3,
                modifier = Modifier.padding(top = 14.dp)
            )
        }

        Column(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
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
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
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
        Column(
            Modifier
                .fillMaxSize()
                .background(Cable.Surface)
                .border(2.dp, Cable.Accent.copy(alpha = 0.45f), shape)
                .padding(28.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = number.toString(),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 72.sp
                ),
                color = Cable.Accent
            )
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleLarge,
                    color = Cable.Led
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Cable.Ink2
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(current: Settings, onSave: (Settings) -> Unit, onExit: () -> Unit) {
    var xs by remember { mutableStateOf(current.xtreamServer) }
    var xu by remember { mutableStateOf(current.xtreamUser) }
    var xp by remember { mutableStateOf(current.xtreamPass) }
    var m3u by remember { mutableStateOf(current.m3uUrl) }
    var js by remember { mutableStateOf(current.jellyfinServer) }
    var ju by remember { mutableStateOf("") }
    var jp by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

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
            title = "ON DEMAND",
            subtitle = "Jellyfin server. The password signs you in and is not stored."
        ) {
            CableField("Server URL", js) { js = it }
            CableField("Username (only to sign in again)", ju) { ju = it }
            CableField("Password", jp, secret = true) { jp = it }
        }

        message?.let { StatusLine(it) }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                enabled = !busy,
                onClick = {
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
                                    jellyfinUserId = userId
                                )
                            }
                        }
                        busy = false
                        result
                            .onSuccess { onSave(it) }
                            .onFailure { message = "Save failed: ${it.message}" }
                    }
                }
            ) { Text("SAVE") }

            OutlinedButton(onClick = onExit) { Text("CANCEL") }
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
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 20.sp, color = Cable.Ink),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Cable.Led,
            unfocusedBorderColor = Cable.Track,
            focusedLabelColor = Cable.Led,
            unfocusedLabelColor = Cable.Ink3,
            cursorColor = Cable.Led
        )
    )
}
