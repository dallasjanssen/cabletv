package com.dallas.cabletv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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

private fun libraryRows(shelves: VodShelves): List<Pair<String, List<VodItem>>> {
    fun grouped(title: String, items: List<VodItem>): List<Pair<String, List<VodItem>>> {
        if (items.isEmpty()) return emptyList()
        val groups = items.groupBy { it.category.ifBlank { title } }
        return if (groups.size <= 1) {
            listOf(title to items)
        } else {
            groups.map { (name, list) ->
                val label = if (name.equals(title, true)) title else "$title · $name"
                label to list
            }
        }
    }
    return listOfNotNull(
        shelves.continueWatching.takeIf { it.isNotEmpty() }?.let { "Continue watching" to it },
        shelves.recent.takeIf { it.isNotEmpty() }?.let { "Recently added" to it }
    ) + grouped("Movies", shelves.movies) + grouped("Shows", shelves.series)
}

/**
 * Movies and shows from Jellyfin and the TV provider. Picking a title opens its details page,
 * which plays, resumes, or lists the episodes. Back steps out one level at a time.
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
    jellyfinUserId: String = ""
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
    var playing by remember { mutableStateOf<VodItem?>(null) }
    var startOver by remember { mutableStateOf(false) }
    var lastPlayedId by remember { mutableStateOf<String?>(null) }
    var libraryFocusKey by remember { mutableStateOf<String?>(null) }
    var resumeTick by remember { mutableIntStateOf(0) }

    fun open(item: VodItem, fromElsewhere: Boolean) {
        openedFromElsewhere = fromElsewhere
        // A half-watched episode from a resume row plays straight away; everything else opens its page.
        if (item.type == "Episode") {
            detail = null
            startOver = false
            playing = item
            return
        }
        detail = item
        episodes = null
        lastPlayedId = null
    }

    LaunchedEffect(pendingVod) {
        val pick = pendingVod ?: return@LaunchedEffect
        open(pick, fromElsewhere = true)
        onPendingConsumed()
    }

    // Load a show's episodes when its page opens.
    LaunchedEffect(detail?.id) {
        val d = detail ?: return@LaunchedEffect
        if (d.type != "Series") return@LaunchedEffect
        episodes = withContext(Dispatchers.IO) {
            runCatching {
                if (d.source == "xtream") xtream?.seriesEpisodes(d.id).orEmpty() else jellyfin?.episodes(d.id).orEmpty()
            }.getOrDefault(emptyList())
        }
    }

    val nowPlaying = playing
    val openDetail = detail
    when {
        nowPlaying != null -> {
            val list = episodes.orEmpty()
            val at = list.indexOfFirst { it.id == nowPlaying.id }
            VodPlayer(
                item = nowPlaying,
                startOver = startOver,
                store = userState,
                jellyfin = if (nowPlaying.source == "jellyfin") jellyfin else null,
                nextEpisode = if (at >= 0) list.getOrNull(at + 1) else null,
                onPlayNext = {
                    lastPlayedId = it.id
                    startOver = true
                    playing = it
                },
                onExit = {
                    playing = null
                    resumeTick++
                    onPlaybackEnded()
                    if (openDetail == null && openedFromElsewhere) onLeaveDetail()
                }
            )
        }
        openDetail != null -> {
            BackHandler {
                detail = null
                if (openedFromElsewhere) onLeaveDetail()
            }
            DetailScreen(
                item = openDetail,
                xtream = xtream,
                episodes = episodes,
                store = userState,
                resumeTick = resumeTick,
                focusEpisodeId = lastPlayedId,
                onPlay = { item, fromStart ->
                    if (item.type == "Episode") lastPlayedId = item.id
                    startOver = fromStart
                    playing = item
                }
            )
        }
        else -> Library(
            nav = nav,
            shelves = shelves,
            hasSource = settings.hasJellyfin || settings.hasXtream,
            focusKey = libraryFocusKey,
            onOpen = { key, item ->
                libraryFocusKey = key
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
    focusKey: String?,
    onOpen: (String, VodItem) -> Unit
) {
    val rows = libraryRows(shelves)
    var focused by remember { mutableStateOf(rows.firstOrNull()?.second?.firstOrNull()) }
    val target = focusKey ?: rows.firstOrNull()?.let { (t, list) -> "$t|${keyOf(list.first())}" }
    val requester = remember { FocusRequester() }
    LaunchedEffect(target, shelves.loading) {
        if (target != null && !shelves.loading) {
            withFrameNanos { }
            runCatching { requester.requestFocus() }
        }
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
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(rows, key = { it.first }) { (title, list) ->
                            Shelf(title, side) {
                                items(list, key = { keyOf(it) }) { item ->
                                    val key = "$title|${keyOf(item)}"
                                    val mod = if (key == target) Modifier.focusRequester(requester) else Modifier
                                    if (title == "Continue watching") {
                                        ResumeCard(item, onClick = { onOpen(key, item) }, modifier = mod, onFocus = { if (it) focused = item })
                                    } else {
                                        PosterCard(item, onClick = { onOpen(key, item) }, modifier = mod, onFocus = { if (it) focused = item })
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
    val isSeries = item.type == "Series"
    val resumeMs = remember(item.id, resumeTick) { if (isSeries) 0L else store.resumePosition(item) }

    // For a show, Resume picks the episode watched most recently, otherwise the first unwatched one.
    val resumeEpisode = remember(episodes, resumeTick) {
        val list = episodes.orEmpty()
        val recent = store.continueWatching().map { keyOf(it.first) }
        recent.firstNotNullOfOrNull { k -> list.firstOrNull { keyOf(it) == k } }
    }
    val startEpisode = resumeEpisode ?: episodes.orEmpty().let { list -> list.firstOrNull { !it.played } ?: list.firstOrNull() }
    val seasons = remember(episodes) { episodes.orEmpty().mapNotNull { it.season }.distinct().sorted() }
    var season by remember(item.id, seasons) { mutableStateOf(startEpisode?.season ?: seasons.firstOrNull()) }
    val shown = remember(episodes, season) { episodes.orEmpty().filter { season == null || it.season == season } }

    LaunchedEffect(item.id, episodes != null) {
        withFrameNanos { }
        if (focusEpisodeId != null && shown.any { it.id == focusEpisodeId }) {
            runCatching { episodeFocus.requestFocus() }
        } else {
            runCatching { primary.requestFocus() }
        }
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
                    seasons.forEach { s -> Chip("Season $s", selected = s == season, onClick = { season = s }) }
                }
                Spacer(Modifier.height(8.dp))
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
                items(shown, key = { it.id }) { ep ->
                    EpisodeRow(
                        ep,
                        resumed = resumeEpisode?.id == ep.id,
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
private fun EpisodeRow(ep: VodItem, resumed: Boolean, modifier: Modifier, onClick: () -> Unit) {
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
                        ep.played -> "Watched"
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
 * Full-screen playback with the app's own controls. Any key shows them. OK pauses, left and right
 * skip ten seconds, and Audio and captions picks language, subtitles and picture size. Playback
 * starts where it stopped last time, saves its place, reports to Jellyfin, and plays the next
 * episode by itself.
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
                    if (next != null) pendingNext = next else latestExit()
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

    LaunchedEffect(item.streamUrl) {
        failed = false
        val start = if (startOver) 0L else store.resumePosition(item)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().build()
        player.setMediaItem(MediaItem.fromUri(item.streamUrl), start)
        player.prepare()
        player.playWhenReady = true
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

    LaunchedEffect(controls, tracks) {
        withFrameNanos { }
        runCatching { if (controls && !tracks) seek.requestFocus() else if (!controls) root.requestFocus() }
    }

    fun skip(ms: Long) {
        val d = player.duration
        val target = (player.currentPosition + ms).coerceAtLeast(0L).let { if (d > 0) it.coerceAtMost(d - 1000) else it }
        player.seekTo(target)
        position = target
    }

    fun togglePause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    BackHandler(enabled = !tracks && controls) { controls = false }
    BackHandler(enabled = !tracks && !controls) { onExit() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                lastKey = System.currentTimeMillis()
                when (ev.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePause(); controls = true; return@onPreviewKeyEvent true }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); return@onPreviewKeyEvent true }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); controls = true; return@onPreviewKeyEvent true }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { skip(30_000); controls = true; return@onPreviewKeyEvent true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { skip(-10_000); controls = true; return@onPreviewKeyEvent true }
                }
                if (tracks || controls) return@onPreviewKeyEvent false
                when (ev.nativeKeyEvent.keyCode) {
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
                    onSkip = { skip(it) }
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
                onCaptions = { enabled, language -> TrackMemory.saveCaptions(context, player, enabled, language) },
                onClose = { tracks = false },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }

        pendingNext?.let { next ->
            Column(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Color(0xE6101218)).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Next: ${next.name}", style = TvType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Starts in a few seconds", style = TvType.Small, color = Tv.Ink2)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Play now", { latestOnNext(next) }, icon = TvIcons.Play)
                    PillButton("Cancel", {
                        pendingNext = null
                        latestExit()
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

/** The playback position. When focused, left and right skip ten seconds and OK pauses. */
@Composable
private fun SeekBar(fraction: Float, modifier: Modifier, onTogglePause: () -> Unit, onSkip: (Long) -> Unit) {
    var focused by remember { mutableStateOf(false) }
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
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            val w = maxWidth
            ProgressBar(fraction, Modifier.fillMaxWidth(), color = Tv.Accent, height = if (focused) 6.dp else 4.dp)
            if (focused) {
                Box(Modifier.offset(x = w * fraction.coerceIn(0f, 1f) - 7.dp).size(14.dp).clip(CircleShape).background(Color.White))
            }
        }
    }
}
