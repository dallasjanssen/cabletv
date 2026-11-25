package com.dallas.cabletv.ui.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dallas.cabletv.data.companion.client.SessionStatus
import com.dallas.cabletv.ui.StatusChip
import com.dallas.cabletv.ui.Tv
import com.dallas.cabletv.ui.TvType

/** One TV's screen, with Remote and Settings tabs. The connection lives while this screen is open. */
@Composable
fun TvScreen(model: CompanionModel, tvId: String, onBack: () -> Unit) {
    val tvs by model.tvs.collectAsState()
    val tv = tvs.firstOrNull { it.id == tvId }
    if (tv == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val m = remember(tvId) { TvScreenModel(tvId, model) }
    DisposableEffect(m) {
        m.start()
        onDispose { m.stop() }
    }
    val status by m.status.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            QuietButton("Back", onClick = onBack)
            Text(tv.name, style = TvType.Headline, color = Tv.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            when (status) {
                is SessionStatus.Online -> StatusChip("Connected", true)
                is SessionStatus.Connecting -> StatusChip("Connecting", false)
                else -> StatusChip("Not connected", false)
            }
        }
        TabRow(
            selectedTabIndex = tab,
            containerColor = Tv.Bg,
            contentColor = Tv.Accent
        ) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Remote", style = TvType.BodyStrong) },
                selectedContentColor = Tv.Accent, unselectedContentColor = Tv.Ink2)
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Settings", style = TvType.BodyStrong) },
                selectedContentColor = Tv.Accent, unselectedContentColor = Tv.Ink2)
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Debug", style = TvType.BodyStrong) },
                selectedContentColor = Tv.Accent, unselectedContentColor = Tv.Ink2)
        }
        val s = status
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (s is SessionStatus.IdentityChanged || s is SessionStatus.NotPaired) {
                Column(Modifier.padding(16.dp)) {
                    SectionCard("Pair again") {
                        val text = if (s is SessionStatus.IdentityChanged) s.message else "This TV does not know this phone. Pair it again."
                        MessageLine(text, error = true)
                        PrimaryButton(
                            "Pair again",
                            onClick = { model.startPairing(tv.host, tv.port, tv.name, repairId = tv.id) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            } else if (tab == 0) {
                RemoteTab(m, tv)
            } else if (tab == 1) {
                SettingsTab(m)
            } else {
                DebugTab(m)
            }
        }
    }

    val atvUi by m.atvPairUi.collectAsState()
    when (val ui = atvUi) {
        null -> Unit
        is AtvPairUi.Connecting -> MessageDialog(
            title = "Power and volume",
            message = "Connecting to ${tv.name}. A code will appear on the TV.",
            confirmLabel = "Cancel",
            onConfirm = { m.cancelAtvPairing() },
            onDismiss = { m.cancelAtvPairing() },
            dismissLabel = "Close"
        )
        is AtvPairUi.EnterCode -> CodeDialog(
            title = "Power and volume",
            message = "Type the 6 character code shown on the TV.",
            length = 6,
            hex = true,
            error = ui.error,
            busy = ui.busy,
            onConfirm = { m.submitAtvCode(it) },
            onDismiss = { m.cancelAtvPairing() }
        )
        is AtvPairUi.Failed -> MessageDialog(
            title = "Power and volume",
            message = ui.message,
            confirmLabel = "Try again",
            onConfirm = { m.startAtvPairing() },
            onDismiss = { m.cancelAtvPairing() }
        )
    }
}
