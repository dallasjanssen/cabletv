package com.dallas.cabletv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.dallas.cabletv.data.EpgCache
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.NowNext
import com.dallas.cabletv.data.Program
import com.dallas.cabletv.data.XtreamClient
import com.dallas.cabletv.data.newPlayer
import com.dallas.cabletv.data.playbackHttpStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val MAX_RETRIES = 4

private fun progressOf(p: Program): Float {
    val total = (p.end - p.start).coerceAtLeast(1L)
    return ((System.currentTimeMillis() - p.start).toFloat() / total).coerceIn(0f, 1f)
}

/** Full schedule first, then the provider's short listing as a fallback. */
private fun lookupNowNext(channel: LiveChannel, epg: XtreamClient?): NowNext? =
    EpgCache.nowNext(channel.epgId)
        ?: if (epg != null) runCatching { epg.nowNext(channel.id) }.getOrNull() else null

@Composable
fun LiveTvScreen(
    channels: List<LiveChannel>,
    loading: Boolean,
    error: String?,
    startIndex: Int,
    epg: XtreamClient?,
    favorites: Set<String>,
    onToggleFavorite: (LiveChannel) -> Unit,
    onExit: () -> Unit,
    onChannelChanged: (LiveChannel) -> Unit = {}
) {
    val context = LocalContext.current
    val player = remember { newPlayer(context) }
    val scope = rememberCoroutineScope()
    // Which load is current, and which of those loads has actually started playing.
    val session = remember { AtomicInteger(0) }
    val readySession = remember { AtomicInteger(-1) }
    val triedLiveFallback = remember { AtomicBoolean(false) }
    val liveOverride = remember { mutableStateOf<String?>(null) }
    val fallbackFor = remember { mutableStateOf<String?>(null) }

    var index by remember(startIndex) { mutableIntStateOf(startIndex) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(false) }
    var retries by remember { mutableIntStateOf(0) }
    var retryTick by remember { mutableIntStateOf(0) }
    var showBanner by remember { mutableStateOf(false) }
    var bannerTick by remember { mutableIntStateOf(0) }
    var digits by remember { mutableStateOf("") }
    var nowNext by remember { mutableStateOf<NowNext?>(null) }
    // Left opens the channel list. Up and down browse it, OK tunes, right or Back closes it.
    var showMiniGuide by remember { mutableStateOf(false) }
    var guideHighlight by remember { mutableIntStateOf(0) }
    // INFO opens the replay list for channels with a catch-up archive. While a replay plays, BACK returns to live.
    var showReplay by remember { mutableStateOf(false) }
    var replayUrl by remember { mutableStateOf<String?>(null) }
    val replayFocus = remember { FocusRequester() }
    BackHandler(enabled = showMiniGuide) { showMiniGuide = false }
    BackHandler(enabled = showReplay) { showReplay = false }
    BackHandler(enabled = replayUrl != null && !showReplay && !showMiniGuide) { replayUrl = null }
    val focus = remember { FocusRequester() }
    val current = channels.getOrNull(index)
    val latestChannel = rememberUpdatedState(current)
    val latestReplay = rememberUpdatedState(replayUrl)
    val channelFallback = if (fallbackFor.value == current?.id) liveOverride.value else null
    val playUrl = replayUrl ?: channelFallback ?: current?.streamUrl
    val latestPlayUrl = rememberUpdatedState(playUrl)

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                val fallback = latestChannel.value?.fallbackStreamUrl
                val status = playbackHttpStatus(e)
                val played = readySession.get() == session.get()
                // One switch from HLS to MPEG-TS when the panel refuses the manifest before it plays.
                if (
                    latestReplay.value == null &&
                    !triedLiveFallback.get() &&
                    !played &&
                    !fallback.isNullOrBlank() &&
                    fallback != latestPlayUrl.value &&
                    status != null &&
                    status >= 400
                ) {
                    triedLiveFallback.set(true)
                    fallbackFor.value = latestChannel.value?.id
                    liveOverride.value = fallback
                    return
                }
                // Retry a few times before saying there is no signal, since most failures are brief.
                if (retries < MAX_RETRIES) {
                    retries++
                    retryTick++
                } else {
                    playerError = e.message ?: "Signal lost"
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    val tag = player.currentMediaItem?.localConfiguration?.tag
                    if (tag == session.get()) readySession.set(session.get())
                    retries = 0
                    playerError = null
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Retries a failed stream after a short wait.
    LaunchedEffect(retryTick) {
        if (retryTick > 0 && retries <= MAX_RETRIES) {
            delay(2000)
            player.prepare()
            player.playWhenReady = true
        }
    }

    // A new channel gets its own HLS attempt. A channel that already fell back to TS keeps that address.
    LaunchedEffect(current?.id) {
        triedLiveFallback.set(false)
    }

    // Load whatever should be on screen: the live channel, its TS fallback, or a replay. A short wait means
    // holding a key only loads the channel you stop on, not every one on the way.
    LaunchedEffect(playUrl) {
        val id = session.incrementAndGet()
        readySession.set(-1)
        if (playUrl == null) return@LaunchedEffect
        delay(300)
        if (session.get() != id) return@LaunchedEffect
        playerError = null
        retries = 0
        player.setMediaItem(MediaItem.Builder().setUri(playUrl).setTag(id).build())
        player.prepare()
        player.playWhenReady = true
    }

    // Report the channel on screen, so the next launch can return to it and it joins Recent.
    LaunchedEffect(current?.id) {
        if (current == null) return@LaunchedEffect
        delay(500)
        onChannelChanged(current)
    }

    // Refresh now and next for the channel on screen.
    LaunchedEffect(current?.id) {
        nowNext = null
        if (current == null) return@LaunchedEffect
        delay(300)
        nowNext = withContext(Dispatchers.IO) { lookupNowNext(current, epg) }
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
            replayUrl = null
            bannerTick++
        }
    }

    // Opens the replay list with focus on its first row.
    LaunchedEffect(showReplay) {
        if (showReplay) {
            withFrameNanos { }
            runCatching { replayFocus.requestFocus() }
        }
    }

    fun flashBanner() {
        bannerTick++
    }

    fun tune(delta: Int) {
        if (channels.isEmpty()) return
        index = (index + delta + channels.size) % channels.size
        digits = ""
        replayUrl = null
        flashBanner()
    }

    fun pickGuideChannel(idx: Int) {
        index = idx
        digits = ""
        replayUrl = null
        showMiniGuide = false
        flashBanner()
    }

    // Consumes only the keys the list uses. Everything else, Back and volume included, goes to the system.
    fun handleMiniGuideKey(code: Int): Boolean {
        val last = (channels.size - 1).coerceAtLeast(0)
        return when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                guideHighlight = (guideHighlight - 1).coerceAtLeast(0)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                guideHighlight = (guideHighlight + 1).coerceAtMost(last)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                pickGuideChannel(guideHighlight)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                showMiniGuide = false
                true
            }
            // The menu key marks the highlighted channel as a favorite.
            KeyEvent.KEYCODE_MENU -> {
                channels.getOrNull(guideHighlight)?.let(onToggleFavorite)
                true
            }
            else -> false
        }
    }

    val replayPrograms = remember(current?.id, current?.archiveDays, showReplay) {
        val channel = current
        if (channel != null && channel.catchup && epg != null) {
            val day = 24L * 3_600_000L
            val window = if (channel.archiveDays > 0) channel.archiveDays * day else day
            EpgCache.pastPrograms(channel.epgId, window)
        } else {
            emptyList()
        }
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = ev.nativeKeyEvent.keyCode
                // While the replay list is open, its rows handle the remote.
                if (showReplay) return@onPreviewKeyEvent false
                if (showMiniGuide) return@onPreviewKeyEvent handleMiniGuideKey(code)
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (channels.isEmpty()) {
                            false
                        } else {
                            guideHighlight = index
                            showMiniGuide = true
                            true
                        }
                    }
                    KeyEvent.KEYCODE_INFO -> {
                        if (replayPrograms.isNotEmpty()) showReplay = true
                        true
                    }
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
                    KeyEvent.KEYCODE_MENU -> {
                        current?.let(onToggleFavorite)
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

        when {
            channels.isEmpty() -> EmptyState(
                title = when {
                    loading -> "Finding channels..."
                    error != null -> "Can't load channels"
                    else -> "No channels yet"
                },
                body = error ?: if (loading) null else "Open Settings to add a live source.",
                modifier = Modifier
                    .fillMaxSize()
                    .background(Cable.Canvas.copy(alpha = 0.8f))
            )
            playerError != null -> EmptyState(
                title = "No signal",
                body = "This channel is not responding. Press up or down to try another.",
                modifier = Modifier
                    .fillMaxSize()
                    .background(Cable.Canvas.copy(alpha = 0.6f))
            )
        }

        // Shown while the stream is waiting for more data, so a stall never looks like a freeze.
        if (buffering && playerError == null && channels.isNotEmpty() && !showBanner) {
            Text(
                text = "BUFFERING...",
                style = MaterialTheme.typography.labelMedium,
                color = Cable.Accent,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Cable.Canvas.copy(alpha = 0.8f))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }

        // Digits typed on the remote appear in the top corner.
        if (digits.isNotEmpty()) {
            Text(
                text = digits,
                style = MaterialTheme.typography.headlineLarge,
                color = Cable.Accent,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(40.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Cable.Canvas.copy(alpha = 0.85f))
                    .padding(horizontal = 28.dp, vertical = 10.dp)
            )
        }

        if (replayUrl != null) {
            Text(
                text = "REPLAY    BACK  Return to live",
                style = MaterialTheme.typography.labelMedium,
                color = Cable.Led,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Cable.Canvas.copy(alpha = 0.8f))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }

        if (showMiniGuide && channels.isNotEmpty()) {
            MiniGuide(
                channels = channels,
                highlight = guideHighlight,
                current = index,
                favorites = favorites,
                onPick = { pickGuideChannel(it) },
                modifier = Modifier.align(Alignment.CenterStart)
            )
        }

        if (showReplay && current != null) {
            ReplayPanel(
                channelName = current.name,
                programs = replayPrograms,
                focusRequester = replayFocus,
                onPick = { p ->
                    val client = epg
                    if (client != null) {
                        val channelId = current.id
                        val requested = index
                        scope.launch {
                            val url = withContext(Dispatchers.IO) {
                                runCatching { client.resolveTimeshiftUrl(channelId, p.start, p.end) }.getOrNull()
                            }
                            if (url != null && index == requested) {
                                replayUrl = url
                                showReplay = false
                                flashBanner()
                            }
                        }
                    }
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }

        AnimatedVisibility(
            visible = showBanner && current != null && !showMiniGuide && !showReplay,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            if (current != null) {
                ChannelBanner(
                    channel = current,
                    nn = nowNext,
                    isFavorite = current.id in favorites,
                    canReplay = replayPrograms.isNotEmpty()
                )
            }
        }
    }
}

@Composable
private fun ChannelBanner(channel: LiveChannel, nn: NowNext?, isFavorite: Boolean, canReplay: Boolean) {
    val now = nn?.now
    val next = nn?.next

    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Cable.Canvas.copy(alpha = 0.92f), Cable.Canvas)
                )
            )
            .padding(horizontal = 56.dp, vertical = 48.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = channel.number.toString(),
                        style = MaterialTheme.typography.headlineLarge,
                        color = Cable.Accent
                    )
                    Spacer(Modifier.width(20.dp))
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Cable.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isFavorite) {
                        Spacer(Modifier.width(20.dp))
                        Pill(text = "FAVORITE", color = Cable.Led, textColor = Cable.Canvas)
                    }
                }

                if (now != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill(text = "LIVE", color = Cable.Live)
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = now.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = Cable.Led,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    ProgressLine(progressOf(now), Modifier.fillMaxWidth(0.55f))
                    Text(
                        text = "${formatTime(now.start)} to ${formatTime(now.end)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Cable.Ink2
                    )
                } else {
                    Text(
                        text = "No guide data for this channel",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Cable.Ink3
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (canReplay) {
                        "UP / DOWN  Channel    LEFT  Channel list    INFO  Replay    MENU  Favorite"
                    } else {
                        "UP / DOWN  Channel    LEFT  Channel list    MENU  Favorite    0-9  Jump"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Cable.Ink2
                )
            }

            next?.let { UpNextCard(it) }
        }
    }
}

