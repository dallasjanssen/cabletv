package com.dallas.cabletv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.dallas.cabletv.data.JellyfinClient
import com.dallas.cabletv.data.Settings
import com.dallas.cabletv.data.VodItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OnDemandScreen(settings: Settings) {
    if (settings.hasJellyfin) {
        JellyfinBrowser(settings)
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 40.dp)
        ) {
            PageHeader(
                title = "On demand",
                subtitle = "Movies and shows from your Jellyfin server"
            )
            EmptyState(
                title = "Jellyfin is not connected",
                body = "Add your server in Settings to browse your library.",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun JellyfinBrowser(settings: Settings) {
    val client = remember(settings) {
        JellyfinClient(settings.jellyfinServer, settings.jellyfinToken, settings.jellyfinUserId)
    }
    val scope = rememberCoroutineScope()

    var resume by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    var recent by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var series by remember { mutableStateOf<VodItem?>(null) }
    var episodes by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    var playing by remember { mutableStateOf<VodItem?>(null) }

    LaunchedEffect(client) {
        loading = true
        withContext(Dispatchers.IO) {
            runCatching { client.continueWatching() to client.recentlyAdded() }
        }
            .onSuccess { (r, n) ->
                resume = r
                recent = n
            }
            .onFailure { loadError = it.message ?: "Could not reach Jellyfin" }
        loading = false
    }

    // Back steps out one level at a time: player, then episodes, then the library.
    BackHandler(enabled = playing != null) { playing = null }
    BackHandler(enabled = series != null && playing == null) { series = null }

    val nowPlaying = playing
    val openSeries = series
    when {
        nowPlaying != null -> VodPlayer(nowPlaying.streamUrl)
        openSeries != null -> SeriesDetail(
            series = openSeries,
            episodes = episodes,
            onPlay = { playing = it },
            onBack = { series = null }
        )
        else -> Library(
            loading = loading,
            loadError = loadError,
            resume = resume,
            recent = recent,
            onSelect = { item ->
                if (item.type == "Series") {
                    scope.launch {
                        val eps = withContext(Dispatchers.IO) {
                            runCatching { client.episodes(item.id) }.getOrDefault(emptyList())
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

@Composable
private fun Library(
    loading: Boolean,
    loadError: String?,
    resume: List<VodItem>,
    recent: List<VodItem>,
    onSelect: (VodItem) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 48.dp, end = 48.dp, top = 40.dp)
    ) {
        PageHeader(title = "On demand", subtitle = "Pick something to watch")
        Spacer(Modifier.height(32.dp))
        when {
            loading -> EmptyState(
                title = "Loading your library...",
                modifier = Modifier.weight(1f)
            )
            loadError != null -> EmptyState(
                title = "Couldn't reach Jellyfin",
                body = loadError,
                modifier = Modifier.weight(1f)
            )
            resume.isEmpty() && recent.isEmpty() -> EmptyState(
                title = "Nothing here yet",
                body = "Add media to your Jellyfin library and it will show up here.",
                modifier = Modifier.weight(1f)
            )
            else -> LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(36.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp)
            ) {
                if (resume.isNotEmpty()) {
                    item { PosterRow("Continue watching", resume, onSelect) }
                }
                item { PosterRow("Recently added", recent, onSelect) }
            }
        }
    }
}

@Composable
private fun PosterRow(title: String, list: List<VodItem>, onSelect: (VodItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = Cable.Ink
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 10.dp)
        ) {
            items(list, key = { it.id }) { vod ->
                PosterCard(vod) { onSelect(vod) }
            }
        }
    }
}

@Composable
private fun PosterCard(vod: VodItem, onClick: () -> Unit) {
    Column(
        Modifier.width(184.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        FocusSurface(
            onClick = onClick,
            modifier = Modifier
                .width(184.dp)
                .height(276.dp),
            shape = RoundedCornerShape(16.dp),
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
    onPlay: (VodItem) -> Unit,
    onBack: () -> Unit
) {
    val firstEpisode = remember { FocusRequester() }
    LaunchedEffect(episodes.size) {
        if (episodes.isNotEmpty()) runCatching { firstEpisode.requestFocus() }
    }

    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp)
    ) {
        Column(
            Modifier
                .width(280.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            OutlinedButton(onClick = onBack) { Text("Back to library") }
            AsyncImage(
                model = series.posterUrl,
                contentDescription = series.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(280.dp)
                    .height(420.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Cable.SurfaceHi)
            )
            Text(
                text = series.name,
                style = MaterialTheme.typography.titleLarge,
                color = Cable.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
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
                text = "Episodes",
                style = MaterialTheme.typography.titleLarge,
                color = Cable.Ink
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
                    itemsIndexed(episodes, key = { _, ep -> ep.id }) { idx, ep ->
                        EpisodeRow(
                            episode = ep,
                            focusRequester = if (idx == 0) firstEpisode else null
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
        shape = RoundedCornerShape(14.dp),
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
                color = Cable.Accent
            )
        }
    }
}

@Composable
private fun VodPlayer(url: String) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(url) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }

    AndroidView(
        factory = { ctx -> PlayerView(ctx).also { it.player = player } },
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    )
}
