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
import com.dallas.cabletv.data.SearchMatcher
import com.dallas.cabletv.data.Settings
import com.dallas.cabletv.data.VodItem
import com.dallas.cabletv.data.XtreamClient
import com.dallas.cabletv.data.foldForSearch
import com.dallas.cabletv.data.guideSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val KEYS = "abcdefghijklmnopqrstuvwxyz1234567890".map { it.toString() }
/** A row of symbols, six wide like the letters, so the arrows move straight up and down. */
private val SYMBOL_KEYS = listOf("'", "-", "&", ".", "+", ":")
/** Symbols a connected keyboard can type, besides letters, digits and space. */
private const val TYPED_SYMBOLS = "'-&.+:!?,/"
/** Pause after typing before results are worked out. */
private const val TYPING_PAUSE_MS = 250L
private const val MAX_RESULTS = 30

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

    // The provider's full catalogue is kept for a while, so it is not downloaded on every visit.
    val vodKey = if (settings.hasXtream) "${settings.xtreamServer}\n${settings.xtreamUser}\n${settings.xtreamPass}" else ""
    var catalog by remember(vodKey) { mutableStateOf(VodCatalog.fresh(vodKey).orEmpty()) }
    var catalogLoading by remember(vodKey) { mutableStateOf(xtream != null && VodCatalog.fresh(vodKey) == null) }
    LaunchedEffect(vodKey) {
        val client = xtream
        if (client == null || !catalogLoading) return@LaunchedEffect
        catalog = VodCatalog.load(vodKey) { client.vodMovies(Int.MAX_VALUE) + client.vodSeries(Int.MAX_VALUE) }
        catalogLoading = false
    }

    val q = query.trim()

    // Jellyfin is searched on the server after a short pause in typing.
    var online by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    var onlineFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(q, jellyfin) {
        online = emptyList()
        onlineFor = null
        if (q.length < 2 || jellyfin == null) return@LaunchedEffect
        delay(400)
        online = withContext(Dispatchers.IO) { runCatching { jellyfin.search(q) }.getOrDefault(emptyList()) }
        onlineFor = q
    }

    // Channels by the guide listing they show, built away from the main thread once per channel list and guide download.
    val guideVersion = guide.version
    val index by produceState<ChannelIndex?>(null, channels, guideVersion) {
        value = withContext(Dispatchers.Default) { buildChannelIndex(channels) }
    }

    // Channels, shows and the provider's titles are matched off the main thread, after a short pause in typing.
    var found by remember { mutableStateOf<SearchResults?>(null) }
    LaunchedEffect(q, index, catalog) {
        if (q.isEmpty()) {
            found = null
            return@LaunchedEffect
        }
        val idx = index ?: return@LaunchedEffect
        if (found?.query != q) delay(TYPING_PAUSE_MS)
        val list = catalog
        found = withContext(Dispatchers.Default) { runSearch(q, idx, list) }
    }

    val current = found
    val matchChannels = current?.channels.orEmpty()
    val matchShows = current?.shows.orEmpty()
    val matchVod = remember(current, online) {
        (current?.vod.orEmpty() + online).distinctBy(::vodKeyOf)
    }
    val localPending = q.isNotEmpty() && current?.query != q
    val onlinePending = jellyfin != null && q.length >= 2 && onlineFor != q
    val favs = remember(channels, favorites) { channels.filter { it.id in favorites }.distinctBy { it.id } }

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
                (KEYS + SYMBOL_KEYS).chunked(6).forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEachIndexed { c, k ->
                            Key(k.uppercase(), Modifier.weight(1f).then(if (r == 0 && c == 0) Modifier.focusRequester(firstKey) else Modifier)) { type(k) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Key("Space", Modifier.weight(2f)) { type(" ") }
                    Key("Delete", Modifier.weight(1f)) { type("del") }
                    Key("Clear", Modifier.weight(1f)) { type("clear") }
                }
            }
        }
        val results: @Composable (Modifier) -> Unit = { mod ->
            LazyColumn(mod, contentPadding = PaddingValues(bottom = 24.dp)) {
                if (q.isEmpty()) {
                    item(key = "intro") {
                        Column(Modifier.padding(start = 8.dp, end = side, top = 14.dp, bottom = 6.dp)) {
                            Text("Find a channel, movie or show", style = TvType.Headline)
                            Spacer(Modifier.height(6.dp))
                            Text("Results show up here as you type. Search by channel name or number, or the name of a show.", style = TvType.Body, color = Tv.Ink2, modifier = Modifier.widthIn(max = 460.dp))
                        }
                    }
                    if (favs.isNotEmpty()) {
                        item(key = "favorites") { Shelf("Your favorite channels", 8.dp) { items(favs, key = { it.id }) { ch -> ChannelCard(ch, guide.nowNext(ch)?.now, true, { onPlayChannel(ch) }) } } }
                    }
                } else if (matchChannels.isEmpty() && matchShows.isEmpty() && matchVod.isEmpty()) {
                    item(key = "status") {
                        Column(Modifier.padding(start = 8.dp, end = side, top = 14.dp)) {
                            if (localPending || onlinePending) {
                                Text("Searching", style = TvType.Headline)
                            } else {
                                Text("No matches for “$query”", style = TvType.Headline)
                                Spacer(Modifier.height(6.dp))
                                Text("Check the spelling, or search for part of a name.", style = TvType.Body, color = Tv.Ink2)
                            }
                        }
                    }
                } else {
                    if (matchChannels.isNotEmpty()) {
                        item(key = "channels") { Shelf("Channels", 8.dp) { items(matchChannels, key = { it.id }) { ch -> ChannelCard(ch, guide.nowNext(ch)?.now, ch.id in favorites, { onPlayChannel(ch) }) } } }
                    }
                    if (matchShows.isNotEmpty()) {
                        item(key = "shows") {
                            Shelf("Shows", 8.dp) {
                                items(matchShows, key = { (ch, program) -> showKeyOf(ch, program) }) { (ch, program) ->
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
                        item(key = "vod") { Shelf("Movies and shows", 8.dp) { items(matchVod, key = ::vodKeyOf) { v -> PosterCard(v, onClick = { onOpenVod(v) }) } } }
                    }
                }
                if (q.isNotEmpty() && catalogLoading) {
                    item(key = "catalog") {
                        Text(
                            "Still loading movies and shows from your provider.",
                            style = TvType.Small,
                            color = Tv.Ink3,
                            modifier = Modifier.padding(start = 8.dp, end = side, top = 10.dp)
                        )
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
            // A number pad types digits whether or not Num Lock is on.
            if (code in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) {
                type(('0' + (code - KeyEvent.KEYCODE_NUMPAD_0)).toString())
                return@onPreviewKeyEvent true
            }
            val ch = ev.utf16CodePoint.toChar()
            if (code !in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER && (ch.isLetterOrDigit() || ch == ' ' || ch in TYPED_SYMBOLS)) {
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

/** A movie and a series from the provider can share an id, so the type is part of the key. */
private fun vodKeyOf(v: VodItem): String = "${v.source}:${v.type}:${v.id}"

private fun showKeyOf(ch: LiveChannel, p: Program): String = "${ch.id}@${p.start}"

/** The channel list prepared for searching: names folded once, and channels grouped by the guide listing they show. */
private class ChannelIndex(
    val channels: List<LiveChannel>,
    val foldedNames: List<String>,
    val byGuideKey: Map<String, List<LiveChannel>>
)

/**
 * Uses the guide's own rules (the id, then the display name) to find each channel's listing, so a
 * show is found on every channel the guide shows it on, HD and SD copies included.
 */
private fun buildChannelIndex(channels: List<LiveChannel>): ChannelIndex {
    val now = System.currentTimeMillis()
    val byKey = HashMap<String, MutableList<LiveChannel>>()
    for (ch in channels) {
        val key = EpgCache.scheduleKey(ch.epgId, ch.name, now) ?: continue
        byKey.getOrPut(key) { mutableListOf() }.add(ch)
    }
    return ChannelIndex(channels, channels.map { foldForSearch(it.name) }, byKey)
}

private class SearchResults(
    val query: String,
    val channels: List<LiveChannel>,
    val shows: List<Pair<LiveChannel, Program>>,
    val vod: List<VodItem>
)

/** Runs on a background thread. Every list is unique by its row key. */
private fun runSearch(q: String, index: ChannelIndex, catalog: List<VodItem>): SearchResults {
    val matcher = SearchMatcher(q)
    val now = System.currentTimeMillis()
    val channels = ArrayList<LiveChannel>()
    val seen = HashSet<String>()
    for (i in index.channels.indices) {
        val ch = index.channels[i]
        if ((ch.number.toString() == q || matcher.matchesFolded(index.foldedNames[i])) && seen.add(ch.id)) {
            channels += ch
            if (channels.size >= MAX_RESULTS) break
        }
    }
    val matchedIds = channels.mapTo(HashSet()) { it.id }
    val hits = EpgCache.programmesMatching(q, now, limit = MAX_RESULTS * 2, keys = index.byGuideKey.keys)
    val shows = hits
        .flatMap { (key, program) -> index.byGuideKey[key].orEmpty().map { it to program } }
        // A channel that already matched shows what is on now on its own card.
        .filterNot { (ch, program) -> ch.id in matchedIds && isOnNow(program, now) }
        .distinctBy { (ch, program) -> showKeyOf(ch, program) }
        .take(MAX_RESULTS)
    val vod = if (matcher.folded.length < 2) {
        emptyList()
    } else {
        catalog.asSequence().filter { matcher.matches(it.name) }.distinctBy(::vodKeyOf).take(MAX_RESULTS).toList()
    }
    return SearchResults(q, channels, shows, vod)
}

/** The provider's movies and series, kept for half an hour per login for the life of the app. */
private object VodCatalog {
    private const val LIFETIME_MS = 30 * 60_000L
    private val loading = Mutex()
    private var key = ""
    private var items: List<VodItem> = emptyList()
    private var loadedAt = 0L

    fun fresh(forKey: String): List<VodItem>? = synchronized(this) {
        if (forKey.isNotEmpty() && key == forKey && System.currentTimeMillis() - loadedAt < LIFETIME_MS) items else null
    }

    /** The cached list, or a fresh download. A failure gives an empty list and is tried again next time. */
    suspend fun load(forKey: String, download: () -> List<VodItem>): List<VodItem> = loading.withLock {
        fresh(forKey)?.let { return@withLock it }
        // Leaving Search does not waste a download that is nearly done.
        withContext(NonCancellable + Dispatchers.IO) {
            val list = runCatching { download() }.getOrNull()
            if (list != null) {
                synchronized(this@VodCatalog) {
                    key = forKey
                    items = list
                    loadedAt = System.currentTimeMillis()
                }
            }
            list.orEmpty()
        }
    }
}
