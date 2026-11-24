package com.dallas.cabletv.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import com.dallas.cabletv.data.liveMediaItem
import com.dallas.cabletv.data.TrackMemory
import com.dallas.cabletv.data.newPlayer
import com.dallas.cabletv.data.playbackHttpStatus
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.dallas.cabletv.R
import com.dallas.cabletv.data.EpgCache
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.NowNext
import com.dallas.cabletv.data.Program
import kotlinx.coroutines.delay

/**
 * The live TV player. It lives for the whole app, so Home and the guide can keep the channel
 * playing behind them, and coming back to full screen never reloads the stream.
 */
class LivePlayer(context: Context) {
    val exo: ExoPlayer = newPlayer(context)
    var buffering by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    /** False once the viewer pauses. It follows playWhenReady, so it stays true while the stream buffers. */
    var playing by mutableStateOf(true)
        private set
    /** True once the viewer paused or moved back, so Left and Right skip instead of opening the channel list. */
    var behindLive by mutableStateOf(false)
        private set
    /** Goes up each time a replay plays to its end, so the app can return to the live channel. */
    var replayEnds by mutableIntStateOf(0)
        private set
    /** Goes up when Try again has no address to reload, so the app looks the channel up again. */
    var reloadRequests by mutableIntStateOf(0)
        private set
    var resizeMode by mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)

    // The address asked for, and the one actually loaded, which differs after a fallback.
    private var requested: String? = null
    private var url: String? = null
    private var fallback: String? = null
    private var triedFallback = false
    private var played = false
    private var retries = 0
    private var replay = false
    // A live stream that keeps ending soon after it starts is counted, so it ends in No signal
    // instead of being loaded again forever.
    private var readyAt = 0L
    private var quickEnds = 0
    private val handler = Handler(Looper.getMainLooper())
    // Channels whose main stream the provider refused, mapped to the MPEG-TS address that worked instead.
    private val fallbackFor = HashMap<String, String>()

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            // One switch from HLS to MPEG-TS when the panel refuses the stream before it plays.
            val status = playbackHttpStatus(error)
            val fb = fallback
            if (!played && !triedFallback && !fb.isNullOrBlank() && fb != url && status != null && status >= 400) {
                triedFallback = true
                requested?.let { fallbackFor[it] = fb }
                load(fb)
                return
            }
            // Paused for longer than the provider keeps: start again at the live edge, without waiting.
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && retries < MAX_RETRIES) {
                retries++
                behindLive = false
                handler.post {
                    exo.seekToDefaultPosition()
                    exo.prepare()
                    exo.playWhenReady = true
                }
                return
            }
            retryLater(reload = false)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            buffering = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_READY) {
                played = true
                retries = 0
                failed = false
                readyAt = SystemClock.elapsedRealtime()
            }
            if (playbackState == Player.STATE_ENDED) {
                // A replay that finished goes back to live. A live stream that the provider closed loads again.
                if (replay) {
                    replayEnds++
                } else {
                    quickEnds = if (SystemClock.elapsedRealtime() - readyAt < QUICK_END_MS) quickEnds + 1 else 0
                    if (quickEnds >= MAX_QUICK_ENDS) failed = true else retryLater(reload = true)
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            playing = playWhenReady
            // Any pause, by the viewer or by another app taking the sound, leaves the picture behind live.
            if (!playWhenReady && requested != null) behindLive = true
        }
    }

    init {
        exo.addListener(listener)
    }

    // Most failures are brief, so retry a few times before saying there is no signal.
    private fun retryLater(reload: Boolean) {
        if (retries >= MAX_RETRIES) {
            failed = true
            return
        }
        retries++
        handler.postDelayed({
            val target = url
            runCatching {
                if (reload && target != null) exo.setMediaItem(liveMediaItem(target))
                exo.prepare()
                exo.playWhenReady = true
            }.onFailure {
                buffering = false
                failed = true
            }
        }, 2000)
    }

    /**
     * Starts a stream. Asking for the one already playing does nothing. [fallbackUrl] is an MPEG-TS
     * address tried once if the provider refuses [newUrl] before it plays. Replays pass none, and
     * [replay] true, so the end of a replay returns to live instead of loading it again.
     */
    fun play(newUrl: String, fallbackUrl: String? = null, replay: Boolean = false) {
        if (newUrl == requested) return
        requested = newUrl
        fallback = fallbackUrl
        this.replay = replay
        val known = fallbackFor[newUrl]
        triedFallback = known != null
        load(known ?: newUrl)
    }

    private fun load(target: String) {
        handler.removeCallbacksAndMessages(null)
        url = target
        failed = false
        played = false
        retries = 0
        quickEnds = 0
        behindLive = false
        // A track picked on the previous channel must not stick. The saved language still applies.
        runCatching {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().clearOverrides().build()
            exo.setMediaItem(liveMediaItem(target))
            exo.prepare()
            exo.playWhenReady = true
        }.onFailure {
            buffering = false
            failed = true
        }
    }

    /** Tries the current address again after the retries have given up. */
    fun retry() {
        val target = url
        handler.removeCallbacksAndMessages(null)
        retries = 0
        quickEnds = 0
        failed = false
        played = false
        if (target == null) {
            // Nothing was loaded, for example a portal link that did not resolve. The app looks it up again.
            buffering = true
            reloadRequests++
            return
        }
        behindLive = false
        runCatching {
            exo.setMediaItem(liveMediaItem(target))
            exo.prepare()
            exo.playWhenReady = true
        }.onFailure {
            buffering = false
            failed = true
        }
    }

    fun togglePause() {
        if (exo.playWhenReady) pause() else resume()
    }

    fun pause() {
        exo.pause()
        behindLive = true
    }

    fun resume() {
        exo.play()
    }

    /** Jumps back to the live edge after a pause or a short rewind. */
    fun backToLive() {
        behindLive = false
        exo.seekToDefaultPosition()
        exo.play()
    }

    /** Moves within a replay, or within what is buffered of a live channel. Forward past the live edge returns to live. */
    fun seekBy(deltaMs: Long) {
        if (!replay && deltaMs > 0) {
            val edge = liveEdgeMs()
            if (edge != null && exo.currentPosition + deltaMs >= edge) {
                backToLive()
                return
            }
        }
        val duration = exo.duration
        var target = exo.currentPosition + deltaMs
        if (target < 0L) target = 0L
        if (duration != C.TIME_UNSET && duration > 0L && target > duration - 1000L) target = (duration - 1000L).coerceAtLeast(0L)
        exo.seekTo(target)
        if (deltaMs < 0) behindLive = true
    }

    /**
     * True while live playback is at the edge, so Left still opens the channel list. Normal HLS plays
     * some way behind the newest segment, so only a pause or a move back counts as behind.
     */
    fun atLiveEdge(): Boolean {
        if (failed || !behindLive) return true
        if (!playing) return false
        // A stream with no live window (plain MPEG-TS) cannot be moved through, so once it plays again it counts as live.
        val edge = liveEdgeMs() ?: return true
        return exo.currentPosition >= edge - 3_000L
    }

    // Where the player joins a live stream, in the same terms as currentPosition. Null when not live.
    private fun liveEdgeMs(): Long? {
        if (!exo.isCurrentMediaItemLive) return null
        val timeline = exo.currentTimeline
        if (timeline.isEmpty) return null
        val window = timeline.getWindow(exo.currentMediaItemIndex, Timeline.Window())
        return window.defaultPositionMs.takeIf { it != C.TIME_UNSET }
    }

    /** Stops and lets go of the provider connection, for example while a movie plays. */
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        requested = null
        url = null
        buffering = false
        failed = false
        exo.stop()
    }

    /** Stops the previous channel while the next one's address is looked up, and shows Loading. */
    fun resolving() {
        stop()
        buffering = true
    }

    /** Stops the previous channel and shows the no-signal message, for a channel whose address was not found. */
    fun fail() {
        stop()
        failed = true
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        exo.removeListener(listener)
        exo.release()
    }

    private companion object {
        const val MAX_RETRIES = 4
        const val QUICK_END_MS = 15_000L
        const val MAX_QUICK_ENDS = 3
    }
}

