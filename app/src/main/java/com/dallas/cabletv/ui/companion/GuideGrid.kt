package com.dallas.cabletv.ui.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dallas.cabletv.data.companion.client.BrowseProgram
import com.dallas.cabletv.data.companion.client.GridChannel
import com.dallas.cabletv.data.companion.client.SessionStatus
import com.dallas.cabletv.ui.GROUP_ALL
import com.dallas.cabletv.ui.Tv
import com.dallas.cabletv.ui.TvType
import com.dallas.cabletv.ui.formatTime
import com.dallas.cabletv.ui.localSlotStart
import kotlinx.coroutines.delay
import java.util.TimeZone

/** Half hours across the grid, as the TV's guide draws them. */
private const val SLOT_MS = 30 * 60_000L

/** Four hours of half hours, the window the TV sends. */
private const val SLOTS = 8

/** How far Earlier and Later move the window. */
private const val STEP_MS = 2 * 60 * 60_000L

private val SLOT_W = 96.dp
private val CHANNEL_W = 120.dp
private val ROW_H = 58.dp

/** The half hour the TV's guide starts on right now, on this phone's clock. */
private fun currentWindowStart(): Long {
    val now = System.currentTimeMillis()
    return localSlotStart(now, TimeZone.getDefault().getOffset(now).toLong())
}

/**
 * The guide, as the TV draws it: categories across the top, channels down the side, half hours
 * across, and a line for now. Choosing a programme on now, or a programme's details, works the
 * way the TV's guide does. Browsing never changes what the TV shows; only Watch does.
 */
