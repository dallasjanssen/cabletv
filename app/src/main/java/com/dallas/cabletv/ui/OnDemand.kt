package com.dallas.cabletv.ui

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.dallas.cabletv.data.JellyfinClient
import com.dallas.cabletv.data.Settings
import com.dallas.cabletv.data.UserStateStore
import com.dallas.cabletv.data.VodItem
import com.dallas.cabletv.data.XtreamClient
import com.dallas.cabletv.data.TrackMemory
import com.dallas.cabletv.data.newPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the movie and show rows need, loaded once by the app and shared with Home. */
data class VodShelves(
    val continueWatching: List<VodItem> = emptyList(),
    val recent: List<VodItem> = emptyList(),
    val movies: List<VodItem> = emptyList(),
    val series: List<VodItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
) {
    val isEmpty: Boolean get() = continueWatching.isEmpty() && recent.isEmpty() && movies.isEmpty() && series.isEmpty()
}

private fun keyOf(item: VodItem) = "${item.source}:${item.id}"

/** Past this much of an episode, it counts as watched. The provider sends no watched flag of its own. */
private const val WATCHED_FRACTION = 0.9f

/** One row of the library. [key] is unique even when two rows share a label. */
private class LibraryRow(val key: String, val title: String, val items: List<VodItem>)

/** The card to focus when the library comes back: its row, its key, and where it sat in the row. */
private data class LibraryFocus(val rowKey: String, val itemKey: String, val index: Int)

private fun libraryRows(shelves: VodShelves): List<LibraryRow> {
    fun grouped(title: String, items: List<VodItem>): List<Pair<String, List<VodItem>>> {
        if (items.isEmpty()) return emptyList()
        // Categories that differ only in case or spacing share one row.
        val groups = items.groupBy { it.category.trim().ifBlank { title }.lowercase() }
        if (groups.size <= 1) return listOf(title to items)
        return groups.values.map { list ->
            val name = list.first().category.trim().ifBlank { title }
            (if (name.equals(title, true)) title else "$title · $name") to list
        }
    }
    val labelled = listOfNotNull(
        shelves.continueWatching.takeIf { it.isNotEmpty() }?.let { "Continue watching" to it },
        shelves.recent.takeIf { it.isNotEmpty() }?.let { "Recently added" to it }
    ) + grouped("Movies", shelves.movies) + grouped("Shows", shelves.series)
    // List keys must be unique, so a label seen before gets a number. Cards in a row are unique too.
    val used = HashSet<String>()
    return labelled.map { (label, list) ->
        var key = label
        var n = 1
        while (!used.add(key)) {
            n++
            key = "$label ($n)"
        }
        LibraryRow(key, label, list.distinctBy { keyOf(it) })
    }
}

/** Row and card index for [focus], or the first card when it is gone. Null when there are no rows. */
private fun focusTarget(rows: List<LibraryRow>, focus: LibraryFocus?): Pair<Int, Int>? {
    if (rows.isEmpty()) return null
    if (focus == null) return 0 to 0
    val r = rows.indexOfFirst { it.key == focus.rowKey }
    if (r < 0) return 0 to 0
    val list = rows[r].items
    val i = list.indexOfFirst { keyOf(it) == focus.itemKey }
    return r to (if (i >= 0) i else focus.index.coerceIn(0, list.lastIndex))
}

/**
 * Movies and shows from Jellyfin and the TV provider. Picking a title opens its details page,
 * which plays, resumes, or lists the episodes. Back steps out one level at a time.
 * [requireUnlock] runs before any title opens or plays: it calls the action when the parental
 * lock allows it, asking for the PIN first when needed.
 */
