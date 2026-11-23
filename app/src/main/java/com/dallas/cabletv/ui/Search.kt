package com.dallas.cabletv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dallas.cabletv.data.JellyfinClient
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.Settings
import com.dallas.cabletv.data.VodItem
import com.dallas.cabletv.data.XtreamClient
import com.dallas.cabletv.data.displayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Finds live channels by name, and movies and series from both the provider and Jellyfin.
 * The provider's full catalogue is fetched once per visit. Jellyfin is searched on the server
 * after a short pause while typing.
 */
@Composable
fun SearchScreen(
    channels: List<LiveChannel>,
    settings: Settings,
    stripPrefixes: Boolean,
    onPlayChannel: (LiveChannel) -> Unit,
    onOpenVod: (VodItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var catalog by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    var online by remember { mutableStateOf<List<VodItem>>(emptyList()) }

    val xtream = remember(settings) {
        if (settings.hasXtream) {
            XtreamClient(settings.xtreamServer, settings.xtreamUser, settings.xtreamPass)
        } else {
            null
        }
    }
    val jellyfin = remember(settings) {
        if (settings.hasJellyfin) {
            JellyfinClient(settings.jellyfinServer, settings.jellyfinToken, settings.jellyfinUserId)
        } else {
            null
        }
    }

    LaunchedEffect(settings) {
        if (xtream != null) {
            catalog = withContext(Dispatchers.IO) {
                runCatching { xtream.vodMovies(Int.MAX_VALUE) + xtream.vodSeries(Int.MAX_VALUE) }
                    .getOrDefault(emptyList())
            }
        }
    }

    LaunchedEffect(query, settings) {
        online = emptyList()
        val term = query.trim()
        if (term.length < 2 || jellyfin == null) return@LaunchedEffect
        delay(400)
        online = withContext(Dispatchers.IO) {
            runCatching { jellyfin.search(term) }.getOrDefault(emptyList())
        }
    }

    val q = query.trim()
    val matchChannels = if (q.isEmpty()) {
        emptyList()
    } else {
        channels.filter { it.name.contains(q, ignoreCase = true) }.take(30)
    }
    val matchVod = if (q.length < 2) {
        emptyList()
    } else {
        (catalog.filter { it.name.contains(q, ignoreCase = true) }.take(30) + online)
            .distinctBy { it.source + ":" + it.id }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Type a channel or a title. OK opens the keyboard.",
            style = MaterialTheme.typography.bodyLarge,
            color = Cable.Ink2
        )
        CableField(label = "SEARCH", value = query) { query = it }

        when {
            q.isEmpty() -> Text(
                text = "Nothing searched yet.",
                style = MaterialTheme.typography.bodyLarge,
                color = Cable.Ink3
            )
            matchChannels.isEmpty() && matchVod.isEmpty() -> Text(
                text = "No matches for \"$q\".",
                style = MaterialTheme.typography.bodyLarge,
                color = Cable.Ink3
            )
            else -> LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (matchChannels.isNotEmpty()) {
                    item { SectionLabel("CHANNELS") }
                    items(matchChannels, key = { it.id }) { ch ->
                        ResultRow(
                            title = if (stripPrefixes) displayName(ch.name) else ch.name,
                            detail = "CH ${ch.number}",
                            onClick = { onPlayChannel(ch) }
                        )
                    }
                }
                if (matchVod.isNotEmpty()) {
                    item { SectionLabel("MOVIES AND SERIES") }
                    items(matchVod, key = { it.source + ":" + it.id }) { vod ->
                        ResultRow(
                            title = vod.name,
                            detail = if (vod.type == "Series") "SERIES" else "MOVIE",
                            onClick = { onOpenVod(vod) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Cable.Accent,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun ResultRow(title: String, detail: String, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        focusScale = 1f
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Cable.Surface)
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Cable.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(20.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.labelMedium,
                color = Cable.Ink2
            )
        }
    }
}