@Composable
fun GuideTab(m: TvScreenModel) {
    val status by m.status.collectAsState()
    val grid by m.grid.collectAsState()
    val message by m.message.collectAsState()
    val online = status is SessionStatus.Online
    val page = grid.data
    val hScroll = rememberScrollState()
    var picked by remember { mutableStateOf<Pair<GridChannel, BrowseProgram>?>(null) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000)
        }
    }

    LaunchedEffect(online) {
        if (online && page == null && !grid.busy) m.loadGrid(GROUP_ALL, currentWindowStart())
    }

    if (!online) {
        NeedsTheTv("The guide needs the TV. Cable TV is closed or the TV is off.")
        return
    }

    val windowStart = page?.startMs ?: currentWindowStart()
    val windowEnd = page?.endMs ?: (windowStart + SLOTS * SLOT_MS)
    val slots = ((windowEnd - windowStart) / SLOT_MS).toInt().coerceAtLeast(1)
    val gridW = SLOT_W * slots
    val nowX: Dp? = if (now >= windowStart && now < windowEnd) {
        SLOT_W * ((now - windowStart).toFloat() / SLOT_MS)
    } else {
        null
    }
    val category = page?.group ?: GROUP_ALL

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        message?.let { MessageLine(it, error = false) }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(page?.groups ?: listOf(GROUP_ALL)) { name ->
                val chosen = name == category
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (chosen) Tv.Accent else Tv.Surface3)
                        .clickable(enabled = !grid.busy) { m.loadGrid(name, windowStart) }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(name, style = TvType.CaptionStrong, color = if (chosen) Tv.FocusInk else Tv.Ink2, maxLines = 1)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                "Earlier",
                onClick = { m.loadGrid(category, windowStart - STEP_MS) },
                modifier = Modifier.weight(1f),
                enabled = page != null && !grid.busy
            )
            SecondaryButton(
                "Now",
                onClick = { m.loadGrid(category, currentWindowStart()) },
                modifier = Modifier.weight(1f),
                enabled = !grid.busy
            )
            SecondaryButton(
                "Later",
                onClick = { m.loadGrid(category, windowStart + STEP_MS) },
                modifier = Modifier.weight(1f),
                enabled = page != null && !grid.busy
            )
        }
        Text(
            "${formatTime(windowStart)} to ${formatTime(windowEnd)}",
            style = TvType.Body,
            color = Tv.Ink3
        )

        grid.error?.let { MessageLine(it, error = true) }
        if (page == null) {
            Text(
                if (grid.busy) "Loading the guide..." else "The guide has not loaded yet.",
                style = TvType.Body,
                color = Tv.Ink3
            )
        } else {
            // The time ruler. It scrolls sideways together with the rows below.
            Row(Modifier.fillMaxWidth().height(26.dp)) {
                Box(Modifier.width(CHANNEL_W).fillMaxHeight()) {
                    Text("Channel", style = TvType.CaptionStrong, color = Tv.Ink3)
                }
                Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
                    Row(Modifier.width(gridW).fillMaxHeight()) {
                        repeat(slots) { i ->
                            Box(Modifier.width(SLOT_W).fillMaxHeight().padding(start = 6.dp)) {
                                Text(
                                    formatTime(windowStart + i * SLOT_MS),
                                    style = TvType.CaptionStrong,
                                    color = Tv.Ink3,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            LazyColumn(Modifier.weight(1f)) {
                items(page.channels, key = { it.id }) { ch ->
                    Row(Modifier.fillMaxWidth().height(ROW_H), verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier
                                .width(CHANNEL_W)
                                .fillMaxHeight()
                                .clickable { m.watchChannel(ch.id) }
                                .padding(end = 8.dp)
                        ) {
                            Text(
                                if (ch.number > 0) "${ch.number} ${ch.name}" else ch.name,
                                style = TvType.BodyStrong,
                                color = Tv.Ink,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (ch.locked) Text("PIN", style = TvType.Small, color = Tv.Ink3)
                        }
                        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
                            Box(Modifier.width(gridW).fillMaxHeight()) {
                                ChannelCells(ch, windowStart, windowEnd, nowX) { picked = ch to it }
                            }
                        }
                    }
                }
                if (page.channels.size < page.total) {
                    item {
                        SecondaryButton(
                            "Load more channels",
                            onClick = { m.loadMoreGrid() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !grid.busy
                        )
                    }
                }
            }
        }
    }

    picked?.let { (ch, p) ->
        MessageDialog(
            title = p.title,
            message = listOf(ch.name, "${formatTime(p.startMs)} to ${formatTime(p.endMs)}", p.desc)
                .filter { it.isNotBlank() }
                .joinToString("\n"),
            confirmLabel = if (p.onNow) "Watch on the TV" else "Close",
            onConfirm = {
                if (p.onNow) m.watchChannel(ch.id)
                picked = null
            },
            onDismiss = { picked = null },
            dismissLabel = "Close"
        )
    }
}

/**
 * One channel's programmes across the window, each as a block from its start to its end, and the
 * line for now. A channel the guide has nothing for gets one blank block, as on the TV.
 */
@Composable
private fun ChannelCells(
    ch: GridChannel,
    windowStart: Long,
    windowEnd: Long,
    nowX: Dp?,
    onPick: (BrowseProgram) -> Unit
) {
    val cells = ch.programs.ifEmpty {
        listOf(BrowseProgram("No guide information", windowStart, windowEnd, "", false))
    }
    cells.forEach { p ->
        val from = maxOf(p.startMs, windowStart)
        val to = minOf(p.endMs, windowEnd)
        if (to > from) {
            val left = SLOT_W * ((from - windowStart).toFloat() / SLOT_MS)
            val width = SLOT_W * ((to - from).toFloat() / SLOT_MS)
            Box(
                Modifier
                    .offset(x = left)
                    .width(width)
                    .fillMaxHeight()
                    .padding(vertical = 3.dp, horizontal = 1.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (p.onNow) Tv.Accent.copy(alpha = 0.22f) else Tv.Surface2)
                    .clickable { onPick(p) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    p.title,
                    style = TvType.Small,
                    color = if (p.onNow) Tv.Ink else Tv.Ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    if (nowX != null) {
        Box(Modifier.offset(x = nowX).width(2.dp).fillMaxHeight().background(Tv.Accent))
    }
}