/** The live picture. The app places it full screen, or in the guide's corner preview. */
@Composable
fun LiveVideo(player: LivePlayer, modifier: Modifier = Modifier) {
    val resize = player.resizeMode
    AndroidView(
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(R.layout.live_player_view, null) as PlayerView).also { it.player = player.exo }
        },
        update = { it.resizeMode = resize },
        onRelease = { it.player = null },
        modifier = modifier
    )
}

private enum class LivePanel { None, Options, Channels, Replay, Tracks }

/**
 * Full-screen live TV. Up and down change channel. OK opens a bar with everything you can do,
 * Left opens the channel list, and number keys jump to a channel. Every action is also a button,
 * so a remote with only arrows, OK and Back can reach all of it.
 * [onTune] is a direct choice by index and asks for the PIN on a locked channel. [onStep] moves up
 * or down by one and skips locked channels. [overlayOpen] is true while the PIN pad or a reminder
 * is on top, so the screen takes focus back when it closes.
 */
@Composable
fun LiveTvScreen(
    channels: List<LiveChannel>,
    index: Int,
    player: LivePlayer,
    guide: GuideData,
    favorites: Set<String>,
    recents: List<String>,
    group: String,
    onGroupChange: (String) -> Unit,
    loading: Boolean,
    error: String?,
    canReplay: Boolean,
    replaying: Boolean,
    onTune: (Int) -> Unit,
    onStep: (Int) -> Unit,
    onToggleFavorite: (LiveChannel) -> Unit,
    onReplay: (LiveChannel, Program) -> Unit,
    onReturnToLive: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenHome: () -> Unit,
    onOpenSearch: () -> Unit,
    onLastChannel: () -> Unit,
    onExit: () -> Unit,
    onMultiView: () -> Unit,
    onRecord: (LiveChannel, Program) -> Unit,
    showToast: (String) -> Unit,
    locked: Boolean = false,
    onUnlock: () -> Unit = {},
    overlayOpen: Boolean = false,
    onReload: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val current = channels.getOrNull(index)
    val nn = current?.let { guide.nowNext(it) }
    var panel by remember { mutableStateOf(LivePanel.None) }
    var bannerTick by remember { mutableIntStateOf(1) }
    var showBanner by remember { mutableStateOf(false) }
    var digits by remember { mutableStateOf("") }
    var lastKey by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val rootFocus = remember { FocusRequester() }
    val emptyAction = remember { FocusRequester() }
    // With no channels to show, Try again and Settings take the remote instead of the screen.
    val emptyActions = channels.isEmpty() && !loading

    fun close() {
        panel = LivePanel.None
    }

    fun tune(newIndex: Int) {
        if (channels.isEmpty()) return
        onTune((newIndex + channels.size) % channels.size)
        digits = ""
        bannerTick++
    }

    fun step(delta: Int) {
        if (channels.isEmpty()) return
        onStep(delta)
        digits = ""
        bannerTick++
    }

    // Tunes to the typed number. OK and the fourth digit do this at once, otherwise a short pause does.
    fun confirmDigits() {
        val typed = digits
        digits = ""
        if (typed.isEmpty()) return
        val target = channels.indexOfFirst { it.number.toString() == typed }
        if (target >= 0) {
            panel = LivePanel.None
            tune(target)
        } else {
            showToast("There is no channel $typed")
        }
    }

    BackHandler(enabled = panel == LivePanel.None) { if (replaying) onReturnToLive() else onExit() }
    BackHandler(enabled = panel != LivePanel.None) { close() }

    // With no panel open, the screen itself holds focus so it receives the remote's keys. This runs
    // again when the PIN pad or a reminder closes, so the next key press is not lost.
    LaunchedEffect(panel, overlayOpen, emptyActions) {
        // A PIN pad or a reminder on top closes the options bar or channel list, so focus has one place to come back to.
        if (overlayOpen && panel != LivePanel.None) panel = LivePanel.None
        if (panel != LivePanel.None || overlayOpen) return@LaunchedEffect
        withFrameNanos { }
        if (emptyActions) runCatching { emptyAction.requestFocus() } else runCatching { rootFocus.requestFocus() }
    }

    LaunchedEffect(bannerTick) {
        showBanner = true
        delay(4500)
        showBanner = false
    }

    // A dead channel is tried again every so often, so a short network drop recovers by itself.
    LaunchedEffect(player.failed) {
        if (!player.failed) return@LaunchedEffect
        delay(15_000)
        if (player.failed) player.retry()
    }

    // The options bar and channel list close by themselves after a while without a key press.
    LaunchedEffect(panel, lastKey) {
        val wait = when (panel) {
            LivePanel.Options -> 10_000L
            LivePanel.Channels -> 20_000L
            else -> return@LaunchedEffect
        }
        delay(wait)
        close()
    }

    // Typed channel numbers tune once the viewer pauses.
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1400)
        confirmDigits()
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                lastKey = System.currentTimeMillis()
                val code = ev.nativeKeyEvent.keyCode
                val digit = when (code) {
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
                    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
                    else -> -1
                }
                if (digit >= 0 && panel != LivePanel.Tracks && channels.isNotEmpty()) {
                    if (digits.length < 4) digits += digit.toString()
                    if (digits.length == 4) confirmDigits()
                    return@onPreviewKeyEvent true
                }
                val ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                if (ok && digits.isNotEmpty()) {
                    confirmDigits()
                    return@onPreviewKeyEvent true
                }
                when (code) {
                    KeyEvent.KEYCODE_CHANNEL_UP -> { panel = LivePanel.None; step(1); return@onPreviewKeyEvent true }
                    KeyEvent.KEYCODE_CHANNEL_DOWN -> { panel = LivePanel.None; step(-1); return@onPreviewKeyEvent true }
                    KeyEvent.KEYCODE_GUIDE -> { onOpenGuide(); return@onPreviewKeyEvent true }
                    KeyEvent.KEYCODE_LAST_CHANNEL -> { panel = LivePanel.None; onLastChannel(); return@onPreviewKeyEvent true }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        player.togglePause()
                        return@onPreviewKeyEvent true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        player.resume()
                        return@onPreviewKeyEvent true
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        player.pause()
                        return@onPreviewKeyEvent true
                    }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        if (replaying || !player.atLiveEdge()) {
                            player.seekBy(-10_000)
                            bannerTick++
                            return@onPreviewKeyEvent true
                        }
                        return@onPreviewKeyEvent false
                    }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        if (replaying || !player.atLiveEdge()) {
                            player.seekBy(30_000)
                            bannerTick++
                            return@onPreviewKeyEvent true
                        }
                        return@onPreviewKeyEvent false
                    }
                }
                // The Try again and Settings buttons handle their own keys.
                if (channels.isEmpty()) return@onPreviewKeyEvent false
                if (panel == LivePanel.Options && code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    close()
                    return@onPreviewKeyEvent true
                }
                if (panel != LivePanel.None) return@onPreviewKeyEvent false
                if (locked && ok) {
                    onUnlock()
                    return@onPreviewKeyEvent true
                }
                if (player.failed && ok) {
                    player.retry()
                    return@onPreviewKeyEvent true
                }
                val skipping = !locked && !player.failed && (replaying || !player.atLiveEdge())
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> { step(1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { step(-1); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (skipping) {
                            player.seekBy(-10_000)
                            bannerTick++
                        } else {
                            panel = LivePanel.Channels
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (skipping) player.seekBy(30_000)
                        bannerTick++
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                        showBanner = false
                        panel = LivePanel.Options
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                // A tap on a phone opens the options bar, like OK on a remote.
                when {
                    panel != LivePanel.None -> close()
                    locked -> onUnlock()
                    channels.isNotEmpty() -> { panel = LivePanel.Options }
                }
            }
    ) {
        when {
            channels.isEmpty() -> Column(
                Modifier.fillMaxSize().background(Tv.Scrim),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                EmptyState(
                    title = when {
                        loading -> "Finding your channels"
                        error != null -> "Your channels did not load"
                        else -> "No channels yet"
                    },
                    body = when {
                        loading -> "This takes a few seconds the first time."
                        error != null -> "${error.trim().trimEnd('.')}. Check your login in Settings, Live TV."
                        else -> "Add your TV provider in Settings, Live TV."
                    }
                )
                if (emptyActions) {
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Try again", onReload, icon = TvIcons.Replay, modifier = Modifier.focusRequester(emptyAction))
                        PillButton("Settings", onOpenSettings)
                    }
                }
            }
            locked -> Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(
                        title = "This channel is locked",
                        body = "Press OK to enter the PIN. Up or down picks another channel."
                    )
                    Spacer(Modifier.height(16.dp))
                    PillButton("Enter PIN", onUnlock, icon = TvIcons.Lock)
                }
            }
            player.failed -> Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (replaying) {
                        EmptyState(
                            title = "The replay did not start",
                            body = "The provider did not send this replay. OK tries again. Back returns to live."
                        )
                    } else {
                        EmptyState(
                            title = "No signal on ${current?.name ?: "this channel"}",
                            body = "The provider is not sending this channel right now. OK tries again. Up or down picks another."
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    PillButton("Try again", { player.retry() }, icon = TvIcons.Replay)
                }
            }
        }

        if (player.buffering && !player.failed && !locked && channels.isNotEmpty() && !showBanner && panel == LivePanel.None) {
            Text(
                "Loading",
                style = TvType.SmallStrong,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(40.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Tv.Scrim)
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }

        if (replaying && panel == LivePanel.None) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(30.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Tv.Scrim)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(TvIcons.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("Replay. Back returns to live.", style = TvType.CaptionStrong)
            }
        }

        // Paused, or watching behind live after a pause or a rewind.
        val paused = !player.playing
        if (!replaying && !locked && !player.failed && current != null && panel == LivePanel.None &&
            (paused || (player.behindLive && !player.atLiveEdge()))
        ) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(30.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Tv.Scrim)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(if (paused) TvIcons.Pause else TvIcons.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(
                    "${if (paused) "Paused" else "Behind live"}. Left and Right move back and forward. OK for Back to live.",
                    style = TvType.CaptionStrong
                )
            }
        }

        if (digits.isNotEmpty()) {
            val match = channels.firstOrNull { it.number.toString() == digits }
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(30.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Tv.Scrim)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text(digits, style = TvType.Display.copy(fontSize = 38.sp), color = Tv.Accent)
                Text(match?.name ?: "No channel $digits", style = TvType.Small, color = Tv.Ink2)
            }
        }

        AnimatedVisibility(
            visible = showBanner && panel == LivePanel.None && current != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart)
        ) {
            if (current != null) ChannelBanner(current, nn, current.id in favorites, replaying)
        }

        if (replaying && panel == LivePanel.None) {
            ReplayTransport(
                player = player,
                onReturnToLive = onReturnToLive,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        if (panel == LivePanel.Options && current != null) {
            OptionsBar(
                channel = current,
                nn = nn,
                isFavorite = current.id in favorites,
                canReplay = canReplay && current.catchup,
                replaying = replaying,
                playing = player.playing,
                onGuide = onOpenGuide,
                onHome = onOpenHome,
                onSearch = onOpenSearch,
                onChannels = { panel = LivePanel.Channels },
                behind = !replaying && !player.atLiveEdge(),
                onPause = { player.togglePause() },
                onJumpLive = { player.backToLive() },
                onMultiView = onMultiView,
                onRecord = {
                    val showing = nn?.now
                    if (showing == null) showToast("There is nothing to record right now")
                    else onRecord(current, showing)
                },
                onSeek = {
                    player.seekBy(it)
                    bannerTick++
                },
                onReturnToLive = onReturnToLive,
                onFavorite = {
                    val adding = current.id !in favorites
                    onToggleFavorite(current)
                    showToast(if (adding) "Added ${current.name} to favorites" else "Removed ${current.name} from favorites")
                },
                onReplay = { panel = LivePanel.Replay },
                onTracks = { panel = LivePanel.Tracks },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        AnimatedVisibility(
            visible = panel == LivePanel.Channels,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            ChannelListPanel(
                channels = channels,
                currentIndex = index,
                favorites = favorites,
                recents = recents,
                guide = guide,
                group = group,
                onGroupChange = onGroupChange,
                onPick = {
                    close()
                    tune(it)
                },
                onClose = { close() }
            )
        }

        if (panel == LivePanel.Replay && current != null) {
            ReplayPanel(
                channel = current,
                now = nn?.now,
                onPick = { p ->
                    close()
                    onReplay(current, p)
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }

        if (panel == LivePanel.Tracks) {
            TrackPanel(
                player = player.exo,
                resizeMode = player.resizeMode,
                onResize = { player.resizeMode = it },
                onAudioLanguage = { TrackMemory.saveAudio(context, player.exo, it) },
                onCaptions = { enabled, language -> TrackMemory.saveCaptions(context, player.exo, enabled, language) },
                onClose = { close() },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

/** Pause and skip for a replay. Left and right on the remote do the same thing. */
@Composable
private fun ReplayTransport(player: LivePlayer, onReturnToLive: () -> Unit, modifier: Modifier = Modifier) {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            position = player.exo.currentPosition.coerceAtLeast(0L)
            val reported = player.exo.duration
            duration = if (reported != C.TIME_UNSET && reported > 0L) reported else 0L
            delay(500)
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE60D0F13))))
            .padding(start = 40.dp, end = 40.dp, top = 28.dp, bottom = 22.dp)
    ) {
        if (duration > 0L) {
            ProgressBar(position.toFloat() / duration, Modifier.fillMaxWidth(), color = Tv.Accent)
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatPosition(position), style = TvType.Caption, color = Tv.Ink2)
            Spacer(Modifier.weight(1f))
            Text(if (duration > 0L) formatPosition(duration) else "Replay", style = TvType.Caption, color = Tv.Ink2)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(if (player.playing) "Pause" else "Play", { player.togglePause() }, icon = if (player.playing) TvIcons.Pause else TvIcons.Play)
            PillButton("Back 10 seconds", { player.seekBy(-10_000) }, icon = TvIcons.Back)
            PillButton("Forward 30 seconds", { player.seekBy(30_000) }, icon = TvIcons.Next)
            PillButton("Back to live", onReturnToLive, icon = TvIcons.Replay)
        }
    }
}

/** Shown for a few seconds after each channel change. */
@Composable
private fun ChannelBanner(channel: LiveChannel, nn: NowNext?, isFavorite: Boolean, replaying: Boolean) {
    val now = nn?.now
    Column(
        Modifier
            .padding(start = 40.dp, bottom = if (replaying) 136.dp else 28.dp, end = 40.dp)
            .widthIn(max = 540.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Tv.Scrim)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel, Modifier.size(78.dp, 44.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(channel.number.toString(), style = TvType.BodyStrong, color = Tv.Accent)
                    Spacer(Modifier.width(9.dp))
                    Text(channel.name, style = TvType.Title.copy(fontWeight = FontWeight.ExtraBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (isFavorite) {
                        Spacer(Modifier.width(8.dp))
                        Icon(TvIcons.StarFilled, contentDescription = "Favorite", tint = Tv.Accent, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (now != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(now.title, style = TvType.SmallStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        ProgressBar(progressOf(now), Modifier.width(80.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(timeLeft(now), style = TvType.Small, color = Tv.Ink2, maxLines = 1)
                    }
                } else {
                    Text("No guide information for this channel", style = TvType.Small, color = Tv.Ink3)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x14FFFFFF)))
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            KeyHint("OK", "More options")
            if (replaying) {
                KeyHint("◀ ▶", "Skip")
                KeyHint("Back", "Live")
            } else {
                KeyHint("◀", "Channel list")
                KeyHint("Back", "Home")
            }
            KeyHint("▲▼", "Change channel")
        }
    }
}

/** Everything you can do while watching, as buttons. Opens with OK. */
@Composable
private fun OptionsBar(
    channel: LiveChannel,
    nn: NowNext?,
    isFavorite: Boolean,
    canReplay: Boolean,
    replaying: Boolean,
    playing: Boolean,
    onGuide: () -> Unit,
    onHome: () -> Unit,
    onSearch: () -> Unit,
    onChannels: () -> Unit,
    onFavorite: () -> Unit,
    onReplay: () -> Unit,
    behind: Boolean,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onReturnToLive: () -> Unit,
    onJumpLive: () -> Unit,
    onMultiView: () -> Unit,
    onRecord: () -> Unit,
    onTracks: () -> Unit,
    modifier: Modifier = Modifier
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val now = nn?.now
    val next = nn?.next
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE60D0F13), Color(0xF80D0F13))))
    ) {
        val side = if (maxWidth < 600.dp) 16.dp else 40.dp
        val wide = maxWidth >= 700.dp
        Column(Modifier.trapFocus().padding(start = side, end = side, top = 70.dp, bottom = 26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(channel, Modifier.size(78.dp, 44.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(channel.number.toString(), style = TvType.Section, color = Tv.Accent)
                        Spacer(Modifier.width(10.dp))
                        Text(channel.name, style = TvType.Headline.copy(fontSize = 24.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        if (isFavorite) "★ Favorite" else channel.group,
                        style = TvType.SmallStrong,
                        color = if (isFavorite) Tv.Accent else Tv.Ink3,
                        maxLines = 1
                    )
                }
                Clock()
            }
            Spacer(Modifier.height(10.dp))
            if (now != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LivePill()
                    Spacer(Modifier.width(10.dp))
                    Text(now.title, style = TvType.Title.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatRange(now), style = TvType.Small, color = Tv.Ink2)
                    Spacer(Modifier.width(10.dp))
                    ProgressBar(progressOf(now), Modifier.width(220.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(timeLeft(now), style = TvType.Small, color = Tv.Ink2)
                    Spacer(Modifier.weight(1f))
                    if (next != null && wide) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Next at ${formatTime(next.start)}", style = TvType.Small, color = Tv.Ink2)
                            Text(next.title, style = TvType.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp))
                        }
                    }
                }
                if (now.desc.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(now.desc, style = TvType.Small, color = Tv.Ink2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 620.dp))
                }
            } else {
                Text("No guide information for this channel", style = TvType.Body, color = Tv.Ink3)
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PillButton("Guide", onGuide, icon = TvIcons.Guide, modifier = Modifier.focusRequester(first))
                PillButton("Home", onHome, icon = TvIcons.Tv)
                PillButton("Search", onSearch, icon = TvIcons.Search)
                PillButton("Channel list", onChannels, icon = TvIcons.List)
                PillButton(
                    if (isFavorite) "Remove favorite" else "Add to favorites",
                    onFavorite,
                    icon = if (isFavorite) TvIcons.StarFilled else TvIcons.Star
                )
                if (replaying || behind) {
                    PillButton(if (playing) "Pause" else "Play", onPause, icon = if (playing) TvIcons.Pause else TvIcons.Play)
                    PillButton("Back 10 seconds", { onSeek(-10_000) }, icon = TvIcons.Back)
                    PillButton("Forward 30 seconds", { onSeek(30_000) }, icon = TvIcons.Next)
                    PillButton("Back to live", if (replaying) onReturnToLive else onJumpLive, icon = TvIcons.Replay)
                } else {
                    PillButton(if (playing) "Pause" else "Play", onPause, icon = if (playing) TvIcons.Pause else TvIcons.Play)
                    if (canReplay) PillButton("Watch from the start", onReplay, icon = TvIcons.Replay)
                }
                PillButton("Record", onRecord, icon = TvIcons.Play)
                PillButton("Two channels", onMultiView, icon = TvIcons.Tv)
                PillButton("Audio and captions", onTracks, icon = TvIcons.Captions)
            }
        }
    }
}

