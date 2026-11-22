package com.dallas.cabletv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.dallas.cabletv.CableViewModel
import com.dallas.cabletv.data.JellyfinClient
import com.dallas.cabletv.data.Settings
import com.dallas.cabletv.data.TICKS_PER_MS
import com.dallas.cabletv.data.VodItem
import com.dallas.cabletv.data.VodTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the On Demand section currently is. */
private sealed interface VodView {
    data object Home : VodView
    data class Browse(val tab: VodTab) : VodView
    data class Detail(val item: VodItem, val back: VodView) : VodView
    data class Play(val item: VodItem, val queue: List<VodItem>, val index: Int, val startMs: Long, val back: VodView) : VodView
}

private const val PAGE_SIZE = 60

@Composable
fun OnDemandScreen(vm: CableViewModel, onExit: () -> Unit) {
    val settings by vm.settings.collectAsState()
    if (settings.hasJellyfin) {
        JellyfinBrowser(vm, settings, onExit)
    } else {
        Column(Modifier.fillMaxSize()) {
            CableHeader("ON DEMAND", onBack = onExit)
            CenterMessage("JELLYFIN IS NOT CONNECTED.\nADD YOUR SERVER IN SETTINGS.")
        }
    }
}

@Composable
private fun JellyfinBrowser(vm: CableViewModel, settings: Settings, onExit: () -> Unit) {
    val client = remember(settings.jellyfinServer, settings.jellyfinToken, settings.jellyfinUserId) {
        JellyfinClient(settings.jellyfinServer, settings.jellyfinToken, settings.jellyfinUserId)
    }
    var view by remember { mutableStateOf<VodView>(VodView.Home) }

    // Back steps out one level at a time. Playback stops in the player itself.
    BackHandler(enabled = view !is VodView.Home) {
        view = when (val v = view) {
            is VodView.Detail -> v.back
            is VodView.Browse -> VodView.Home
            is VodView.Play -> v.back
            VodView.Home -> VodView.Home
        }
    }

    val play = view as? VodView.Play
    if (play != null) {
        VodPlayer(
            vm = vm,
            client = client,
            queue = play.queue,
            startIndex = play.index,
            startMs = play.startMs,
            allowFallback = settings.jellyfinTranscodeFallback,
            onExit = { view = play.back },
            onOpenDetail = { view = VodView.Detail(it, play.back) }
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        CableHeader(
            title = when (val v = view) {
                is VodView.Browse -> v.tab.label
                is VodView.Detail -> v.item.name
                else -> "ON DEMAND"
            },
            onBack = if (view is VodView.Home) onExit else null
        )
        TabStrip(
            selected = when (val v = view) {
                VodView.Home -> -1
                is VodView.Browse -> VodTab.values().indexOf(v.tab)
                is VodView.Detail, is VodView.Play -> -2
            },
            onHome = { view = VodView.Home },
            onTab = { view = VodView.Browse(it) }
        )

        when (val v = view) {
            VodView.Home -> HomeView(
                client = client,
                onSelect = { item -> view = VodView.Detail(item, VodView.Home) },
                onExit = onExit
            )
            is VodView.Browse -> BrowseView(
                client = client,
                tab = v.tab,
                onSelect = { item -> view = VodView.Detail(item, v) }
            )
            is VodView.Detail -> DetailView(
                client = client,
                item = v.item,
                onPlay = { startMs, queue, index ->
                    view = VodView.Play(queue.getOrElse(index) { v.item }, queue, index, startMs, v)
                }
            )
            is VodView.Play -> Unit
        }
    }
}

@Composable
private fun TabStrip(selected: Int, onHome: () -> Unit, onTab: (VodTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(CableColors.PanelDeep)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TabChip("HOME", selected == -1) { onHome() }
        VodTab.values().forEach { tab ->
            TabChip(tab.label, selected == VodTab.values().indexOf(tab)) { onTab(tab) }
        }
    }
}

@Composable
private fun TabChip(label: String, active: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        label,
        color = if (active) CableColors.OnSelected else CableColors.Text,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    active -> CableColors.Selected
                    focused -> CableColors.Highlight
                    else -> CableColors.Cell
                }
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

// ---- Home ----

@Composable
private fun HomeView(client: JellyfinClient, onSelect: (VodItem) -> Unit, onExit: () -> Unit) {
    var resume by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    var nextUp by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    var recent by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(client) {
        loading = true
        withContext(Dispatchers.IO) {
            runCatching {
                Triple(client.continueWatching(), client.nextUp(), client.recentlyAdded())
            }
        }.onSuccess { (r, n, a) ->
            resume = r
            nextUp = n
            recent = a
            loadError = null
        }.onFailure {
            loadError = it.message ?: "Could not reach Jellyfin"
        }
        loading = false
    }

    when {
        loading -> CenterMessage("LOADING LIBRARY...")
        loadError != null -> CenterMessage("ERROR: $loadError")
        else -> Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            if (resume.isNotEmpty()) PosterRow("CONTINUE WATCHING", resume, onSelect)
            if (nextUp.isNotEmpty()) PosterRow("NEXT UP", nextUp, onSelect)
            PosterRow("RECENTLY ADDED", recent, onSelect)
        }
    }
}

