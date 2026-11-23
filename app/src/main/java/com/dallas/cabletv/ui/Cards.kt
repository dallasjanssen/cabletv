package com.dallas.cabletv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.Program
import com.dallas.cabletv.data.VodItem

/** A titled horizontal row of cards, as used on Home, Movies and Shows, and Search. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Shelf(title: String, side: Dp, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Text(title, style = TvType.Section, modifier = Modifier.padding(horizontal = side))
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberEdgeSpec(side)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = side, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) { content() }
        }
    }
}

/** A live channel: its logo, number and how far through the current show it is, with the show below. */
@Composable
fun ChannelCard(ch: LiveChannel, now: Program?, favorite: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(168.dp)) {
        Focusable(
            onClick = onClick,
            modifier = Modifier.size(168.dp, 94.dp),
            look = FocusLook.Ring,
            onFocus = { focused = it }
        ) {
            ChannelLogo(ch, Modifier.fillMaxSize())
            Text(
                ch.number.toString(),
                style = TvType.CaptionStrong,
                color = Color(0xC0FFFFFF),
                modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 5.dp)
            )
            if (favorite) {
                Icon(TvIcons.StarFilled, contentDescription = "Favorite", tint = Tv.Accent, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(14.dp))
            }
            if (now != null && isOnNow(now)) {
                ProgressBar(
                    progressOf(now),
                    Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                    track = Color(0x59000000),
                    height = 3.dp
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            now?.title ?: ch.name,
            style = TvType.SmallStrong,
            color = if (focused) Color.White else Tv.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text("${ch.number} · ${ch.name}", style = TvType.Caption, color = Tv.Ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A poster for a movie or show, with its name and year underneath. */
@Composable
fun PosterCard(item: VodItem, onClick: () -> Unit, modifier: Modifier = Modifier, onFocus: ((Boolean) -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(104.dp)) {
        Focusable(
            onClick = onClick,
            modifier = Modifier.size(104.dp, 156.dp),
            shape = RoundedCornerShape(9.dp),
            look = FocusLook.Ring,
            onFocus = {
                focused = it
                onFocus?.invoke(it)
            }
        ) {
            ArtPlaceholder(item.name)
            AsyncImage(
                model = item.posterUrl.takeIf { it.isNotBlank() },
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (item.played) {
                Icon(
                    TvIcons.Check,
                    contentDescription = "Watched",
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)
                        .background(Tv.Ok, RoundedCornerShape(10.dp)).padding(3.dp)
                )
            }
            item.progress?.takeIf { it > 0f && !item.played }?.let {
                ProgressBar(it, Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(6.dp), color = Tv.Accent, height = 3.dp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(item.name, style = TvType.SmallStrong, color = if (focused) Color.White else Tv.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(vodSubtitle(item), style = TvType.Caption, color = Tv.Ink3, maxLines = 1)
    }
}

/** A wide card for something half watched, with a progress line and the time left. */
@Composable
fun ResumeCard(item: VodItem, onClick: () -> Unit, modifier: Modifier = Modifier, onFocus: ((Boolean) -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(168.dp)) {
        Focusable(
            onClick = onClick,
            modifier = Modifier.size(168.dp, 94.dp),
            look = FocusLook.Ring,
            onFocus = {
                focused = it
                onFocus?.invoke(it)
            }
        ) {
            ArtPlaceholder(item.seriesName ?: item.name, bottomPadding = 18.dp)
            AsyncImage(
                model = (if (item.type == "Episode") item.posterUrl else item.backdropUrl ?: item.posterUrl).takeIf { it.isNotBlank() },
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xB3000000))))
            ProgressBar(
                item.progress ?: 0f,
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp),
                color = Tv.Accent,
                track = Color(0x40FFFFFF),
                height = 3.dp
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(item.seriesName ?: item.name, style = TvType.SmallStrong, color = if (focused) Color.White else Tv.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(resumeSubtitle(item), style = TvType.Caption, color = Tv.Ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A soft colored tile with the title, shown while a picture loads or when there is none. */
@Composable
fun ArtPlaceholder(title: String, bottomPadding: Dp = 8.dp) {
    val hue = remember(title) { hueOf(title) }
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color.hsl(hue, 0.4f, 0.26f), Color.hsl((hue + 40f) % 360f, 0.4f, 0.09f)))
        ),
        contentAlignment = Alignment.BottomStart
    ) {
        Text(
            title,
            style = TvType.CaptionStrong,
            color = Color(0xE6FFFFFF),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = bottomPadding)
        )
    }
}

/** "S2 E4" for episodes. */
fun episodeLabel(item: VodItem): String? =
    if (item.season != null && item.episode != null) "S${item.season} E${item.episode}" else null

/** The second line under a poster: the year, or that it is a series. */
fun vodSubtitle(item: VodItem): String = when (item.type) {
    "Series" -> listOfNotNull("Series", item.year?.toString()).joinToString(" · ")
    "Episode" -> listOfNotNull(episodeLabel(item), item.seriesName).joinToString(" · ")
    else -> item.year?.toString() ?: "Movie"
}

/** The second line under a half-watched card, for example "S2 E4 · 18 min left". */
fun resumeSubtitle(item: VodItem): String {
    val left = if (item.runtimeMinutes != null && item.progress != null) {
        "${minutesText(((1f - item.progress) * item.runtimeMinutes).toLong().coerceAtLeast(1))} left"
    } else {
        null
    }
    val ep = episodeLabel(item)?.let { if (item.seriesName != null) "$it · ${item.name}" else it }
    return listOfNotNull(ep, left).joinToString(" · ").ifBlank { "Resume" }
}
