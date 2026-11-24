package com.dallas.cabletv.ui.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dallas.cabletv.data.companion.client.ChannelSettings
import com.dallas.cabletv.data.companion.client.LibrarySettings
import com.dallas.cabletv.data.companion.client.LiveSettings
import com.dallas.cabletv.data.companion.client.SessionStatus
import com.dallas.cabletv.data.companion.client.SettingsChanges
import com.dallas.cabletv.ui.StatusChip
import com.dallas.cabletv.ui.Tv
import com.dallas.cabletv.ui.TvType
import org.json.JSONObject

/** The Settings tab: the TV's settings sections as forms, each with its own Save. */
@Composable
fun SettingsTab(m: TvScreenModel) {
    val status by m.status.collectAsState()
    val load by m.settings.collectAsState()
    val sections by m.sections.collectAsState()
    val online = status is SessionStatus.Online

    LaunchedEffect(online, load) {
        if (online && load is SettingsLoad.Idle) m.loadSettings()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        when {
            load is SettingsLoad.Loaded -> {
                val s = (load as SettingsLoad.Loaded).settings
                fun save(key: String, change: JSONObject) = m.save(key, change)
                LiveSection(s.live, sections["live"]) { save("live", it) }
                LibrarySection(s.library, sections["library"]) { save("library", it) }
                ChannelsSection(s.channels, s.activeProfileName, sections["channels"]) { save("channels", it) }
                WebhookSection(s.webhook, sections["home_assistant"]) { save("home_assistant", it) }
            }
            !online -> SectionCard(null) {
                Text("Settings need the TV. Cable TV is closed or the TV is off.", style = TvType.Body, color = Tv.Ink2)
            }
            load is SettingsLoad.Failed -> SectionCard("Settings") {
                MessageLine((load as SettingsLoad.Failed).message, error = true)
                SecondaryButton("Try again", onClick = { m.loadSettings() }, modifier = Modifier.fillMaxWidth())
            }
            else -> Text("Loading settings...", style = TvType.Body, color = Tv.Ink3)
        }
    }
}

@Composable
private fun SaveResult(result: SectionResult?) {
    if (result == null) return
    result.messages.forEach { MessageLine(it, error = false) }
    result.error?.let { MessageLine(it, error = true) }
}

@Composable
private fun LiveSection(live: LiveSettings, result: SectionResult?, onSave: (JSONObject) -> Unit) {
    var server by remember(live) { mutableStateOf(live.server) }
    var user by remember(live) { mutableStateOf(live.username) }
    var pass by remember(live) { mutableStateOf(live.password) }
    var playlist by remember(live) { mutableStateOf(live.playlist) }
    var guide by remember(live) { mutableStateOf(live.guide) }
    var portal by remember(live) { mutableStateOf(live.stalkerPortal) }
    var mac by remember(live) { mutableStateOf(live.stalkerMac) }
    SectionCard("Live TV") {
        StatusChip(if (live.connected) "Connected, ${live.channelCount} channels" else "Not set up", live.connected)
        LabeledField("Server", server, { server = it }, keyboardType = KeyboardType.Uri)
        LabeledField("Username", user, { user = it })
        LabeledField("Password", pass, { pass = it }, secret = true)
        LabeledField("Playlist address (M3U)", playlist, { playlist = it }, keyboardType = KeyboardType.Uri)
        LabeledField("Guide address (XMLTV)", guide, { guide = it }, keyboardType = KeyboardType.Uri)
        LabeledField("Stalker portal", portal, { portal = it }, keyboardType = KeyboardType.Uri)
        LabeledField("Stalker MAC address", mac, { mac = it })
        PrimaryButton(
            if (result?.busy == true) "Checking..." else "Check and save",
            onClick = { onSave(SettingsChanges.live(server, user, pass, playlist, guide, portal, mac)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = result?.busy != true
        )
        SaveResult(result)
    }
}

@Composable
private fun LibrarySection(library: LibrarySettings, result: SectionResult?, onSave: (JSONObject) -> Unit) {
    var server by remember(library) { mutableStateOf(library.server) }
    var user by remember(library) { mutableStateOf("") }
    var pass by remember(library) { mutableStateOf("") }
    SectionCard("Movies & Shows") {
        StatusChip(if (library.signedIn) "Signed in" else "Not signed in", library.signedIn)
        if (library.signedIn) {
            if (library.server.isNotBlank()) Text(library.server, style = TvType.Small, color = Tv.Ink2)
            SecondaryButton(
                if (result?.busy == true) "Signing out..." else "Sign out",
                onClick = { onSave(SettingsChanges.librarySignOut()) },
                modifier = Modifier.fillMaxWidth(),
                enabled = result?.busy != true
            )
        } else {
            LabeledField("Jellyfin server", server, { server = it }, keyboardType = KeyboardType.Uri)
            LabeledField("Username", user, { user = it })
            LabeledField("Password", pass, { pass = it }, secret = true)
            PrimaryButton(
                if (result?.busy == true) "Signing in..." else "Sign in",
                onClick = { onSave(SettingsChanges.librarySignIn(server, user, pass)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = result?.busy != true
            )
        }
        SaveResult(result)
    }
}

@Composable
private fun ChannelsSection(channels: ChannelSettings, profile: String?, result: SectionResult?, onSave: (JSONObject) -> Unit) {
    var hidden by remember(channels) { mutableStateOf(channels.hidden) }
    var locked by remember(channels) { mutableStateOf(channels.locked) }
    var strip by remember(channels) { mutableStateOf(channels.stripPrefixes) }
    SectionCard("Channel list") {
        LabeledField(
            if (profile != null) "Hidden channels for $profile" else "Hidden channels",
            hidden, { hidden = it }, singleLine = false, minLines = 2
        )
        LabeledField("Locked channels", locked, { locked = it }, singleLine = false, minLines = 2)
        LabeledRow("Show names without country prefixes") {
            Switch(
                checked = strip,
                onCheckedChange = { strip = it },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Tv.Accent,
                    checkedThumbColor = Tv.FocusInk,
                    uncheckedTrackColor = Tv.Surface3,
                    uncheckedThumbColor = Tv.Ink2,
                    uncheckedBorderColor = Tv.Surface3
                )
            )
        }
        PrimaryButton(
            if (result?.busy == true) "Saving..." else "Save",
            onClick = { onSave(SettingsChanges.channels(hidden, locked, strip)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = result?.busy != true
        )
        SaveResult(result)
    }
}

@Composable
private fun WebhookSection(webhook: String, result: SectionResult?, onSave: (JSONObject) -> Unit) {
    var url by remember(webhook) { mutableStateOf(webhook) }
    SectionCard("Home Assistant") {
        Text("The TV sends what is playing to this webhook address. Leave it empty to turn that off.", style = TvType.Small, color = Tv.Ink2)
        LabeledField("Webhook address", url, { url = it }, keyboardType = KeyboardType.Uri)
        PrimaryButton(
            if (result?.busy == true) "Saving..." else "Save",
            onClick = { onSave(SettingsChanges.webhook(url)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = result?.busy != true
        )
        SaveResult(result)
    }
}
