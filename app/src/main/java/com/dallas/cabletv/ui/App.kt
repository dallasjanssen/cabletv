package com.dallas.cabletv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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

enum class Screen { Menu, LiveTv, Guide, OnDemand, Settings }

@Composable
fun CableApp(store: SettingsStore) {
    var settings by remember { mutableStateOf(store.load()) }
    var screen by remember { mutableStateOf(Screen.Menu) }
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

    BackHandler(enabled = screen != Screen.Menu) { screen = Screen.Menu }

    val goMenu: () -> Unit = { screen = Screen.Menu }

    Box(Modifier.fillMaxSize().background(CableColors.Bg).scanlines()) {
        when (screen) {
            Screen.Menu -> MenuScreen(
                channelCount = channels.size,
                jellyfinReady = settings.hasJellyfin,
                onOpen = { screen = it }
            )
            Screen.LiveTv -> LiveTvScreen(
                channels = channels,
                loading = channelsLoading,
                error = channelError,
                startIndex = tuneIndex,
                epg = xtream,
                onExit = goMenu
            )
            Screen.Guide -> GuideScreen(
                channels = channels,
                epg = xtream,
                onWatch = { idx ->
                    tuneIndex = idx
                    screen = Screen.LiveTv
                },
                onExit = goMenu
            )
            Screen.OnDemand -> OnDemandScreen(settings = settings, onExit = goMenu)
            Screen.Settings -> SettingsScreen(
                current = settings,
                onSave = { updated ->
                    store.save(updated)
                    settings = updated
                    screen = Screen.Menu
                },
                onExit = goMenu
            )
        }
    }
}

@Composable
private fun MenuScreen(channelCount: Int, jellyfinReady: Boolean, onOpen: (Screen) -> Unit) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocus() }

    val entries = listOf(
        Screen.LiveTv to "1   LIVE TV",
        Screen.Guide to "2   PROGRAM GUIDE",
        Screen.OnDemand to "3   ON DEMAND",
        Screen.Settings to "4   SETTINGS"
    )

    Column(Modifier.fillMaxSize()) {
        CableHeader("CABLE TV")
        Row(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Column(Modifier.weight(1f).border(2.dp, CableColors.Dim)) {
                entries.forEachIndexed { i, (screen, label) ->
                    CableRow(
                        onClick = { onOpen(screen) },
                        modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier
                    ) {
                        Text(
                            text = label,
                            color = CableColors.Text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 24.sp
                        )
                    }
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .border(2.dp, CableColors.Dim)
                    .padding(24.dp)
            ) {
                Text(
                    "SYSTEM STATUS",
                    color = CableColors.Led,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "CHANNELS LOADED: $channelCount",
                    color = CableColors.Text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp
                )
                Text(
                    "ON DEMAND: ${if (jellyfinReady) "CONNECTED" else "NOT SET UP"}",
                    color = CableColors.Text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    "SELECT AN OPTION WITH THE REMOTE OR BY TAPPING IT.",
                    color = CableColors.Dim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
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

    Column(Modifier.fillMaxSize()) {
        CableHeader("SETTINGS", onBack = onExit)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionTitle("LIVE TV (XTREAM CODES)")
            CableField("Server URL", xs) { xs = it }
            CableField("Username", xu) { xu = it }
            CableField("Password", xp, secret = true) { xp = it }

            SectionTitle("LIVE TV (M3U URL, USED IF NO XTREAM LOGIN)")
            CableField("Playlist URL", m3u) { m3u = it }

            SectionTitle("ON DEMAND (JELLYFIN)")
            CableField("Server URL", js) { js = it }
            CableField("Username (only needed to sign in again)", ju) { ju = it }
            CableField("Password (not stored)", jp, secret = true) { jp = it }

            message?.let {
                Text(it, color = CableColors.Amber, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        message = "CONTACTING SERVERS..."
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
                                .onFailure { message = "SAVE FAILED: ${it.message}" }
                        }
                    }
                ) { Text("SAVE") }

                OutlinedButton(onClick = onExit) { Text("CANCEL") }
            }
        }
    }
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
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, color = CableColors.Text),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = CableColors.Led,
            unfocusedBorderColor = CableColors.Dim,
            focusedLabelColor = CableColors.Led,
            unfocusedLabelColor = CableColors.Dim,
            cursorColor = CableColors.Led
        )
    )
}
