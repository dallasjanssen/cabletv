package com.dallas.cabletv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dallas.cabletv.data.BUFFER_AUTO
import com.dallas.cabletv.data.BUFFER_LARGE
import com.dallas.cabletv.data.BUFFER_MEDIUM
import com.dallas.cabletv.data.BUFFER_SMALL
import com.dallas.cabletv.data.BUFFER_VERY_LARGE
import com.dallas.cabletv.data.LOGO_EPG
import com.dallas.cabletv.data.LOGO_FOLDER
import com.dallas.cabletv.data.LOGO_PLAYLIST
import com.dallas.cabletv.data.PIN_GRACE_10M
import com.dallas.cabletv.data.PIN_GRACE_1H
import com.dallas.cabletv.data.PIN_GRACE_NONE
import com.dallas.cabletv.data.PIN_GRACE_SESSION
import com.dallas.cabletv.data.PIN_GRACE_UNTIL_CLEAR
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.PlayerOptions
import com.dallas.cabletv.data.PlayerOptionsStore
import com.dallas.cabletv.data.assignGuide
import com.dallas.cabletv.data.editCustomGroup
import com.dallas.cabletv.data.moveInOrder
import com.dallas.cabletv.data.orderChannels
import com.dallas.cabletv.data.toggleCsvId

/**
 * Picture and guide choices for the open Settings visit. Each change is written at once.
 * Leaving Settings reads the store again, so these rows have no Save button.
 */
internal class PlayerOptionsEditor(private val store: PlayerOptionsStore) {
    var options by mutableStateOf(store.load())
        private set

    fun update(block: (PlayerOptions) -> PlayerOptions) {
        val next = block(options)
        options = next
        store.save(next)
    }
}

@Composable
internal fun rememberPlayerOptionsEditor(): PlayerOptionsEditor {
    val context = LocalContext.current
    val store = remember { PlayerOptionsStore(context) }
    return remember { PlayerOptionsEditor(store) }
}

private data class Choice(val id: String, val label: String, val sub: String? = null)

private val bufferChoices = listOf(
    Choice(BUFFER_AUTO, "Auto", "Follows this device"),
    Choice(BUFFER_SMALL, "Small"),
    Choice(BUFFER_MEDIUM, "Medium"),
    Choice(BUFFER_LARGE, "Large"),
    Choice(BUFFER_VERY_LARGE, "Very large", "Smoother picture, slower channel changes")
)

private val logoChoices = listOf(
    Choice(LOGO_PLAYLIST, "Playlist"),
    Choice(LOGO_EPG, "Guide"),
    Choice(LOGO_FOLDER, "Folder")
)

private val pinGraceChoices = listOf(
    Choice(PIN_GRACE_NONE, "Every time"),
    Choice(PIN_GRACE_10M, "10 minutes"),
    Choice(PIN_GRACE_1H, "1 hour"),
    Choice(PIN_GRACE_SESSION, "Until the app leaves this profile"),
    Choice(PIN_GRACE_UNTIL_CLEAR, "Until a different locked channel")
)

private val multiviewChoices = listOf(
    Choice("2", "2"),
    Choice("3", "3"),
    Choice("4", "4")
)

/** A remote action. A null id is Default, which is not written into the map. */
private data class RemoteChoice(val id: String?, val label: String)

private val remoteChoices = listOf(
    RemoteChoice(null, "Default"),
    RemoteChoice("guide", "Guide"),
    RemoteChoice("channel_list", "Channel list"),
    RemoteChoice("info", "Info"),
    RemoteChoice("previous_channel", "Previous channel"),
    RemoteChoice("next_channel", "Next channel"),
    RemoteChoice("rewind", "Rewind"),
    RemoteChoice("favorites", "Favorites"),
    RemoteChoice("search", "Search"),
    RemoteChoice("home", "Home"),
    RemoteChoice("pip", "Picture in picture"),
    RemoteChoice("tracks", "Audio and captions"),
    RemoteChoice("multiview", "Multiview"),
    RemoteChoice("none", "Nothing")
)

private data class RemoteKey(val id: String, val label: String)

private val remoteKeys = listOf(
    RemoteKey("ok", "OK"),
    RemoteKey("long_ok", "OK held"),
    RemoteKey("left", "Left"),
    RemoteKey("right", "Right"),
    RemoteKey("up", "Up"),
    RemoteKey("down", "Down"),
    RemoteKey("back", "Back"),
    RemoteKey("long_back", "Back held"),
    RemoteKey("play_pause", "Play and pause")
)