// ---- Movies, Shows and Search ----

@Composable
private fun BrowseView(client: JellyfinClient, tab: VodTab, onSelect: (VodItem) -> Unit) {
    var genres by remember(tab) { mutableStateOf<List<String>>(emptyList()) }
    var genre by remember(tab) { mutableStateOf("") }
    var query by remember(tab) { mutableStateOf("") }
    val items = remember(tab) { mutableStateListOf<VodItem>() }
    var page by remember(tab) { mutableIntStateOf(0) }
    var canLoadMore by remember(tab) { mutableStateOf(true) }
    var loading by remember(tab) { mutableStateOf(false) }
    var error by remember(tab) { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()

    LaunchedEffect(tab) {
        if (tab != VodTab.Search) {
            genres = withContext(Dispatchers.IO) { runCatching { client.genres(tab) }.getOrDefault(emptyList()) }
        }
    }

    // Reloads from page one whenever the genre or search text changes.
    suspend fun loadPage(reset: Boolean) {
        if (loading) return
        if (reset) {
            items.clear()
            page = 0
            canLoadMore = true
        }
        if (!canLoadMore) return
        loading = true
        val start = page * PAGE_SIZE
        val result = withContext(Dispatchers.IO) {
            runCatching { client.library(tab, search = query, genre = genre, start = start, limit = PAGE_SIZE) }
        }
        result.onSuccess { list ->
            items.addAll(list)
            page++
            canLoadMore = list.size == PAGE_SIZE
            error = null
        }.onFailure { error = it.message ?: "Could not load" }
        loading = false
    }

    LaunchedEffect(tab, genre) {
        if (tab == VodTab.Search) return@LaunchedEffect
        loadPage(reset = true)
    }

    // Search waits for a short pause in typing before asking the server.
    LaunchedEffect(query) {
        if (tab != VodTab.Search) return@LaunchedEffect
        if (query.isBlank()) {
            items.clear()
            page = 0
            canLoadMore = true
            return@LaunchedEffect
        }
        delay(400)
        loadPage(reset = true)
    }

    // Load the next page when the user nears the end of the grid.
    LaunchedEffect(gridState, tab) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .map { it }
            .distinctUntilChanged()
            .filter { idx -> items.isNotEmpty() && idx >= items.size - 12 }
            .collect { loadPage(reset = false) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (tab == VodTab.Search) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Search movies and shows") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
        } else if (genres.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("") + genres, key = { it }) { g ->
                    GenreChip(label = if (g.isEmpty()) "ALL" else g.uppercase(), active = g == genre) {
                        genre = g
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        error?.let { Text("ERROR: $it", color = CableColors.Live, fontSize = 14.sp) }

        when {
            items.isEmpty() && loading -> CenterMessage("LOADING...")
            items.isEmpty() && tab == VodTab.Search && query.isBlank() ->
                CenterMessage("TYPE A TITLE TO SEARCH.")
            items.isEmpty() -> CenterMessage("NOTHING FOUND.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    PosterCard(item) { onSelect(item) }
                }
            }
        }
    }
}

@Composable
private fun GenreChip(label: String, active: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        label,
        color = if (active) CableColors.OnSelected else CableColors.Text,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    active -> CableColors.Selected
                    focused -> CableColors.Highlight
                    else -> CableColors.Cell
                }
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

// ---- Detail ----

@Composable
private fun DetailView(
    client: JellyfinClient,
    item: VodItem,
    onPlay: (startMs: Long, queue: List<VodItem>, index: Int) -> Unit
) {
    val isSeries = item.type == "Series"
    val scope = rememberCoroutineScope()
    var seasons by remember(item.id) { mutableStateOf<List<VodItem>>(emptyList()) }
    var seasonIndex by remember(item.id) { mutableIntStateOf(0) }
    var episodes by remember(item.id) { mutableStateOf<List<VodItem>>(emptyList()) }
    var detailError by remember(item.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.id) {
        if (!isSeries) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching { client.seasons(item.id) }
        }.onSuccess { seasons = it }
            .onFailure { detailError = it.message }
    }

    LaunchedEffect(item.id, seasonIndex, seasons) {
        if (!isSeries || seasons.isEmpty()) return@LaunchedEffect
        val season = seasons.getOrNull(seasonIndex) ?: return@LaunchedEffect
        episodes = withContext(Dispatchers.IO) {
            runCatching { client.episodes(item.id, season.id) }.getOrDefault(emptyList())
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            AsyncImage(
                model = item.posterUrl,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(180.dp)
                    .height(270.dp)
                    .background(CableColors.Panel)
            )
            Column(Modifier.weight(1f)) {
                Text(item.name, color = CableColors.Text, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                val meta = listOfNotNull(
                    item.year?.toString(),
                    item.runtimeTicks.takeIf { it > 0 }?.let { formatDuration(it / TICKS_PER_MS) },
                    item.genres.take(3).joinToString(", ").takeIf { it.isNotBlank() }
                ).joinToString("  |  ")
                if (meta.isNotBlank()) Text(meta, color = CableColors.Led, fontSize = 15.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    item.overview ?: "No description.",
                    color = CableColors.Text,
                    fontSize = 16.sp,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(16.dp))
                if (!isSeries) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (item.resumeTicks > 0) {
                            Button(onClick = {
                                scope.launch {
                                    val queue = queueFor(client, item)
                                    onPlay(item.resumeTicks / TICKS_PER_MS, queue.first, queue.second)
                                }
                            }) { Text("RESUME AT ${formatPosition(item.resumeTicks / TICKS_PER_MS)}") }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val queue = queueFor(client, item)
                                    onPlay(0L, queue.first, queue.second)
                                }
                            }) { Text("START OVER") }
                        } else {
                            Button(onClick = {
                                scope.launch {
                                    val queue = queueFor(client, item)
                                    onPlay(0L, queue.first, queue.second)
                                }
                            }) { Text("PLAY") }
                        }
                    }
                }
                detailError?.let { Text("ERROR: $it", color = CableColors.Live) }
            }
        }

        if (isSeries) {
            Spacer(Modifier.height(16.dp))
            if (seasons.isEmpty()) {
                Text("NO SEASONS FOUND.", color = CableColors.Dim)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(seasons, key = { _, s -> s.id }) { idx, season ->
                        GenreChip(
                            label = "SEASON ${season.seasonNumber ?: (idx + 1)}",
                            active = idx == seasonIndex
                        ) { seasonIndex = idx }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (episodes.isEmpty()) {
                    Text("NO EPISODES.", color = CableColors.Dim)
                } else {
                    episodes.forEachIndexed { idx, ep ->
                        EpisodeRow(ep) {
                            onPlay(ep.resumeTicks / TICKS_PER_MS, episodes, idx)
                        }
                    }
                }
            }
        }
    }
}

