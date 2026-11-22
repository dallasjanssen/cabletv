package com.dallas.cabletv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.dallas.cabletv.CableViewModel
import com.dallas.cabletv.data.ChannelLogic
import com.dallas.cabletv.data.EpgRepository
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.Program
import kotlinx.coroutines.delay

private const val ROW_HEIGHT_DP = 52
private const val HOUR_MS = 60 * 60 * 1000L

/**
 * One stretch of a row: a program, or a gap with no listing. [start] and [end] are the
 * visible times. [key] identifies the segment when it is selected: a program's real start,
 * or the visible start of a gap.
 */
private class Segment(val start: Long, val end: Long, val program: Program?) {
    val key: Long get() = program?.start ?: start
    val minutes: Float get() = (end - start) / 60_000f
}

private fun segmentsFor(programs: List<Program>, from: Long, to: Long): List<Segment> {
    val out = mutableListOf<Segment>()
    var cursor = from
    for (p in programs) {
        if (p.end <= from || p.start >= to) continue
        val s = maxOf(p.start, cursor)
        if (s > cursor) out += Segment(cursor, s, null)
        val e = minOf(p.end, to)
        if (e > s) {
            out += Segment(s, e, p)
            cursor = e
        }
    }
    if (cursor < to) out += Segment(cursor, to, null)
    return out
}