private fun choiceFor(choices: List<Choice>, id: String): Choice =
    choices.firstOrNull { it.id == id } ?: Choice(id, id)

private fun nextChoice(choices: List<Choice>, id: String): Choice {
    val index = choices.indexOfFirst { it.id == id }
    return choices[if (index < 0) 0 else (index + 1) % choices.size]
}

private fun remoteActionId(map: String, key: String): String? {
    var found: String? = null
    for (piece in map.split(',', '\n')) {
        val parts = piece.split('=', limit = 2)
        if (parts.size < 2) continue
        val k = parts[0].trim()
        val action = parts[1].trim()
        if (k == key && action.isNotEmpty()) found = action
    }
    return found
}

/**
 * One override per line, "key=action". A null action drops that key, which is how Default
 * puts the button back. Other keys are kept, including ones this screen does not show.
 */
private fun remoteMapWith(map: String, key: String, actionId: String?): String {
    val kept = ArrayList<String>()
    for (piece in map.split(',', '\n')) {
        val parts = piece.split('=', limit = 2)
        if (parts.size < 2) continue
        val k = parts[0].trim()
        val action = parts[1].trim()
        if (k.isEmpty() || action.isEmpty() || k == key) continue
        kept.add("$k=$action")
    }
    if (!actionId.isNullOrEmpty()) kept.add("$key=$actionId")
    return kept.joinToString("\n")
}

private fun nextRemoteId(current: String?): String? {
    val index = remoteChoices.indexOfFirst { it.id == current }
    val next = if (index < 0) 0 else (index + 1) % remoteChoices.size
    return remoteChoices[next].id
}

private fun remoteLabel(actionId: String?): String =
    remoteChoices.firstOrNull { it.id == actionId }?.label ?: actionId ?: "Default"

@Composable
internal fun SettingsNote(text: String) {
    Text(text, style = TvType.Small, color = Tv.Ink2, modifier = Modifier.widthIn(max = 560.dp))
}

/** A whole row that is the button. OK moves to the next choice. */
@Composable
internal fun CycleRow(label: String, value: String, sub: String? = null, onCycle: () -> Unit) {
    Focusable(
        onClick = onCycle,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        shape = RoundedCornerShape(10.dp),
        color = Tv.Surface,
        contentAlignment = Alignment.CenterStart
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = TvType.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!sub.isNullOrBlank()) Text(sub, style = TvType.Small, color = ink3, maxLines = 2)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                value,
                style = TvType.BodyStrong,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = 260.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text("Change", style = TvType.Caption, color = ink3)
        }
    }
}

/**
 * A number typed on the remote. [range] is what the store keeps.
 * A half-typed number stays on screen, and Back or Done puts an unfinished one back.
 */
@Composable
internal fun IntSetting(
    label: String,
    value: Int,
    placeholder: String = "",
    range: IntRange,
    onValue: (Int) -> Unit
) {
    var text by remember { mutableStateOf(value.toString()) }
    var saved by remember { mutableStateOf(value) }
    SettingsField(label, text, placeholder = placeholder, onEditingFinished = {
        val parsed = text.trim().toIntOrNull()
        if (parsed == null) {
            text = saved.toString()
        } else {
            val clamped = parsed.coerceIn(range)
            saved = clamped
            text = clamped.toString()
            onValue(clamped)
        }
    }) { raw ->
        text = raw
        val parsed = raw.trim().toIntOrNull() ?: return@SettingsField
        if (parsed in range) {
            saved = parsed
            onValue(parsed)
        }
    }
}

@Composable
private fun ChoiceRow(label: String, choices: List<Choice>, id: String, onId: (String) -> Unit) {
    val current = choiceFor(choices, id)
    CycleRow(label, current.label, current.sub) { onId(nextChoice(choices, id).id) }
}

@Composable
internal fun LogoPriorityRow(priority: String, onPriority: (String) -> Unit) {
    ChoiceRow("Logos come from", logoChoices, priority, onPriority)
}

@Composable
internal fun PinGraceRow(grace: String, onGrace: (String) -> Unit) {
    ChoiceRow("A correct PIN lasts", pinGraceChoices, grace, onGrace)
}