/**
 * The channel list over the left of the picture. The highlight moves without changing channel until
 * OK. The category at the top is shared with the guide. Left, or OK on the category, opens the list of
 * categories beside it.
 */
@Composable
private fun ChannelListPanel(
    channels: List<LiveChannel>,
    currentIndex: Int,
    favorites: Set<String>,
    recents: List<String>,
    guide: GuideData,
    group: String,
    onGroupChange: (String) -> Unit,
    onPick: (Int) -> Unit,
    onClose: () -> Unit
) {
    var picking by remember { mutableStateOf(false) }
    val groups = remember(channels) { groupsOf(channels) }
    val indexById = remember(channels) { channels.withIndex().associate { it.value.id to it.index } }
    val shown: List<Int> = remember(group, channels, favorites, recents) {
        channelsIn(group, channels, favorites, recents).mapNotNull { indexById[it.id] }
    }
    val listState = rememberLazyListState()
    val start = remember { FocusRequester() }
    val categoryButton = remember { FocusRequester() }
    val startPos = shown.indexOf(currentIndex).coerceAtLeast(0)

    BackHandler(enabled = picking) { picking = false }

    // Focus lands on the channel playing, or the first in the category, whenever the list is shown.
    LaunchedEffect(group, picking) {
        if (picking) return@LaunchedEffect
        if (shown.isEmpty()) {
            withFrameNanos { }
            runCatching { categoryButton.requestFocus() }
            return@LaunchedEffect
        }
        listState.scrollToItem((startPos - 3).coerceAtLeast(0))
        repeat(3) {
            withFrameNanos { }
            if (runCatching { start.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    BoxWithConstraints {
        val narrow = maxWidth < 600.dp
        val panelWidth = minOf(340.dp, maxWidth * 0.88f)
        Row {
            if (picking) {
                CategoryPanel(
                    title = "Categories",
                    items = categoryItems(groups),
                    selected = group,
                    countOf = { g -> channelsIn(g, channels, favorites, recents).size },
                    onPick = { g ->
                        onGroupChange(g)
                        picking = false
                    },
                    onClose = { picking = false }
                )
            }
            if (!picking || !narrow) {
                SidePanel(
                    "Channels",
                    Modifier
                        .width(panelWidth)
                        .onPreviewKeyEvent { ev ->
                            if (ev.type != KeyEventType.KeyDown || picking) return@onPreviewKeyEvent false
                            when (ev.nativeKeyEvent.keyCode) {
                                KeyEvent.KEYCODE_DPAD_LEFT -> { picking = true; true }
                                KeyEvent.KEYCODE_DPAD_RIGHT -> { onClose(); true }
                                else -> false
                            }
                        }
                ) {
                    Focusable(
                        onClick = { picking = true },
                        modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 10.dp).fillMaxWidth().height(32.dp).focusRequester(categoryButton),
                        shape = RoundedCornerShape(8.dp),
                        color = Tv.Surface2,
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(Modifier.padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(group, style = TvType.SmallStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${shown.size}", style = TvType.Caption, color = ink3)
                            Spacer(Modifier.width(6.dp))
                            Icon(TvIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                    if (shown.isEmpty()) {
                        Text(
                            when (group) {
                                GROUP_FAVORITES -> "No favorites yet. Use Add to favorites while watching."
                                GROUP_RECENT -> "Channels you watch show up here."
                                else -> "No channels in this category."
                            },
                            style = TvType.Small,
                            color = Tv.Ink2,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                        )
                    }
                    LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                        itemsIndexed(shown, key = { _, i -> channels[i].id }) { pos, i ->
                            val ch = channels[i]
                            val now = guide.nowNext(ch)?.now
                            Focusable(
                                onClick = { onPick(i) },
                                modifier = (if (pos == startPos) Modifier.focusRequester(start) else Modifier).fillMaxWidth(),
                                shape = RoundedCornerShape(9.dp)
                            ) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        ch.number.toString(),
                                        style = TvType.SmallStrong,
                                        color = if (LocalFocused.current) Tv.FocusInk else if (i == currentIndex) Tv.Accent else Tv.Ink3,
                                        modifier = Modifier.width(34.dp),
                                        maxLines = 1
                                    )
                                    ChannelLogo(ch, Modifier.size(52.dp, 30.dp), compact = true)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(ch.name, style = TvType.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(now?.title ?: "No guide information", style = TvType.Caption, color = ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (now != null) {
                                            Spacer(Modifier.height(5.dp))
                                            ProgressBar(progressOf(now), Modifier.widthIn(max = 150.dp).fillMaxWidth(), height = 3.dp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.padding(horizontal = 24.dp).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        KeyHint("OK", "Watch")
                        KeyHint("\u25C0", "Categories")
                        KeyHint("\u25B6", "Close")
                    }
                }
            }
        }
    }
}

/** Earlier shows on a channel with catch-up, and the current one from its start. */
@Composable
private fun ReplayPanel(channel: LiveChannel, now: Program?, onPick: (Program) -> Unit, modifier: Modifier = Modifier) {
    // Shows inside the provider's archive window. One day when the provider does not say.
    val past = remember(channel.id, channel.archiveDays) {
        val day = 24L * 3_600_000L
        val days = if (channel.archiveDays > 0) channel.archiveDays.coerceAtMost(2) else 2
        EpgCache.pastPrograms(channel.epgId, days * day, channel.name)
    }
    val items = listOfNotNull(now) + past
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    SidePanel("Watch from the start", modifier.width(360.dp)) {
        if (items.isEmpty()) {
            Text(
                "There is nothing to replay on this channel yet.",
                style = TvType.Small,
                color = Tv.Ink2,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp)) {
            itemsIndexed(items, key = { _, p -> p.start }) { i, p ->
                Focusable(
                    onClick = { onPick(p) },
                    modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth(),
                    shape = RoundedCornerShape(9.dp)
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Text(p.title, style = TvType.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (p == now) "On now. Started at ${formatTime(p.start)}" else "Earlier, ${formatRange(p)}",
                            style = TvType.Caption,
                            color = ink3
                        )
                    }
                }
            }
        }
        Text(
            "About ${if (channel.archiveDays > 0) channel.archiveDays.coerceAtMost(2) else 2} days of ${channel.name} are listed. Rewind on a live channel only reaches the last few minutes.",
            style = TvType.Caption,
            color = Tv.Ink3,
            modifier = Modifier.padding(horizontal = 24.dp).padding(top = 8.dp)
        )
    }
}
