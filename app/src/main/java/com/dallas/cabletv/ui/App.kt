package com.dallas.cabletv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
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
import java.util.Calendar

enum class Screen { Home, LiveTv, Guide, OnDemand, Settings }

private val navItems = listOf(
    Screen.Home to "Home",
    Screen.LiveTv to "Live TV",
    Screen.Guide to "Guide",
    Screen.OnDemand to "On Demand",
    Screen.Settings to "Settings"
)

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

    Box(Modifier.fillMaxSize().background(Cable.Canvas)) {
        when (screen) {
            // Full-bleed playback has no navigation rail.
            Screen.LiveTv -> LiveTvScreen(
                channels = channels,
                loading = channelsLoading,
                error = channelError,
                startIndex = tuneIndex,
                epg = xtream,
                onExit = goHome
            )
            else -> AppShell(current = screen, onNavigate = navigate) {
                when (screen) {
                    Screen.Home -> HomeScreen(
                        channelCount = channels.size,
                        liveReady = settings.hasLiveSource,
                        jellyfinReady = settings.hasJellyfin,
                        onOpen = navigate
                    )
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
                    Screen.LiveTv -> Unit
                }
            }
        }
    }
}

/** Left navigation rail plus the page content. */
@Composable
fun AppShell(current: Screen, onNavigate: (Screen) -> Unit, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        NavRail(current = current, onNavigate = onNavigate)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            content()
        }
    }
}

@Composable
private fun NavRail(current: Screen, onNavigate: (Screen) -> Unit) {
    Column(
        Modifier
            .width(248.dp)
            .fillMaxHeight()
            .background(Cable.Rail)
            .padding(horizontal = 20.dp, vertical = 40.dp)
    ) {
        Text(
            text = "Cable",
            style = MaterialTheme.typography.headlineMedium,
            color = Cable.Ink,
            modifier = Modifier.padding(start = 12.dp, bottom = 32.dp)
        )
        navItems.forEach { (screen, label) ->
            NavItem(label = label, selected = screen == current) { onNavigate(screen) }
        }
        Spacer(Modifier.weight(1f))
        Clock(Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        targetValue = if (focused) Cable.SurfaceHi else Color.Transparent,
        label = "navBg"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 24.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) Cable.Accent else Color.Transparent)
        )
        Spacer(Modifier.width(18.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected || focused) Cable.Ink else Cable.Ink2
        )
    }
}

@Composable
private fun HomeScreen(
    channelCount: Int,
    liveReady: Boolean,
    jellyfinReady: Boolean,
    onOpen: (Screen) -> Unit
) {
    val heroFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { heroFocus.requestFocus() } }
    val greeting = remember { greetingNow() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        PageHeader(
            title = greeting,
            subtitle = "Pick up where you left off, or start something new."
        )
        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            LiveHeroTile(
                channelCount = channelCount,
                ready = liveReady,
                modifier = Modifier
                    .weight(1.6f)
                    .fillMaxHeight()
                    .focusRequester(heroFocus),
                onClick = { onOpen(if (liveReady) Screen.LiveTv else Screen.Settings) }
            )
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                InfoTile(
                    title = "Program guide",
                    body = if (liveReady) "Now and next on every channel" else "Needs a live source",
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) { onOpen(Screen.Guide) }
                InfoTile(
                    title = "On demand",
                    body = if (jellyfinReady) "Movies and shows from Jellyfin" else "Not connected yet",
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) { onOpen(Screen.OnDemand) }
            }
        }
    }
}

@Composable
private fun LiveHeroTile(
    channelCount: Int,
    ready: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        focusScale = 1.02f
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(listOf(Color(0xFF1D4470), Color(0xFF0D1522)))
                )
                .padding(40.dp)
        ) {
            Column(
                Modifier.align(Alignment.BottomStart),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Pill(text = "LIVE TV", color = Cable.Live)
                Text(
                    text = "Live TV",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Cable.Ink
                )
                Text(
                    text = if (ready) {
                        "$channelCount channels ready"
                    } else {
                        "Add a live source in Settings to start watching"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Cable.Ink2
                )
                Spacer(Modifier.height(8.dp))
                Pill(
                    text = if (ready) "START WATCHING" else "OPEN SETTINGS",
                    color = Cable.Accent,
                    textColor = Cable.Canvas
                )
            }
        }
    }
}

@Composable
private fun InfoTile(
    title: String,
    body: String,
    modifier: Modifier,
    onClick: () -> Unit
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Cable.Surface)
                .padding(28.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Cable.Ink
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = Cable.Ink2
            )
        }
    }
}

private fun greetingNow(): String =
    when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
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
            .padding(horizontal = 48.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        PageHeader(
            title = "Settings",
            subtitle = "Connect your sources. Credentials are encrypted on this device."
        )

        SettingsCard(
            title = "Live TV",
            subtitle = "Xtream Codes login. Used first when it is filled in."
        ) {
            CableField("Server URL", xs) { xs = it }
            CableField("Username", xu) { xu = it }
            CableField("Password", xp, secret = true) { xp = it }
        }

        SettingsCard(
            title = "Live TV playlist",
            subtitle = "M3U URL. Used only when no Xtream login is set."
        ) {
            CableField("Playlist URL", m3u) { m3u = it }
        }

        SettingsCard(
            title = "On demand",
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
            ) { Text("Save") }

            OutlinedButton(onClick = onExit) { Text("Cancel") }
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
            .clip(RoundedCornerShape(20.dp))
            .background(Cable.Surface)
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = Cable.Ink
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
        color = Cable.Accent,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
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
        textStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Normal, color = Cable.Ink),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Cable.Accent,
            unfocusedBorderColor = Cable.Track,
            focusedLabelColor = Cable.Accent,
            unfocusedLabelColor = Cable.Ink3,
            cursorColor = Cable.Accent
        )
    )
}