@Composable
internal fun PicturePane() {
    val editor = rememberPlayerOptionsEditor()
    val options = editor.options
    PaneTitle(
        "Picture & sound",
        "How this TV plays the picture and sound, how far a skip moves, and what the remote buttons do."
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("Buffer")
        ChoiceRow("Buffer size", bufferChoices, options.bufferPreset) { preset ->
            editor.update { it.copy(bufferPreset = preset) }
        }

        SectionLabel("Picture and sound", Modifier.padding(top = 16.dp))
        SwitchRow("Software video decoder", null, options.softwareVideoDecoder) {
            editor.update { it.copy(softwareVideoDecoder = !it.softwareVideoDecoder) }
        }
        SwitchRow("Software audio decoder", null, options.softwareAudioDecoder) {
            editor.update { it.copy(softwareAudioDecoder = !it.softwareAudioDecoder) }
        }
        SwitchRow("Audio passthrough", null, options.audioPassthrough) {
            editor.update { it.copy(audioPassthrough = !it.audioPassthrough) }
        }
        SwitchRow("Tunneled playback", null, options.tunneledPlayback) {
            editor.update { it.copy(tunneledPlayback = !it.tunneledPlayback) }
        }
        SwitchRow("Amlogic picture fix", null, options.amlogicFix) {
            editor.update { it.copy(amlogicFix = !it.amlogicFix) }
        }
        SwitchRow("Auto frame rate", null, options.autoFrameRate) {
            editor.update { it.copy(autoFrameRate = !it.autoFrameRate) }
        }
        SwitchRow("Only 50 and 60 fps", null, options.afrFilmRatesOnly) {
            editor.update { it.copy(afrFilmRatesOnly = !it.afrFilmRatesOnly) }
        }
        SwitchRow("Match screen resolution", null, options.switchResolution) {
            editor.update { it.copy(switchResolution = !it.switchResolution) }
        }
        SwitchRow("Black frame while changing channel", null, options.blackFrameOnZap) {
            editor.update { it.copy(blackFrameOnZap = !it.blackFrameOnZap) }
        }
        SwitchRow("Second Back press to exit", null, options.doubleBackToExit) {
            editor.update { it.copy(doubleBackToExit = !it.doubleBackToExit) }
        }
        SwitchRow("Picture-in-picture when leaving", null, options.pipOnHome) {
            editor.update { it.copy(pipOnHome = !it.pipOnHome) }
        }

        SectionLabel("Network and timing", Modifier.padding(top = 16.dp))
        SettingsNote("Milliseconds. A positive audio offset delays the sound, up to 2 seconds, and a channel change can wait up to 5 seconds. Leave the user agent blank for the usual one. A connection limit of 0 means no limit, and 8 is the most.")
        IntSetting("Audio offset", options.audioOffsetMs, "Milliseconds", -2_000..2_000) { n ->
            editor.update { it.copy(audioOffsetMs = n) }
        }
        SettingsField("User agent", options.userAgent, placeholder = "Blank means the usual one") { text ->
            editor.update { it.copy(userAgent = text) }
        }
        SettingsField("UDP proxy", options.udpProxy, placeholder = "host:port") { text ->
            editor.update { it.copy(udpProxy = text) }
        }
        IntSetting("Channel change delay", options.zapDelayMs, "Milliseconds", 0..5_000) { n ->
            editor.update { it.copy(zapDelayMs = n) }
        }
        IntSetting("Connection limit", options.maxConnections, "0 means no limit", 0..8) { n ->
            editor.update { it.copy(maxConnections = n) }
        }

        SectionLabel("Info panel", Modifier.padding(top = 16.dp))
        SettingsNote("Seconds the banner stays up, from 1 to 30. Transparency is a percent: 0 is solid, and 80 is the most see-through.")
        IntSetting("Timeout", options.infoTimeoutSec, "Seconds", 1..30) { n ->
            editor.update { it.copy(infoTimeoutSec = n) }
        }
        IntSetting("Transparency", options.infoTransparency, "Percent", 0..80) { n ->
            editor.update { it.copy(infoTransparency = n) }
        }

        SectionLabel("Skip distances", Modifier.padding(top = 16.dp))
        SettingsNote("Seconds, from 5 up to 10 minutes.")
        IntSetting("Skip back", options.skipBackSec, "Seconds", 5..600) { n ->
            editor.update { it.copy(skipBackSec = n) }
        }
        IntSetting("Skip forward", options.skipForwardSec, "Seconds", 5..600) { n ->
            editor.update { it.copy(skipForwardSec = n) }
        }
        IntSetting("Time bar back", options.barBackSec, "Seconds", 5..600) { n ->
            editor.update { it.copy(barBackSec = n) }
        }
        IntSetting("Time bar forward", options.barForwardSec, "Seconds", 5..600) { n ->
            editor.update { it.copy(barForwardSec = n) }
        }

        SectionLabel("Multiview", Modifier.padding(top = 16.dp))
        ChoiceRow("Multiview pictures", multiviewChoices, options.multiviewSlots.coerceIn(2, 4).toString()) { slots ->
            editor.update { it.copy(multiviewSlots = slots.toInt()) }
        }

        SectionLabel("Remote buttons", Modifier.padding(top = 16.dp))
        SettingsNote("Each button shows what that key does now. Press it for the next action. Default puts the key back, and only keys you change are saved.")
        remoteKeys.forEach { button ->
            key(button.id) {
                RemoteKeyRow(button.label, remoteLabel(remoteActionId(options.remoteMap, button.id))) {
                    editor.update { current ->
                        val action = remoteActionId(current.remoteMap, button.id)
                        current.copy(remoteMap = remoteMapWith(current.remoteMap, button.id, nextRemoteId(action)))
                    }
                }
            }
        }
    }
}

