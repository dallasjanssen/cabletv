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
import androidx.compose.ui.graphics.Color
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
import com.dallas.cabletv.data.ConnectionGate
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.PlayerOptionsStore
import com.dallas.cabletv.data.liveMediaItem
import com.dallas.cabletv.data.newPlayer
import com.dallas.cabletv.data.proxiedStreamUrl
import kotlinx.coroutines.delay

/** One small picture: its player, and whether it is loading or failed. */
private class Tile(private val context: Context) {
    val exo: ExoPlayer = newPlayer(context, compact = true)
    var buffering by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    /** True when this tile was refused because the stream cap is already full. */
    var limited by mutableStateOf(false)
        private set
    /** Hides the previous picture while a new channel is still starting, when that option is on. */
    var coverBlack by mutableStateOf(false)
        private set
    private var url: String? = null
    private var fallback: String? = null
    private var played = false
    private var holdsConnection = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            buffering = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_READY) {
                played = true
                failed = false
                coverBlack = false
            }
            if (playbackState == Player.STATE_ENDED) {
                coverBlack = false
                failed = true
            }
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
            coverBlack = false
            failed = true
        }
    }

    init {
        exo.addListener(listener)
    }

    fun play(target: String, fallbackUrl: String?) {
        val options = PlayerOptionsStore(context).load()
        ConnectionGate.setLimit(options.maxConnections)
        // Zero is unlimited and is not counted, so stopping this tile cannot free another stream's slot.
        if (options.maxConnections <= 0) {
            releaseConnection()
        } else if (!holdsConnection) {
            if (!ConnectionGate.tryAcquire()) {
                limited = true
                failed = true
                buffering = false
                coverBlack = false
                exo.stop()
                return
            }
            holdsConnection = true
        }
        limited = false
        // udp:// only plays when the viewer has named a proxy. http is left as it is.
        val main = proxiedStreamUrl(target, options.udpProxy)
        fallback = fallbackUrl?.let { proxiedStreamUrl(it, options.udpProxy) }
        played = false
        coverBlack = options.blackFrameOnZap
        load(main)
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
            coverBlack = false
            failed = true
        }
    }

    /** Lets go of the provider connection. */
    fun stop() {
        releaseConnection()
        url = null
        buffering = false
        failed = false
        limited = false
        coverBlack = false
        exo.stop()
    }

    fun release() {
        releaseConnection()
        exo.removeListener(listener)
        exo.release()
    }

    private fun releaseConnection() {
        if (!holdsConnection) return
        holdsConnection = false
        ConnectionGate.release()
    }
}

/**
 * The first picture is the channel playing, when it is not locked. The rest are the following
 * channels that are not locked. Empty slots stay empty when there are not enough of those.
 */
private fun initialPicks(
    channels: List<LiveChannel>,
    start: Int,
    slots: Int,
    isLocked: (LiveChannel) -> Boolean
): List<Int> {
    val count = slots.coerceIn(2, 4)
    if (channels.isEmpty()) return List(count) { -1 }
    val first = start.coerceIn(0, channels.size - 1)
    val chosen = ArrayList<Int>(count)
    var step = 0
    while (chosen.size < count && step < channels.size) {
        val i = (first + step) % channels.size
        step++
        if (isLocked(channels[i])) continue
        if (i !in chosen) chosen.add(i)
    }
    while (chosen.size < count) chosen.add(-1)
    return chosen
}

