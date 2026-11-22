package com.dallas.cabletv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.dallas.cabletv.CableViewModel
import com.dallas.cabletv.data.JellyfinClient
import com.dallas.cabletv.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(vm: CableViewModel, onExit: () -> Unit) {
    val current by vm.settings.collectAsState()
    val scope = rememberCoroutineScope()

    var xs by remember { mutableStateOf(current.xtreamServer) }
    var xu by remember { mutableStateOf(current.xtreamUser) }
    var xp by remember { mutableStateOf(current.xtreamPass) }
    var m3u by remember { mutableStateOf(current.m3uUrl) }
    var epg by remember { mutableStateOf(current.epgUrl) }
    var js by remember { mutableStateOf(current.jellyfinServer) }
    var ju by remember { mutableStateOf("") }
    var jp by remember { mutableStateOf("") }
    var transcode by remember { mutableStateOf(current.jellyfinTranscodeFallback) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val results = remember { mutableStateMapOf<CableViewModel.Source, String>() }

    /** Settings as they are on screen right now, used by the connection tests. */
    fun formSettings(jellyfinToken: String, jellyfinUser: String): Settings = Settings(
        xtreamServer = xs.trim(),
        xtreamUser = xu.trim(),
        xtreamPass = xp,
        m3uUrl = m3u.trim(),
        epgUrl = epg.trim(),
        jellyfinServer = js.trim(),
        jellyfinToken = jellyfinToken,
        jellyfinUserId = jellyfinUser,
        jellyfinTranscodeFallback = transcode
    )

    fun runTest(source: CableViewModel.Source) {
        // The saved token belongs to the saved server, so testing a changed server needs a save first.
        if (source == CableViewModel.Source.Jellyfin && sameServer(js, current.jellyfinServer).not()) {
            results[source] = "FAILED: Save the new server first, then test."
            return
        }
        scope.launch {
            results[source] = "TESTING..."
            val result = vm.testConnection(source, formSettings(current.jellyfinToken, current.jellyfinUserId))
            results[source] = result.fold(
                onSuccess = { it },
                onFailure = { "FAILED: ${it.message ?: "unknown error"}" }
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        CableHeader("SETTINGS", onBack = onExit)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionTitle("LIVE TV (XTREAM CODES)")
            CableField("Server URL", xs) { xs = it }
            CableField("Username", xu) { xu = it }
            CableField("Password", xp, secret = true) { xp = it }
            TestRow("TEST LIVE TV", results[CableViewModel.Source.Xtream]) {
                runTest(CableViewModel.Source.Xtream)
            }

            SectionTitle("LIVE TV (M3U URL, USED IF NO XTREAM LOGIN)")
            CableField("Playlist URL", m3u) { m3u = it }
            TestRow("TEST PLAYLIST", results[CableViewModel.Source.M3u]) {
                runTest(CableViewModel.Source.M3u)
            }

            SectionTitle("PROGRAM GUIDE")
            Text(
                "With Xtream, the guide comes from the provider automatically. " +
                    "For an M3U playlist, enter an XMLTV guide URL here.",
                color = CableColors.Dim,
                fontSize = 13.sp
            )
            CableField("XMLTV guide URL (optional)", epg) { epg = it }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TestRow("TEST GUIDE", results[CableViewModel.Source.Guide]) {
                    runTest(CableViewModel.Source.Guide)
                }
                OutlinedButton(onClick = { vm.reloadGuide(force = true) }) { Text("RELOAD GUIDE") }
            }

            SectionTitle("ON DEMAND (JELLYFIN)")
            CableField("Server URL", js) { js = it }
            CableField("Username (only needed to sign in again)", ju) { ju = it }
            CableField("Password (not stored)", jp, secret = true) { jp = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Transcode when the TV can't play a file", color = CableColors.Text, fontSize = 16.sp)
                    Text(
                        "Asks the server to convert unsupported files to H.264 and AAC.",
                        color = CableColors.Dim,
                        fontSize = 13.sp
                    )
                }
                Switch(checked = transcode, onCheckedChange = { transcode = it })
            }
            TestRow("TEST ON DEMAND", results[CableViewModel.Source.Jellyfin]) {
                runTest(CableViewModel.Source.Jellyfin)
            }

            message?.let {
                Text(it, color = CableColors.Selected, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        message = "CONTACTING SERVERS..."
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    val xServer = xs.trim()
                                    if (xServer.isNotBlank() && xu.isNotBlank() && xp.isNotBlank()) {
                                        val ok = com.dallas.cabletv.data.XtreamClient(xServer, xu.trim(), xp).login()
                                        check(ok) { "Live TV login failed. Check server, user and password." }
                                    }

                                    var token = current.jellyfinToken
                                    var userId = current.jellyfinUserId
                                    val jServer = js.trim()
                                    if (jServer.isBlank() || !sameServer(jServer, current.jellyfinServer)) {
                                        token = ""
                                        userId = ""
                                    }
                                    if (jServer.isNotBlank() && ju.isNotBlank() && jp.isNotBlank()) {
                                        val (t, u) = JellyfinClient.login(jServer, ju.trim(), jp, vm.deviceId)
                                        token = t
                                        userId = u
                                    }

                                    formSettings(token, userId)
                                }
                            }
                            busy = false
                            result
                                .onSuccess {
                                    message = null
                                    vm.save(it)
                                    onExit()
                                }
                                .onFailure { message = "SAVE FAILED: ${it.message}" }
                        }
                    }
                ) { Text("SAVE") }

                OutlinedButton(onClick = onExit) { Text("CANCEL") }
            }
        }
    }
}

@Composable
private fun TestRow(label: String, result: String?, onTest: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onTest) { Text(label, color = CableColors.Led, fontWeight = FontWeight.Bold) }
        result?.let {
            Text(
                it,
                color = if (it.startsWith("FAILED")) CableColors.Live else CableColors.Good,
                fontSize = 14.sp
            )
        }
    }
}

/** Two server URLs match when they differ only by case, spaces, or a trailing slash. */
private fun sameServer(a: String, b: String): Boolean =
    a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true)

@Composable
private fun CableField(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text
        ),
        modifier = Modifier.fillMaxWidth(),
        textStyle = TextStyle(color = CableColors.Text, fontSize = 17.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = CableColors.Selected,
            unfocusedBorderColor = CableColors.Dim,
            focusedLabelColor = CableColors.Selected,
            unfocusedLabelColor = CableColors.Dim,
            cursorColor = CableColors.Selected
        )
    )
}
