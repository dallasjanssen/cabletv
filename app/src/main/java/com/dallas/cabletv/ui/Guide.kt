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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.dallas.cabletv.data.EpgCache
import com.dallas.cabletv.data.GuideSelection
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.PlayerOptionsStore
import com.dallas.cabletv.data.Program
import com.dallas.cabletv.data.WatchStats
import com.dallas.cabletv.data.guideSelection
import kotlinx.coroutines.delay
import java.util.TimeZone

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

/** The menu OK opens on a programme: a reminder or recording for a later show, or live or from the start for the show on now. */
private data class ProgramChoice(val channel: LiveChannel, val program: Program, val onNow: Boolean)

private fun groupOf(ch: LiveChannel) = ch.group.ifBlank { "Other" }

private fun rowsFor(
    group: String,
    channels: List<LiveChannel>,
    favorites: Set<String>,
    recents: List<String>,
    custom: Map<String, List<String>> = emptyMap()
): List<LiveChannel> = when (group) {
    GROUP_ALL -> channels
    GROUP_FAVORITES -> channels.filter { it.id in favorites }
    GROUP_RECENT -> recents.mapNotNull { id -> channels.firstOrNull { it.id == id } }
    else -> {
        // A custom group wins over a provider category of the same name, and stays in the saved order.
        val ids = custom[group]
        if (ids != null) {
            val byId = channels.associateBy { it.id }
            ids.mapNotNull { byId[it] }
        } else {
            channels.filter { groupOf(it) == group }
        }
    }
}

/** Watched channels first. A tie keeps the order the list already had, including a custom group's order. */
internal fun sortGuideRows(channels: List<LiveChannel>, counts: Map<String, Int>): List<LiveChannel> {
    if (counts.isEmpty() || channels.size < 2) return channels
    return channels.withIndex().sortedWith(
        compareByDescending<IndexedValue<LiveChannel>> { counts[it.value.id] ?: 0 }.thenBy { it.index }
    ).map { it.value }
}