/** Episodes queue up with their series, so playback can move to the next one. */
private suspend fun queueFor(client: JellyfinClient, item: VodItem): Pair<List<VodItem>, Int> {
    val seriesId = item.seriesId ?: return listOf(item) to 0
    val all = withContext(Dispatchers.IO) {
        runCatching { client.episodes(seriesId) }.getOrDefault(emptyList())
    }
    val idx = all.indexOfFirst { it.id == item.id }
    return if (idx >= 0) all to idx else listOf(item) to 0
}

@Composable
private fun EpisodeRow(ep: VodItem, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (focused) CableColors.Highlight else Color.Transparent)
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) CableColors.Selected else Color.Transparent
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        val code = if (ep.seasonNumber != null && ep.episodeNumber != null) {
            "S${ep.seasonNumber}E${ep.episodeNumber}  "
        } else ""
        Text(
            code + ep.name,
            color = CableColors.Text,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (ep.resumeTicks > 0 && ep.runtimeTicks > 0) {
            val fraction = (ep.resumeTicks.toFloat() / ep.runtimeTicks).coerceIn(0f, 1f)
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(CableColors.Cell)
            ) {
                Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(CableColors.Selected))
            }
        }
    }
}

// ---- Shared poster views ----

@Composable
private fun PosterRow(title: String, list: List<VodItem>, onSelect: (VodItem) -> Unit) {
    Column {
        Text(title, color = CableColors.Led, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(list, key = { it.id }) { item ->
                PosterCard(item) { onSelect(item) }
            }
        }
    }
}

