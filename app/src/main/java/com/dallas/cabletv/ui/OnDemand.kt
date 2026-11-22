package com.dallas.cabletv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
fun OnDemandScreen(settings: Settings, onExit: () -> Unit) {
    if (settings.hasJellyfin) {
        JellyfinBrowser(settings, onExit)
    } else {
        Column(Modifier.fillMaxSize()) {
            CableHeader("ON DEMAND", onBack = onExit)
            CenterMessage("JELLYFIN IS NOT CONNECTED.\nADD YOUR SERVER IN SETTINGS.")
        }
    }
}

@Composable
private fun JellyfinBrowser(settings: Settings, onExit: () -> Unit) {
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
        openSeries != null -> EpisodeList(
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
            onExit = onExit,
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
    onExit: () -> Unit,
    onSelect: (VodItem) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        CableHeader("ON DEMAND", onBack = onExit)
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
                PosterRow("RECENTLY ADDED", recent, onSelect)
            }
        }
    }
}

@Composable
private fun PosterRow(title: String, list: List<VodItem>, onSelect: (VodItem) -> Unit) {
    Column {
        Text(
            title,
            color = CableColors.Led,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )
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
            .border(2.dp, if (focused) CableColors.Led else Color.Transparent)
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
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EpisodeList(series: VodItem, episodes: List<VodItem>, onPlay: (VodItem) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        CableHeader(series.name.uppercase(), onBack = onBack)
        if (episodes.isEmpty()) {
            CenterMessage("NO EPISODES FOUND.")
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(episodes, key = { it.id }) { ep ->
                    CableRow(onClick = { onPlay(ep) }) {
                        Text(
                            ep.name,
                            color = CableColors.Text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 18.sp
                        )
                    }
                }
            }
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