/** The channel with this number. When two share it, the one in the category on screen wins. */
private fun channelForNumber(typed: String, channels: List<LiveChannel>, rows: List<LiveChannel>): LiveChannel? =
    rows.firstOrNull { it.number.toString() == typed } ?: channels.firstOrNull { it.number.toString() == typed }

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
    group: String,
    onGroupChange: (String) -> Unit,
    onWatch: (LiveChannel) -> Unit,
    onReplay: (LiveChannel, Program) -> Unit,
    onRemind: (LiveChannel, Program) -> Unit,
    onRecord: (LiveChannel, Program) -> Unit,
    onToggleFavorite: (LiveChannel) -> Unit,
    onPreviewBounds: (Rect?) -> Unit,
    showToast: (String) -> Unit,
    guideRefreshing: Boolean,
    onRefreshGuide: () -> Unit
) {
    val context = LocalContext.current
    // Read on each pass so a choice changed in Settings shows the next time the guide draws.
    val options = PlayerOptionsStore(context).load()
    val customGroups = options.customGroupMap()
    val watchCounts = remember(options.sortByWatchTime, channels) {
        if (options.sortByWatchTime) runCatching { WatchStats.counts(context) }.getOrElse { emptyMap() } else emptyMap()
    }
    val groups = remember(channels) { groupsOf(channels) }
    LaunchedEffect(groups, customGroups) {
        // While channels are still loading there are no groups yet, so a category the viewer picked is kept.
        if (channels.isEmpty()) return@LaunchedEffect
        if (group != GROUP_ALL && group != GROUP_FAVORITES && group != GROUP_RECENT && group !in groups && group !in customGroups) {
            onGroupChange(GROUP_ALL)
        }
    }
    val rows = remember(channels, group, favorites, recents, customGroups, options.sortByWatchTime, watchCounts) {
        val filtered = rowsFor(group, channels, favorites, recents, customGroups)
        // Watch time is applied after the category, so a custom group keeps its members and then reorders them.
        if (options.sortByWatchTime) sortGuideRows(filtered, watchCounts) else filtered
    }
    @Suppress("UNUSED_VARIABLE") val epgVersion = guide.version
    // Entries that end before they start turn up in real feeds. They are skipped so cells never get a negative width.
    fun programsOf(ch: LiveChannel): List<Program> = guide.schedule(ch).filter { it.end > it.start }.ifEmpty { listOf(NO_INFO) }
    fun cellsOf(ch: LiveChannel): List<GuideCell> = guideCells(programsOf(ch))

    val now = rememberNow(30_000L)
    // Half hours on the local clock, so a zone 45 minutes off the hour still reads :00 and :30.
    val firstWindow = localSlotStart(now, TimeZone.getDefault().getOffset(now).toLong())
    // A past-days choice widens how far the grid can move back. Zero keeps the cache's usual window.
    val earliest = firstWindow - EpgCache.retainedBehindMs()
    val lastWindow = firstWindow + EpgCache.AHEAD_MS
    var windowStart by remember { mutableLongStateOf(firstWindow) }
    var anchor by remember { mutableLongStateOf(now) }

    fun cellAt(ch: LiveChannel, t: Long, winEnd: Long): Long? {
        val cells = cellsOf(ch)
        return cells.getOrNull(cellIndexAt(cells, t, windowStart, winEnd))?.start
    }

    val startRow = remember(rows) { rows.indexOfFirst { it.id == currentId }.coerceAtLeast(0) }
    var focus by remember {
        mutableStateOf(GuideFocus(startRow, rows.getOrNull(startRow)?.let { cellAt(it, now, now + 2 * HOUR) }))
    }
    if (focus.row > rows.lastIndex && rows.isNotEmpty()) focus = GuideFocus(rows.lastIndex, null)
    var focusTick by remember { mutableIntStateOf(1) }
    var showCategories by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<LiveChannel?>(null) }
    var programMenu by remember { mutableStateOf<ProgramChoice?>(null) }
    var digits by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val target = remember { FocusRequester() }
    // How many cells currently carry [target]. Focus may only be sent to it while one does,
    // because asking for a focus target that is not on screen crashes.
    val targetHolders = remember { intArrayOf(0) }
    // True while the highlight is in the grid, so it can be put back if its cell is replaced.
    var gridOwnsFocus by remember { mutableStateOf(true) }
    var restoreTick by remember { mutableIntStateOf(0) }
    val categoryButton = remember { FocusRequester() }
    val refreshButton = remember { FocusRequester() }
    // True while focus waits on the category button because there were no rows to highlight.
    var parkedOnCategory by remember { mutableStateOf(false) }
    var hadRows by remember { mutableStateOf(rows.isNotEmpty()) }

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
        if (rows.isEmpty()) {
            // Nothing to highlight, so focus waits on the category button instead of being lost.
            if (showCategories || menuFor != null || programMenu != null) return@LaunchedEffect
            repeat(3) {
                withFrameNanos { }
                if (runCatching { categoryButton.requestFocus() }.isSuccess) {
                    parkedOnCategory = true
                    return@LaunchedEffect
                }
            }
            return@LaunchedEffect
        }
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

    // Channels can arrive after the guide opens, or a category can run out of channels.
    LaunchedEffect(rows.isEmpty()) {
        if (rows.isEmpty()) {
            hadRows = false
            if (gridOwnsFocus) focusTick++
            return@LaunchedEffect
        }
        if (hadRows) return@LaunchedEffect
        hadRows = true
        // The first rows to arrive put the highlight on the channel playing.
        val r = rows.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val t = System.currentTimeMillis()
        anchor = maxOf(t, windowStart)
        focus = GuideFocus(r, cellAt(rows[r], anchor, anchor + 2 * HOUR))
        if (gridOwnsFocus || parkedOnCategory) {
            parkedOnCategory = false
            focusTick++
        }
    }

    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1400)
        val typed = digits
        val ch = channelForNumber(typed, channels, rows)
        digits = ""
        if (ch == null) {
            showToast("There is no channel $typed")
            return@LaunchedEffect
        }
        var list = rows
        if (ch !in list) {
            onGroupChange(GROUP_ALL)
            list = channels
        }
        val r = list.indexOf(ch)
        anchor = maxOf(now, windowStart)
        focus = GuideFocus(r, cellAt(ch, anchor, windowStart + 4 * SLOT))
        focusTick++
    }

    // Number keys work anywhere on the screen, the category and refresh buttons included.
    val numberKeys = Modifier.onPreviewKeyEvent { ev ->
        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val d = digitOf(ev.nativeKeyEvent.keyCode) ?: return@onPreviewKeyEvent false
        if (digits.length < 4) digits += d.toString()
        true
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val outerNarrow = maxWidth < 600.dp
    TabScaffold(
        Tab.Guide,
        nav,
        modifier = Modifier.background(Tv.Bg).then(numberKeys),
        backEnabled = !showCategories && menuFor == null && programMenu == null,
        onNavFocus = { if (it) gridOwnsFocus = false }
    ) { narrow ->
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

            // The highlighted show can disappear: listings arrive and replace "No guide information",
            // or the show ends and the grid moves on. The highlight then moves to whatever is on at
            // that time in the same row, or to the channel name.
            val shown = run {
                val ch = rows.getOrNull(focus.row)
                val s = focus.start
                when {
                    ch == null || s == null -> focus
                    cellsOf(ch).any { it.start == s && it.end > windowStart && it.start < windowEnd } -> focus
                    else -> GuideFocus(focus.row, cellAt(ch, maxOf(anchor, windowStart), windowEnd))
                }
            }
            if (shown != focus) {
                SideEffect {
                    focus = shown
                    restoreTick++
                }
            }
            LaunchedEffect(restoreTick) {
                if (restoreTick > 0 && gridOwnsFocus && !showCategories && menuFor == null && programMenu == null) {
                    repeat(3) {
                        withFrameNanos { }
                        if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
                    }
                }
            }

            // Works out the new highlight. handleKey below then moves real focus to it.
            // Right and Left follow the row's programmes in order, so every one can be reached.
            fun moveIn(code: Int): Boolean {
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
                            val cells = cellsOf(ch)
                            val i = cells.indexOfFirst { it.start == f.start }
                            // "No guide information" has nothing after it.
                            if (i < 0 || cells[i].program === NO_INFO) return true
                            val next = stepRight(cells, i, windowStart, slots * SLOT, lastWindow)
                            if (next == null) {
                                // The last show runs past the edge: move the grid on so the rest of it comes into view.
                                if (cells[i].end > windowStart + slots * SLOT && windowStart < lastWindow) windowStart += SLOT
                                return true
                            }
                            val nextStart = next.start ?: return true
                            windowStart = next.windowStart
                            anchor = nextStart
                            focus = GuideFocus(f.row, nextStart)
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (f.start == null) {
                            gridOwnsFocus = false
                            showCategories = true
                            return true
                        }
                        val cells = cellsOf(ch)
                        val i = cells.indexOfFirst { it.start == f.start }
                        // "No guide information" goes straight to the channel name.
                        if (i < 0 || cells[i].program === NO_INFO) {
                            focus = GuideFocus(f.row, null)
                            return true
                        }
                        val prev = stepLeft(cells, i, windowStart, earliest)
                        windowStart = prev.windowStart
                        prev.start?.let { anchor = maxOf(it, windowStart) }
                        focus = GuideFocus(f.row, prev.start)
                    }
                    else -> return false
                }
                return true
            }

            fun handleKey(code: Int): Boolean {
                val before = focus to windowStart
                val handled = moveIn(code)
                if (handled && (focus to windowStart) != before) focusTick++
                return handled
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .focusProperties { enter = { if (targetHolders[0] > 0) target else FocusRequester.Default } }
                    .focusGroup()
            ) {
                // Details of whatever is highlighted, with the live preview beside it.
                val headCh = rows.getOrNull(focus.row)
                val headProgram = headCh?.let { c -> programsOf(c).firstOrNull { it.start == focus.start } ?: guide.nowNext(c)?.now }
                Row(Modifier.fillMaxWidth().height(if (narrow) 96.dp else 130.dp).padding(horizontal = side).padding(top = 6.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                        if (headCh != null) GuideDetails(
                            headCh,
                            headProgram?.takeIf { it !== NO_INFO },
                            now,
                            narrow,
                            showNumbers = options.showChannelNumbers,
                            twoTitles = options.twoLineTitles
                        )
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
                            val watchingLabel = when {
                                playing == null -> "Nothing playing"
                                options.showChannelNumbers -> "Watching ${playing.number} · ${playing.name}"
                                else -> "Watching ${playing.name}"
                            }
                            Text(
                                watchingLabel,
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

                Row(Modifier.fillMaxWidth().padding(horizontal = side).padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                    PillButton(
                        if (guideRefreshing) "Refreshing" else "Refresh guide",
                        {
                            // A hand refresh still downloads, even when an automatic one would wait.
                            EpgCache.noteManualRefresh()
                            onRefreshGuide()
                        },
                        modifier = Modifier
                            .focusRequester(refreshButton)
                            .onPreviewKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                                    runCatching { categoryButton.requestFocus() }
                                    true
                                } else {
                                    false
                                }
                            },
                        enabled = !guideRefreshing
                    )
                }

                // The category button and the times along the top of the grid.
                Row(Modifier.fillMaxWidth().padding(horizontal = side).padding(top = 8.dp).height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                    Focusable(
                        onClick = {
                            gridOwnsFocus = false
                            showCategories = true
                        },
                        onFocus = { if (it) gridOwnsFocus = false else parkedOnCategory = false },
                        modifier = Modifier
                            .width(chanW)
                            .height(28.dp)
                            .focusRequester(categoryButton)
                            .onPreviewKeyEvent { ev ->
                                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (ev.nativeKeyEvent.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_DOWN -> if (rows.isNotEmpty()) {
                                        focusTick++
                                        true
                                    } else {
                                        false
                                    }
                                    KeyEvent.KEYCODE_DPAD_UP -> {
                                        runCatching { refreshButton.requestFocus() }
                                        true
                                    }
                                    else -> false
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
                                .heightIn(max = nowLineMaxDp(rows.size, density = LocalDensity.current.density).dp)
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
                            body = when {
                                group == GROUP_FAVORITES -> "Pick All channels, press OK on a channel name, then choose Add to favorites."
                                group in customGroups -> "None of the channels in this group are in the current list."
                                else -> "Channels you watch show up here."
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
                                    handleKey(ev.nativeKeyEvent.keyCode)
                                }
                        ) {
                            itemsIndexed(rows, key = { _, c -> c.id }) { i, ch ->
                                GuideRow(
                                    ch = ch,
                                    cells = cellsOf(ch),
                                    rowIndex = i,
                                    focus = shown,
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
                                    showNumbers = options.showChannelNumbers,
                                    twoNames = options.twoLineNames,
                                    twoTitles = options.twoLineTitles,
                                    showCatchupMark = options.showCatchupMark,
                                    onChannel = {
                                        focus = GuideFocus(i, null)
                                        gridOwnsFocus = false
                                        menuFor = ch
                                    },
                                    onProgram = { p ->
                                        focus = GuideFocus(i, p.start)
                                        when (guideSelection(p.start, p.end, System.currentTimeMillis(), p === NO_INFO, ch.catchup)) {
                                            GuideSelection.WatchLive -> onWatch(ch)
                                            GuideSelection.LiveOrStart -> programMenu = ProgramChoice(ch, p, onNow = true)
                                            GuideSelection.Replay -> onReplay(ch, p)
                                            GuideSelection.Later -> programMenu = ProgramChoice(ch, p, onNow = false)
                                            GuideSelection.Unavailable -> showToast("${p.title} is not available to replay")
                                        }
                                    },
                                    onFocused = { start ->
                                        gridOwnsFocus = true
                                        if (focus.row != i || focus.start != start) focus = GuideFocus(i, start)
                                    },
                                    targetHolders = targetHolders
                                )
                            }
                        }
                    }
                }
            }

            if (digits.isNotEmpty()) {
                val match = channelForNumber(digits, channels, rows)
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
            val items = (listOf(GROUP_FAVORITES, GROUP_RECENT, GROUP_ALL) + customGroups.keys + groups).distinct()
            CategoryPanel(
                title = "Show in the guide",
                items = items,
                selected = group,
                countOf = { g -> rowsFor(g, channels, favorites, recents, customGroups).size },
                onPick = { g ->
                    onGroupChange(g)
                    showCategories = false
                    val list = rowsFor(g, channels, favorites, recents, customGroups)
                    // The picked category puts the highlight on its first row, so the "rows arrived" step leaves it there.
                    hadRows = list.isNotEmpty()
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

        programMenu?.let { choice ->
            val ch = choice.channel
            val program = choice.program
            fun close() {
                programMenu = null
                focusTick++
            }
            val actions: List<Pair<String, () -> Unit>> = if (choice.onNow) {
                listOf(
                    "Watch now" to {
                        programMenu = null
                        onWatch(ch)
                    },
                    "Watch from the start" to {
                        programMenu = null
                        onReplay(ch, program)
                    }
                )
            } else {
                listOf(
                    "Remind me" to {
                        onRemind(ch, program)
                        close()
                    },
                    "Record" to {
                        onRecord(ch, program)
                        close()
                    }
                )
            }
            ProgrammeMenu(
                channel = ch,
                program = program,
                actions = actions,
                onClose = { close() },
                modifier = Modifier.align(Alignment.Center)
            )
        }

        menuFor?.let { ch ->
            ChannelMenu(
                channel = ch,
                favorite = ch.id in favorites,
                showNumbers = options.showChannelNumbers,
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
private fun GuideDetails(ch: LiveChannel, p: Program?, now: Long, narrow: Boolean, showNumbers: Boolean, twoTitles: Boolean) {
    val live = p != null && isOnNow(p, now)
    Text(
        p?.title ?: ch.name,
        style = if (narrow) TvType.Title else TvType.Headline,
        maxLines = if (twoTitles && p != null) 2 else 1,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (live) LivePill()
        Text(
            if (showNumbers) "${ch.number} · ${ch.name}" else ch.name,
            style = TvType.Small,
            color = Tv.Ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (p != null) Text(formatRange(p), style = TvType.Small, color = Tv.Ink2, maxLines = 1)
        if (live && p != null && !narrow) {
            ProgressBar(progressOf(p, now), Modifier.width(110.dp))
            Text(timeLeft(p, now), style = TvType.Small, color = Tv.Ink2, maxLines = 1)
        }
    }
    // The description is read from the guide database for this one programme, not kept with the schedule.
    val desc = remember(ch.id, p?.start) { p?.let { EpgCache.descriptionFor(ch.epgId, ch.name, it.start) }.orEmpty() }
    if (p != null && desc.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(desc, style = TvType.Small, color = Tv.Ink2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 560.dp))
    } else if (p == null) {
        Spacer(Modifier.height(6.dp))
        Text("No guide information for this channel.", style = TvType.Small, color = Tv.Ink3)
    }
}

@Composable
private fun GuideRow(
    ch: LiveChannel,
    cells: List<GuideCell>,
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
    showNumbers: Boolean,
    twoNames: Boolean,
    twoTitles: Boolean,
    showCatchupMark: Boolean,
    onChannel: () -> Unit,
    onProgram: (Program) -> Unit,
    onFocused: (Long?) -> Unit,
    targetHolders: IntArray
) {
    Row(Modifier.fillMaxWidth().height(ROW_DP.dp), verticalAlignment = Alignment.CenterVertically) {
        val isTargetChannel = focus.row == rowIndex && focus.start == null
        if (isTargetChannel) HoldsTarget(targetHolders)
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
                if (showNumbers) {
                    Text(
                        ch.number.toString(),
                        style = TvType.CaptionStrong,
                        color = if (LocalFocused.current) Tv.FocusInk else if (watching) Tv.Accent else Tv.Ink3,
                        maxLines = 1,
                        modifier = Modifier.width(if (compact) 26.dp else 30.dp)
                    )
                }
                if (!compact) {
                    ChannelLogo(ch, Modifier.size(44.dp, 26.dp), compact = true)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    ch.name,
                    style = TvType.SmallStrong.copy(lineHeight = TvType.SmallStrong.fontSize * 1.15f),
                    maxLines = if (twoNames) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (favorite) Icon(TvIcons.StarFilled, contentDescription = "Favorite", tint = if (LocalFocused.current) Tv.FocusInk else Tv.Accent, modifier = Modifier.size(12.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(gridW).fillMaxHeight()) {
            // Cells are keyed by their place in the row, not by show. When listings arrive and replace
            // "No guide information", or the grid moves on, the focused cell stays and shows the new
            // programme instead of disappearing from under the remote.
            val visible = cells.filter { it.end > windowStart && it.start < windowEnd }
            if (visible.isEmpty()) {
                Text(
                    "No guide information",
                    style = TvType.Small,
                    color = Tv.Ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp)
                )
            }
            visible.forEachIndexed { place, cell ->
                run {
                    key(place) {
                        val p = cell.program
                        // A cell ends where the next programme starts, so overlapping listings sit side by side.
                        val left = slotW * ((maxOf(cell.start, windowStart) - windowStart).toFloat() / SLOT)
                        val right = slotW * ((minOf(cell.end, windowEnd) - windowStart).toFloat() / SLOT)
                        val isTarget = focus.row == rowIndex && focus.start == p.start
                        if (isTarget) HoldsTarget(targetHolders)
                        val wide = right - left > 46.dp
                        // The mark is only a picture. OK still plays catch-up when the mark is hidden.
                        val catchupMark = showCatchupMark && ch.catchup && p !== NO_INFO && p.start <= now
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
                                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            (if (p.start < windowStart && p !== NO_INFO) "‹ " else "") + p.title,
                                            style = TvType.SmallStrong,
                                            maxLines = if (twoTitles) 2 else 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (catchupMark) {
                                            Spacer(Modifier.width(4.dp))
                                            Icon(TvIcons.Replay, contentDescription = "Catch-up", tint = ink3, modifier = Modifier.size(12.dp))
                                        }
                                    }
                                    // A second title line already fills the row, so the clock time stays in the details above.
                                    if (p !== NO_INFO && !twoTitles) {
                                        Text(formatTime(p.start), style = TvType.Caption, color = ink3, maxLines = 1)
                                    }
                                }
                            } else if (catchupMark) {
                                Icon(
                                    TvIcons.Replay,
                                    contentDescription = "Catch-up",
                                    tint = ink3,
                                    modifier = Modifier.padding(start = 6.dp).size(12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Counts the cells carrying the guide's focus target while they are on screen. */
@Composable
private fun HoldsTarget(holders: IntArray) {
    DisposableEffect(Unit) {
        holders[0]++
        onDispose { holders[0]-- }
    }
}

/** The categories a channel list can show: favorites, recents, everything, then the provider's groups. */
fun groupsOf(channels: List<LiveChannel>): List<String> = channels.map(::groupOf).distinct().sorted()

fun categoryItems(groups: List<String>): List<String> = (listOf(GROUP_FAVORITES, GROUP_RECENT, GROUP_ALL) + groups).distinct()

/**
 * Tallest the guide's now-line may be, in dp. It stops at the last channel row.
 * Compose packs a size into a fixed number of bits and refuses anything past 262142 px,
 * so a lineup of several thousand channels cannot ask for 50 dp times every row.
 */
internal fun nowLineMaxDp(rows: Int, rowDp: Float = ROW_DP, density: Float): Float {
    if (rows <= 0 || density <= 0f) return 0f
    val wantedPx = rowDp * rows * density
    val px = if (wantedPx.isFinite() && wantedPx <= MAX_CONSTRAINT_PX) wantedPx else MAX_CONSTRAINT_PX
    return px / density
}

private const val ROW_DP = 50f
private const val MAX_CONSTRAINT_PX = 262_142f

/**
 * A programme as the guide draws it. Feeds often start a show a few minutes before the previous one
 * ends, so a cell stops where the next programme starts and no cell covers another.
 */
internal class GuideCell(val program: Program, val end: Long) {
    val start: Long get() = program.start
}

/** One cell per start time, oldest first. Every cell has some width, so every programme can be highlighted. */
internal fun guideCells(programs: List<Program>): List<GuideCell> {
    val list = programs.filter { it.end > it.start }.distinctBy { it.start }.sortedBy { it.start }
    return list.mapIndexed { i, p -> GuideCell(p, minOf(p.end, list.getOrNull(i + 1)?.start ?: p.end)) }
}

/**
 * The cell to highlight at time [t]: the one on at [t], else the first starting before [winEnd],
 * else the nearest one already in the window. -1 when the window shows nothing for the row.
 */
internal fun cellIndexAt(cells: List<GuideCell>, t: Long, winStart: Long, winEnd: Long): Int {
    val on = cells.indexOfFirst { t >= it.start && t < it.end }
    if (on >= 0) return on
    val soon = cells.indexOfFirst { it.start >= t && it.start < winEnd }
    if (soon >= 0) return soon
    return cells.indexOfLast { it.end > winStart && it.start < winEnd && it.start < t }
}

/** Where Right or Left leaves the highlight: a programme's start, or null for the channel name, and the window's start. */
internal data class GuideStep(val start: Long?, val windowStart: Long)

/**
 * Right from cell [index]: the next programme in the row, with the window moved on just far enough
 * to show where it starts. Null when nothing comes later inside the guide.
 */
internal fun stepRight(cells: List<GuideCell>, index: Int, windowStart: Long, span: Long, lastWindow: Long): GuideStep? {
    val next = cells.getOrNull(index + 1) ?: return null
    if (next.start >= lastWindow + span) return null
    var w = windowStart
    while (next.start >= w + span && w < lastWindow) w += SLOT
    return GuideStep(next.start, w)
}

/**
 * Left from cell [index]: the previous programme in the row, with the window moved back to show where
 * it starts, or the channel name when nothing earlier is inside the guide.
 */
internal fun stepLeft(cells: List<GuideCell>, index: Int, windowStart: Long, earliest: Long): GuideStep {
    val prev = cells.getOrNull(index - 1)
    if (prev == null || prev.end <= earliest) return GuideStep(null, windowStart)
    var w = windowStart
    while (prev.start < w && w > earliest) w -= SLOT
    return GuideStep(prev.start, w)
}

/** The start of the half hour on the local clock at or before [t]. [offsetMs] is the zone's offset from UTC. */
internal fun localSlotStart(t: Long, offsetMs: Long): Long = Math.floorDiv(t + offsetMs, SLOT) * SLOT - offsetMs

/** The digit a number key types, from the top row or the number pad, or null for any other key. */
internal fun digitOf(keyCode: Int): Int? = when (keyCode) {
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
    else -> null
}

/** The channels in a category, in list order. */
fun channelsIn(
    group: String,
    channels: List<LiveChannel>,
    favorites: Set<String>,
    recents: List<String>,
    custom: Map<String, List<String>> = emptyMap()
): List<LiveChannel> = rowsFor(group, channels, favorites, recents, custom)

@Composable
fun CategoryPanel(
    title: String,
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
            title,
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
    showNumbers: Boolean,
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
                    if (showNumbers) Text("Channel ${channel.number}", style = TvType.Caption, color = Tv.Ink3)
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

/**
 * Opens with OK on a programme. A later show offers a reminder or a recording. The show on now,
 * on a channel with an archive, offers live or from the start.
 */
@Composable
private fun ProgrammeMenu(
    channel: LiveChannel,
    program: Program,
    actions: List<Pair<String, () -> Unit>>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(channel.id, program.start) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Column(
            modifier.trapFocus().width(340.dp).clip(RoundedCornerShape(16.dp)).background(Tv.Surface2).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(program.title, style = TvType.Section, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${channel.name} · ${formatRange(program)}", style = TvType.Caption, color = Tv.Ink3)
            actions.forEachIndexed { k, (label, action) ->
                PillButton(
                    label,
                    action,
                    modifier = Modifier.fillMaxWidth().then(if (k == 0) Modifier.focusRequester(first) else Modifier)
                )
            }
            PillButton("Close", onClose, modifier = Modifier.fillMaxWidth().then(if (actions.isEmpty()) Modifier.focusRequester(first) else Modifier))
        }
    }
}