@Composable
private fun PosterCard(item: VodItem, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .width(140.dp)
            .onFocusChanged { focused = it.isFocused }
            .border(3.dp, if (focused) CableColors.Selected else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        AsyncImage(
            model = item.posterUrl,
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(132.dp)
                .height(198.dp)
                .background(CableColors.Panel)
        )
        Text(
            item.name,
            color = CableColors.Text,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ---- Player ----

@Composable
private fun VodPlayer(
    vm: CableViewModel,
    client: JellyfinClient,
    queue: List<VodItem>,
    startIndex: Int,
    startMs: Long,
    allowFallback: Boolean,
    onExit: () -> Unit,
    onOpenDetail: (VodItem) -> Unit
) {
    val playback = vm.playback
    var index by remember(queue) { mutableIntStateOf(startIndex) }
    var pendingStart by remember(queue) { mutableLongStateOf(startMs) }
    var controlsTick by remember { mutableIntStateOf(0) }
    var showControls by remember { mutableStateOf(true) }
    var trackOpen by remember { mutableStateOf(false) }
    val item = queue.getOrNull(index)
    val focus = remember { FocusRequester() }

    // Start the item whenever the queue position changes.
    LaunchedEffect(item?.id) {
        val current = item ?: return@LaunchedEffect
        val start = pendingStart
        pendingStart = 0L
        playback.playVod(current, client, start, allowFallback)
    }

    // Controls fade out after a few seconds with no key presses.
    LaunchedEffect(controlsTick) {
        showControls = true
        delay(4000)
        showControls = false
    }

    // Move to the next episode when one ends, or leave after the last one.
    DisposableEffect(playback.player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    if (index + 1 < queue.size) {
                        index += 1
                    } else {
                        onExit()
                    }
                }
            }
        }
        playback.player.addListener(listener)
        onDispose { playback.player.removeListener(listener) }
    }

    DisposableEffect(Unit) {
        onDispose { playback.stopVod() }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }

    BackHandler(enabled = trackOpen) { trackOpen = false }
    BackHandler(enabled = !trackOpen) { onExit() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown || trackOpen) return@onPreviewKeyEvent false
                val handled = when (ev.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        playback.togglePlayPause(); true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { playback.seekBy(-10_000L); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { playback.seekBy(10_000L); true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { playback.seekBy(-60_000L); true }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { playback.seekBy(60_000L); true }
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        if (index + 1 < queue.size) index += 1
                        true
                    }
                    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        if (index > 0) index -= 1
                        true
                    }
                    KeyEvent.KEYCODE_MENU -> { trackOpen = true; true }
                    KeyEvent.KEYCODE_INFO -> { item?.let(onOpenDetail); true }
                    else -> false
                }
                if (handled) controlsTick++
                handled
            }
            .clickable { controlsTick++ }
    ) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { useController = false; player = playback.player } },
            update = { it.player = playback.player },
            modifier = Modifier.fillMaxSize()
        )

        SignalOverlay(playback)

        if (playback.transcoding) {
            Tag("TRANSCODED", modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))
        }

        if (showControls && item != null) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(CableColors.PanelDeep.copy(alpha = 0.9f))
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Text(
                    vodTitle(item),
                    color = CableColors.Text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                val duration = playback.durationMs
                val fraction = if (duration > 0) {
                    (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f)
                } else 0f
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(CableColors.Cell)
                ) {
                    Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(CableColors.Selected))
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        formatPosition(playback.positionMs) + " / " + formatPosition(duration),
                        color = CableColors.Text,
                        fontSize = 15.sp
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (queue.size > 1) "${index + 1} OF ${queue.size}" else "",
                        color = CableColors.Dim,
                        fontSize = 15.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
                LegendBar {
                    KeyLegend("OK", "PLAY / PAUSE", CableColors.Selected)
                    KeyLegend("LEFT RIGHT", "10 SEC", CableColors.TimeBar)
                    KeyLegend("CH", "NEXT / PREV", CableColors.TimeBar)
                    KeyLegend("MENU", "AUDIO / SUBS", CableColors.TimeBar)
                    KeyLegend("BACK", "EXIT", CableColors.Live)
                }
            }
        }

        if (trackOpen) {
            TrackMenu(playback = playback, onDismiss = { trackOpen = false })
        }
    }
}

private fun vodTitle(item: VodItem): String {
    val code = if (item.seasonNumber != null && item.episodeNumber != null) {
        "S${item.seasonNumber}E${item.episodeNumber}  "
    } else ""
    return code + item.name
}
