package com.dallas.cabletv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dallas.cabletv.BuildConfig
import com.dallas.cabletv.data.AppUpdater
import com.dallas.cabletv.data.JellyfinClient
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.Profile
import com.dallas.cabletv.data.ProfileStore
import com.dallas.cabletv.data.Settings
import com.dallas.cabletv.data.XtreamClient
import com.dallas.cabletv.data.channelFilters
import com.dallas.cabletv.data.xtreamLoginFields
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SettingsPane(val label: String, val icon: ImageVector) {
    Phone("Set up with your phone", TvIcons.Phone),
    Live("Live TV", TvIcons.Tv),
    Library("Movies & Shows", TvIcons.Film),
    Channels("Channel list", TvIcons.Eye),
    Profiles("Profiles", TvIcons.People),
    Lock("Parental lock", TvIcons.Lock),
    Updates("Updates", TvIcons.Download)
}

/** Checks a TV provider login, or accepts a playlist link, and returns the settings to save. */
suspend fun checkLiveLogin(current: Settings, server: String, user: String, pass: String, m3u: String): Settings =
    withContext(Dispatchers.IO) {
        val playlist = m3u.trim()
        // A pasted playlist link in the server field fills in a blank username and password.
        val (xs, xu, xp) = if (server.isNotBlank() || user.isNotBlank() || pass.isNotBlank()) {
            xtreamLoginFields(server, user, pass)
        } else {
            Triple("", "", "")
        }
        if (xs.isNotEmpty() || xu.isNotEmpty() || xp.isNotEmpty()) {
            require(xs.isNotEmpty() && xu.isNotEmpty() && xp.isNotEmpty()) { "Fill in the server, username and password" }
            XtreamClient(xs, xu, xp).requireActive()
        } else {
            require(playlist.isNotEmpty()) { "Enter a provider login or a playlist link" }
        }
        current.copy(xtreamServer = xs, xtreamUser = xu, xtreamPass = xp, m3uUrl = playlist)
    }

/** Signs in to Jellyfin and returns the settings to save. The password is used once and not stored. */
suspend fun signInJellyfin(current: Settings, server: String, user: String, pass: String): Settings =
    withContext(Dispatchers.IO) {
        val js = server.trim()
        require(js.isNotEmpty() && user.isNotBlank()) { "Enter the server address and username" }
        val (token, userId) = JellyfinClient.login(js, user.trim(), pass)
        current.copy(jellyfinServer = js, jellyfinToken = token, jellyfinUserId = userId)
    }

/** What the person has typed but not saved yet. Phone setup fills these in too. */
private class Drafts(s: Settings) {
    var xs by mutableStateOf(s.xtreamServer)
    var xu by mutableStateOf(s.xtreamUser)
    var xp by mutableStateOf(s.xtreamPass)
    var m3u by mutableStateOf(s.m3uUrl)
    var js by mutableStateOf(s.jellyfinServer)
    var ju by mutableStateOf("")
    var jp by mutableStateOf("")
}

