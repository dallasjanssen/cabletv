package com.dallas.cabletv.ui.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dallas.cabletv.data.companion.client.SessionStatus
import com.dallas.cabletv.ui.Tv
import com.dallas.cabletv.ui.TvType

/** Lines of the log shown on screen. The Copy button takes the whole log. */
private const val LINES_SHOWN = 200

/** The Debug tab: turns the TV's debug logging on or off, and shows what it recorded. */
@Composable
fun DebugTab(m: TvScreenModel) {
    val status by m.status.collectAsState()
    val load by m.debugLog.collectAsState()
    val online = status is SessionStatus.Online
    val clipboard = LocalClipboardManager.current
    val log = load.log
    val text = log?.log ?: ""
    val tail = remember(text) { text.lines().takeLast(LINES_SHOWN).joinToString("\n") }

    LaunchedEffect(online) {
        if (online && log == null && !load.busy) m.loadDebugLog()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (!online) {
            SectionCard(null) {
                Text("The debug log needs the TV. Cable TV is closed or the TV is off.", style = TvType.Body, color = Tv.Ink2)
            }
        } else {
            SectionCard("Debug logging") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep a log on this TV", style = TvType.BodyStrong, color = Tv.Ink)
                        Text(
                            "Records playback and phone connection events. Stream addresses and logins are hidden. Turn it off when you are done.",
                            style = TvType.Body,
                            color = Tv.Ink2
                        )
                    }
                    Switch(
                        checked = log?.enabled == true,
                        onCheckedChange = { m.setDebugLogging(it) },
                        enabled = log != null && !load.busy,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = Tv.Accent,
                            checkedThumbColor = Tv.FocusInk,
                            uncheckedTrackColor = Tv.Surface3
                        )
                    )
                }
            }
            SectionCard("Log") {
                load.error?.let { MessageLine(it, error = true) }
                if (log == null) {
                    Text(if (load.busy) "Loading the log..." else "No log yet.", style = TvType.Body, color = Tv.Ink3)
                } else {
                    Text(
                        if (tail.isBlank()) "Nothing has been recorded yet." else tail,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        color = Tv.Ink2
                    )
                }
                SecondaryButton("Refresh", onClick = { m.loadDebugLog() }, modifier = Modifier.fillMaxWidth(), enabled = !load.busy)
                SecondaryButton(
                    "Copy full log",
                    onClick = { clipboard.setText(AnnotatedString(text)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = log != null
                )
                SecondaryButton(
                    "Clear log",
                    onClick = { m.clearDebugLog() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = log != null && !load.busy
                )
            }
        }
    }
}
