package com.dallas.cabletv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.VodItem

/**
 * Home sits on top of the channel that is playing. The top shows what is on with a button to go
 * back to it, and rows below hold favorite and recent channels and anything half watched.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    nav: NavActions,
    channels: List<LiveChannel>,
    current: LiveChannel?,
    guide: GuideData,
    favorites: Set<String>,
    recents: List<String>,
    shelves: VodShelves,
    hasLive: Boolean,
    loadingChannels: Boolean,
    channelError: String?,
    onRetry: () -> Unit,
    onWatch: () -> Unit,
    onGuide: () -> Unit,
    onTune: (LiveChannel) -> Unit,
    onOpenVod: (VodItem) -> Unit,
    onSetUp: () -> Unit
) {
    val firstButton = remember { FocusRequester() }
    var inRows by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showPicture = hasLive && current != null
    // True while anything on Home has focus, so focus lost with a replaced button can be put back.
    var screenHasFocus by remember { mutableStateOf(false) }
    var focusedOnce by remember { mutableStateOf(false) }
    // Which buttons the top section shows. They change when the channels finish loading.
    val heroState = when {
        showPicture -> 0
        hasLive && loadingChannels -> 1
        hasLive && channelError != null -> 2
        hasLive -> 3
        else -> 4
    }
    LaunchedEffect(heroState) {
        repeat(2) { withFrameNanos { } }
        // The first time, focus starts on the first button. After that it only comes back when it was lost.
        if (!focusedOnce || !screenHasFocus) {
            if (runCatching { firstButton.requestFocus() }.isSuccess) focusedOnce = true
        }
    }
    val dim by animateFloatAsState(if (inRows || !showPicture) 1f else 0f, tween(350), label = "dim")

    TabScaffold(Tab.Home, nav, modifier = Modifier.onFocusChanged { screenHasFocus = it.hasFocus }) { narrow ->
        val side = gutter(narrow)
        // Shading over the live picture, so text stays readable.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Tv.Bg.copy(alpha = 0.9f), 0.4f to Tv.Bg.copy(alpha = 0.5f), 0.7f to Color.Transparent)
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Tv.Bg.copy(alpha = 0.7f),
                    0.2f to Color.Transparent,
                    0.42f to Tv.Bg.copy(alpha = 0.75f),
                    0.6f to Tv.Bg,
                    1f to Tv.Bg
                )
            )
        )
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = dim }.background(if (showPicture) Tv.Scrim else Tv.Bg))

        val favoriteChannels = remember(channels, favorites) { channels.filter { it.id in favorites } }
        val recentChannels = remember(channels, recents, current) {
            recents.filter { it != current?.id }.mapNotNull { id -> channels.firstOrNull { it.id == id } }
        }

        // The page moves a whole row at a time: the top section, or a row with its heading at the top.
        CompositionLocalProvider(LocalBringIntoViewSpec provides NoScroll) {
            LazyColumn(Modifier.fillMaxSize().padding(top = 52.dp), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "hero") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(if (narrow) 220.dp else 244.dp)
                            .onFocusChanged {
                                if (it.hasFocus) {
                                    inRows = false
                                    scope.launch { listState.animateScrollToItem(0) }
                                }
                            }
                            .padding(horizontal = side)
                            .padding(bottom = 22.dp),
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        // Ticks, so the progress bar and the time left keep moving.
                        val clock = rememberNow()
                        when {
                            showPicture && current != null -> {
                                val now = guide.nowNext(current)?.now
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LivePill()
                                    Spacer(Modifier.width(9.dp))
                                    Text("Channel ${current.number} · ${current.group}", style = TvType.SmallStrong, color = Tv.Ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(Modifier.height(7.dp))
                                Text(current.name, style = TvType.Hero, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (now != null) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(now.title, style = TvType.Title.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(6.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(formatRange(now), style = TvType.Small, color = Tv.Ink2)
                                        ProgressBar(progressOf(now, clock), Modifier.width(140.dp))
                                        Text(timeLeft(now, clock), style = TvType.Small, color = Tv.Ink2)
                                    }
                                }
                                Spacer(Modifier.height(14.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    PillButton("Keep watching", onWatch, icon = TvIcons.Play, modifier = Modifier.focusRequester(firstButton))
                                    PillButton("Guide", onGuide, icon = TvIcons.Guide)
                                }
                            }
                            hasLive -> {
                                Text("Live TV", style = TvType.Hero)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    when {
                                        loadingChannels -> "Finding your channels. This takes a few seconds the first time."
                                        channelError != null -> "Your channels did not load. $channelError"
                                        else -> "No channels came back from your provider."
                                    },
                                    style = TvType.Body,
                                    color = Tv.Ink2
                                )
                                Spacer(Modifier.height(14.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (!loadingChannels && channelError != null) {
                                        PillButton("Try again", onRetry, icon = TvIcons.Replay, modifier = Modifier.focusRequester(firstButton))
                                        PillButton("Settings", onSetUp, icon = TvIcons.Tv)
                                    } else if (!loadingChannels) {
                                        PillButton("Set up live TV", onSetUp, icon = TvIcons.Tv, modifier = Modifier.focusRequester(firstButton))
                                    }
                                    PillButton(
                                        "Guide",
                                        onGuide,
                                        icon = TvIcons.Guide,
                                        modifier = if (loadingChannels) Modifier.focusRequester(firstButton) else Modifier
                                    )
                                }
                            }
                            else -> {
                                Text("Add live TV", style = TvType.Hero)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Sign in to your TV provider to watch live channels and see the guide.",
                                    style = TvType.Body,
                                    color = Tv.Ink2
                                )
                                Spacer(Modifier.height(14.dp))
                                PillButton("Set up live TV", onSetUp, icon = TvIcons.Tv, modifier = Modifier.focusRequester(firstButton))
                            }
                        }
                    }
                }

                var position = 1
                fun shelf(key: String, title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
                    val at = position++
                    item(key = key) {
                        Shelf(title, side, Modifier.onFocusChanged {
                            if (it.hasFocus) {
                                inRows = true
                                scope.launch { listState.animateScrollToItem(at) }
                            }
                        }, content)
                    }
                }

                if (favoriteChannels.isNotEmpty()) {
                    shelf("fav", "Favorite channels") {
                        items(favoriteChannels, key = { it.id }) { ch ->
                            ChannelCard(ch, guide.nowNext(ch)?.now, favorite = ch.id in favorites, onClick = { onTune(ch) })
                        }
                    }
                }
                if (recentChannels.isNotEmpty()) {
                    shelf("recent", "Recently watched") {
                        items(recentChannels, key = { it.id }) { ch ->
                            ChannelCard(ch, guide.nowNext(ch)?.now, favorite = ch.id in favorites, onClick = { onTune(ch) })
                        }
                    }
                }
                if (shelves.continueWatching.isNotEmpty()) {
                    shelf("resume", "Continue watching") {
                        items(shelves.continueWatching, key = { it.source + it.id }) { item -> ResumeCard(item, onClick = { onOpenVod(item) }) }
                    }
                }
                if (shelves.recent.isNotEmpty()) {
                    shelf("new", "Recently added") {
                        items(shelves.recent.take(30), key = { it.source + it.id }) { item -> PosterCard(item, onClick = { onOpenVod(item) }) }
                    }
                }
                if (shelves.movies.isNotEmpty()) {
                    shelf("movies", "New movies") {
                        items(shelves.movies.take(30), key = { it.source + it.id }) { item -> PosterCard(item, onClick = { onOpenVod(item) }) }
                    }
                }
                if (shelves.series.isNotEmpty()) {
                    shelf("shows", "New shows") {
                        items(shelves.series.take(30), key = { it.source + it.id }) { item -> PosterCard(item, onClick = { onOpenVod(item) }) }
                    }
                }
                if (hasLive && favoriteChannels.isEmpty()) {
                    item(key = "tip") {
                        Text(
                            "Tip: press OK while watching and choose Add to favorites. Favorites appear here and at the top of the guide.",
                            style = TvType.Small,
                            color = Tv.Ink3,
                            modifier = Modifier.padding(horizontal = side, vertical = 8.dp).widthIn(max = 560.dp)
                        )
                    }
                }
            }
        }
    }
}
