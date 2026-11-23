package com.dallas.cabletv.ui

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.ui.PlayerView
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.newPlayer

/**
 * Two channels at once. Each one is its own player, so a provider that allows a single
 * connection will refuse one of them. Back returns to the highlighted channel full screen.
 */
@Composable
fun MultiViewScreen(
    channels: List<LiveChannel>,
    start: Int,
    onExit: (LiveChannel) -> Unit
) {
    val context = LocalContext.current
    val first = channels.getOrNull(start)
    val second = channels.getOrNull(if (channels.isEmpty()) 0 else (start + 1) % channels.size)
    var focused by remember { mutableIntStateOf(0) }
    val players = remember { List(2) { newPlayer(context) } }
    val requesters = remember { List(2) { FocusRequester() } }
    DisposableEffect(Unit) { onDispose { players.forEach { it.release() } } }
    LaunchedEffect(first?.id, second?.id) {
        listOf(first, second).forEachIndexed { i, ch ->
            val url = ch?.streamUrl?.takeIf { it.startsWith("http") }
            if (url == null) players[i].stop()
            else {
                players[i].setMediaItem(MediaItem.fromUri(url))
                players[i].prepare()
                players[i].play()
            }
        }
    }
    BackHandler {
        val ch = if (focused == 0) first else second
        if (ch != null) onExit(ch)
    }
    Column(Modifier.fillMaxSize().background(Tv.Bg).padding(18.dp)) {
        Text(
            "Two channels. Each picture is a separate connection, so a one-stream login may stop one of them. OK watches the highlighted channel.",
            style = TvType.Small,
            color = Tv.Ink2
        )
        Spacer(Modifier.height(12.dp))
        LaunchedEffect(Unit) { runCatching { requesters[0].requestFocus() } }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(first, second).forEachIndexed { i, ch ->
                Column(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Tv.Surface)
                    ) {
                        if (ch?.streamUrl?.startsWith("http") == true) {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        useController = false
                                        player = players[i]
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
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
