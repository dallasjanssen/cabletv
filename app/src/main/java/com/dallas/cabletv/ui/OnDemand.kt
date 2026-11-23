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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.dallas.cabletv.data.JellyfinClient
import com.dallas.cabletv.data.Settings
import com.dallas.cabletv.data.UserStateStore
import com.dallas.cabletv.data.VodItem
import com.dallas.cabletv.data.XtreamClient
import com.dallas.cabletv.data.newPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The season number in a provider episode name such as "S2 E5  Title", or null. */
private fun seasonNumber(name: String): Int? =
    Regex("^S(\\d+) E").find(name)?.groupValues?.get(1)?.toIntOrNull()

@Composable
fun OnDemandScreen(
    settings: Settings,
    profileId: String,
    pendingVod: VodItem?,
    onPendingConsumed: () -> Unit,
    onExit: () -> Unit
) {
    if (settings.hasJellyfin || settings.hasXtream) {
        JellyfinBrowser(settings, profileId, pendingVod, onPendingConsumed, onExit)
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
private fun JellyfinBrowser(
    settings: Settings,
    profileId: String,
    pendingVod: VodItem?,
    onPendingConsumed: () -> Unit,
    onExit: () -> Unit
) {
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
    val userState = remember(profileId) { UserStateStore(context, profileId) }
    val scope = rememberCoroutineScope()

    var resumeItems by remember(userState) { mutableStateOf(userState.continueWatching()) }
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

    // Opens a title: a series loads its episodes, anything else starts playing.
    fun openItem(item: VodItem) {
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

    // A title chosen in search opens here.
    LaunchedEffect(pendingVod) {
        val pick = pendingVod ?: return@LaunchedEffect
        openItem(pick)
        onPendingConsumed()
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
    // The episode after the one playing, so the next one starts by itself.
    val nextEpisode = nowPlaying?.let { p ->
        val at = episodes.indexOfFirst { it.id == p.id }
        if (at >= 0) episodes.getOrNull(at + 1) else null
    }
    // Playback fills the whole screen with no header, like live TV.
    // Every other view keeps the header, and its back button steps out one level.
    when {
        nowPlaying != null -> VodPlayer(
            item = nowPlaying,
            store = userState,
            jellyfin = if (nowPlaying.source == "jellyfin") client else null,
            nextEpisode = nextEpisode,
            onPlayNext = {
                lastEpisodeId = it.id
                playing = it
            }
        )
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
                        openItem(item)
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
            if (vod.played) {
                Pill(
                    text = "WATCHED",
                    color = Cable.Led,
                    textColor = Cable.Canvas,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }
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
    // Provider series list their episodes by season, so a season row appears for them.
    val seasons = episodes.mapNotNull { seasonNumber(it.name) }.distinct().sorted()
    var season by remember(series.id) { mutableStateOf(seasons.firstOrNull()) }
    val shown = if (season == null) episodes else episodes.filter { seasonNumber(it.name) == season }

    val firstEpisode = remember { FocusRequester() }
    // Focus returns to the episode last played, or the first one shown.
    val targetId = focusEpisodeId?.takeIf { id -> shown.any { it.id == id } }
        ?: shown.firstOrNull()?.id
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
            if (seasons.size > 1) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    items(seasons, key = { it }) { s ->
                        SeasonChip(label = "SEASON $s", selected = season == s) { season = s }
                    }
                }
            }
            if (shown.isEmpty()) {
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
                    items(shown, key = { it.id }) { ep ->
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
private fun SeasonChip(label: String, selected: Boolean, onClick: () -> Unit) {
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
            if (episode.played) {
                Spacer(Modifier.width(16.dp))
                Pill(text = "WATCHED", color = Cable.Led, textColor = Cable.Canvas)
            }
            Spacer(Modifier.width(16.dp))
            Text(
                text = "PLAY",
                style = MaterialTheme.typography.labelLarge,
                color = Cable.Led
            )
        }
    }
}

/** Label for a track: its title, its language, or its position. */
private fun trackLabel(format: androidx.media3.common.Format, index: Int): String =
    format.label ?: format.language?.uppercase() ?: "Track ${index + 1}"

/**
 * Full-screen playback. It starts where the viewer stopped last time, saves the position,
 * reports progress to Jellyfin, plays the next episode by itself, and shows a menu for
 * audio, subtitles and picture size when MENU is pressed.
 */
@Composable
private fun VodPlayer(
    item: VodItem,
    store: UserStateStore,
    jellyfin: JellyfinClient?,
    nextEpisode: VodItem?,
    onPlayNext: (VodItem) -> Unit
) {
    val context = LocalContext.current
    val player = remember { newPlayer(context) }
    var failed by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    val latestNext by rememberUpdatedState(nextEpisode)
    val latestOnNext by rememberUpdatedState(onPlayNext)
    val menuFocus = remember { FocusRequester() }
    BackHandler(enabled = showMenu) { showMenu = false }

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

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    val next = latestNext
                    if (next != null) latestOnNext(next)
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
        val start = store.resumePosition(item)
        player.setMediaItem(MediaItem.fromUri(item.streamUrl), start)
        player.prepare()
        player.playWhenReady = true
        jellyfin?.let { c -> runCatching { withContext(Dispatchers.IO) { c.reportStart(item.id, start) } } }
    }

    // Every ten seconds: save the position on the TV, and tell Jellyfin where playback is.
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

    // Audio, subtitle and picture menu, read from what the stream actually offers.
    val audioTracks = remember(showMenu) { if (showMenu) tracksOf(player, C.TRACK_TYPE_AUDIO) else emptyList() }
    val subtitleTracks = remember(showMenu) { if (showMenu) tracksOf(player, C.TRACK_TYPE_TEXT) else emptyList() }
    LaunchedEffect(showMenu) {
        if (showMenu) {
            withFrameNanos { }
            runCatching { menuFocus.requestFocus() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MENU && !showMenu) {
                    showMenu = true
                    true
                } else {
                    false
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    requestFocus()
                }.also { it.player = player }
            },
            update = { view -> view.resizeMode = resizeMode },
            modifier = Modifier.fillMaxSize()
        )

        if (showMenu) {
            TrackMenu(
                audio = audioTracks,
                subtitles = subtitleTracks,
                resizeMode = resizeMode,
                focusRequester = menuFocus,
                onAudio = { group, index ->
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                        .build()
                },
                onSubtitle = { group, index ->
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                        .build()
                },
                onSubtitlesOff = {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                },
                onResize = { resizeMode = it },
                onClose = { showMenu = false }
            )
        }

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

/** One selectable track in the player menu. */
private class TrackChoice(val group: androidx.media3.common.Tracks.Group, val index: Int, val label: String)

private fun tracksOf(player: Player, type: Int): List<TrackChoice> {
    val out = mutableListOf<TrackChoice>()
    player.currentTracks.groups.forEach { group ->
        if (group.type == type) {
            for (i in 0 until group.length) {
                if (group.isTrackSupported(i)) {
                    out += TrackChoice(group, i, trackLabel(group.getTrackFormat(i), out.size))
                }
            }
        }
    }
    return out
}

@Composable
private fun TrackMenu(
    audio: List<TrackChoice>,
    subtitles: List<TrackChoice>,
    resizeMode: Int,
    focusRequester: FocusRequester,
    onAudio: (androidx.media3.common.Tracks.Group, Int) -> Unit,
    onSubtitle: (androidx.media3.common.Tracks.Group, Int) -> Unit,
    onSubtitlesOff: () -> Unit,
    onResize: (Int) -> Unit,
    onClose: () -> Unit
) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(560.dp)
            .background(Cable.Canvas.copy(alpha = 0.94f))
            .padding(horizontal = 28.dp, vertical = 32.dp)
    ) {
        Text(
            text = "PICTURE AND SOUND      BACK  Close",
            style = MaterialTheme.typography.labelMedium,
            color = Cable.Accent
        )
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("PICTURE SIZE", style = MaterialTheme.typography.labelMedium, color = Cable.Ink3) }
            item {
                TrackRow("Fit to screen", selected = resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT,
                    modifier = Modifier.focusRequester(focusRequester)) {
                    onResize(AspectRatioFrameLayout.RESIZE_MODE_FIT)
                }
            }
            item {
                TrackRow("Fill screen", selected = resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FILL,
                    modifier = Modifier) {
                    onResize(AspectRatioFrameLayout.RESIZE_MODE_FILL)
                }
            }
            item {
                TrackRow("Zoom to fill", selected = resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                    modifier = Modifier) {
                    onResize(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
            item { Text("AUDIO", style = MaterialTheme.typography.labelMedium, color = Cable.Ink3) }
            if (audio.isEmpty()) {
                item { Text("Only one audio track", style = MaterialTheme.typography.bodyMedium, color = Cable.Ink2) }
            }
            items(audio) { t -> TrackRow(t.label, selected = false, modifier = Modifier) { onAudio(t.group, t.index) } }
            item { Spacer(Modifier.height(12.dp)) }
            item { Text("SUBTITLES", style = MaterialTheme.typography.labelMedium, color = Cable.Ink3) }
            item { TrackRow("Off", selected = false, modifier = Modifier) { onSubtitlesOff() } }
            items(subtitles) { t -> TrackRow(t.label, selected = false, modifier = Modifier) { onSubtitle(t.group, t.index) } }
        }
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        focusScale = 1f
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) Cable.SurfaceHi else Cable.Surface)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = Cable.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Text(text = "ON", style = MaterialTheme.typography.labelLarge, color = Cable.Led)
            }
        }
    }
}