/**
 * Hide, block, reorder, group, or assign a guide for one channel.
 * The saved text is what the channel list and the guide already read.
 */
@Composable
internal fun ChannelEditor(
    channel: LiveChannel,
    channels: List<LiveChannel>,
    editor: PlayerOptionsEditor,
    onBack: () -> Unit
) {
    val options = editor.options
    val place = orderChannels(channels, options.channelOrder).indexOfFirst { it.id == channel.id }
    var groupName by remember(channel.id) { mutableStateOf("") }
    val guide = options.epgIdFor(channel.id, null).orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PillButton("Back to the list", onBack)
        Text(
            "${channel.number}   ${channel.name}",
            style = TvType.BodyStrong,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(channel.group.ifBlank { "No group" }, style = TvType.Small, color = Tv.Ink2)
        SwitchRow("Hide this channel", "It leaves the list and the guide", channel.id in options.hiddenIds) {
            editor.update { it.copy(hiddenChannelIds = toggleCsvId(it.hiddenChannelIds, channel.id)) }
        }
        SwitchRow("Block this channel", "Asks for the PIN. Set a PIN on the Lock page", channel.id in options.blockedIds) {
            editor.update { it.copy(blockedChannelIds = toggleCsvId(it.blockedChannelIds, channel.id)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(
                "Move earlier",
                { editor.update { it.copy(channelOrder = moveInOrder(channels, it.channelOrder, channel.id, -1)) } },
                enabled = place > 0
            )
            PillButton(
                "Move later",
                { editor.update { it.copy(channelOrder = moveInOrder(channels, it.channelOrder, channel.id, 1)) } },
                enabled = place >= 0 && place < channels.lastIndex
            )
        }
        if (place >= 0) SettingsNote("Position ${place + 1} of ${channels.size}.")
        SettingsField("Guide id", guide, placeholder = channel.epgId?.takeIf { it.isNotBlank() } ?: "From the playlist") { text ->
            editor.update { it.copy(epgAssignments = assignGuide(it.epgAssignments, channel.id, text)) }
        }
        SectionLabel("Custom groups", Modifier.padding(top = 8.dp, bottom = 2.dp))
        SettingsNote("A group name cannot contain an equals sign. The guide lists these groups with the provider's groups.")
        options.customGroupMap().forEach { (name, members) ->
            key(name) {
                SwitchRow(name, null, channel.id in members) {
                    editor.update { current ->
                        val member = channel.id in current.customGroupMap()[name].orEmpty()
                        current.copy(customGroups = editCustomGroup(current.customGroups, name, channel.id, !member))
                    }
                }
            }
        }
        SettingsField("New group", groupName, placeholder = "Kids") { groupName = it }
        val groupOk = groupName.isNotBlank() && !groupName.contains('=') && !groupName.contains('\n')
        PillButton("Add to this group", {
            val name = groupName.trim()
            editor.update { it.copy(customGroups = editCustomGroup(it.customGroups, name, channel.id, true)) }
            groupName = ""
        }, enabled = groupOk)
    }
}

@Composable
private fun RemoteKeyRow(label: String, action: String, onCycle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = TvType.BodyStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        )
        PillButton(action, onCycle, modifier = Modifier.widthIn(min = 200.dp))
    }
}
