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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.NowNext
import com.dallas.cabletv.data.Program
import com.dallas.cabletv.data.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

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
    onExit: () -> Unit,
    onChannelChanged: (LiveChannel) -> Unit = {}
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
                playerError = e.message ?: "Signal lost"
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

    // Report every channel change, so the next launch can return to it.
    LaunchedEffect(current?.id) {
        if (current != null) onChannelChanged(current)
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
            .background(Color.Black)
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

        AnimatedVisibility(
            visible = showBanner && current != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            if (current != null) {
                ChannelBanner(channel = current, nn = nowNext)
            }
        }
    }
}

@Composable
private fun ChannelBanner(channel: LiveChannel, nn: NowNext?) {
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
                    text = "UP / DOWN   Change channel      0-9   Jump to number      BACK   Menu",
                    style = MaterialTheme.typography.labelMedium,
                    color = Cable.Ink3
                )
            }

            next?.let { UpNextCard(it) }
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

private fun groupOf(ch: LiveChannel): String = ch.group.ifBlank { "Other" }

@Composable
fun GuideScreen(
    channels: List<LiveChannel>,
    epg: XtreamClient?,
    onWatch: (Int) -> Unit
) {
    val cache = remember { mutableStateMapOf<String, NowNext>() }
    val gate = remember { Semaphore(3) } // limit guide requests so the provider is not flooded
    val firstRow = remember { FocusRequester() }

    val groups = remember(channels) {
        listOf(ALL_GROUP) + channels.map(::groupOf).distinct().sorted()
    }
    var selectedGroup by remember { mutableStateOf(ALL_GROUP) }
    LaunchedEffect(groups) {
        if (selectedGroup !in groups) selectedGroup = ALL_GROUP
    }
    val visible = remember(channels, selectedGroup) {
        if (selectedGroup == ALL_GROUP) channels else channels.filter { groupOf(it) == selectedGroup }
    }

    LaunchedEffect(channels.size) {
        if (channels.isNotEmpty()) runCatching { firstRow.requestFocus() }
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)
            ) {
                items(groups, key = { it }) { group ->
                    CategoryChip(label = group, selected = group == selectedGroup) {
                        selectedGroup = group
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
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
                        focusRequester = if (idx == 0) firstRow else null
                    ) {
                        // Tune using the index in the full list, not the filtered one.
                        onWatch(channels.indexOfFirst { it.id == ch.id })
                    }
                }
            }
        }
    }
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

@Composable
private fun GuideRow(
    ch: LiveChannel,
    cache: MutableMap<String, NowNext>,
    epg: XtreamClient?,
    gate: Semaphore,
    focusRequester: FocusRequester?,
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
    val now = nn?.now
    val next = nn?.next

    val rowModifier = if (focusRequester != null) {
        Modifier.fillMaxWidth().focusRequester(focusRequester)
    } else {
        Modifier.fillMaxWidth()
    }

    FocusSurface(
        onClick = onClick,
        modifier = rowModifier,
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
                    text = ch.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Cable.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (now != null) {
                    Text(
                        text = now.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Cable.Ink2,
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
