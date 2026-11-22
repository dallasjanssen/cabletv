package com.dallas.cabletv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.graphics.Color
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
import com.dallas.cabletv.data.JellyfinClient
import com.dallas.cabletv.data.Settings
import com.dallas.cabletv.data.UserStateStore
import com.dallas.cabletv.data.VodItem
import com.dallas.cabletv.data.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OnDemandScreen(settings: Settings, onExit: () -> Unit) {
    if (settings.hasJellyfin || settings.hasXtream) {
        JellyfinBrowser(settings, onExit)
    } else {
        Column(Modifier.fillMaxSize().scanlines()) {
            CableHeader(title = "ON DEMAND", onBack = onExit)
            EmptyState(
                title = "No on-demand source yet",
                body = "Add a Jellyfin server or use a provider login in Settings.",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** Shows Jellyfin and provider titles side by side. Either source can be missing. */
@Composable
private fun JellyfinBrowser(settings: Settings, onExit: () -> Unit) {
    val client = remember(settings) {
        JellyfinClient(settings.jellyfinServer, settings.jellyfinToken, settings.jellyfinUserId)
    }
    val xtream = remember(settings) {
        if (settings.hasXtream) {
            XtreamClient(settings.xtreamServer, settings.xtreamUser, settings.xtreamPass)
        } else {
            null
        }
    }
    val context = LocalContext.current
    val userState = remember { UserStateStore(context) }
    val scope = rememberCoroutineScope()

    var resumeItems by remember { mutableStateOf(userState.continueWatching()) }
    var resume by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    var recent by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    var providerMovies by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    var providerSeries by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var series by remember { mutableStateOf<VodItem?>(null) }
    var episodes by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    var playing by remember { mutableStateOf<VodItem?>(null) }

    // Where focus goes when the library comes back: the card last picked, and the episode last played.
    var focusKey by remember { mutableStateOf<String?>(null) }
    var lastEpisodeId by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    // Scroll positions live here, so they survive a trip to playback and back.
    val libraryState = rememberLazyListState()
    val rowStates = remember { mutableMapOf<String, LazyListState>() }

    // Each source loads on its own, so a failure in one still shows the other.
    LaunchedEffect(settings) {
        loading = true
        loadError = null
        if (settings.hasJellyfin) {
            withContext(Dispatchers.IO) {
                runCatching { client.continueWatching() to client.recentlyAdded() }
            }
                .onSuccess { (r, n) ->
                    resume = r
                    recent = n
                }
                .onFailure { loadError = it.message ?: "Could not reach Jellyfin" }
        }
        if (xtream != null) {
            withContext(Dispatchers.IO) {
                runCatching { xtream.vodMovies() to xtream.vodSeries() }
            }
                .onSuccess { (m, s) ->
                    providerMovies = m
                    providerSeries = s
                }
                .onFailure { loadError = loadError ?: (it.message ?: "Could not reach your provider") }
        }
        loading = false
    }

    // Back from playback refreshes the "left off" row with the new positions.
    LaunchedEffect(playing) {
        if (playing == null) resumeItems = userState.continueWatching()
    }

    // Back steps out one level at a time: player, then episodes, then the library.
    BackHandler(enabled = playing != null) { playing = null }
    BackHandler(enabled = series != null && playing == null) { series = null }

    val nowPlaying = playing
    val openSeries = series
    // Playback fills the whole screen with no header, like live TV.
    // Every other view keeps the header, and its back button steps out one level.
    when {
        nowPlaying != null -> VodPlayer(nowPlaying, userState)
        openSeries != null -> Column(Modifier.fillMaxSize().scanlines()) {
            CableHeader(title = openSeries.name.uppercase(), onBack = { series = null })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                SeriesDetail(
                    series = openSeries,
                    episodes = episodes,
                    focusEpisodeId = lastEpisodeId,
                    onPlay = {
                        lastEpisodeId = it.id
                        playing = it
                    },
                    onBack = { series = null }
                )
            }
        }
        else -> Column(Modifier.fillMaxSize().scanlines()) {
            CableHeader(title = "ON DEMAND", onBack = onExit)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Library(
                    loading = loading,
                    loadError = loadError,
                    leftOff = resumeItems.map { it.first },
                    resume = resume,
                    recent = recent,
                    providerMovies = providerMovies,
                    providerSeries = providerSeries,
                    focusKey = focusKey,
                    focusRequester = focusRequester,
                    listState = libraryState,
                    rowStates = rowStates,
                    onSelect = { key, item ->
                        focusKey = key
                        if (item.type == "Series") {
                            scope.launch {
                                val eps = withContext(Dispatchers.IO) {
                                    runCatching {
                                        if (item.source == "xtream") {
                                            xtream?.seriesEpisodes(item.id).orEmpty()
                                        } else {
                                            client.episodes(item.id)
                                        }
                                    }.getOrDefault(emptyList())
                                }
                                episodes = eps
                                series = item
                            }
                        } else {
                            playing = item
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun Library(
    loading: Boolean,
    loadError: String?,
    leftOff: List<VodItem>,
    resume: List<VodItem>,
    recent: List<VodItem>,
    providerMovies: List<VodItem>,
    providerSeries: List<VodItem>,
    focusKey: String?,
    focusRequester: FocusRequester,
    listState: LazyListState,
    rowStates: MutableMap<String, LazyListState>,
    onSelect: (String, VodItem) -> Unit
) {
    val rows = listOfNotNull(
        leftOff.takeIf { it.isNotEmpty() }?.let { "Pick up where you left off" to it },
        resume.takeIf { it.isNotEmpty() }?.let { "Continue watching" to it },
        recent.takeIf { it.isNotEmpty() }?.let { "Recently added" to it },
        providerMovies.takeIf { it.isNotEmpty() }?.let { "New movies" to it },
        providerSeries.takeIf { it.isNotEmpty() }?.let { "New series" to it }
    )

    // The card that should hold focus: the last one picked, otherwise the first card on screen.
    val target = focusKey ?: rows.firstOrNull()?.let { (title, list) -> "$title|${list.first().id}" }
    LaunchedEffect(target, loading) {
        if (!loading && target != null) {
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 48.dp, end = 48.dp, top = 32.dp)
    ) {
        when {
            loading -> EmptyState(
                title = "Loading your library...",
                modifier = Modifier.weight(1f)
            )
            rows.isEmpty() && loadError != null -> EmptyState(
                title = "Couldn't reach your sources",
                body = loadError,
                modifier = Modifier.weight(1f)
            )
            rows.isEmpty() -> EmptyState(
                title = "Nothing here yet",
                body = "Add media to Jellyfin or your provider and it will show up here.",
                modifier = Modifier.weight(1f)
            )
            else -> LazyColumn(
                Modifier.weight(1f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(36.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp)
            ) {
                if (loadError != null) {
                    item {
                        Text(
                            text = loadError,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Cable.Accent
                        )
                    }
                }
                items(rows, key = { it.first }) { (title, list) ->
                    PosterRow(
                        title = title,
                        list = list,
                        target = target,
                        focusRequester = focusRequester,
                        rowState = rowStates.getOrPut(title) { LazyListState() },
                        onSelect = { vod -> onSelect("$title|${vod.id}", vod) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PosterRow(
    title: String,
    list: List<VodItem>,
    target: String?,
    focusRequester: FocusRequester,
    rowState: LazyListState,
    onSelect: (VodItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = Cable.Led
        )
        LazyRow(
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 10.dp)
        ) {
            items(list, key = { it.id }) { vod ->
                val holdsFocus = target == "$title|${vod.id}"
                PosterCard(
                    vod = vod,
                    modifier = if (holdsFocus) Modifier.focusRequester(focusRequester) else Modifier,
                    onClick = { onSelect(vod) }
                )
            }
        }
    }
}

@Composable
private fun PosterCard(vod: VodItem, modifier: Modifier, onClick: () -> Unit) {
    Column(
        Modifier.width(184.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        FocusSurface(
            onClick = onClick,
            modifier = modifier
                .width(184.dp)
                .height(276.dp),
            shape = RoundedCornerShape(8.dp),
            focusScale = 1.05f
        ) {
            AsyncImage(
                model = vod.posterUrl,
                contentDescription = vod.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Cable.SurfaceHi)
            )
        }
        Text(
            text = vod.name,
            style = MaterialTheme.typography.bodyLarge,
            color = Cable.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        vod.year?.let {
            Text(
                text = it.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = Cable.Ink3
            )
        }
    }
}

@Composable
private fun SeriesDetail(
    series: VodItem,
    episodes: List<VodItem>,
    focusEpisodeId: String?,
    onPlay: (VodItem) -> Unit,
    onBack: () -> Unit
) {
    val firstEpisode = remember { FocusRequester() }
    // Focus returns to the episode last played, or the first one for a new series.
    val targetId = focusEpisodeId?.takeIf { id -> episodes.any { it.id == id } }
        ?: episodes.firstOrNull()?.id
    LaunchedEffect(targetId) {
        if (targetId != null) {
            withFrameNanos { }
            runCatching { firstEpisode.requestFocus() }
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp)
    ) {
        Column(
            Modifier
                .width(280.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            CableButton(label = "BACK TO LIBRARY", onClick = onBack)
            // The poster takes whatever height is left, so it never pushes the year off screen.
            AsyncImage(
                model = series.posterUrl,
                contentDescription = series.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(280.dp)
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Cable.SurfaceHi)
            )
            series.year?.let {
                Text(
                    text = it.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Cable.Ink3
                )
            }
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            series.overview?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Cable.Ink2,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "EPISODES",
                style = MaterialTheme.typography.titleLarge,
                color = Cable.Accent
            )
            if (episodes.isEmpty()) {
                EmptyState(
                    title = "No episodes found",
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)
                ) {
                    items(episodes, key = { it.id }) { ep ->
                        EpisodeRow(
                            episode = ep,
                            focusRequester = if (ep.id == targetId) firstEpisode else null
                        ) { onPlay(ep) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: VodItem,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    val rowModifier = if (focusRequester != null) {
        Modifier.fillMaxWidth().focusRequester(focusRequester)
    } else {
        Modifier.fillMaxWidth()
    }
    FocusSurface(
        onClick = onClick,
        modifier = rowModifier,
        shape = RoundedCornerShape(8.dp),
        focusScale = 1f
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Cable.Surface)
                .padding(horizontal = 28.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = episode.name,
                style = MaterialTheme.typography.titleMedium,
                color = Cable.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "PLAY",
                style = MaterialTheme.typography.labelLarge,
                color = Cable.Led
            )
        }
    }
}

/**
 * Full-screen playback. It starts where the viewer stopped last time, saves the
 * position as it plays, and shows a message if the stream will not start.
 */
@Composable
private fun VodPlayer(item: VodItem, store: UserStateStore) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    var failed by remember { mutableStateOf(false) }

    // The duration is unknown until the stream is ready, so nothing is saved before then.
    fun savePosition() {
        val duration = player.duration
        if (duration > 0) store.saveResume(item, player.currentPosition, duration)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                failed = true
            }
        }
        player.addListener(listener)
        onDispose {
            savePosition()
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(item.streamUrl) {
        failed = false
        val start = store.resumePosition(item)
        player.setMediaItem(MediaItem.fromUri(item.streamUrl), start)
        player.prepare()
        player.playWhenReady = true
    }

    // Save every ten seconds, so a crash or a power cut loses very little.
    LaunchedEffect(item.streamUrl) {
        while (true) {
            delay(10_000)
            if (player.isPlaying) savePosition()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    requestFocus()
                }.also { it.player = player }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (failed) {
            EmptyState(
                title = "This title will not play",
                body = "The stream may use a format this TV cannot decode. Press BACK to return.",
                modifier = Modifier
                    .fillMaxSize()
                    .background(Cable.Canvas.copy(alpha = 0.8f))
            )
        }
    }
}
