package com.dallas.cabletv.ui.companion

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dallas.cabletv.data.companion.client.BrowseProgram
import com.dallas.cabletv.data.companion.client.GuideChannel
import com.dallas.cabletv.data.companion.client.SessionStatus
import com.dallas.cabletv.data.companion.client.TitleItem
import com.dallas.cabletv.ui.Tv
import com.dallas.cabletv.ui.TvType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

/** A time of day on this phone, such as 18:30. */
private fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(clockFormat)

private fun span(p: BrowseProgram): String = "${clock(p.startMs)} to ${clock(p.endMs)}"

@Composable
private fun NeedsTheTv(text: String) {
    SectionCard(null) {
        Text(text, style = TvType.Body, color = Tv.Ink2)
    }
}

/** One channel, with what is on now and next. Tapping it runs [onClick]. */
@Composable
private fun ChannelRow(ch: GuideChannel, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        val label = if (ch.number > 0) "${ch.number} ${ch.name}" else ch.name
        Text(label, style = TvType.BodyStrong, color = Tv.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val now = ch.now
        Text(
            if (now != null) "Now: ${now.title} (${span(now)})" else "Nothing listed right now",
            style = TvType.Body,
            color = Tv.Ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        ch.next?.let {
            Text("Next: ${it.title} at ${clock(it.startMs)}", style = TvType.Body, color = Tv.Ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (ch.locked) Text("Locked. The TV asks for its PIN to play it.", style = TvType.Body, color = Tv.Ink3)
    }
}

/** One movie or show. Tapping it runs [onClick], which opens it on the TV. */
@Composable
private fun TitleRow(item: TitleItem, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        val year = item.year?.let { " ($it)" }.orEmpty()
        Text(item.name + year, style = TvType.BodyStrong, color = Tv.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (item.locked) Text("Locked. The TV asks for its PIN to open it.", style = TvType.Body, color = Tv.Ink3)
    }
}

/**
 * The guide: every channel with what is on now and next. Choosing a channel shows its programmes
 * for the next hours. The TV keeps what it is showing until the person picks something here.
 */
@Composable
fun GuideTab(m: TvScreenModel) {
    val status by m.status.collectAsState()
    val guide by m.guide.collectAsState()
    val schedule by m.schedule.collectAsState()
    val message by m.message.collectAsState()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val online = status is SessionStatus.Online
    LaunchedEffect(online) {
        if (online && guide.data == null && !guide.busy) m.loadGuide()
    }

    if (!online) {
        NeedsTheTv("The guide needs the TV. Cable TV is closed or the TV is off.")
        return
    }
    val chosen = selected
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        message?.let { MessageLine(it, error = false) }
        if (chosen == null) {
            guide.error?.let { MessageLine(it, error = true) }
            if (guide.busy && guide.data == null) Text("Loading the guide...", style = TvType.Body, color = Tv.Ink3)
            SecondaryButton("Refresh guide", onClick = { m.loadGuide() }, modifier = Modifier.fillMaxWidth(), enabled = !guide.busy)
            LazyColumn(Modifier.weight(1f)) {
                items(guide.data.orEmpty(), key = { it.id }) { ch ->
                    ChannelRow(ch) {
                        selected = ch.id
                        m.loadSchedule(ch.id)
                    }
                }
            }
        } else {
            val channel = guide.data?.firstOrNull { it.id == chosen }
            SecondaryButton("Back to the guide", onClick = { selected = null }, modifier = Modifier.fillMaxWidth())
            Text(
                channel?.name ?: "Channel",
                style = TvType.Headline,
                color = Tv.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            PrimaryButton("Watch on the TV", onClick = { m.watchChannel(chosen) }, modifier = Modifier.fillMaxWidth())
            schedule.error?.let { MessageLine(it, error = true) }
            val programs = schedule.data?.takeIf { it.channelId == chosen }?.programs
            when {
                programs == null -> Text("Loading the schedule...", style = TvType.Body, color = Tv.Ink3)
                programs.isEmpty() -> Text("Nothing is listed for the next hours.", style = TvType.Body, color = Tv.Ink2)
                else -> LazyColumn(Modifier.weight(1f)) {
                    items(programs) { p ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).let { base ->
                            if (p.onNow) base.clickable { m.watchChannel(chosen) } else base
                        }) {
                            Text(
                                (if (p.onNow) "On now  " else "") + span(p),
                                style = TvType.Body,
                                color = if (p.onNow) Tv.Accent else Tv.Ink3
                            )
                            Text(p.title, style = TvType.BodyStrong, color = if (p.onNow) Tv.Ink else Tv.Ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (p.onNow) Text("Tap to watch it on the TV.", style = TvType.Body, color = Tv.Ink3)
                        }
                    }
                }
            }
        }
    }
}

/** Search across channels and movies or shows. Tapping a channel tunes the TV; tapping a title opens it there. */
@Composable
fun SearchTab(m: TvScreenModel) {
    val status by m.status.collectAsState()
    val results by m.searchResults.collectAsState()
    val message by m.message.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val online = status is SessionStatus.Online

    if (!online) {
        NeedsTheTv("Search needs the TV. Cable TV is closed or the TV is off.")
        return
    }
    val found = results.data
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        LabeledField(
            label = "Search channels and titles",
            value = query,
            onValueChange = { query = it },
            placeholder = "A channel, show or movie"
        )
        PrimaryButton(
            "Search",
            onClick = { m.search(query) },
            modifier = Modifier.fillMaxWidth(),
            enabled = query.isNotBlank() && !results.busy
        )
        message?.let { MessageLine(it, error = false) }
        results.error?.let { MessageLine(it, error = true) }
        if (results.busy) Text("Searching...", style = TvType.Body, color = Tv.Ink3)
        LazyColumn(Modifier.weight(1f)) {
            if (found != null) {
                if (found.channels.isNotEmpty()) {
                    item { Text("Channels", style = TvType.Headline, color = Tv.Ink) }
                    items(found.channels, key = { "channel:" + it.id }) { ch ->
                        ChannelRow(ch) { m.watchChannel(ch.id) }
                    }
                }
                if (found.titles.isNotEmpty()) {
                    item { Text("Movies and shows", style = TvType.Headline, color = Tv.Ink) }
                    items(found.titles, key = { "title:" + it.source + ":" + it.mediaType + ":" + it.id }) { t ->
                        TitleRow(t) { m.openTitleOnTv(t) }
                    }
                }
                if (found.channels.isEmpty() && found.titles.isEmpty()) {
                    item { Text("Nothing matched.", style = TvType.Body, color = Tv.Ink2) }
                }
            }
        }
    }
}

/** The movies and shows on the TV, sorted by name. Tapping one opens it on the TV. */
@Composable
fun LibraryTab(m: TvScreenModel) {
    val status by m.status.collectAsState()
    val page by m.libraryPage.collectAsState()
    val movies by m.libraryMovies.collectAsState()
    val message by m.message.collectAsState()
    val online = status is SessionStatus.Online
    LaunchedEffect(online) {
        if (online && page.data == null && !page.busy) m.loadLibrary(true)
    }

    if (!online) {
        NeedsTheTv("The library needs the TV. Cable TV is closed or the TV is off.")
        return
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (movies) {
                PrimaryButton("Movies", onClick = { m.loadLibrary(true) }, modifier = Modifier.weight(1f))
                SecondaryButton("Shows", onClick = { m.loadLibrary(false) }, modifier = Modifier.weight(1f))
            } else {
                SecondaryButton("Movies", onClick = { m.loadLibrary(true) }, modifier = Modifier.weight(1f))
                PrimaryButton("Shows", onClick = { m.loadLibrary(false) }, modifier = Modifier.weight(1f))
            }
        }
        message?.let { MessageLine(it, error = false) }
        page.error?.let { MessageLine(it, error = true) }
        val data = page.data
        if (data == null) {
            Text(if (page.busy) "Loading..." else "Nothing loaded yet.", style = TvType.Body, color = Tv.Ink3)
        } else {
            Text(
                "${data.items.size} of ${data.total} ${if (movies) "movies" else "shows"}",
                style = TvType.Body,
                color = Tv.Ink3
            )
            LazyColumn(Modifier.weight(1f)) {
                items(data.items, key = { "${it.source}:${it.mediaType}:${it.id}" }) { t ->
                    TitleRow(t) { m.openTitleOnTv(t) }
                }
                if (data.items.size < data.total) {
                    item {
                        SecondaryButton(
                            "Load more",
                            onClick = { m.loadMoreLibrary() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !page.busy
                        )
                    }
                }
            }
        }
    }
}