/**
 * Settings in sections. On a TV the sections are listed on the left and moving through them shows
 * each one on the right. Each section saves by itself, so there is no Save button to forget.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(
    nav: NavActions,
    settings: Settings,
    onSave: (Settings) -> Unit,
    startPane: SettingsPane,
    channelCount: Int,
    allChannels: List<LiveChannel>,
    profiles: List<Profile>,
    activeProfileId: String,
    onSwitchProfile: (String) -> Unit,
    onAddProfile: (String) -> Unit,
    onRemoveProfile: (String) -> Unit,
    pinSet: Boolean,
    onSetPin: () -> Unit,
    onRemovePin: () -> Unit,
    update: AppUpdater.Release?,
    updateMessage: String?,
    updateBusy: Boolean,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: (AppUpdater.Release) -> Unit,
    showToast: (String) -> Unit
) {
    var pane by rememberSaveable { mutableStateOf(startPane) }
    var narrowOpen by rememberSaveable { mutableStateOf(false) }
    val drafts = remember { Drafts(settings) }
    val scope = rememberCoroutineScope()
    var liveBusy by remember { mutableStateOf(false) }
    var liveMessage by remember { mutableStateOf<String?>(null) }
    var libraryBusy by remember { mutableStateOf(false) }
    var libraryMessage by remember { mutableStateOf<String?>(null) }
    var phoneMessage by remember { mutableStateOf<String?>(null) }
    val latest by rememberUpdatedState(settings)

    fun saveLive(after: (String) -> Unit = {}) {
        liveBusy = true
        liveMessage = "Checking your login"
        scope.launch {
            runCatching { checkLiveLogin(latest, drafts.xs, drafts.xu, drafts.xp, drafts.m3u) }
                .onSuccess {
                    drafts.xs = it.xtreamServer
                    drafts.xu = it.xtreamUser
                    drafts.xp = it.xtreamPass
                    onSave(it)
                    liveMessage = "Saved. Your channels are loading."
                    after("Live TV is connected.")
                }
                .onFailure {
                    liveMessage = "${it.message ?: "That did not work"}."
                    after("Live TV did not connect: ${it.message ?: "unknown error"}.")
                }
            liveBusy = false
        }
    }

    fun saveLibrary(after: (String) -> Unit = {}) {
        libraryBusy = true
        libraryMessage = "Signing in"
        scope.launch {
            runCatching { signInJellyfin(latest, drafts.js, drafts.ju, drafts.jp) }
                .onSuccess {
                    onSave(it)
                    drafts.jp = ""
                    libraryMessage = "Signed in."
                    after("Jellyfin is signed in.")
                }
                .onFailure {
                    libraryMessage = "Sign in failed. ${it.message ?: ""}".trim()
                    after("Jellyfin did not sign in.")
                }
            libraryBusy = false
        }
    }

    // Details from the phone are filled in and saved right away, then the result is shown here.
    fun fromPhone(fields: Map<String, String>) {
        fields["xs"]?.takeIf { it.isNotBlank() }?.let { drafts.xs = it.trim() }
        fields["xu"]?.takeIf { it.isNotBlank() }?.let { drafts.xu = it.trim() }
        fields["xp"]?.takeIf { it.isNotEmpty() }?.let { drafts.xp = it }
        fields["m3u"]?.takeIf { it.isNotBlank() }?.let { drafts.m3u = it.trim() }
        fields["js"]?.takeIf { it.isNotBlank() }?.let { drafts.js = it.trim() }
        fields["ju"]?.takeIf { it.isNotBlank() }?.let { drafts.ju = it.trim() }
        fields["jp"]?.takeIf { it.isNotEmpty() }?.let { drafts.jp = it }
        val live = listOf("xs", "xu", "xp", "m3u").any { !fields[it].isNullOrBlank() }
        val library = !fields["js"].isNullOrBlank() && !fields["ju"].isNullOrBlank()
        val results = mutableListOf<String>()
        phoneMessage = "Details received. Checking them now."
        fun report(r: String) {
            results += r
            phoneMessage = results.joinToString(" ")
        }
        if (live) saveLive(::report)
        if (library) saveLibrary(::report)
        if (!live && !library) phoneMessage = "Nothing was filled in on the phone."
    }

    val listRequesters = remember { SettingsPane.entries.associateWith { FocusRequester() } }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { listRequesters.getValue(pane).requestFocus() }
    }

    TabScaffold(Tab.Settings, nav, modifier = Modifier.background(Tv.Bg), backEnabled = !narrowOpen) { narrow ->
        val side = gutter(narrow)
        BackHandler(enabled = narrow && narrowOpen) { narrowOpen = false }

        val paneContent: @Composable ColumnScope.() -> Unit = {
            when (pane) {
                SettingsPane.Phone -> PhonePane(phoneMessage, onReceived = { fromPhone(it) })
                SettingsPane.Live -> LivePane(settings, channelCount, drafts, liveBusy, liveMessage, onSave = { saveLive() }, onSignOut = {
                    onSave(settings.copy(xtreamServer = "", xtreamUser = "", xtreamPass = "", m3uUrl = ""))
                    drafts.xs = ""; drafts.xu = ""; drafts.xp = ""; drafts.m3u = ""
                    liveMessage = "Signed out of live TV."
                })
                SettingsPane.Library -> LibraryPane(settings, drafts, libraryBusy, libraryMessage, onSave = { saveLibrary() }, onSignOut = {
                    onSave(settings.copy(jellyfinToken = "", jellyfinUserId = ""))
                    libraryMessage = "Signed out of Jellyfin."
                })
                SettingsPane.Channels -> ChannelsPane(settings, allChannels, onSave)
                SettingsPane.Profiles -> ProfilesPane(profiles, activeProfileId, onSwitchProfile, onAddProfile, onRemoveProfile, showToast)
                SettingsPane.Lock -> LockPane(pinSet, onSetPin, onRemovePin)
                SettingsPane.Updates -> UpdatesPane(update, updateMessage, updateBusy, onCheckUpdate, onInstallUpdate)
            }
        }

        if (narrow) {
            if (!narrowOpen) {
                Column(Modifier.fillMaxSize().padding(top = 60.dp).padding(horizontal = side).verticalScroll(rememberScrollState())) {
                    SettingsPane.entries.forEach { p ->
                        PaneListItem(p, selected = false, Modifier.focusRequester(listRequesters.getValue(p))) {
                            pane = p
                            narrowOpen = true
                        }
                    }
                }
            } else {
                key(pane) {
                    Column(Modifier.fillMaxSize().padding(top = 60.dp).padding(horizontal = side).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                        paneContent()
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxSize().padding(top = 62.dp)) {
                Column(
                    Modifier
                        .padding(start = side - 12.dp)
                        .width(262.dp)
                        .focusProperties { enter = { listRequesters.getValue(pane) } }
                        .focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    SettingsPane.entries.forEach { p ->
                        PaneListItem(
                            p,
                            selected = p == pane,
                            modifier = Modifier.focusRequester(listRequesters.getValue(p)),
                            onFocus = { pane = p }
                        ) {
                            pane = p
                            // OK on a section moves into it.
                            focusManager.moveFocus(FocusDirection.Right)
                        }
                    }
                }
                Spacer(Modifier.width(22.dp))
                key(pane) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .focusProperties {
                                exit = { dir -> if (dir == FocusDirection.Left) listRequesters.getValue(pane) else FocusRequester.Default }
                            }
                            .focusGroup()
                            .verticalScroll(rememberScrollState())
                            .padding(end = side, bottom = 24.dp)
                    ) {
                        paneContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun PaneListItem(p: SettingsPane, selected: Boolean, modifier: Modifier, onFocus: (() -> Unit)? = null, onClick: () -> Unit) {
    Focusable(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(42.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) Tv.Surface2 else Color.Transparent,
        onFocus = { if (it) onFocus?.invoke() },
        contentAlignment = Alignment.CenterStart
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(p.icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (LocalFocused.current) Tv.FocusInk else Tv.Ink2)
            Spacer(Modifier.width(12.dp))
            Text(p.label, style = TvType.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PaneTitle(title: String, lead: String) {
    Text(title, style = TvType.Headline)
    Spacer(Modifier.height(4.dp))
    Text(lead, style = TvType.Small, color = Tv.Ink2, modifier = Modifier.widthIn(max = 560.dp))
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun Message(text: String?) {
    if (text == null) return
    Text(text, style = TvType.SmallStrong, color = Tv.Ink2, modifier = Modifier.padding(top = 10.dp).widthIn(max = 560.dp))
}

@Composable
private fun PhonePane(message: String?, onReceived: (Map<String, String>) -> Unit) {
    PaneTitle(
        "Set up with your phone",
        "Typing server addresses with a remote is slow. Show a code, scan it with your phone, and fill in your details there. They are checked and saved here as soon as you send them."
    )
    PhoneSignInPanel(autoStart = false, onReceived = onReceived)
    Message(message)
}

@Composable
private fun LivePane(
    settings: Settings,
    channelCount: Int,
    drafts: Drafts,
    busy: Boolean,
    message: String?,
    onSave: () -> Unit,
    onSignOut: () -> Unit
) {
    PaneTitle("Live TV", "Your TV provider login. Channels and the guide come from here. A full playlist link pasted into Server fills in the username and password.")
    if (settings.hasLiveSource) {
        StatusChip(if (channelCount > 0) "Connected · $channelCount channels" else "Connected", ok = true)
    } else {
        StatusChip("Not set up", ok = false)
    }
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsField("Server", drafts.xs, placeholder = "http://example.com:8080") { drafts.xs = it }
        SettingsField("Username", drafts.xu) { drafts.xu = it }
        SettingsField("Password", drafts.xp, secret = true) { drafts.xp = it }
        SettingsField("Playlist link", drafts.m3u, placeholder = "Only if you have no login") { drafts.m3u = it }
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton(if (busy) "Checking" else "Check and save", onSave, icon = TvIcons.Check, enabled = !busy)
        if (settings.hasLiveSource) ConfirmButton("Sign out", "Press again to sign out", onSignOut)
    }
    Message(message)
}

@Composable
private fun LibraryPane(
    settings: Settings,
    drafts: Drafts,
    busy: Boolean,
    message: String?,
    onSave: () -> Unit,
    onSignOut: () -> Unit
) {
    PaneTitle("Movies & Shows", "Add your Jellyfin server to see your own library. Movies and shows from your TV provider appear on their own.")
    if (settings.hasJellyfin) StatusChip("Signed in to Jellyfin", ok = true) else StatusChip("Jellyfin is not set up", ok = false)
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsField("Jellyfin server", drafts.js, placeholder = "http://192.168.1.20:8096") { drafts.js = it }
        SettingsField("Username", drafts.ju, placeholder = if (settings.hasJellyfin) "Only to sign in again" else "") { drafts.ju = it }
        SettingsField("Password", drafts.jp, secret = true, placeholder = "Used once, never stored") { drafts.jp = it }
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton(if (busy) "Signing in" else "Sign in", onSave, icon = TvIcons.Check, enabled = !busy)
        if (settings.hasJellyfin) ConfirmButton("Sign out", "Press again to sign out", onSignOut)
    }
    Message(message)
}

@Composable
private fun ChannelsPane(settings: Settings, allChannels: List<LiveChannel>, onSave: (Settings) -> Unit) {
    PaneTitle("Channel list", "Choose what shows in the guide and the channel list. Changes save right away.")
    val hidden = settings.hiddenTerms
    fun toggle(term: String) {
        val set = hidden.toMutableSet()
        if (!set.remove(term)) set.add(term)
        onSave(settings.copy(hiddenFilters = set.joinToString(",")))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SwitchRow("Show names without country codes", "UK| News becomes News", settings.stripPrefixes) {
            onSave(settings.copy(stripPrefixes = !settings.stripPrefixes))
        }
    }
    if (allChannels.isEmpty()) {
        Message("Connect live TV to choose which groups show.")
        return
    }
    val (prefixes, categories) = remember(allChannels) { channelFilters(allChannels) }
    if (prefixes.isNotEmpty()) {
        SectionLabel("Countries to show", Modifier.padding(top = 18.dp, bottom = 8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            prefixes.forEach { f -> SwitchRow(f.label, channelCount(f.count), f.term !in hidden) { toggle(f.term) } }
        }
    }
    if (categories.isNotEmpty()) {
        SectionLabel("Groups to show", Modifier.padding(top = 18.dp, bottom = 8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            categories.forEach { f -> SwitchRow(f.label, channelCount(f.count), f.term !in hidden) { toggle(f.term) } }
        }
    }
}

@Composable
private fun ProfilesPane(
    profiles: List<Profile>,
    activeId: String,
    onSwitch: (String) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    showToast: (String) -> Unit
) {
    PaneTitle("Profiles", "Each person gets their own favorites, recently watched channels and resume points. Switch from the name at the top right.")
    var newName by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        profiles.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Focusable(
                    onClick = {
                        onSwitch(p.id)
                        showToast("Switched to ${p.name}")
                    },
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = Tv.Surface,
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(p.name, Modifier.size(26.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(p.name, style = TvType.BodyStrong, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (p.id == activeId) "Watching now" else "Switch to this profile", style = TvType.Caption, color = ink3)
                    }
                }
                if (p.id != ProfileStore.DEFAULT_ID) ConfirmButton("Remove", "Press again", { onRemove(p.id) })
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    SettingsField("New profile name", newName) { newName = it }
    Spacer(Modifier.height(10.dp))
    PillButton("Add profile", {
        onAdd(newName)
        showToast("Added ${newName.trim()}")
        newName = ""
    }, icon = TvIcons.Plus, enabled = newName.isNotBlank())
}

@Composable
private fun LockPane(pinSet: Boolean, onSetPin: () -> Unit, onRemovePin: () -> Unit) {
    PaneTitle("Parental lock", "A PIN stops others from opening Settings and changing which channels show.")
    if (pinSet) StatusChip("On. Settings need the PIN.", ok = true) else StatusChip("Off. Anyone can open Settings.", ok = false)
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (pinSet) {
            PillButton("Change PIN", onSetPin, icon = TvIcons.Lock)
            PillButton("Turn off", onRemovePin)
        } else {
            PillButton("Set a PIN", onSetPin, icon = TvIcons.Lock)
        }
    }
}

@Composable
private fun UpdatesPane(
    update: AppUpdater.Release?,
    message: String?,
    busy: Boolean,
    onCheck: () -> Unit,
    onInstall: (AppUpdater.Release) -> Unit
) {
    PaneTitle("Updates", "New versions come from your GitHub builds. Installing always asks you to confirm.")
    if (update != null) {
        StatusChip("Build ${update.versionCode} is ready. You have build ${BuildConfig.VERSION_CODE}.", ok = false)
    } else {
        StatusChip("You have build ${BuildConfig.VERSION_CODE}", ok = true)
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (update != null) PillButton("Install build ${update.versionCode}", { onInstall(update) }, icon = TvIcons.Download, enabled = !busy)
        PillButton(if (busy) "Checking" else "Check for updates", onCheck, enabled = !busy)
    }
    Message(message)
}

private fun channelCount(n: Int) = if (n == 1) "1 channel" else "$n channels"

/** A button that asks for a second press before doing something hard to undo. */
@Composable
private fun ConfirmButton(label: String, confirmLabel: String, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(4000)
            armed = false
        }
    }
    PillButton(if (armed) confirmLabel else label, {
        if (armed) {
            armed = false
            onConfirm()
        } else {
            armed = true
        }
    }, onFocus = { if (!it) armed = false })
}