@Composable
fun OnDemandScreen(
    nav: NavActions,
    settings: Settings,
    shelves: VodShelves,
    userState: UserStateStore,
    pendingVod: VodItem?,
    onPendingConsumed: () -> Unit,
    onPlaybackEnded: () -> Unit,
    onLeaveDetail: () -> Unit,
    jellyfinToken: String = "",
    jellyfinUserId: String = "",
    requireUnlock: (VodItem, () -> Unit) -> Unit = { _, go -> go() }
) {
    val token = jellyfinToken.ifBlank { settings.jellyfinToken }
    val userId = jellyfinUserId.ifBlank { settings.jellyfinUserId }
    val jellyfin = remember(settings.jellyfinServer, token, userId) {
        if (settings.jellyfinServer.isNotBlank() && token.isNotBlank() && userId.isNotBlank()) {
            JellyfinClient(settings.jellyfinServer, token, userId)
        } else {
            null
        }
    }
    val xtream = remember(settings) {
        if (settings.hasXtream) XtreamClient(settings.xtreamServer, settings.xtreamUser, settings.xtreamPass) else null
    }

    var detail by remember { mutableStateOf<VodItem?>(null) }
    var openedFromElsewhere by remember { mutableStateOf(false) }
    var episodes by remember { mutableStateOf<List<VodItem>?>(null) }
    // The show [episodes] belongs to, as source:id.
    var episodesFor by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf<VodItem?>(null) }
    var startOver by remember { mutableStateOf(false) }
    var lastPlayedId by remember { mutableStateOf<String?>(null) }
    var resumeTick by remember { mutableIntStateOf(0) }

    // Kept out here, not inside the library or the details page, so coming back from a page or
    // from playback finds the same scroll positions, the same card and the same season.
    var libraryFocus by remember { mutableStateOf<LibraryFocus?>(null) }
    val libraryList = rememberLazyListState()
    val rowStates = remember { mutableMapOf<String, LazyListState>() }
    var detailSeason by remember { mutableStateOf<Int?>(null) }
    val episodeList = remember(detail?.source, detail?.id) { LazyListState() }

    suspend fun loadEpisodes(source: String, seriesId: String): List<VodItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (source == "xtream") xtream?.seriesEpisodes(seriesId).orEmpty() else jellyfin?.episodes(seriesId).orEmpty()
            }.getOrDefault(emptyList())
        }.distinctBy { it.id }

    fun play(item: VodItem, fromStart: Boolean) {
        requireUnlock(item) {
            if (item.type == "Episode") {
                lastPlayedId = item.id
                if (item.season != null) detailSeason = item.season
            }
            startOver = fromStart
            playing = item
        }
    }

    fun open(item: VodItem, fromElsewhere: Boolean) {
        requireUnlock(item) {
            openedFromElsewhere = fromElsewhere
            if (item.type == "Episode") {
                // A half-watched episode from a resume row plays straight away.
                detail = null
                startOver = false
                playing = item
            } else {
                detail = item
                episodes = null
                episodesFor = null
                lastPlayedId = null
                detailSeason = null
            }
        }
    }

    LaunchedEffect(pendingVod) {
        val pick = pendingVod ?: return@LaunchedEffect
        open(pick, fromElsewhere = true)
        onPendingConsumed()
    }

    // Load a show's episodes when its page opens.
    LaunchedEffect(detail?.source, detail?.id) {
        val d = detail ?: return@LaunchedEffect
        if (d.type != "Series") return@LaunchedEffect
        val list = loadEpisodes(d.source, d.id)
        episodes = list
        episodesFor = "${d.source}:${d.id}"
    }

    // An episode opened from a resume row, Home or Search comes without its show's episode list.
    // It loads in the background, so the next episode can follow.
    val playingSeries = playing?.takeIf { it.type == "Episode" }?.let { p ->
        p.seriesId?.takeIf { it.isNotBlank() }?.let { id -> p.source to id }
    }
    LaunchedEffect(playingSeries) {
        val (source, seriesId) = playingSeries ?: return@LaunchedEffect
        val show = "$source:$seriesId"
        if (show == episodesFor && episodes != null) return@LaunchedEffect
        val list = loadEpisodes(source, seriesId)
        if (list.isNotEmpty()) {
            episodes = list
            episodesFor = show
        }
    }

    val nowPlaying = playing
    val openDetail = detail
    when {
        nowPlaying != null -> {
            val list = episodes.orEmpty()
            val at = list.indexOfFirst { it.id == nowPlaying.id && it.source == nowPlaying.source }
            // A fresh player for each title, so its saved place, its Jellyfin report and the
            // next episode prompt always belong to the title that was playing.
            key(nowPlaying.source, nowPlaying.id) {
                VodPlayer(
                    item = nowPlaying,
                    startOver = startOver,
                    store = userState,
                    jellyfin = if (nowPlaying.source == "jellyfin") jellyfin else null,
                    nextEpisode = if (at >= 0) list.getOrNull(at + 1) else null,
                    onPlayNext = { play(it, fromStart = true) },
                    onExit = {
                        playing = null
                        resumeTick++
                        onPlaybackEnded()
                        if (openDetail == null && openedFromElsewhere) onLeaveDetail()
                    }
                )
            }
        }
        openDetail != null -> {
            BackHandler {
                detail = null
                if (openedFromElsewhere) onLeaveDetail()
            }
            DetailScreen(
                item = openDetail,
                xtream = xtream,
                episodes = episodes.takeIf { episodesFor == "${openDetail.source}:${openDetail.id}" },
                store = userState,
                resumeTick = resumeTick,
                focusEpisodeId = lastPlayedId,
                season = detailSeason,
                onSeason = { detailSeason = it },
                listState = episodeList,
                onPlay = { item, fromStart -> play(item, fromStart) }
            )
        }
        else -> Library(
            nav = nav,
            shelves = shelves,
            hasSource = settings.hasJellyfin || settings.hasXtream,
            listState = libraryList,
            rowState = { key -> rowStates.getOrPut(key) { LazyListState() } },
            focus = libraryFocus,
            onOpen = { f, item ->
                libraryFocus = f
                open(item, fromElsewhere = false)
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Library(
    nav: NavActions,
    shelves: VodShelves,
    hasSource: Boolean,
    listState: LazyListState,
    rowState: (String) -> LazyListState,
    focus: LibraryFocus?,
    onOpen: (LibraryFocus, VodItem) -> Unit
) {
    // Grouping thousands of titles runs when the shelves change, not on every focus move.
    val rows = remember(shelves) { libraryRows(shelves) }
    var focused by remember { mutableStateOf(rows.firstOrNull()?.items?.firstOrNull()) }
    val target = remember(rows, focus) { focusTarget(rows, focus) }
    val requester = remember { FocusRequester() }
    val firstCard = remember { FocusRequester() }
    val hasRows = rows.isNotEmpty()
    LaunchedEffect(hasRows) {
        val (r, i) = target ?: return@LaunchedEffect
        withFrameNanos { }
        // A card below the fold or far along its row is not composed yet, so scroll to it first.
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == r }) listState.scrollToItem(r)
        val row = rowState(rows[r].key)
        if (row.layoutInfo.visibleItemsInfo.none { it.index == i }) row.scrollToItem(i)
        withFrameNanos { }
        if (runCatching { requester.requestFocus() }.isFailure) runCatching { firstCard.requestFocus() }
    }

    TabScaffold(Tab.OnDemand, nav, modifier = Modifier.background(Tv.Bg)) { narrow ->
        val side = gutter(narrow)
        val shown = focused
        if (shown != null && !narrow) {
            Box(Modifier.align(Alignment.TopEnd).width(620.dp).height(330.dp)) {
                AsyncImage(
                    model = shown.backdropUrl ?: shown.posterUrl.takeIf { it.isNotBlank() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(0.8f)
                )
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Tv.Bg, 0.5f to Tv.Bg.copy(alpha = 0.3f), 1f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Tv.Bg.copy(alpha = 0.5f), 0.25f to Color.Transparent, 0.4f to Color.Transparent, 1f to Tv.Bg)))
            }
        }
        when {
            !hasSource -> EmptyState(
                "No movies or shows yet",
                "Add a Jellyfin server in Settings, or sign in to a TV provider that includes movies and shows.",
                Modifier.align(Alignment.Center)
            )
            shelves.loading && rows.isEmpty() -> EmptyState("Loading your library", null, Modifier.align(Alignment.Center))
            rows.isEmpty() && shelves.error != null -> EmptyState("Your library did not load", shelves.error, Modifier.align(Alignment.Center))
            rows.isEmpty() -> EmptyState("Nothing here yet", "Movies and shows you add show up here.", Modifier.align(Alignment.Center))
            else -> {
                if (shown != null) {
                    Column(
                        Modifier.padding(start = side, end = side, top = 62.dp).widthIn(max = 540.dp).height(if (narrow) 110.dp else 136.dp),
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Text(shown.seriesName ?: shown.name, style = if (narrow) TvType.Title else TvType.Hero.copy(fontSize = TvType.Headline.fontSize * 1.2f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            metaParts(shown).forEach { Text(it, style = TvType.Small, color = Tv.Ink2) }
                            Badge(if (shown.source == "jellyfin") "Your library" else "From your provider")
                        }
                        shown.overview?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, style = TvType.Small, color = Tv.Ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPivotSpec(34.dp)) {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(top = if (narrow) 176.dp else 206.dp),
                        state = listState,
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        itemsIndexed(rows, key = { _, row -> row.key }) { r, row ->
                            Shelf(row.title, side, state = rowState(row.key)) {
                                itemsIndexed(row.items, key = { _, item -> keyOf(item) }) { i, item ->
                                    var mod: Modifier = Modifier
                                    if (target != null && r == target.first && i == target.second) mod = mod.focusRequester(requester)
                                    if (r == 0 && i == 0) mod = mod.focusRequester(firstCard)
                                    val click = { onOpen(LibraryFocus(row.key, keyOf(item), i), item) }
                                    if (row.title == "Continue watching") {
                                        ResumeCard(item, onClick = click, modifier = mod, onFocus = { if (it) focused = item })
                                    } else {
                                        PosterCard(item, onClick = click, modifier = mod, onFocus = { if (it) focused = item })
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

private fun metaParts(item: VodItem): List<String> = listOfNotNull(
    item.year?.toString(),
    when (item.type) {
        "Series" -> "Series"
        "Episode" -> episodeLabel(item)
        else -> item.runtimeMinutes?.let { minutesText(it.toLong()) }
    }
)

/** The page for one movie or show. Movies offer Resume and Start over. Shows list their seasons and episodes. */
@Composable
private fun DetailScreen(
    item: VodItem,
    xtream: XtreamClient?,
    episodes: List<VodItem>?,
    store: UserStateStore,
    resumeTick: Int,
    focusEpisodeId: String?,
    season: Int?,
    onSeason: (Int) -> Unit,
    listState: LazyListState,
    onPlay: (VodItem, Boolean) -> Unit
) {
    var details by remember(item.id) { mutableStateOf(item) }
    LaunchedEffect(item.id) {
        if (item.source == "xtream" && item.type == "Movie" && item.overview.isNullOrBlank() && xtream != null) {
            val extra = withContext(Dispatchers.IO) { runCatching { xtream.movieDetails(item.id) }.getOrNull() }
            if (extra != null) {
                details = item.copy(
                    name = extra.name.ifBlank { item.name },
                    year = extra.year ?: item.year,
                    overview = extra.overview ?: item.overview,
                    backdropUrl = extra.backdropUrl ?: item.backdropUrl,
                    runtimeMinutes = extra.runtimeMinutes ?: item.runtimeMinutes
                )
            }
        }
    }
    val primary = remember { FocusRequester() }
    val episodeFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val isSeries = item.type == "Series"
    val resumeMs = remember(item.id, resumeTick) { if (isSeries) 0L else store.resumePosition(item) }

    val list = episodes.orEmpty()
    // This show's episodes with a saved place, most recent first, with how far each got.
    val saved = remember(episodes, resumeTick) {
        val byKey = list.associateBy { keyOf(it) }
        store.continueWatching().mapNotNull { (entry, _) -> byKey[keyOf(entry)]?.let { it to (entry.progress ?: 0f) } }
    }
    val watched = remember(saved) { saved.filter { it.second >= WATCHED_FRACTION }.map { it.first.id }.toSet() }
    // For a show, Resume picks the episode watched most recently. When that one was watched to the
    // end, the one after it plays. Otherwise the first unwatched one.
    val latest = saved.firstOrNull()
    val resumeEpisode = latest?.takeIf { it.second < WATCHED_FRACTION }?.first
    val startEpisode = resumeEpisode
        ?: latest?.first?.let { done -> list.getOrNull(list.indexOfFirst { it.id == done.id } + 1) }
        ?: list.firstOrNull { !it.played && it.id !in watched }
        ?: list.firstOrNull()
    val seasons = remember(episodes) { list.mapNotNull { it.season }.distinct().sorted() }
    val shownSeason = season?.takeIf { it in seasons } ?: startEpisode?.season ?: seasons.firstOrNull()
    val shown = remember(episodes, shownSeason) { list.filter { shownSeason == null || it.season == shownSeason } }

    LaunchedEffect(item.id, episodes != null) {
        withFrameNanos { }
        val at = focusEpisodeId?.let { id -> shown.indexOfFirst { it.id == id } } ?: -1
        if (at >= 0) {
            // An episode below the fold is not composed, so scroll to it before asking for focus.
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == at }) listState.scrollToItem(at)
            withFrameNanos { }
            if (runCatching { episodeFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
        runCatching { primary.requestFocus() }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Tv.Bg)) {
        val narrow = maxWidth < 600.dp
        val side = gutter(narrow)
        Box(Modifier.align(Alignment.TopEnd).fillMaxHeight().width(maxWidth * 0.72f)) {
            AsyncImage(
                model = details.backdropUrl ?: details.posterUrl.takeIf { it.isNotBlank() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0.55f)
            )
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Tv.Bg, 0.55f to Tv.Bg.copy(alpha = 0.2f), 1f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Tv.Bg)))
        }

        val info: @Composable ColumnScope.() -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(if (isSeries) "Series" else "Movie")
                metaParts(details).filter { it != "Series" }.forEach { Text(it, style = TvType.Small, color = Tv.Ink2) }
                if (isSeries && seasons.isNotEmpty()) Text("${seasons.size} season${if (seasons.size == 1) "" else "s"}", style = TvType.Small, color = Tv.Ink2)
            }
            Text(details.name, style = TvType.Display, maxLines = 3, overflow = TextOverflow.Ellipsis)
            details.overview?.let { Text(it, style = TvType.Body, color = Tv.Ink2, maxLines = if (narrow) 4 else 6, overflow = TextOverflow.Ellipsis) }
            if (!isSeries && resumeMs > 0) {
                val total = item.runtimeMinutes?.let { it * 60_000L }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (total != null) ProgressBar(resumeMs.toFloat() / total, Modifier.width(160.dp), color = Tv.Accent)
                    Text("Stopped at ${formatPosition(resumeMs)}", style = TvType.Caption, color = Tv.Ink3)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    !isSeries && resumeMs > 0 -> {
                        PillButton("Resume from ${formatPosition(resumeMs)}", { onPlay(item, false) }, icon = TvIcons.Play, modifier = Modifier.focusRequester(primary))
                        PillButton("Start over", { onPlay(item, true) }, icon = TvIcons.Replay)
                    }
                    !isSeries -> PillButton("Play", { onPlay(item, true) }, icon = TvIcons.Play, modifier = Modifier.focusRequester(primary))
                    episodes == null -> PillButton("Loading episodes", {}, modifier = Modifier.focusRequester(primary))
                    startEpisode != null -> {
                        val label = episodeLabel(startEpisode)?.let { " $it" }.orEmpty()
                        PillButton(
                            (if (resumeEpisode != null) "Resume" else "Play") + label,
                            { onPlay(startEpisode, resumeEpisode == null) },
                            icon = TvIcons.Play,
                            modifier = Modifier.focusRequester(primary)
                        )
                    }
                    else -> PillButton("No episodes found", {}, modifier = Modifier.focusRequester(primary))
                }
            }
        }

        val episodesList: @Composable ColumnScope.() -> Unit = {
            if (seasons.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    seasons.forEach { s ->
                        Chip("Season $s", selected = s == shownSeason, onClick = {
                            if (s != shownSeason) {
                                onSeason(s)
                                scope.launch { listState.scrollToItem(0) }
                            }
                        })
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            LazyColumn(
                Modifier.weight(1f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                items(shown, key = { it.id }) { ep ->
                    EpisodeRow(
                        ep,
                        resumed = resumeEpisode?.id == ep.id,
                        watched = ep.played || ep.id in watched,
                        modifier = if (ep.id == focusEpisodeId) Modifier.focusRequester(episodeFocus) else Modifier,
                        onClick = { onPlay(ep, resumeEpisode?.id != ep.id) }
                    )
                }
            }
        }

        if (narrow) {
            Column(Modifier.fillMaxSize().padding(horizontal = side, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                info()
                if (isSeries) episodesList()
            }
        } else {
            Row(Modifier.fillMaxSize().padding(start = side, end = side, top = 44.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                Column(Modifier.width(430.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { info() }
                if (isSeries) {
                    Column(Modifier.weight(1f).fillMaxHeight()) { episodesList() }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: VodItem, resumed: Boolean, watched: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Focusable(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(112.dp, 63.dp).clip(RoundedCornerShape(7.dp))) {
                ArtPlaceholder("")
                AsyncImage(
                    model = ep.posterUrl.takeIf { it.isNotBlank() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (resumed && ep.progress != null) {
                    ProgressBar(ep.progress, Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(6.dp), color = Tv.Accent, height = 3.dp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull(ep.episode?.let { "$it." }, ep.name).joinToString(" "),
                    style = TvType.BodyStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val bits = listOfNotNull(
                    ep.runtimeMinutes?.let { minutesText(it.toLong()) },
                    when {
                        watched -> "Watched"
                        resumed -> "In progress"
                        else -> null
                    }
                )
                if (bits.isNotEmpty()) Text(bits.joinToString(" · "), style = TvType.Caption, color = ink3)
                ep.overview?.let { Text(it, style = TvType.Caption, color = ink3, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/**
 * Full-screen playback with the app's own controls. Any key shows them, and so does a tap on a
 * phone. OK pauses, left and right skip ten seconds, and Audio and captions picks language,
 * subtitles and picture size. Playback starts where it stopped last time, saves its place,
 * reports to Jellyfin, and plays the next episode by itself. One instance plays one title.
 */
@Composable
private fun VodPlayer(
    item: VodItem,
    startOver: Boolean,
    store: UserStateStore,
    jellyfin: JellyfinClient?,
    nextEpisode: VodItem?,
    onPlayNext: (VodItem) -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = (context as? ComponentActivity)?.lifecycle
    val player = remember { newPlayer(context) }
    var failed by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var tracks by remember { mutableStateOf(false) }
    var playingNow by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var lastKey by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    val latestNext by rememberUpdatedState(nextEpisode)
    val latestOnNext by rememberUpdatedState(onPlayNext)
    val latestExit by rememberUpdatedState(onExit)
    var pendingNext by remember { mutableStateOf<VodItem?>(null) }
    LaunchedEffect(pendingNext?.id) {
        val next = pendingNext ?: return@LaunchedEffect
        delay(10_000)
        if (pendingNext?.id == next.id) latestOnNext(next)
    }
    val root = remember { FocusRequester() }
    val seek = remember { FocusRequester() }
    val playNow = remember { FocusRequester() }

    fun savePosition() {
        val d = player.duration
        if (d > 0) store.saveResume(item.copy(runtimeMinutes = (d / 60_000L).toInt()), player.currentPosition, d)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failed = true
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playingNow = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    val next = latestNext
                    if (next != null) {
                        // The countdown takes over the screen, and its buttons take the remote.
                        tracks = false
                        controls = false
                        pendingNext = next
                    } else {
                        latestExit()
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            savePosition()
            jellyfin?.let { c ->
                val at = player.currentPosition
                Thread { runCatching { c.reportStopped(item.id, at) } }.start()
            }
            player.removeListener(listener)
            player.release()
        }
    }

    // Leaving the app (Home on the remote) pauses, saves the place and reports it. Coming back
    // shows the controls and stays paused. A pending next episode waits for the viewer instead.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    player.pause()
                    pendingNext = null
                    savePosition()
                    jellyfin?.let { c ->
                        val at = player.currentPosition
                        Thread { runCatching { c.reportProgress(item.id, at, true) } }.start()
                    }
                }
                Lifecycle.Event.ON_START -> {
                    controls = true
                    lastKey = System.currentTimeMillis()
                }
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    LaunchedEffect(item.streamUrl) {
        failed = false
        val start = if (startOver) 0L else store.resumePosition(item)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().build()
        player.setMediaItem(MediaItem.fromUri(item.streamUrl), start)
        player.prepare()
        // A title that starts while the app is in the background waits, paused.
        player.playWhenReady = lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false
        controls = true
        lastKey = System.currentTimeMillis()
        jellyfin?.let { c -> withContext(Dispatchers.IO) { runCatching { c.reportStart(item.id, start) } } }
    }

    // Every ten seconds: save the position here, and tell Jellyfin where playback is.
    LaunchedEffect(item.streamUrl) {
        while (true) {
            delay(10_000)
            if (player.isPlaying) {
                savePosition()
                jellyfin?.let { c ->
                    val at = player.currentPosition
                    withContext(Dispatchers.IO) { runCatching { c.reportProgress(item.id, at, false) } }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    // The controls hide after five seconds without a key, unless paused.
    LaunchedEffect(controls, lastKey, playingNow, tracks) {
        if (controls && playingNow && !tracks) {
            delay(5000)
            controls = false
        }
    }

    // One place decides where focus goes: the countdown, the seek bar, or the picture itself.
    LaunchedEffect(controls, tracks, pendingNext) {
        withFrameNanos { }
        runCatching {
            when {
                pendingNext != null -> playNow.requestFocus()
                tracks -> Unit
                controls -> seek.requestFocus()
                else -> root.requestFocus()
            }
        }
    }

    fun skip(ms: Long) {
        if (pendingNext != null) return
        val d = player.duration
        val target = (player.currentPosition + ms).coerceAtLeast(0L).let { if (d > 0) it.coerceAtMost(d - 1000) else it }
        player.seekTo(target)
        position = target
        lastKey = System.currentTimeMillis()
    }

    fun seekToFraction(fraction: Float) {
        val d = player.duration
        if (d <= 0 || pendingNext != null) return
        val target = (d * fraction.coerceIn(0f, 1f)).toLong().coerceIn(0L, (d - 1000).coerceAtLeast(0L))
        player.seekTo(target)
        position = target
        lastKey = System.currentTimeMillis()
    }

    fun resume() {
        // A title that has ended starts again from the beginning.
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    fun togglePause() {
        // While the next episode counts down, the one that ended stays ended.
        if (pendingNext != null) return
        lastKey = System.currentTimeMillis()
        if (player.isPlaying) player.pause() else resume()
    }

    BackHandler(enabled = !tracks && controls && pendingNext == null) { controls = false }
    BackHandler(enabled = !tracks && !controls && pendingNext == null) { onExit() }
    // During the countdown, Back leaves the player.
    BackHandler(enabled = pendingNext != null) { onExit() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                lastKey = System.currentTimeMillis()
                val code = ev.nativeKeyEvent.keyCode
                if (pendingNext != null) {
                    // The countdown's buttons take the arrows and OK. Media keys do nothing, so the
                    // episode that ended does not start again.
                    return@onPreviewKeyEvent when (code) {
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND -> true
                        else -> false
                    }
                }
                when (code) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePause(); controls = true; return@onPreviewKeyEvent true }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> { resume(); return@onPreviewKeyEvent true }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); controls = true; return@onPreviewKeyEvent true }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { skip(30_000); controls = true; return@onPreviewKeyEvent true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { skip(-10_000); controls = true; return@onPreviewKeyEvent true }
                }
                if (tracks || controls) return@onPreviewKeyEvent false
                when (code) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> togglePause()
                    KeyEvent.KEYCODE_DPAD_LEFT -> skip(-10_000)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> skip(10_000)
                    KeyEvent.KEYCODE_BACK -> return@onPreviewKeyEvent false
                }
                controls = true
                true
            }
            .focusRequester(root)
            .focusable()
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                }.also { it.player = player }
            },
            update = { it.resizeMode = resizeMode },
            modifier = Modifier.fillMaxSize()
        )

        // Touch: a tap on the picture shows or hides the controls. Taps on a button go to the button.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        if (!tracks && pendingNext == null) {
                            controls = !controls
                            lastKey = System.currentTimeMillis()
                        }
                    })
                }
        )

        if (controls && !tracks) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xC0000000), 0.26f to Color.Transparent, 0.58f to Color.Transparent, 1f to Color(0xD9000000))))
            Column(Modifier.align(Alignment.TopStart).padding(start = 40.dp, top = 28.dp, end = 40.dp)) {
                Text(item.seriesName ?: item.name, style = TvType.Headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = if (item.type == "Episode") listOfNotNull(episodeLabel(item), item.name.takeIf { item.seriesName != null }).joinToString(" · ") else item.year?.toString()
                if (!sub.isNullOrBlank()) Text(sub, style = TvType.Small, color = Tv.Ink2)
            }
            if (!playingNow) {
                Box(
                    Modifier.align(Alignment.Center).size(76.dp).clip(CircleShape).background(Color(0x8C000000)),
                    contentAlignment = Alignment.Center
                ) { Icon(TvIcons.Pause, contentDescription = "Paused", modifier = Modifier.size(30.dp)) }
            }
            Column(Modifier.align(Alignment.BottomStart).trapFocus().fillMaxWidth().padding(horizontal = 40.dp, vertical = 24.dp)) {
                SeekBar(
                    fraction = if (duration > 0) position.toFloat() / duration else 0f,
                    modifier = Modifier.focusRequester(seek),
                    onTogglePause = { togglePause() },
                    onSkip = { skip(it) },
                    onSeekTo = { seekToFraction(it) }
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text(formatPosition(position), style = TvType.Caption, color = Tv.Ink2)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (playingNow) "◀ ▶ skip 10 seconds · OK pauses" else "Paused · OK plays",
                        style = TvType.Caption,
                        color = Tv.Ink2
                    )
                    Spacer(Modifier.weight(1f))
                    Text(if (duration > 0) formatPosition(duration) else "", style = TvType.Caption, color = Tv.Ink2)
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(if (playingNow) "Pause" else "Play", { togglePause() }, icon = if (playingNow) TvIcons.Pause else TvIcons.Play)
                    PillButton("Audio and captions", { tracks = true }, icon = TvIcons.Captions)
                    if (nextEpisode != null) {
                        PillButton("Next episode", { onPlayNext(nextEpisode) }, icon = TvIcons.Next)
                    }
                }
            }
        }

        if (tracks) {
            TrackPanel(
                player = player,
                resizeMode = resizeMode,
                onResize = { resizeMode = it },
                onAudioLanguage = { TrackMemory.saveAudio(context, player, it) },
                onCaptionsLanguage = { TrackMemory.saveCaptions(context, player, it) },
                onClose = { tracks = false },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }

        pendingNext?.let { next ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .trapFocus()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xE6101218))
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Next: ${next.name}", style = TvType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Starts in a few seconds", style = TvType.Small, color = Tv.Ink2)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Play now", { latestOnNext(next) }, icon = TvIcons.Play, modifier = Modifier.focusRequester(playNow))
                    // Cancel stops the countdown only. The player stays, with its controls showing.
                    PillButton("Cancel", {
                        pendingNext = null
                        controls = true
                        lastKey = System.currentTimeMillis()
                    })
                }
            }
        }

        if (failed) {
            Box(Modifier.fillMaxSize().background(Tv.Scrim), contentAlignment = Alignment.Center) {
                EmptyState(
                    "This title will not play",
                    "The file may use a format this TV cannot decode. Press Back to return."
                )
            }
        }
    }
}

/** The playback position. When focused, left and right skip ten seconds and OK pauses. A tap jumps there. */
@Composable
private fun SeekBar(
    fraction: Float,
    modifier: Modifier,
    onTogglePause: () -> Unit,
    onSkip: (Long) -> Unit,
    onSeekTo: (Float) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val latestSeekTo by rememberUpdatedState(onSeekTo)
    Focusable(
        onClick = onTogglePause,
        modifier = modifier
            .fillMaxWidth()
            .height(22.dp)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { onSkip(-10_000); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { onSkip(10_000); true }
                    else -> false
                }
            },
        shape = RoundedCornerShape(11.dp),
        look = FocusLook.Ring,
        focusScale = 1f,
        onFocus = { focused = it },
        contentAlignment = Alignment.CenterStart
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset -> if (size.width > 0) latestSeekTo(offset.x / size.width) }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            val w = maxWidth
            ProgressBar(fraction, Modifier.fillMaxWidth(), color = Tv.Accent, height = if (focused) 6.dp else 4.dp)
            if (focused) {
                Box(Modifier.offset(x = w * fraction.coerceIn(0f, 1f) - 7.dp).size(14.dp).clip(CircleShape).background(Color.White))
            }
        }
    }
}
