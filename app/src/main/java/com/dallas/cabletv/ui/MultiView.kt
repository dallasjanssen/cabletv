package com.dallas.cabletv.ui

import android.content.Context
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.liveMediaItem
import com.dallas.cabletv.data.newPlayer
import kotlinx.coroutines.delay

/** One of the two small pictures: its player, and whether it is loading or failed. */
private class Tile(context: Context) {
    val exo: ExoPlayer = newPlayer(context, compact = true)
    var buffering by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    private var url: String? = null
    private var fallback: String? = null
    private var played = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            buffering = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_READY) {
                played = true
                failed = false
            }
            if (playbackState == Player.STATE_ENDED) failed = true
        }

        override fun onPlayerError(error: PlaybackException) {
            // One try of the MPEG-TS address when the stream fails before it plays.
            val fb = fallback
            if (!played && !fb.isNullOrBlank() && fb != url) {
                fallback = null
                load(fb)
                return
            }
            buffering = false
            failed = true
        }
    }

    init {
        exo.addListener(listener)
    }

    fun play(target: String, fallbackUrl: String?) {
        fallback = fallbackUrl
        played = false
        load(target)
    }

    private fun load(target: String) {
        url = target
        failed = false
        runCatching {
            exo.setMediaItem(liveMediaItem(target))
            exo.prepare()
            exo.playWhenReady = true
        }.onFailure {
            buffering = false
            failed = true
        }
    }

    /** Lets go of the provider connection. */
    fun stop() {
        url = null
        buffering = false
        failed = false
        exo.stop()
    }

    fun release() {
        exo.removeListener(listener)
        exo.release()
    }
}

/** The first picture is the channel playing. The second is the next one that is not locked. */
private fun initialPicks(channels: List<LiveChannel>, start: Int, isLocked: (LiveChannel) -> Boolean): List<Int> {
    if (channels.isEmpty()) return listOf(-1, -1)
    val first = start.coerceIn(0, channels.size - 1)
    if (channels.size < 2) return listOf(first, -1)
    for (step in 1 until channels.size) {
        val i = (first + step) % channels.size
        if (!isLocked(channels[i])) return listOf(first, i)
    }
    return listOf(first, (first + 1) % channels.size)
}

/**
 * Two channels at once. Each one is its own player, so a provider that allows a single
 * connection will refuse one of them. Up and down change the highlighted picture's channel, and
 * the highlighted picture has the sound. OK or Back returns to it full screen.
 * [isLocked] is true for a channel that needs the PIN. [resolve] finds the playable address of a
 * channel whose stream is not a web address, such as a Stalker portal channel.
 */
@Composable
fun MultiViewScreen(
    channels: List<LiveChannel>,
    start: Int,
    onExit: (LiveChannel) -> Unit,
    isLocked: (LiveChannel) -> Boolean = { false },
    resolve: suspend (LiveChannel) -> String? = { it.streamUrl },
    onClose: () -> Unit = {}
) {
    val context = LocalContext.current
    var picks by remember { mutableStateOf(initialPicks(channels, start, isLocked)) }
    val shown = List(2) { i -> channels.getOrNull(picks[i]) }
    var focused by remember { mutableIntStateOf(0) }
    val tiles = remember { List(2) { Tile(context) } }
    val notes = remember { mutableStateListOf<String?>(null, null) }
    val requesters = remember { List(2) { FocusRequester() } }
    DisposableEffect(Unit) { onDispose { tiles.forEach { it.release() } } }

    // Leaving the app stops both pictures and lets go of the connections. Coming back starts them again.
    val lifecycle = (context as? ComponentActivity)?.lifecycle
    var foreground by remember { mutableStateOf(lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { foreground = true }
                Lifecycle.Event.ON_STOP -> {
                    foreground = false
                    tiles.forEach { it.stop() }
                }
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    // Only the highlighted picture plays sound.
    SideEffect {
        tiles.forEachIndexed { i, tile -> tile.exo.volume = if (i == focused) 1f else 0f }
    }

    shown.forEachIndexed { i, ch ->
        val locked = ch != null && isLocked(ch)
        LaunchedEffect(ch?.id, locked, foreground) {
            val tile = tiles[i]
            notes[i] = null
            if (ch == null || !foreground) {
                tile.stop()
                return@LaunchedEffect
            }
            if (locked) {
                tile.stop()
                notes[i] = "Locked"
                return@LaunchedEffect
            }
            // A short wait means holding a key only loads the channel you stop on.
            delay(300)
            if (ch.streamUrl.startsWith("http")) {
                tile.play(ch.streamUrl, ch.fallbackStreamUrl)
            } else {
                tile.stop()
                val url = resolve(ch)
                if (url == null) notes[i] = "Not available here" else tile.play(url, null)
            }
        }
    }

    fun move(delta: Int) {
        if (channels.isEmpty()) return
        val from = picks[focused]
        if (from < 0) return
        val next = (from + delta).mod(channels.size)
        picks = picks.toMutableList().also { it[focused] = next }
    }

    BackHandler {
        val ch = shown[focused] ?: shown.firstOrNull { it != null }
        if (ch != null) onExit(ch) else onClose()
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { requesters[0].requestFocus() }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Tv.Bg)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { move(1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { move(-1); true }
                    else -> false
                }
            }
            .padding(18.dp)
    ) {
        Text(
            "Two channels. Each picture is a separate connection, so a one-stream login may stop one of them. Up and down change the highlighted channel. OK watches it full screen.",
            style = TvType.Small,
            color = Tv.Ink2
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            shown.forEachIndexed { i, ch ->
                val tile = tiles[i]
                val note = notes[i]
                Column(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Tv.Surface)
                    ) {
                        if (ch != null) {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        useController = false
                                        player = tile.exo
                                    }
                                },
                                onRelease = { it.player = null },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        val status = when {
                            ch == null -> null
                            note != null -> note
                            tile.failed -> "This channel did not start. A one-stream login refuses the second picture."
                            tile.buffering -> "Loading"
                            else -> null
                        }
                        if (status != null) {
                            Text(
                                status,
                                style = TvType.SmallStrong,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.align(Alignment.Center).padding(16.dp)
                            )
                        }
                        Text(
                            ch?.name ?: "",
                            style = TvType.CaptionStrong,
                            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    PillButton(
                        if (ch == null) "Empty" else "Watch ${ch.name}",
                        { if (ch != null) onExit(ch) },
                        modifier = Modifier.focusRequester(requesters[i]).fillMaxWidth(),
                        onFocus = { if (it) focused = i }
                    )
                }
            }
        }
    }
}