@Composable
fun GuideScreen(vm: CableViewModel, onWatch: (LiveChannel) -> Unit, onLive: () -> Unit, onExit: () -> Unit) {
    val channels by vm.channels.collectAsState()
    val guideVersion by vm.guideVersion.collectAsState()
    val guideStatus by vm.guideStatus.collectAsState()
    val playback = vm.playback
    val epg = vm.epg

    // Phones get a shorter window and a narrower channel column, so half-hour cells stay usable.
    val narrow = LocalConfiguration.current.screenWidthDp < 720
    val windowMs = if (narrow) 90 * 60_000L else 180 * 60_000L
    val windowMin = windowMs / 60_000f
    val channelCol = if (narrow) 104.dp else 210.dp

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(30_000)
        }
    }

    var windowStart by remember { mutableLongStateOf(ChannelLogic.floorToSlot(nowMs)) }
    val windowEnd = windowStart + windowMs
    var selChannelId by remember { mutableStateOf<String?>(null) }
    var selStart by remember { mutableLongStateOf(0L) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    val selIndex = channels.indexOfFirst { it.id == selChannelId }.coerceAtLeast(0)
    val selChannel = channels.getOrNull(selIndex)
    val selProgram: Program? = remember(selChannelId, selStart, guideVersion) {
        selChannel?.let { ch -> epg.programsFor(ch).firstOrNull { it.start == selStart } }
    }

    fun segmentsIn(ch: LiveChannel, from: Long, to: Long): List<Segment> =
        segmentsFor(epg.programsFor(ch), from, to)

    /** Selects whatever is on screen at [time] for [ch]: a program, or a gap if nothing is listed. */
    fun selectAt(ch: LiveChannel, time: Long) {
        val segs = segmentsIn(ch, windowStart, windowEnd)
        val seg = segs.firstOrNull { time >= it.start && time < it.end }
            ?: segs.firstOrNull { it.end > time }
            ?: segs.firstOrNull()
        selChannelId = ch.id
        selStart = seg?.key ?: time
    }

    /** Clamps a window start between an hour before now and the end of the guide's listings. */
    fun shiftedStart(candidate: Long): Long {
        val earliest = ChannelLogic.floorToSlot(nowMs - HOUR_MS)
        val latest = ChannelLogic.floorToSlot(nowMs + EpgRepository.LOOKAHEAD_MS - windowMs)
        return ChannelLogic.floorToSlot(candidate).coerceIn(earliest, maxOf(earliest, latest))
    }

    fun moveRow(delta: Int) {
        if (channels.isEmpty()) return
        val next = channels[ChannelLogic.step(selIndex, delta, channels.size)]
        selectAt(next, selStart.coerceIn(windowStart, windowEnd - 1))
    }

    /** Moves to the next or previous program or gap on the same row, moving the window when it runs out. */
    fun moveSegment(dir: Int) {
        val ch = selChannel ?: return
        val segs = segmentsIn(ch, windowStart, windowEnd)
        val idx = segs.indexOfFirst { it.key == selStart }
        if (idx < 0) {
            selectAt(ch, windowStart)
            return
        }
        segs.getOrNull(idx + dir)?.let {
            selStart = it.key
            return
        }
        val newStart = shiftedStart(windowStart + dir * windowMs)
        if (newStart == windowStart) return
        windowStart = newStart
        val next = segmentsIn(ch, newStart, newStart + windowMs)
        selStart = (if (dir > 0) next.first() else next.last()).key
    }

    fun shiftWindow(deltaMs: Long) {
        windowStart = shiftedStart(windowStart + deltaMs)
    }

    fun jumpToNow() {
        windowStart = ChannelLogic.floorToSlot(nowMs)
        selChannel?.let { selectAt(it, nowMs) }
    }

    // Start with the playing channel, or the first channel, once data arrives.
    LaunchedEffect(channels, guideVersion) {
        if (channels.isNotEmpty() && channels.none { it.id == selChannelId }) {
            val start = playback.liveChannel ?: channels.first()
            selectAt(start, nowMs)
        }
    }

    // Keep the selection on screen when the window moves.
    LaunchedEffect(windowStart) {
        val ch = selChannel ?: return@LaunchedEffect
        if (selStart < windowStart || selStart >= windowEnd) selectAt(ch, windowStart)
    }

    // Scroll only when the selected row has left the screen, so moving does not jump the list.
    LaunchedEffect(selIndex) {
        if (channels.isEmpty()) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val firstVisible = visible.firstOrNull()?.index ?: 0
        val lastVisible = visible.lastOrNull()?.index ?: 0
        when {
            selIndex < firstVisible -> listState.animateScrollToItem(selIndex)
            selIndex >= lastVisible -> listState.animateScrollToItem((selIndex - visible.size + 2).coerceAtLeast(0))
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler { onExit() }

    Column(
        Modifier
            .fillMaxSize()
            .background(CableColors.Bg)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { moveRow(-1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { moveRow(1); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { moveSegment(1); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { moveSegment(-1); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        selChannel?.let(onWatch)
                        true
                    }
                    KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_REWIND -> { shiftWindow(-windowMs); true }
                    KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { shiftWindow(windowMs); true }
                    KeyEvent.KEYCODE_PROG_BLUE -> { jumpToNow(); true }
                    KeyEvent.KEYCODE_PROG_GREEN -> { vm.reloadGuide(force = true); true }
                    KeyEvent.KEYCODE_GUIDE -> { onLive(); true }
                    else -> false
                }
            }
    ) {
        InfoAndPreview(
            channel = selChannel,
            program = selProgram,
            playback = playback
        )

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val pxPerMin = (maxWidth - channelCol).value / windowMin
            fun minutesToDp(minutes: Float): Dp = (minutes * pxPerMin).dp

            Column(Modifier.fillMaxSize()) {
                TimeHeader(
                    windowStart = windowStart,
                    windowMs = windowMs,
                    channelCol = channelCol,
                    slotWidth = minutesToDp(30f)
                )

                if (channels.isEmpty()) {
                    CenterMessage(guideStatus ?: "NO CHANNELS LOADED.")
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                        itemsIndexed(channels, key = { _, ch -> ch.id }) { idx, ch ->
                            GuideRow(
                                channel = ch,
                                rowIndex = idx,
                                epg = epg,
                                guideVersion = guideVersion,
                                windowStart = windowStart,
                                windowEnd = windowEnd,
                                channelCol = channelCol,
                                selectedStart = if (ch.id == selChannelId) selStart else null,
                                minutesToDp = ::minutesToDp,
                                onCell = { key, program ->
                                    if (ch.id == selChannelId && key == selStart) {
                                        onWatch(ch)
                                    } else {
                                        selChannelId = ch.id
                                        selStart = key
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // A thin marker for the current time.
            if (nowMs in windowStart until windowEnd) {
                val nowX = channelCol + minutesToDp((nowMs - windowStart) / 60_000f)
                Box(
                    Modifier
                        .offset(x = nowX)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(CableColors.Led)
                )
            }
        }

        if (guideStatus != null && channels.isNotEmpty()) {
            Text(
                guideStatus ?: "",
                color = CableColors.Dim,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CableColors.PanelDeep)
                    .padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        LegendBar {
            KeyLegend("OK", "WATCH", CableColors.Selected)
            KeyLegend("LEFT RIGHT", "PROGRAM", CableColors.Selected)
            KeyLegend("PG UP/DN", "MORE TIME", CableColors.TimeBar)
            KeyLegend("BLUE", "NOW", CableColors.TimeBar)
            KeyLegend("GREEN", "REFRESH", CableColors.Good)
        }
    }
}

@Composable
private fun InfoAndPreview(channel: LiveChannel?, program: Program?, playback: com.dallas.cabletv.ui.Playback) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(230.dp)
            .background(CableColors.Bg)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Program details, in the blue panel on the left.
        Column(
            Modifier
                .weight(0.52f)
                .fillMaxHeight()
                .background(CableColors.Panel.copy(alpha = 0.92f))
                .padding(16.dp)
        ) {
            if (channel == null) {
                Text("LOADING...", color = CableColors.Text, fontSize = 18.sp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (channel.logo != null) {
                        AsyncImage(
                            model = channel.logo,
                            contentDescription = channel.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(48.dp)
                                .background(CableColors.Text, RoundedCornerShape(4.dp))
                                .padding(3.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        "${channel.name.uppercase()}   ${channel.number}",
                        color = CableColors.Text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (program == null) {
                    Text("NO GUIDE DATA", color = CableColors.Dim, fontSize = 16.sp)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            program.title,
                            color = CableColors.Text,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        program.rating?.let {
                            Spacer(Modifier.width(8.dp))
                            Tag(it)
                        }
                    }
                    Text(
                        formatRange(program) + "  (" + formatDuration(program.durationMs) + ")",
                        color = CableColors.Led,
                        fontSize = 15.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        program.description ?: program.category ?: "No description listed.",
                        color = CableColors.Text,
                        fontSize = 15.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Live preview of the channel that is tuned, in the box on the right.
        Box(
            Modifier
                .weight(0.48f)
                .fillMaxHeight()
                .background(Color.Black)
        ) {
            if (playback.liveChannel != null) {
                AndroidView(
                    factory = { ctx -> PlayerView(ctx).apply { useController = false; player = playback.player } },
                    update = { it.player = playback.player },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("PRESS OK TO WATCH", color = CableColors.Dim, fontSize = 16.sp)
                }
            }
            val live = playback.liveChannel
            if (live != null) {
                Text(
                    "${live.name.uppercase()} • ${live.number}",
                    color = CableColors.Text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(CableColors.PanelDeep.copy(alpha = 0.85f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Clock(
                Modifier
                    .align(Alignment.BottomEnd)
                    .background(CableColors.PanelDeep.copy(alpha = 0.85f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun TimeHeader(windowStart: Long, windowMs: Long, channelCol: Dp, slotWidth: Dp) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(CableColors.TimeBar),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(channelCol)
                .fillMaxHeight()
                .background(CableColors.PanelDeep),
            contentAlignment = Alignment.Center
        ) {
            Text(formatDay(windowStart), color = CableColors.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        val slots = (windowMs / ChannelLogic.SLOT_MS).toInt()
        for (i in 0 until slots) {
            val slot = windowStart + i * ChannelLogic.SLOT_MS
            Box(
                Modifier
                    .width(slotWidth)
                    .fillMaxHeight()
                    .background(CableColors.TimeBar)
                    .padding(start = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(formatSlot(slot), color = CableColors.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: LiveChannel,
    rowIndex: Int,
    epg: EpgRepository,
    guideVersion: Int,
    windowStart: Long,
    windowEnd: Long,
    channelCol: Dp,
    selectedStart: Long?,
    minutesToDp: (Float) -> Dp,
    onCell: (key: Long, program: Program?) -> Unit
) {
    val segments = remember(channel.id, guideVersion, windowStart, windowEnd) {
        segmentsFor(epg.programsFor(channel), windowStart, windowEnd)
    }
    val rowBg = if (rowIndex % 2 == 0) CableColors.Cell else CableColors.CellAlt
    val rowSelected = selectedStart != null

    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT_DP.dp)
    ) {
        // The channel cell lights up for the selected row, even when the cursor sits on a gap with no listing.
        Row(
            Modifier
                .width(channelCol)
                .fillMaxHeight()
                .background(if (rowSelected) CableColors.Highlight else CableColors.PanelDeep)
                .padding(start = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                channel.number.toString(),
                color = CableColors.Text,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                maxLines = 1,
                modifier = Modifier.width(if (channelCol < 150.dp) 40.dp else 54.dp)
            )
            Text(
                channel.name.uppercase(),
                color = CableColors.Text,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        segments.forEach { seg ->
            val p = seg.program
            val selected = selectedStart != null && seg.key == selectedStart
            Box(
                Modifier
                    .width(minutesToDp(seg.minutes))
                    .fillMaxHeight()
                    .background(
                        when {
                            selected -> CableColors.Selected
                            p == null -> CableColors.PanelDeep
                            else -> rowBg
                        }
                    )
                    .clickable { onCell(seg.key, p) }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                when {
                    p != null -> Text(
                        p.title,
                        color = if (selected) CableColors.OnSelected else CableColors.Text,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    selected -> Text(
                        "NO INFORMATION",
                        color = CableColors.OnSelected,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