/** A labelled on and off switch. The whole row is one target, so OK flips it. */
@Composable
fun SwitchRow(label: String, sub: String?, checked: Boolean, onToggle: () -> Unit) {
    Focusable(onClick = onToggle, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp), shape = RoundedCornerShape(10.dp), color = Tv.Surface, contentAlignment = Alignment.CenterStart) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = TvType.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (sub != null) Text("  ·  $sub", style = TvType.Small, color = ink3, maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
            val focused = LocalFocused.current
            Box(
                Modifier
                    .size(38.dp, 22.dp)
                    .clip(CircleShape)
                    .background(if (checked) Tv.Ok else if (focused) Color(0xFFC9CED6) else Tv.Surface3)
                    .padding(3.dp),
                contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                Box(Modifier.size(16.dp).clip(CircleShape).background(if (checked) Color.White else Tv.Ink2))
            }
        }
    }
}

/**
 * A text setting. Moving over it only highlights it, so the keyboard stays closed. OK starts
 * editing and opens the keyboard. Done or Back finishes and returns the highlight to the row.
 */
@Composable
fun SettingsField(
    label: String,
    value: String,
    secret: Boolean = false,
    placeholder: String = "",
    onChange: (String) -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    val row = remember { FocusRequester() }
    val input = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    fun finish() {
        editing = false
        scope.launch {
            withFrameNanos { }
            runCatching { row.requestFocus() }
        }
    }

    BackHandler(enabled = editing) { finish() }
    LaunchedEffect(editing) {
        if (editing) {
            withFrameNanos { }
            runCatching { input.requestFocus() }
        }
    }

    Focusable(
        onClick = { editing = true },
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).focusRequester(row),
        shape = RoundedCornerShape(10.dp),
        color = if (editing) Tv.Surface3 else Tv.Surface,
        contentAlignment = Alignment.CenterStart
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = TvType.Small, color = ink2, modifier = Modifier.width(150.dp), maxLines = 1)
            if (editing) {
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = TvType.BodyStrong.copy(color = Tv.Ink),
                    cursorBrush = SolidColor(Tv.Accent),
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { finish() }),
                    modifier = Modifier.weight(1f).focusRequester(input)
                )
            } else {
                Text(
                    when {
                        value.isEmpty() -> placeholder.ifEmpty { "Not set" }
                        secret -> "•".repeat(value.length.coerceAtMost(16))
                        else -> value
                    },
                    style = if (value.isEmpty()) TvType.Small else TvType.BodyStrong,
                    color = if (value.isEmpty()) ink3 else androidx.compose.material3.LocalContentColor.current,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                Text("Edit", style = TvType.Caption, color = ink3)
            }
        }
    }
}
