package com.dallas.cabletv.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.unit.dp
import com.dallas.cabletv.data.EpgCache
import com.dallas.cabletv.data.GuideSelection
import com.dallas.cabletv.data.JellyfinClient
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.Program
import com.dallas.cabletv.data.Settings
import com.dallas.cabletv.data.VodItem
import com.dallas.cabletv.data.XtreamClient
import com.dallas.cabletv.data.guideSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val KEYS = "abcdefghijklmnopqrstuvwxyz1234567890".map { it.toString() }

/**
 * Finds channels by name or number, shows that are on now, and movies and shows from the provider
 * and Jellyfin. The keyboard is part of the screen, so the system keyboard never covers the results.
 * A connected keyboard types straight into the search too.
 */
@Composable
fun SearchScreen(
    nav: NavActions,
    channels: List<LiveChannel>,
    favorites: Set<String>,
    guide: GuideData,
    settings: Settings,
    onPlayChannel: (LiveChannel) -> Unit,
    onOpenProgramme: (LiveChannel, Program) -> Unit,
    onOpenVod: (VodItem) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var catalog by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    var online by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    val firstKey = remember { FocusRequester() }

    val xtream = remember(settings) {
        if (settings.hasXtream) XtreamClient(settings.xtreamServer, settings.xtreamUser, settings.xtreamPass) else null
    }
    val jellyfin = remember(settings) {
        if (settings.hasJellyfin) JellyfinClient(settings.jellyfinServer, settings.jellyfinToken, settings.jellyfinUserId) else null
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstKey.requestFocus() }
    }
    // The provider's full catalogue is fetched once per visit and searched here.
    LaunchedEffect(settings) {
        if (xtream != null) {
            catalog = withContext(Dispatchers.IO) {
                runCatching { xtream.vodMovies(Int.MAX_VALUE) + xtream.vodSeries(Int.MAX_VALUE) }.getOrDefault(emptyList())
            }
        }
    }
    // Jellyfin is searched on the server after a short pause in typing.
    LaunchedEffect(query, settings) {
        online = emptyList()
        val term = query.trim()
        if (term.length < 2 || jellyfin == null) return@LaunchedEffect
        delay(400)
        online = withContext(Dispatchers.IO) { runCatching { jellyfin.search(term) }.getOrDefault(emptyList()) }
    }

    val q = query.trim()
    val matchChannels = remember(q, channels) {
        if (q.isEmpty()) emptyList() else channels.filter { it.name.contains(q, ignoreCase = true) || it.number.toString() == q }.take(30)
    }
    val guideVersion = guide.version
    val matchShows = remember(q, channels, guideVersion) {
        if (q.length < 2) {
            emptyList()
        } else {
            EpgCache.programmesMatching(q).mapNotNull { (epgId, program) ->
                val ch = channels.firstOrNull { it.epgId == epgId } ?: return@mapNotNull null
                if (ch in matchChannels && isOnNow(program)) null else ch to program
            }
        }
    }
    val matchVod = remember(q, catalog, online) {
        if (q.length < 2) emptyList() else (catalog.filter { it.name.contains(q, ignoreCase = true) }.take(30) + online).distinctBy { it.source + ":" + it.id }
    }

    fun type(key: String) {
        query = when (key) {
            "del" -> query.dropLast(1)
            "clear" -> ""
            else -> (query + key).take(40)
        }
    }

    TabScaffold(Tab.Search, nav, modifier = Modifier.background(Tv.Bg)) { narrow ->
        val side = gutter(narrow)
        val keyboard: @Composable () -> Unit = {
            Column(Modifier.width(262.dp)) {
                Row(
                    Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(10.dp)).background(Tv.Surface2).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(TvIcons.Search, contentDescription = null, tint = Tv.Ink3, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(query.ifEmpty { "Search" }, style = TvType.Section.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = if (query.isEmpty()) Tv.Ink3 else Tv.Ink, maxLines = 1)
                    Box(Modifier.padding(start = 2.dp).width(2.dp).height(20.dp).background(Tv.Accent))
                }
                Spacer(Modifier.height(12.dp))
                KEYS.chunked(6).forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEachIndexed { c, k ->
                            Key(k.uppercase(), Modifier.weight(1f).then(if (r == 0 && c == 0) Modifier.focusRequester(firstKey) else Modifier)) { type(k) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Key("Space", Modifier.weight(1.3f)) { type(" ") }
                    Key("'", Modifier.weight(0.7f)) { type("'") }
                    Key("Delete", Modifier.weight(1f)) { type("del") }
                    Key("Clear", Modifier.weight(1f)) { type("clear") }
                }
            }
        }
        val results: @Composable (Modifier) -> Unit = { mod ->
            LazyColumn(mod, contentPadding = PaddingValues(bottom = 24.dp)) {
                if (q.isEmpty()) {
                    item {
                        Column(Modifier.padding(start = 8.dp, end = side, top = 14.dp, bottom = 6.dp)) {
                            Text("Find a channel, movie or show", style = TvType.Headline)
                            Spacer(Modifier.height(6.dp))
                            Text("Results show up here as you type. Search by channel name or number, or the name of a show.", style = TvType.Body, color = Tv.Ink2, modifier = Modifier.widthIn(max = 460.dp))
                        }
                    }
                    val favs = channels.filter { it.id in favorites }
                    if (favs.isNotEmpty()) {
                        item { Shelf("Your favorite channels", 8.dp) { items(favs, key = { it.id }) { ch -> ChannelCard(ch, guide.nowNext(ch)?.now, true, { onPlayChannel(ch) }) } } }
                    }
                } else if (matchChannels.isEmpty() && matchShows.isEmpty() && matchVod.isEmpty()) {
                    item {
                        Column(Modifier.padding(start = 8.dp, end = side, top = 14.dp)) {
                            Text("No matches for “$query”", style = TvType.Headline)
                            Spacer(Modifier.height(6.dp))
                            Text("Check the spelling, or search for part of a name.", style = TvType.Body, color = Tv.Ink2)
                        }
                    }
                } else {
                    if (matchChannels.isNotEmpty()) {
                        item { Shelf("Channels", 8.dp) { items(matchChannels, key = { it.id }) { ch -> ChannelCard(ch, guide.nowNext(ch)?.now, ch.id in favorites, { onPlayChannel(ch) }) } } }
                    }
                    if (matchShows.isNotEmpty()) {
                        item {
                            Shelf("Shows", 8.dp) {
                                items(matchShows, key = { it.first.id + it.second.start }) { (ch, program) ->
                                    val shown = if (isOnNow(program)) program else program.copy(title = "${program.title} · ${formatTime(program.start)}")
                                    ChannelCard(ch, shown, ch.id in favorites, onClick = {
                                        val action = guideSelection(program.start, program.end, System.currentTimeMillis(), placeholder = false, catchup = ch.catchup)
                                        if (action == GuideSelection.Replay) onOpenProgramme(ch, program) else onPlayChannel(ch)
                                    })
                                }
                            }
                        }
                    }
                    if (matchVod.isNotEmpty()) {
                        item { Shelf("Movies and shows", 8.dp) { items(matchVod, key = { it.source + ":" + it.id }) { v -> PosterCard(v, onClick = { onOpenVod(v) }) } } }
                    }
                }
            }
        }

        val typing = Modifier.onPreviewKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val code = ev.nativeKeyEvent.keyCode
            if (code == KeyEvent.KEYCODE_DEL && query.isNotEmpty()) {
                type("del")
                return@onPreviewKeyEvent true
            }
            val ch = ev.utf16CodePoint.toChar()
            if (code !in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER && (ch.isLetterOrDigit() || ch == ' ' || ch == '\'' || ch == '-')) {
                type(ch.lowercaseChar().toString())
                true
            } else {
                false
            }
        }

        if (narrow) {
            Column(Modifier.fillMaxSize().padding(top = 60.dp).then(typing)) {
                Box(Modifier.padding(horizontal = side)) { keyboard() }
                results(Modifier.weight(1f).padding(start = side - 8.dp))
            }
        } else {
            Row(Modifier.fillMaxSize().padding(top = 62.dp).then(typing)) {
                Box(Modifier.padding(start = side)) { keyboard() }
                Spacer(Modifier.width(16.dp))
                results(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun Key(label: String, modifier: Modifier, onClick: () -> Unit) {
    Focusable(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(9.dp),
        color = Tv.Surface,
        focusScale = 1.08f,
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = if (label.length > 1) TvType.CaptionStrong else TvType.Section, maxLines = 1)
    }
}
