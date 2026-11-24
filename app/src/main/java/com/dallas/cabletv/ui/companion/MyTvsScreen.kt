package com.dallas.cabletv.ui.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dallas.cabletv.data.companion.client.DiscoveredTv
import com.dallas.cabletv.data.companion.client.PairedTv
import com.dallas.cabletv.ui.Tv
import com.dallas.cabletv.ui.TvType

/**
 * My TVs: the TVs this phone has paired with and which are on the network now, the TVs found
 * that are not paired yet, and the way to add one by address, sync two TVs, or watch on this phone.
 * Discovery runs while this screen is showing.
 */
@Composable
fun MyTvsScreen(
    model: CompanionModel,
    onOpenTv: (String) -> Unit,
    onSync: () -> Unit,
    onWatchOnPhone: () -> Unit
) {
    DisposableEffect(model) {
        model.startDiscovery()
        onDispose { model.stopDiscovery() }
    }
    val paired by model.tvs.collectAsState()
    val found by model.found.collectAsState()
    val notice by model.notice.collectAsState()
    val onlineIds = found.map { it.id }.toSet()
    val unpaired = found.filter { f -> paired.none { it.id == f.id } }

    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<PairedTv?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("My TVs", style = TvType.Hero, color = Tv.Ink)
            Spacer(Modifier.height(2.dp))
            Text("Control the TVs you have paired with this phone.", style = TvType.Body, color = Tv.Ink2)
        }
        if (notice != null) {
            item {
                SectionCard(null) {
                    Text(notice.orEmpty(), style = TvType.Body, color = Tv.Ink)
                    QuietButton("Dismiss", onClick = { model.clearNotice() })
                }
            }
        }
        if (paired.isEmpty()) {
            item {
                SectionCard("No TVs yet") {
                    Text(
                        "Open Cable TV on your TV. It will show up below, and you can pair it here.",
                        style = TvType.Body,
                        color = Tv.Ink2
                    )
                }
            }
        } else {
            items(paired, key = { it.id }) { tv ->
                PairedTvRow(
                    tv = tv,
                    online = tv.id in onlineIds,
                    onOpen = { onOpenTv(tv.id) },
                    onRemove = { removing = tv }
                )
            }
        }

        item {
            Spacer(Modifier.height(6.dp))
            Text("Found on your network", style = TvType.Section, color = Tv.Ink)
        }
        if (unpaired.isEmpty()) {
            item {
                Text(
                    if (found.isEmpty()) "Looking for TVs with Cable TV open." else "No other TVs found.",
                    style = TvType.Body,
                    color = Tv.Ink3
                )
            }
        } else {
            items(unpaired, key = { it.id }) { tv ->
                FoundTvRow(tv) { model.startPairing(tv.host, tv.port, tv.name, repairId = null) }
            }
        }

        item {
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Add by address", onClick = { adding = true }, modifier = Modifier.fillMaxWidth())
                SecondaryButton("Sync", onClick = onSync, modifier = Modifier.fillMaxWidth(), enabled = paired.size >= 2)
                if (paired.size < 2) {
                    Text("Sync needs two paired TVs.", style = TvType.Caption, color = Tv.Ink3)
                }
                SecondaryButton("Watch on this phone", onClick = onWatchOnPhone, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    if (adding) {
        AddByAddressDialog(
            onAdd = { host, port ->
                val problem = model.addByAddress(host, port)
                if (problem == null) adding = false
                problem
            },
            onDismiss = { adding = false }
        )
    }

    removing?.let { tv ->
        MessageDialog(
            title = "Remove ${tv.name}?",
            message = "This phone will forget it. The TV keeps this phone in its own list until you remove it there.",
            confirmLabel = "Remove",
            onConfirm = {
                model.remove(tv)
                removing = null
            },
            onDismiss = { removing = null },
            dismissLabel = "Keep"
        )
    }
}

@Composable
private fun PairedTvRow(tv: PairedTv, online: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Tv.Surface)
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OnlineDot(online)
        Column(Modifier.weight(1f)) {
            Text(tv.name, style = TvType.Title, color = Tv.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (online) "On the network" else "Cable TV is closed or the TV is off",
                style = TvType.Small,
                color = if (online) Tv.Ok else Tv.Ink3
            )
        }
        QuietButton("Remove", onClick = onRemove)
    }
}

@Composable
private fun FoundTvRow(tv: DiscoveredTv, onPair: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Tv.Surface)
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OnlineDot(true)
        Column(Modifier.weight(1f)) {
            Text(tv.name, style = TvType.Title, color = Tv.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (tv.needsNewerApp) "Update this app to pair" else "Not paired yet",
                style = TvType.Small,
                color = Tv.Ink3
            )
        }
        PrimaryButton("Pair", onClick = onPair, enabled = !tv.needsNewerApp)
    }
}

/** Asks for a TV's IP address and port. [onAdd] returns a sentence to show when the address will not do. */
@Composable
private fun AddByAddressDialog(onAdd: (host: String, port: String) -> String?, onDismiss: () -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tv.Surface2,
        titleContentColor = Tv.Ink,
        textContentColor = Tv.Ink2,
        title = { Text("Add by address", style = TvType.Title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Use this when your network hides TVs from this phone. Cable TV must be open on the TV.",
                    style = TvType.Body,
                    color = Tv.Ink2
                )
                LabeledField("IP address", host, { host = it }, placeholder = "192.168.1.20")
                LabeledField(
                    "Port",
                    port,
                    { port = it.filter(Char::isDigit).take(5) },
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    placeholder = "8766"
                )
                problem?.let { MessageLine(it, error = true) }
            }
        },
        confirmButton = { QuietButton("Pair", onClick = { problem = onAdd(host, port) }) },
        dismissButton = { QuietButton("Cancel", onClick = onDismiss) }
    )
}
