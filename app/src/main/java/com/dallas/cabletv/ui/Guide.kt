package com.dallas.cabletv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.Program
import kotlinx.coroutines.delay

private const val SLOT = 30 * 60_000L
private const val HOUR = 60 * 60_000L
private const val ROW_PAGE = 6

const val GROUP_FAVORITES = "Favorites"
const val GROUP_RECENT = "Recently watched"
const val GROUP_ALL = "All channels"

/** Where the guide's highlight is: a row, and a programme's start time, or null for the channel name. */
private data class GuideFocus(val row: Int, val start: Long?)

/** Stands in for a channel with no guide data, so every row still has one cell to land on. */
private val NO_INFO = Program("No guide information", 0L, Long.MAX_VALUE / 4)

private fun groupOf(ch: LiveChannel) = ch.group.ifBlank { "Other" }

private fun rowsFor(group: String, channels: List<LiveChannel>, favorites: Set<String>, recents: List<String>): List<LiveChannel> =
    when (group) {
        GROUP_ALL -> channels
        GROUP_FAVORITES -> channels.filter { it.id in favorites }
        GROUP_RECENT -> recents.mapNotNull { id -> channels.firstOrNull { it.id == id } }
        else -> channels.filter { groupOf(it) == group }
    }

/**
 * The program guide: channels down the side, half hours across the top, and a line for now.
 * The top shows details of the highlighted show and a small preview of the channel playing.
 * Left from a channel name opens the category list. Number keys jump to a channel.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GuideScreen(
    nav: NavActions,
    channels: List<LiveChannel>,
    currentId: String?,
    favorites: Set<String>,
    recents: List<String>,
    guide: GuideData,
    loading: Boolean,
    onWatch: (LiveChannel) -> Unit,
    onToggleFavorite: (LiveChannel) -> Unit,
    onPreviewBounds: (Rect?) -> Unit,
    showToast: (String) -> Unit
) {
    var group by rememberSaveable { mutableStateOf(GROUP_ALL) }
    val groups = remember(channels) { channels.map(::groupOf).distinct().sorted() }
    LaunchedEffect(groups) {
        if (group != GROUP_ALL && group != GROUP_FAVORITES && group != GROUP_RECENT && group !in groups) group = GROUP_ALL
    }
    val rows = remember(channels, group, favorites, recents) { rowsFor(group, channels, favorites, recents) }
    @Suppress("UNUSED_VARIABLE") val epgVersion = guide.version
    fun programsOf(ch: LiveChannel): List<Program> = guide.schedule(ch).ifEmpty { listOf(NO_INFO) }

    val now = rememberNow(30_000L)
    val firstWindow = now / SLOT * SLOT
    val lastWindow = firstWindow + 10 * HOUR
    var windowStart by remember { mutableLongStateOf(firstWindow) }
    LaunchedEffect(firstWindow) { if (windowStart < firstWindow) windowStart = firstWindow }
    var anchor by remember { mutableLongStateOf(now) }

    fun cellAt(ch: LiveChannel, t: Long, winEnd: Long): Long? {
        val list = programsOf(ch)
        return (list.firstOrNull { t >= it.start && t < it.end } ?: list.firstOrNull { it.start in t until winEnd })?.start
    }

    val startRow = remember(rows) { rows.indexOfFirst { it.id == currentId }.coerceAtLeast(0) }
    var focus by remember {
        mutableStateOf(GuideFocus(startRow, rows.getOrNull(startRow)?.let { cellAt(it, now, now + 2 * HOUR) }))
    }
    if (focus.row > rows.lastIndex && rows.isNotEmpty()) focus = GuideFocus(rows.lastIndex, null)
    var focusTick by remember { mutableIntStateOf(1) }
    var showCategories by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<LiveChannel?>(null) }
    var digits by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val target = remember { FocusRequester() }
    val categoryButton = remember { FocusRequester() }

    BackHandler(enabled = showCategories) {
        showCategories = false
        focusTick++
    }
    BackHandler(enabled = menuFor != null) {
        menuFor = null
        focusTick++
    }

    // Moves real focus to the highlighted cell, scrolling its row into view first.
    LaunchedEffect(focusTick) {
        if (rows.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isNotEmpty()) {
            val fullFirst = visible.firstOrNull { it.offset >= info.viewportStartOffset }?.index ?: visible.first().index
            val fullLast = visible.lastOrNull { it.offset + it.size <= info.viewportEndOffset }?.index ?: visible.last().index
            val span = (fullLast - fullFirst).coerceAtLeast(1)
            when {
                focus.row < fullFirst + 1 -> listState.scrollToItem((focus.row - 1).coerceAtLeast(0))
                focus.row > fullLast - 1 -> listState.scrollToItem((focus.row - span + 1).coerceAtLeast(0))
            }
        } else {
            listState.scrollToItem((focus.row - 1).coerceAtLeast(0))
        }
        repeat(3) {
            withFrameNanos { }
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1400)
        val typed = digits
        val ch = channels.firstOrNull { it.number.toString() == typed }
        digits = ""
        if (ch == null) {
            showToast("There is no channel $typed")
            return@LaunchedEffect
        }
        var list = rows
        if (ch !in list) {
            group = GROUP_ALL
            list = channels
        }
        val r = list.indexOf(ch)
        anchor = maxOf(now, windowStart)
        focus = GuideFocus(r, cellAt(ch, anchor, windowStart + 4 * SLOT))
        focusTick++
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val outerNarrow = maxWidth < 600.dp
    TabScaffold(Tab.Guide, nav, modifier = Modifier.background(Tv.Bg), backEnabled = !showCategories && menuFor == null) { narrow ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = 52.dp)) {
            val side = gutter(narrow)
            val chanW = if (narrow) 116.dp else 196.dp
            val gridW = maxWidth - side * 2 - chanW - 8.dp
            val slots = when {
                gridW >= 600.dp -> 4
                gridW >= 330.dp -> 3
                else -> 2
            }
            val slotW = gridW / slots
            val windowEnd = windowStart + slots * SLOT

            fun handleKey(code: Int): Boolean {
                val ch = rows.getOrNull(focus.row) ?: return false
                val f = focus
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
                    KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {
                        val step = when (code) {
                            KeyEvent.KEYCODE_DPAD_UP -> -1
                            KeyEvent.KEYCODE_DPAD_DOWN -> 1
                            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> -ROW_PAGE
                            else -> ROW_PAGE
                        }
                        var nr = f.row + step
                        if (nr < 0) {
                            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                                runCatching { categoryButton.requestFocus() }
                                return true
                            }
                            nr = 0
                        }
                        nr = nr.coerceAtMost(rows.lastIndex)
                        if (nr == f.row) return true
                        focus = if (f.start == null) {
                            GuideFocus(nr, null)
                        } else {
                            GuideFocus(nr, cellAt(rows[nr], maxOf(anchor, windowStart), windowEnd))
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (f.start == null) {
                            val t = maxOf(now, windowStart)
                            anchor = t
                            focus = GuideFocus(f.row, cellAt(ch, t, windowEnd) ?: return true)
                        } else {
                            val list = programsOf(ch)
                            val p = list.firstOrNull { it.start == f.start } ?: return true
                            if (p.end > windowEnd) {
                                // A long show runs past the edge, so the grid moves on half an hour.
                                if (windowStart < lastWindow) {
                                    windowStart += SLOT
                                    anchor = maxOf(p.start, windowStart)
                                }
                                return true
                            }
                            val next = list.firstOrNull { it.start >= p.end } ?: return true
                            if (next.start >= lastWindow + slots * SLOT) return true
                            while (next.start >= windowStart + slots * SLOT) windowStart += SLOT
                            anchor = next.start
                            focus = GuideFocus(f.row, next.start)
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (f.start == null) {
                            showCategories = true
                            return true
                        }
                        val list = programsOf(ch)
                        val p = list.firstOrNull { it.start == f.start } ?: return true
                        if (p.start <= windowStart) {
                            if (windowStart > firstWindow) {
                                windowStart -= SLOT
                                anchor = maxOf(p.start, windowStart)
                            } else {
                                focus = GuideFocus(f.row, null)
                            }
                            return true
                        }
                        val prev = list.lastOrNull { it.end <= p.start }
                        if (prev == null || prev.end <= windowStart) {
                            focus = GuideFocus(f.row, null)
                            return true
                        }
                        anchor = maxOf(prev.start, windowStart)
                        focus = GuideFocus(f.row, prev.start)
                    }
                    else -> return false
                }
                focusTick++
                return true
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .focusProperties { enter = { target } }
                    .focusGroup()
            ) {
                // Details of whatever is highlighted, with the live preview beside it.
                val headCh = rows.getOrNull(focus.row)
                val headProgram = headCh?.let { c -> programsOf(c).firstOrNull { it.start == focus.start } ?: guide.nowNext(c)?.now }
                Row(Modifier.fillMaxWidth().height(if (narrow) 96.dp else 130.dp).padding(horizontal = side).padding(top = 6.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                        if (headCh != null) GuideDetails(headCh, headProgram?.takeIf { it !== NO_INFO }, now, narrow)
                    }
                    if (!narrow) {
                        Spacer(Modifier.width(24.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .size(184.dp, 104.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Black)
                                    .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(10.dp))
                                    .onGloballyPositioned { onPreviewBounds(it.boundsInRoot()) }
                            )
                            Spacer(Modifier.height(3.dp))
                            val playing = channels.firstOrNull { it.id == currentId }
                            Text(
                                if (playing != null) "Watching ${playing.number} · ${playing.name}" else "Nothing playing",
                                style = TvType.Caption,
                                color = Tv.Ink3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(184.dp)
                            )
                        }
                        DisposableEffect(Unit) { onDispose { onPreviewBounds(null) } }
                    }
                }

                // The category button and the times along the top of the grid.
                Row(Modifier.fillMaxWidth().padding(horizontal = side).padding(top = 8.dp).height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                    Focusable(
                        onClick = { showCategories = true },
                        modifier = Modifier
                            .width(chanW)
                            .height(28.dp)
                            .focusRequester(categoryButton)
                            .onPreviewKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && rows.isNotEmpty()) {
                                    focusTick++
                                    true
                                } else {
                                    false
                                }
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = Tv.Surface2,
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(Modifier.padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(group, style = TvType.CaptionStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Icon(TvIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.width(gridW).fillMaxHeight()) {
                        val rulerNow = (now - windowStart).toFloat() / SLOT
                        if (rulerNow in 0f..slots.toFloat()) {
                            Box(
                                Modifier
                                    .offset(x = slotW * rulerNow - 4.dp)
                                    .align(Alignment.BottomStart)
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Tv.Accent)
                            )
                        }
                        for (k in 0 until slots) {
                            Row(Modifier.offset(x = slotW * k).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(1.dp).height(16.dp).background(Color(0x1AFFFFFF)))
                                Spacer(Modifier.width(8.dp))
                                Text(formatTime(windowStart + k * SLOT), style = TvType.CaptionStrong, color = Tv.Ink3, maxLines = 1)
                            }
                        }
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
                    // The line for now sits behind the cells, so it shows between rows without covering titles.
                    val nowX = (now - windowStart).toFloat() / SLOT
                    if (rows.isNotEmpty() && nowX >= 0f && nowX <= slots) {
                        Box(
                            Modifier
                                .padding(start = side + chanW + 8.dp + slotW * nowX - 1.dp)
                                .width(2.dp)
                                .fillMaxHeight()
                                .heightIn(max = 50.dp * rows.size)
                                .background(Tv.Accent)
                        )
                    }
                    when {
                        channels.isEmpty() -> EmptyState(
                            title = if (loading) "Finding your channels" else "No channels yet",
                            body = if (loading) "The guide fills in as soon as they load." else "Add your TV provider in Settings, Live TV.",
                            modifier = Modifier.align(Alignment.Center)
                        )
                        rows.isEmpty() -> EmptyState(
                            title = if (group == GROUP_FAVORITES) "No favorites yet" else "Nothing here yet",
                            body = if (group == GROUP_FAVORITES) {
                                "Press OK on a channel name to add it, or use Add to favorites while watching."
                            } else {
                                "Channels you watch show up here."
                            },
                            modifier = Modifier.align(Alignment.Center)
                        )
                        else -> LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = side)
                                .onPreviewKeyEvent { ev ->
                                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    val code = ev.nativeKeyEvent.keyCode
                                    if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                                        if (digits.length < 4) digits += (code - KeyEvent.KEYCODE_0).toString()
                                        return@onPreviewKeyEvent true
                                    }
                                    handleKey(code)
                                }
                        ) {
                            itemsIndexed(rows, key = { _, c -> c.id }) { i, ch ->
                                GuideRow(
                                    ch = ch,
                                    programs = programsOf(ch),
                                    rowIndex = i,
                                    focus = focus,
                                    target = target,
                                    windowStart = windowStart,
                                    windowEnd = windowEnd,
                                    slotW = slotW,
                                    chanW = chanW,
                                    gridW = gridW,
                                    now = now,
                                    watching = ch.id == currentId,
                                    favorite = ch.id in favorites,
                                    compact = narrow,
                                    onChannel = {
                                        focus = GuideFocus(i, null)
                                        menuFor = ch
                                    },
                                    onProgram = { p ->
                                        focus = GuideFocus(i, p.start)
                                        when {
                                            isOnNow(p, now) -> onWatch(ch)
                                            p.start > now -> showToast("${p.title} starts at ${formatTime(p.start)} on ${ch.name}")
                                            else -> onWatch(ch)
                                        }
                                    },
                                    onFocused = { start ->
                                        if (focus.row != i || focus.start != start) focus = GuideFocus(i, start)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (digits.isNotEmpty()) {
                val match = channels.firstOrNull { it.number.toString() == digits }
                Column(
                    Modifier.align(Alignment.TopEnd).padding(end = side, top = 8.dp)
                        .clip(RoundedCornerShape(12.dp)).background(Tv.Surface3).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(digits, style = TvType.Headline, color = Tv.Accent)
                    Text(match?.name ?: "No channel $digits", style = TvType.Caption, color = Tv.Ink2)
                }
            }
        }
    }

        // Panels sit outside the tabbed screen so they cover the top bar too.
        AnimatedVisibility(
            visible = showCategories,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            val items = listOf(GROUP_FAVORITES, GROUP_RECENT, GROUP_ALL) + groups
            CategoryPanel(
                items = items,
                selected = group,
                countOf = { g -> rowsFor(g, channels, favorites, recents).size },
                onPick = { g ->
                    group = g
                    showCategories = false
                    val list = rowsFor(g, channels, favorites, recents)
                    anchor = maxOf(now, windowStart)
                    focus = GuideFocus(0, list.firstOrNull()?.let { cellAt(it, anchor, windowEnd(windowStart, outerNarrow)) })
                    focusTick++
                },
                onClose = {
                    showCategories = false
                    focusTick++
                }
            )
        }

        menuFor?.let { ch ->
            ChannelMenu(
                channel = ch,
                favorite = ch.id in favorites,
                onWatch = {
                    menuFor = null
                    onWatch(ch)
                },
                onFavorite = {
                    val adding = ch.id !in favorites
                    onToggleFavorite(ch)
                    showToast(if (adding) "Added ${ch.name} to favorites" else "Removed ${ch.name} from favorites")
                    menuFor = null
                    focusTick++
                },
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/** The window end used when a category is picked, before the grid has measured itself. */
private fun windowEnd(start: Long, narrow: Boolean) = start + (if (narrow) 2 else 4) * SLOT