/** Past programmes on a channel with catch-up, newest first. OK plays one from the provider's archive. */
@Composable
private fun ReplayPanel(
    channelName: String,
    programs: List<Program>,
    focusRequester: FocusRequester,
    onPick: (Program) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxHeight()
            .width(620.dp)
            .background(Cable.Canvas.copy(alpha = 0.92f))
            .padding(vertical = 32.dp, horizontal = 24.dp)
    ) {
        Text(
            text = "REPLAY  $channelName",
            style = MaterialTheme.typography.labelMedium,
            color = Cable.Accent,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(programs, key = { _, p -> p.start }) { idx, p ->
                FocusSurface(
                    onClick = { onPick(p) },
                    modifier = (if (idx == 0) Modifier.focusRequester(focusRequester) else Modifier).fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    focusScale = 1f
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Cable.Surface)
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTime(p.start),
                            style = MaterialTheme.typography.labelLarge,
                            color = Cable.Accent,
                            modifier = Modifier.width(120.dp)
                        )
                        Text(
                            text = p.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Cable.Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        Text(
            text = "BACK  Close",
            style = MaterialTheme.typography.labelMedium,
            color = Cable.Ink2,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

/**
 * A channel list over the left side of the picture. The highlighted row moves
 * with the remote and does not change the channel until OK is pressed.
 */
@Composable
private fun MiniGuide(
    channels: List<LiveChannel>,
    highlight: Int,
    current: Int,
    favorites: Set<String>,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    LaunchedEffect(highlight) {
        if (highlight in channels.indices) listState.animateScrollToItem(highlight)
    }

    Column(
        modifier
            .fillMaxHeight()
            .width(560.dp)
            .background(Cable.Canvas.copy(alpha = 0.92f))
            .padding(vertical = 32.dp)
    ) {
        Text(
            text = "CHANNELS      MENU  Favorite",
            style = MaterialTheme.typography.labelMedium,
            color = Cable.Accent,
            modifier = Modifier.padding(start = 28.dp, bottom = 12.dp)
        )
        LazyColumn(Modifier.weight(1f), state = listState) {
            itemsIndexed(channels, key = { _, c -> c.id }) { idx, ch ->
                val selected = idx == highlight
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (selected) Cable.SurfaceHi else Color.Transparent)
                        .border(2.dp, if (selected) Cable.Led else Color.Transparent)
                        .clickable { onPick(idx) }
                        .padding(horizontal = 28.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = ch.number.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = Cable.Accent,
                        modifier = Modifier.width(88.dp)
                    )
                    Text(
                        text = (if (ch.id in favorites) "* " else "") + ch.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (idx == current) Cable.Led else Cable.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun UpNextCard(program: Program) {
    Column(
        Modifier
            .width(340.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Cable.Canvas.copy(alpha = 0.8f))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "UP NEXT",
            style = MaterialTheme.typography.labelMedium,
            color = Cable.Ink3
        )
        Text(
            text = program.title,
            style = MaterialTheme.typography.titleMedium,
            color = Cable.Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = formatTime(program.start),
            style = MaterialTheme.typography.bodyMedium,
            color = Cable.Ink2
        )
    }
}

private const val ALL_GROUP = "All"
private const val FAVS_GROUP = "FAVORITES"
private const val RECENT_GROUP = "RECENT"

private fun groupOf(ch: LiveChannel): String = ch.group.ifBlank { "Other" }

@Composable
fun GuideScreen(
    channels: List<LiveChannel>,
    epg: XtreamClient?,
    favorites: Set<String>,
    recents: List<String>,
    onWatch: (Int) -> Unit,
    onToggleFavorite: (LiveChannel) -> Unit
) {
    val cache = remember { mutableStateMapOf<String, NowNext>() }
    val gate = remember { Semaphore(3) } // limit guide requests so the provider is not flooded
    val firstRow = remember { FocusRequester() }

    val categories = remember(channels) { channels.map(::groupOf).distinct().sorted() }
    // Favorites and Recent come before All, so they are one press away.
    val groups = listOf(FAVS_GROUP, RECENT_GROUP, ALL_GROUP) + categories
    var selectedGroup by remember { mutableStateOf(ALL_GROUP) }
    LaunchedEffect(groups) {
        if (selectedGroup !in groups) selectedGroup = ALL_GROUP
    }
    val visible = remember(channels, selectedGroup, favorites, recents) {
        when (selectedGroup) {
            ALL_GROUP -> channels
            FAVS_GROUP -> channels.filter { it.id in favorites }
            RECENT_GROUP -> recents.mapNotNull { id -> channels.firstOrNull { it.id == id } }
            else -> channels.filter { groupOf(it) == selectedGroup }
        }
    }

    // Wait one frame so the first row exists before focus moves onto it.
    LaunchedEffect(channels.size, selectedGroup) {
        if (visible.isNotEmpty()) {
            withFrameNanos { }
            runCatching { firstRow.requestFocus() }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 40.dp)
    ) {
        Text(
            text = if (epg == null) {
                "Connect a live source to see what is on."
            } else {
                "What is on now, and what is next."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = Cable.Ink2
        )
        Spacer(Modifier.height(20.dp))

        if (channels.isEmpty()) {
            EmptyState(
                title = "No channels loaded",
                body = "Add a live source in Settings.",
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp)
            ) {
                items(groups, key = { it }) { group ->
                    CategoryChip(label = groupLabel(group), selected = group == selectedGroup) {
                        selectedGroup = group
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            if (visible.isEmpty()) {
                EmptyState(
                    title = if (selectedGroup == FAVS_GROUP) "No favorites yet" else "Nothing here yet",
                    body = if (selectedGroup == FAVS_GROUP) {
                        "Press the favorite button on a channel, or MENU on the remote while watching."
                    } else {
                        "Channels you watch will appear here."
                    },
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)
                ) {
                    itemsIndexed(visible, key = { _, c -> c.id }) { idx, ch ->
                        GuideRow(
                            ch = ch,
                            cache = cache,
                            epg = epg,
                            gate = gate,
                            favorite = ch.id in favorites,
                            focusRequester = if (idx == 0) firstRow else null,
                            onClick = {
                                // Tune using the index in the full list, not the filtered one.
                                onWatch(channels.indexOfFirst { it.id == ch.id })
                            },
                            onFavorite = { onToggleFavorite(ch) }
                        )
                    }
                }
            }
        }
    }
}

private fun groupLabel(group: String): String = when (group) {
    FAVS_GROUP -> "FAVORITES"
    RECENT_GROUP -> "RECENT"
    else -> group
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        focusScale = 1.06f
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Cable.Canvas else Cable.Ink,
            modifier = Modifier
                .background(if (selected) Cable.Accent else Cable.SurfaceHi)
                .padding(horizontal = 24.dp, vertical = 12.dp)
        )
    }
}

/**
 * One guide row. Wide screens (TVs) show the name, the programme and the next one side by
 * side. Narrow screens (phones) stack them, so nothing is squeezed out.
 */
@Composable
private fun GuideRow(
    ch: LiveChannel,
    cache: MutableMap<String, NowNext>,
    epg: XtreamClient?,
    gate: Semaphore,
    favorite: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    onFavorite: () -> Unit
) {
    LaunchedEffect(ch.id) {
        if (epg == null || cache.containsKey(ch.id) || EpgCache.nowNext(ch.epgId) != null) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            gate.withPermit { runCatching { epg.nowNext(ch.id) }.getOrNull() }
        }
        if (result != null) cache[ch.id] = result
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 600.dp) {
            GuideRowWide(ch, cache, epg, gate, favorite, focusRequester, onClick, onFavorite)
        } else {
            GuideRowNarrow(ch, cache, epg, favorite, focusRequester, onClick, onFavorite)
        }
    }
}

@Composable
private fun GuideRowNarrow(
    ch: LiveChannel,
    cache: MutableMap<String, NowNext>,
    epg: XtreamClient?,
    favorite: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    onFavorite: () -> Unit
) {
    val nn = EpgCache.nowNext(ch.epgId) ?: cache[ch.id]
    val now = nn?.now
    val next = nn?.next
    val mainModifier = if (focusRequester != null) {
        Modifier.fillMaxWidth().focusRequester(focusRequester)
    } else {
        Modifier.fillMaxWidth()
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FocusSurface(
            onClick = onClick,
            modifier = mainModifier,
            shape = RoundedCornerShape(12.dp),
            focusScale = 1f
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Cable.Surface)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = ch.number.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = Cable.Accent
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = if (favorite) "* ${ch.name}" else ch.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Cable.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (now != null) {
                    Text(
                        text = now.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Cable.Led,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    ProgressLine(progressOf(now), Modifier.fillMaxWidth().padding(top = 2.dp))
                } else {
                    Text(
                        text = if (epg == null) "No guide source" else "No guide data",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Cable.Ink3
                    )
                }
                if (next != null) {
                    Text(
                        text = "NEXT ${formatTime(next.start)}  ${next.title}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Cable.Ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // The favorite button is its own small target under the row, so it never pushes the name off screen.
        FocusSurface(
            onClick = onFavorite,
            shape = RoundedCornerShape(12.dp),
            focusScale = 1f
        ) {
            Text(
                text = if (favorite) "* FAVORITE" else "ADD FAVORITE",
                style = MaterialTheme.typography.labelLarge,
                color = if (favorite) Cable.Led else Cable.Ink2,
                modifier = Modifier
                    .background(Cable.Surface)
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun GuideRowWide(
    ch: LiveChannel,
    cache: MutableMap<String, NowNext>,
    epg: XtreamClient?,
    gate: Semaphore,
    favorite: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    onFavorite: () -> Unit
) {
    val nn = EpgCache.nowNext(ch.epgId) ?: cache[ch.id]
    val now = nn?.now
    val next = nn?.next

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // weight is a RowScope modifier, so the main target is built inside the row.
        val mainModifier = if (focusRequester != null) {
            Modifier.weight(1f).focusRequester(focusRequester)
        } else {
            Modifier.weight(1f)
        }
        FocusSurface(
            onClick = onClick,
            modifier = mainModifier,
            shape = RoundedCornerShape(14.dp),
            focusScale = 1f
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Cable.Surface)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = ch.number.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    color = Cable.Accent,
                    modifier = Modifier.width(88.dp)
                )
                ChannelLogo(ch.logo)
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (favorite) "* ${ch.name}" else ch.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Cable.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (now != null) {
                        Text(
                            text = now.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Cable.Led,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        ProgressLine(progressOf(now), Modifier.fillMaxWidth(0.7f).padding(top = 4.dp))
                    } else {
                        Text(
                            text = if (epg == null) "No guide source" else "No guide data",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Cable.Ink3
                        )
                    }
                }
                if (next != null) {
                    Spacer(Modifier.width(24.dp))
                    Column(Modifier.width(300.dp), horizontalAlignment = Alignment.End) {
                        Text(
                            text = "NEXT  ${formatTime(next.start)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Cable.Ink3
                        )
                        Text(
                            text = next.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Cable.Ink2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        FocusSurface(
            onClick = onFavorite,
            modifier = Modifier.width(190.dp),
            shape = RoundedCornerShape(14.dp),
            focusScale = 1f
        ) {
            Text(
                text = if (favorite) "* FAVORITE" else "ADD FAVORITE",
                style = MaterialTheme.typography.labelLarge,
                color = if (favorite) Cable.Led else Cable.Ink2,
                modifier = Modifier
                    .background(Cable.Surface)
                    .padding(horizontal = 20.dp, vertical = 22.dp)
            )
        }
    }
}

@Composable
private fun ChannelLogo(url: String?) {
    if (url.isNullOrBlank()) {
        Spacer(Modifier.width(72.dp))
    } else {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(width = 72.dp, height = 44.dp)
        )
    }
}