/**
 * Two, three, or four channels at once. Each one is its own compact player, so they do not ask
 * for audio focus and pause each other. Only the highlighted picture is unmuted.
 * Up and down change that picture's channel and skip locked ones. Left and right move the
 * highlight. OK or Back returns to the highlighted channel full screen.
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
    val slots = remember {
        PlayerOptionsStore(context).load().multiviewSlots.coerceIn(2, 4)
    }
    var picks by remember { mutableStateOf(initialPicks(channels, start, slots, isLocked)) }
    val shown = List(slots) { i -> channels.getOrNull(picks.getOrElse(i) { -1 }) }
    var focused by remember { mutableIntStateOf(0) }
    val tiles = remember(slots) { List(slots) { Tile(context) } }
    val notes = remember(slots) { mutableStateListOf<String?>().apply { repeat(slots) { add(null) } } }
    val requesters = remember(slots) { List(slots) { FocusRequester() } }
    DisposableEffect(tiles) { onDispose { tiles.forEach { it.release() } } }

    // Leaving the app stops every picture and lets go of the connections. Coming back starts them again.
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

    // Compact players never take audio focus. Volume keeps the sound on the highlighted picture only.
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
        val from = picks.getOrElse(focused) { -1 }
        if (from < 0) {
            val seed = channels.indices.firstOrNull { !isLocked(channels[it]) } ?: return
            picks = picks.toMutableList().also { it[focused] = seed }
            return
        }
        for (step in 1..channels.size) {
            val candidate = (from + delta * step).mod(channels.size)
            if (candidate != from && !isLocked(channels[candidate])) {
                picks = picks.toMutableList().also { it[focused] = candidate }
                return
            }
        }
    }

    fun focusBy(delta: Int) {
        val next = (focused + delta).mod(slots)
        focused = next
        runCatching { requesters[next].requestFocus() }
    }

    BackHandler {
        val ch = shown.getOrNull(focused) ?: shown.firstOrNull { it != null }
        if (ch != null) onExit(ch) else onClose()
    }
    LaunchedEffect(slots) {
        withFrameNanos { }
        runCatching { requesters[0].requestFocus() }
    }
    val heading = when (slots) {
        3 -> "Three channels"
        4 -> "Four channels"
        else -> "Two channels"
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
                    // Up and down change the channel, so left and right are what reach the other pictures.
                    KeyEvent.KEYCODE_DPAD_LEFT -> { focusBy(-1); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { focusBy(1); true }
                    else -> false
                }
            }
            .padding(18.dp)
    ) {
        Text(
            "$heading. Each picture is a separate connection, so a one-stream login may stop one of them. Left and right move between pictures. Up and down change the highlighted channel. OK watches it full screen.",
            style = TvType.Small,
            color = Tv.Ink2
        )
        Spacer(Modifier.height(12.dp))
        when (slots) {
            3 -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MultiTile(0, shown, tiles, notes, requesters, onExit) { focused = it }
                    MultiTile(1, shown, tiles, notes, requesters, onExit) { focused = it }
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    MultiTile(2, shown, tiles, notes, requesters, onExit) { focused = it }
                }
            }
            4 -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MultiTile(0, shown, tiles, notes, requesters, onExit) { focused = it }
                    MultiTile(1, shown, tiles, notes, requesters, onExit) { focused = it }
                }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MultiTile(2, shown, tiles, notes, requesters, onExit) { focused = it }
                    MultiTile(3, shown, tiles, notes, requesters, onExit) { focused = it }
                }
            }
            else -> Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MultiTile(0, shown, tiles, notes, requesters, onExit) { focused = it }
                MultiTile(1, shown, tiles, notes, requesters, onExit) { focused = it }
            }
        }
    }
}

/** One picture and the button that watches it full screen. The button is what takes focus. */
@Composable
private fun RowScope.MultiTile(
    index: Int,
    shown: List<LiveChannel?>,
    tiles: List<Tile>,
    notes: List<String?>,
    requesters: List<FocusRequester>,
    onExit: (LiveChannel) -> Unit,
    onFocus: (Int) -> Unit
) {
    val ch = shown.getOrNull(index)
    val tile = tiles[index]
    val note = notes.getOrNull(index)
    Column(Modifier.weight(1f).fillMaxHeight()) {
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
            if (tile.coverBlack) {
                Box(Modifier.fillMaxSize().background(Color.Black))
            }
            val status = when {
                ch == null -> null
                note != null -> note
                tile.limited -> "Too many streams for this provider"
                tile.failed -> "This channel did not start. A one-stream login refuses another picture."
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
            modifier = Modifier.focusRequester(requesters[index]).fillMaxWidth(),
            onFocus = { if (it) onFocus(index) }
        )
    }
}