@Composable
private fun GuideDetails(ch: LiveChannel, p: Program?, now: Long, narrow: Boolean) {
    val live = p != null && isOnNow(p, now)
    Text(
        p?.title ?: ch.name,
        style = if (narrow) TvType.Title else TvType.Headline,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (live) LivePill()
        Text("${ch.number} · ${ch.name}", style = TvType.Small, color = Tv.Ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (p != null) Text(formatRange(p), style = TvType.Small, color = Tv.Ink2, maxLines = 1)
        if (live && p != null && !narrow) {
            ProgressBar(progressOf(p, now), Modifier.width(110.dp))
            Text(timeLeft(p, now), style = TvType.Small, color = Tv.Ink2, maxLines = 1)
        }
    }
    if (p != null && p.desc.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(p.desc, style = TvType.Small, color = Tv.Ink2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 560.dp))
    } else if (p == null) {
        Spacer(Modifier.height(6.dp))
        Text("No guide information for this channel.", style = TvType.Small, color = Tv.Ink3)
    }
}

@Composable
private fun GuideRow(
    ch: LiveChannel,
    programs: List<Program>,
    rowIndex: Int,
    focus: GuideFocus,
    target: FocusRequester,
    windowStart: Long,
    windowEnd: Long,
    slotW: Dp,
    chanW: Dp,
    gridW: Dp,
    now: Long,
    watching: Boolean,
    favorite: Boolean,
    compact: Boolean,
    onChannel: () -> Unit,
    onProgram: (Program) -> Unit,
    onFocused: (Long?) -> Unit
) {
    Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
        val isTargetChannel = focus.row == rowIndex && focus.start == null
        Focusable(
            onClick = onChannel,
            modifier = Modifier
                .width(chanW)
                .fillMaxHeight()
                .padding(vertical = 3.dp)
                .then(if (isTargetChannel) Modifier.focusRequester(target) else Modifier),
            shape = RoundedCornerShape(8.dp),
            onFocus = { if (it) onFocused(null) },
            contentAlignment = Alignment.CenterStart
        ) {
            Row(Modifier.padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ch.number.toString(),
                    style = TvType.CaptionStrong,
                    color = if (LocalFocused.current) Tv.FocusInk else if (watching) Tv.Accent else Tv.Ink3,
                    maxLines = 1,
                    modifier = Modifier.width(if (compact) 26.dp else 30.dp)
                )
                if (!compact) {
                    ChannelLogo(ch, Modifier.size(44.dp, 26.dp), compact = true)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    ch.name,
                    style = TvType.SmallStrong.copy(lineHeight = TvType.SmallStrong.fontSize * 1.15f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (favorite) Icon(TvIcons.StarFilled, contentDescription = "Favorite", tint = if (LocalFocused.current) Tv.FocusInk else Tv.Accent, modifier = Modifier.size(12.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(gridW).fillMaxHeight()) {
            programs.forEach { p ->
                if (p.end > windowStart && p.start < windowEnd) {
                    key(p.start) {
                        val left = slotW * ((maxOf(p.start, windowStart) - windowStart).toFloat() / SLOT)
                        val right = slotW * ((minOf(p.end, windowEnd) - windowStart).toFloat() / SLOT)
                        val isTarget = focus.row == rowIndex && focus.start == p.start
                        val wide = right - left > 46.dp
                        Focusable(
                            onClick = { onProgram(p) },
                            modifier = Modifier
                                .offset(x = left)
                                .width(right - left)
                                .fillMaxHeight()
                                .padding(vertical = 3.dp, horizontal = 2.dp)
                                .then(if (isTarget) Modifier.focusRequester(target) else Modifier),
                            shape = RoundedCornerShape(7.dp),
                            color = if (isOnNow(p, now)) Tv.Surface2 else Tv.Surface,
                            onFocus = { if (it) onFocused(p.start) },
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (wide) {
                                Column(Modifier.padding(horizontal = 10.dp)) {
                                    Text(
                                        (if (p.start < windowStart && p !== NO_INFO) "‹ " else "") + p.title,
                                        style = TvType.SmallStrong,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (p !== NO_INFO) {
                                        Text(formatTime(p.start), style = TvType.Caption, color = ink3, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryPanel(
    items: List<String>,
    selected: String,
    countOf: (String) -> Int,
    onPick: (String) -> Unit,
    onClose: () -> Unit
) {
    val start = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val at = items.indexOf(selected).coerceAtLeast(0)
    LaunchedEffect(Unit) {
        listState.scrollToItem((at - 3).coerceAtLeast(0))
        withFrameNanos { }
        runCatching { start.requestFocus() }
    }
    BoxWithConstraints {
        SidePanel(
            "Show in the guide",
            Modifier
                .width(minOf(320.dp, maxWidth * 0.88f))
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        onClose()
                        true
                    } else {
                        false
                    }
                }
        ) {
            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(horizontal = 12.dp)) {
                itemsIndexed(items, key = { _, g -> g }) { i, g ->
                    Focusable(
                        onClick = { onPick(g) },
                        modifier = (if (i == at) Modifier.focusRequester(start) else Modifier).fillMaxWidth().height(42.dp),
                        shape = RoundedCornerShape(9.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(g, style = TvType.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (g == selected) {
                                Icon(TvIcons.Check, contentDescription = "Showing", tint = if (LocalFocused.current) Tv.FocusInk else Tv.Accent, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(countOf(g).toString(), style = TvType.Caption, color = ink3)
                        }
                    }
                }
            }
        }
    }
}

/** Opens with OK on a channel name: watch it, or add or remove it from favorites. */
@Composable
private fun ChannelMenu(
    channel: LiveChannel,
    favorite: Boolean,
    onWatch: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(channel.id) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Column(
            modifier
                .trapFocus()
                .width(300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Tv.Surface2)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(channel, Modifier.size(64.dp, 36.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(channel.name, style = TvType.Section, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Channel ${channel.number}", style = TvType.Caption, color = Tv.Ink3)
                }
            }
            Spacer(Modifier.height(2.dp))
            PillButton("Watch now", onWatch, icon = TvIcons.Play, modifier = Modifier.fillMaxWidth().focusRequester(first))
            PillButton(
                if (favorite) "Remove from favorites" else "Add to favorites",
                onFavorite,
                icon = if (favorite) TvIcons.StarFilled else TvIcons.Star,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
