package com.dallas.cabletv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout

/** One choice in the audio and captions panel. */
private class TrackChoice(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean)

private fun labelOf(format: Format, position: Int): String {
    val lang = format.language?.let { code ->
        runCatching { java.util.Locale.forLanguageTag(code).displayLanguage }.getOrNull()?.takeIf { it.isNotBlank() && it != code }
    }
    return format.label ?: lang ?: format.language?.uppercase() ?: "Track ${position + 1}"
}

private fun choicesOf(player: Player, type: Int): List<TrackChoice> {
    val out = mutableListOf<TrackChoice>()
    player.currentTracks.groups.forEach { group ->
        if (group.type == type) {
            for (i in 0 until group.length) {
                if (group.isTrackSupported(i)) {
                    out += TrackChoice(group, i, labelOf(group.getTrackFormat(i), out.size), group.isTrackSelected(i))
                }
            }
        }
    }
    return out
}

/**
 * Audio language, captions and picture size, read from what the stream actually offers.
 * Used by live TV and by movies and shows.
 */
@Composable
fun TrackPanel(
    player: Player,
    resizeMode: Int,
    onResize: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onClose() }
    var tick by remember { mutableIntStateOf(0) }
    val audio = remember(tick) { choicesOf(player, C.TRACK_TYPE_AUDIO) }
    val captions = remember(tick) { choicesOf(player, C.TRACK_TYPE_TEXT) }
    val captionsOff = remember(tick) { player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || captions.none { it.selected } }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    fun pick(type: Int, choice: TrackChoice) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, choice.index))
            .build()
        tick++
    }

    SidePanel("Audio and captions", modifier.width(340.dp)) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp)) {
            item { SectionLabel("Audio", Modifier.padding(start = 12.dp, top = 4.dp, bottom = 6.dp)) }
            if (audio.size <= 1) {
                item { ChoiceRow(audio.firstOrNull()?.label ?: "Default", selected = true, modifier = Modifier.focusRequester(first)) {} }
            } else {
                audio.forEachIndexed { i, c ->
                    item { ChoiceRow(c.label, c.selected, if (i == 0) Modifier.focusRequester(first) else Modifier) { pick(C.TRACK_TYPE_AUDIO, c) } }
                }
            }
            item { SectionLabel("Captions", Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp)) }
            item {
                ChoiceRow("Off", captionsOff) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                    tick++
                }
            }
            captions.forEach { c -> item { ChoiceRow(c.label, c.selected && !captionsOff) { pick(C.TRACK_TYPE_TEXT, c) } } }
            if (captions.isEmpty()) {
                item { Text("This stream has no captions.", style = TvType.Small, color = Tv.Ink3, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
            }
            item { SectionLabel("Picture", Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp)) }
            item { ChoiceRow("Fit to screen", resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) { onResize(AspectRatioFrameLayout.RESIZE_MODE_FIT) } }
            item { ChoiceRow("Fill screen", resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FILL) { onResize(AspectRatioFrameLayout.RESIZE_MODE_FILL) } }
            item { ChoiceRow("Zoom", resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) { onResize(AspectRatioFrameLayout.RESIZE_MODE_ZOOM) } }
        }
    }
}

/** A row in a list of options. The chosen one has an amber check. */
@Composable
fun ChoiceRow(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Focusable(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 42.dp), shape = RoundedCornerShape(9.dp), contentAlignment = Alignment.CenterStart) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = TvType.BodyStrong, modifier = Modifier.weight(1f), maxLines = 1)
            if (selected) Icon(TvIcons.Check, contentDescription = "Selected", tint = if (LocalFocused.current) Tv.FocusInk else Tv.Accent, modifier = Modifier.size(18.dp))
        }
    }
}
