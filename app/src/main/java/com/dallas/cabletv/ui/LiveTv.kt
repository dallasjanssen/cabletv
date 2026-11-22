package com.dallas.cabletv.ui

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.NowNext
import com.dallas.cabletv.data.Program
import com.dallas.cabletv.data.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val clockFmt = SimpleDateFormat("h:mm a", Locale.US)

private fun progressOf(p: Program): Float {
    val total = (p.end - p.start).coerceAtLeast(1L)
    return ((System.currentTimeMillis() - p.start).toFloat() / total).coerceIn(0f, 1f)
}

@Composable
fun LiveTvScreen(
    channels: List<LiveChannel>,
    loading: Boolean,
    error: String?,
    startIndex: Int,
    epg: XtreamClient?,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }

    var index by remember(startIndex) { mutableIntStateOf(startIndex) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var showBanner by remember { mutableStateOf(false) }
    var bannerTick by remember { mutableIntStateOf(0) }
    var digits by remember { mutableStateOf("") }
    var nowNext by remember { mutableStateOf<NowNext?>(null) }
    val focus = remember { FocusRequester() }
    val current = channels.getOrNull(index)

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                playerError = e.message ?: "SIGNAL LOST"
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Tune the player whenever the channel changes.
    LaunchedEffect(current?.streamUrl) {
        if (current == null) return@LaunchedEffect
        playerError = null
        player.setMediaItem(MediaItem.fromUri(current.streamUrl))
        player.prepare()
        player.playWhenReady = true
    }

    // Refresh now and next for the channel on screen.
    LaunchedEffect(current?.id) {
        nowNext = null
        if (current == null || epg == null) return@LaunchedEffect
        nowNext = withContext(Dispatchers.IO) { runCatching { epg.nowNext(current.id) }.getOrNull() }
    }

    // Show the info banner for four seconds after each change.
    LaunchedEffect(bannerTick) {
        if (bannerTick > 0) {
            showBanner = true
            delay(4000)
            showBanner = false
        }
    }

    // Typed channel numbers tune once the user pauses.
    LaunchedEffect(digits) {
        if (digits.isNotEmpty()) {
            delay(1500)
            val target = channels.indexOfFirst { it.number == digits.toIntOrNull() }
            if (target >= 0) index = target
            digits = ""
            bannerTick++
        }
    }

    fun flashBanner() {
        bannerTick++
    }

    fun tune(delta: Int) {
        if (channels.isEmpty()) return
        index = (index + delta + channels.size) % channels.size
        digits = ""
        flashBanner()
    }

    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (val code = ev.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP -> {
                        tune(1)
                        true
                    }
                    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        tune(-1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        flashBanner()
                        true
                    }
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                        if (digits.length < 4) digits += (code - KeyEvent.KEYCODE_0).toString()
                        true
                    }
                    else -> false
                }
            }
            .clickable { flashBanner() }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply { useController = false }.also { it.player = player }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (channels.isEmpty()) {
            CenterMessage(
                when {
                    loading -> "SEARCHING FOR CHANNELS..."
                    error != null -> "ERROR: $error"
                    else -> "NO CHANNELS. OPEN SETTINGS TO ADD A SOURCE."
                }
            )
        } else if (playerError != null) {
            CenterMessage("NO SIGNAL")
        }

        // Digits typed on the remote appear in the top corner.
        if (digits.isNotEmpty()) {
            Text(
                digits,
                color = CableColors.Amber,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 48.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(CableColors.Panel.copy(alpha = 0.9f))
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            )
        }

        AnimatedVisibility(
            visible = showBanner && current != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            if (current != null) {
                ChannelBanner(
                    channel = current,
                    nn = nowNext,
                    onUp = { tune(1) },
                    onDown = { tune(-1) },
                    onMenu = onExit
                )
            }
        }
    }
}

@Composable
private fun ChannelBanner(
    channel: LiveChannel,
    nn: NowNext?,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onMenu: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(CableColors.Panel.copy(alpha = 0.92f))
            .padding(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = channel.number.toString().padStart(3, '0'),
            color = CableColors.Amber,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 56.sp
        )
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(
                channel.name.uppercase(),
                color = CableColors.Text,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp
            )
            val now = nn?.now
            if (now != null) {
                Text(
                    "${clockFmt.format(Date(now.start))}  ${now.title}",
                    color = CableColors.Text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp
                )
                ProgressBar(progressOf(now))
            } else {
                Text(
                    "NO GUIDE DATA",
                    color = CableColors.Dim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp
                )
            }
            nn?.next?.let {
                Text(
                    "NEXT  ${clockFmt.format(Date(it.start))}  ${it.title}",
                    color = CableColors.Dim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
            }
        }
        Column {
            TextButton(onClick = onUp) { Text("CH +", color = CableColors.Led) }
            TextButton(onClick = onDown) { Text("CH -", color = CableColors.Led) }
            TextButton(onClick = onMenu) { Text("MENU", color = CableColors.Amber) }
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(6.dp)
            .background(CableColors.Dim)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .background(CableColors.Led)
        )
    }
}

@Composable
fun GuideScreen(
    channels: List<LiveChannel>,
    epg: XtreamClient?,
    onWatch: (Int) -> Unit,
    onExit: () -> Unit
) {
    val cache = remember { mutableStateMapOf<String, NowNext>() }
    val gate = remember { Semaphore(3) } // limit guide requests so the provider is not flooded

    Column(Modifier.fillMaxSize()) {
        CableHeader("PROGRAM GUIDE", onBack = onExit)
        if (channels.isEmpty()) {
            CenterMessage("NO CHANNELS LOADED.")
        } else {
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(channels, key = { _, c -> c.id }) { idx, ch ->
                    GuideRow(ch, cache, epg, gate) { onWatch(idx) }
                }
            }
        }
    }
}

@Composable
private fun GuideRow(
    ch: LiveChannel,
    cache: MutableMap<String, NowNext>,
    epg: XtreamClient?,
    gate: Semaphore,
    onClick: () -> Unit
) {
    LaunchedEffect(ch.id) {
        if (epg == null || cache.containsKey(ch.id)) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            gate.withPermit { runCatching { epg.nowNext(ch.id) }.getOrNull() }
        }
        if (result != null) cache[ch.id] = result
    }

    val nn = cache[ch.id]
    val subtitle = nn?.now?.let { "${clockFmt.format(Date(it.start))}  ${it.title}" }
        ?: if (epg == null) "NO GUIDE SOURCE" else "--"

    CableRow(onClick = onClick) {
        Text(
            text = ch.number.toString(),
            color = CableColors.Amber,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            modifier = Modifier.width(72.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                ch.name.uppercase(),
                color = CableColors.Text,
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
                maxLines = 1
            )
            Text(
                subtitle,
                color = CableColors.Dim,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                maxLines = 1
            )
        }
    }
}
