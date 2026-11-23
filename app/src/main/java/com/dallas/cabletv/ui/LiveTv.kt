package com.dallas.cabletv.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
    var resizeMode by mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)

    // The address asked for, and the one actually loaded, which differs after a fallback.
    private var requested: String? = null
    private var url: String? = null
    private var fallback: String? = null
    private var triedFallback = false
    private var played = false
    private var retries = 0
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
            // Most failures are brief, so retry a few times before saying there is no signal.
            if (retries < MAX_RETRIES) {
                retries++
                handler.postDelayed({
                    exo.prepare()
                    exo.playWhenReady = true
                }, 2000)
            } else {
                failed = true
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            buffering = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_READY) {
                played = true
                retries = 0
                failed = false
            }
        }
    }

    init {
        exo.addListener(listener)
    }

    /**
     * Starts a stream. Asking for the one already playing does nothing. [fallbackUrl] is an MPEG-TS
     * address tried once if the provider refuses [newUrl] before it plays. Replays pass none.
     */
    fun play(newUrl: String, fallbackUrl: String? = null) {
        if (newUrl == requested) return
        requested = newUrl
        fallback = fallbackUrl
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
        exo.setMediaItem(MediaItem.fromUri(target))
        exo.prepare()
        exo.playWhenReady = true
    }

    /** Stops and lets go of the provider connection, for example while a movie plays. */
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        requested = null
        url = null
        buffering = false
        exo.stop()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        exo.removeListener(listener)
        exo.release()
    }

    private companion object {
        const val MAX_RETRIES = 4
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
    onToggleFavorite: (LiveChannel) -> Unit,
    onReplay: (LiveChannel, Program) -> Unit,
    onReturnToLive: () -> Unit,
    onOpenGuide: () -> Unit,
    onExit: () -> Unit,
    showToast: (String) -> Unit
) {
    val current = channels.getOrNull(index)
    val nn = current?.let { guide.nowNext(it) }
    var panel by remember { mutableStateOf(LivePanel.None) }
    var bannerTick by remember { mutableIntStateOf(1) }
    var showBanner by remember { mutableStateOf(false) }
    var digits by remember { mutableStateOf("") }
    var lastKey by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val rootFocus = remember { FocusRequester() }

    fun close() {
        panel = LivePanel.None
    }

    fun tune(newIndex: Int) {
        if (channels.isEmpty()) return
        onTune((newIndex + channels.size) % channels.size)
        digits = ""
        bannerTick++
    }

    BackHandler(enabled = panel == LivePanel.None) { if (replaying) onReturnToLive() else onExit() }
    BackHandler(enabled = panel != LivePanel.None) { close() }

    // With no panel open, the screen itself holds focus so it receives the remote's keys.
    LaunchedEffect(panel) {
        if (panel == LivePanel.None) {
            withFrameNanos { }
            runCatching { rootFocus.requestFocus() }
        }
    }

    LaunchedEffect(bannerTick) {
        showBanner = true
        delay(4500)
        showBanner = false
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
        val target = channels.indexOfFirst { it.number.toString() == digits }
        if (target >= 0) {
            panel = LivePanel.None
            tune(target)
        } else {
            showToast("There is no channel $digits")
        }
        digits = ""
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                lastKey = System.currentTimeMillis()
                val code = ev.nativeKeyEvent.keyCode
                if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && panel != LivePanel.Tracks) {
                    if (digits.length < 4) digits += (code - KeyEvent.KEYCODE_0).toString()
                    return@onPreviewKeyEvent true
                }
                when (code) {
                    KeyEvent.KEYCODE_CHANNEL_UP -> { panel = LivePanel.None; tune(index + 1); return@onPreviewKeyEvent true }
                    KeyEvent.KEYCODE_CHANNEL_DOWN -> { panel = LivePanel.None; tune(index - 1); return@onPreviewKeyEvent true }
                    KeyEvent.KEYCODE_GUIDE -> { onOpenGuide(); return@onPreviewKeyEvent true }
                    KeyEvent.KEYCODE_LAST_CHANNEL -> return@onPreviewKeyEvent false
                }
                if (panel == LivePanel.Options && code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    close()
                    return@onPreviewKeyEvent true
                }
                if (panel != LivePanel.None) return@onPreviewKeyEvent false
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> { tune(index + 1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { tune(index - 1); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (channels.isNotEmpty()) panel = LivePanel.Channels; true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { bannerTick++; true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                        if (channels.isNotEmpty()) {
                            showBanner = false
                            panel = LivePanel.Options
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                // A tap on a phone opens the options bar, like OK on a remote.
                if (panel == LivePanel.None && channels.isNotEmpty()) panel = LivePanel.Options else close()
            }
    ) {
        when {
            channels.isEmpty() -> EmptyState(
                title = when {
                    loading -> "Finding your channels"
                    error != null -> "Your channels did not load"
                    else -> "No channels yet"
                },
                body = when {
                    loading -> "This takes a few seconds the first time."
                    error != null -> "$error. Check your login in Settings, Live TV."
                    else -> "Add your TV provider in Settings, Live TV."
                },
                modifier = Modifier.fillMaxSize().background(Tv.Scrim)
            )
            player.failed -> Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "No signal on ${current?.name ?: "this channel"}",
                    body = "The provider is not sending this channel right now. Press up or down to try another."
                )
            }
        }

        if (player.buffering && !player.failed && channels.isNotEmpty() && !showBanner && panel == LivePanel.None) {
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
            if (current != null) ChannelBanner(current, nn, current.id in favorites)
        }

        if (panel == LivePanel.Options && current != null) {
            OptionsBar(
                channel = current,
                nn = nn,
                isFavorite = current.id in favorites,
                canReplay = canReplay && current.catchup,
                onGuide = onOpenGuide,
                onChannels = { panel = LivePanel.Channels },
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
                onClose = { close() },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

/** Shown for a few seconds after each channel change. */
@Composable
private fun ChannelBanner(channel: LiveChannel, nn: NowNext?, isFavorite: Boolean) {
    val now = nn?.now
    Column(
        Modifier
            .padding(start = 40.dp, bottom = 28.dp, end = 40.dp)
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
            KeyHint("◀", "Channel list")
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
    onGuide: () -> Unit,
    onChannels: () -> Unit,
    onFavorite: () -> Unit,
    onReplay: () -> Unit,
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
                PillButton("Channel list", onChannels, icon = TvIcons.List)
                PillButton(
                    if (isFavorite) "Remove favorite" else "Add to favorites",
                    onFavorite,
                    icon = if (isFavorite) TvIcons.StarFilled else TvIcons.Star
                )
                if (canReplay) PillButton("Watch from the start", onReplay, icon = TvIcons.Replay)
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
        EpgCache.pastPrograms(channel.epgId, if (channel.archiveDays > 0) channel.archiveDays * day else day).take(30)
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
            "Your provider keeps about a day of shows for ${channel.name}.",
            style = TvType.Caption,
            color = Tv.Ink3,
            modifier = Modifier.padding(horizontal = 24.dp).padding(top = 8.dp)
        )
    }
}
