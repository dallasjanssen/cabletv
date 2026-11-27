package com.dallas.cabletv.ui.companion

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dallas.cabletv.data.companion.client.PHONE_SYNC_ID
import com.dallas.cabletv.data.companion.client.PairedTv
import com.dallas.cabletv.data.companion.client.SyncChoice
import com.dallas.cabletv.ui.Tv
import com.dallas.cabletv.ui.TvType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What the last Copy did. */
private data class SyncOutcome(val title: String, val lines: List<String>, val error: Boolean)

/**
 * Sync: copy chosen parts between TVs, or between a TV and this phone. The phone keeps its own
 * encrypted copy, so it can be copied from as well as to.
 */
@Composable
fun SyncScreen(model: CompanionModel, onBack: () -> Unit) {
    val tvs by model.tvs.collectAsState()
    val scope = rememberCoroutineScope()
    var fromId by rememberSaveable { mutableStateOf<String?>(null) }
    var toId by rememberSaveable { mutableStateOf<String?>(null) }
    var picks by remember { mutableStateOf(SyncChoice.ALL.map { it.key }.toSet()) }
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<SyncOutcome?>(null) }

    // Discovery keeps the TVs' addresses fresh while this screen is open.
    DisposableEffect(model) {
        model.startDiscovery()
        onDispose { model.stopDiscovery() }
    }

    // Start with a TV copied from and this phone copied to. Drop a choice whose TV was removed,
    // and never let both ends be the same place.
    LaunchedEffect(tvs) {
        if (tvs.isEmpty()) return@LaunchedEffect
        if (fromId != PHONE_SYNC_ID && tvs.none { it.id == fromId }) fromId = tvs.first().id
        if (toId != PHONE_SYNC_ID && tvs.none { it.id == toId }) toId = PHONE_SYNC_ID
        if (toId == fromId) toId = if (fromId == PHONE_SYNC_ID) tvs.first().id else PHONE_SYNC_ID
    }
    val fromPhone = fromId == PHONE_SYNC_ID
    val toPhone = toId == PHONE_SYNC_ID
    val from = tvs.firstOrNull { it.id == fromId }
    val to = tvs.firstOrNull { it.id == toId }
    // Read again after each Copy, so the phone's line shows the copy that was just saved.
    val phoneCopy = remember(outcome) { model.copies.saved(PHONE_SYNC_ID) }
    val savedAt = phoneCopy?.let { SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(it.savedAtMs)) }
    val phoneNote = if (savedAt != null) "Copy saved $savedAt" else "No copy yet. Copy from a TV to this phone first."

    val ready = fromId != null && toId != null && fromId != toId &&
        (fromPhone || from != null) && (toPhone || to != null) &&
        (!fromPhone || phoneCopy != null) && picks.isNotEmpty() && !busy

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietButton("Back", onClick = onBack)
            Text("Sync", style = TvType.Headline, color = Tv.Ink)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (tvs.isEmpty()) {
                SectionCard("Sync needs a TV") {
                    Text("Pair a TV, then come back to copy settings to or from it, or to and from this phone.", style = TvType.Body, color = Tv.Ink2)
                }
                return@Column
            }
            EndpointChoice(
                title = "Copy from",
                tvs = tvs,
                selected = fromId,
                other = toId,
                phoneNote = phoneNote,
                phoneEnabled = phoneCopy != null,
                onSelect = { fromId = it; outcome = null }
            )
            EndpointChoice(
                title = "Copy to",
                tvs = tvs,
                selected = toId,
                other = fromId,
                phoneNote = phoneNote,
                phoneEnabled = true,
                onSelect = { toId = it; outcome = null }
            )

            SectionCard("What to copy") {
                for (choice in SyncChoice.ALL) {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            picks = if (choice.key in picks) picks - choice.key else picks + choice.key
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = choice.key in picks,
                            onCheckedChange = { on -> picks = if (on) picks + choice.key else picks - choice.key },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Tv.Accent,
                                checkmarkColor = Tv.FocusInk,
                                uncheckedColor = Tv.Ink3
                            )
                        )
                        Text(choice.label, style = TvType.Body, color = Tv.Ink)
                    }
                }
                Text(
                    "Logins include passwords. They travel encrypted through this phone. A copy kept on this phone stays encrypted there too.",
                    style = TvType.Small,
                    color = Tv.Ink2
                )
            }

            PrimaryButton(
                if (busy) "Copying..." else "Copy",
                onClick = {
                    val sections = SyncChoice.ALL.map { it.key }.filter { it in picks }
                    busy = true
                    outcome = null
                    scope.launch {
                        outcome = try {
                            val messages: List<String>
                            val title: String
                            when {
                                fromPhone && to != null -> {
                                    messages = model.syncFromPhone(to, sections)
                                    title = "Copied from this phone to ${to.name}."
                                }
                                toPhone && from != null -> {
                                    messages = model.syncToPhone(from, sections)
                                    title = "Copied from ${from.name} to this phone."
                                }
                                from != null && to != null -> {
                                    messages = model.sync(from, to, sections)
                                    title = if (model.syncReview.value != null) "Review the differences for ${to.name}." else "Copied to ${to.name}."
                                }
                                else -> error("Choose two different places.")
                            }
                            SyncOutcome(title, messages, error = false)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            model.errorText(e)?.let { SyncOutcome("Nothing was copied.", listOf(it), error = true) }
                        }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = ready
            )

            outcome?.let { o ->
                SectionCard(null) {
                    Text(o.title, style = TvType.BodyStrong, color = if (o.error) Tv.Live else Tv.Ok)
                    o.lines.forEach { MessageLine(it, error = o.error) }
                }
            }
        }
    }
}

/**
 * The places to pick from: each paired TV, then this phone. The place chosen in the other list
 * cannot be picked here. [phoneEnabled] is false when the phone has no copy to send from.
 */
@Composable
private fun EndpointChoice(
    title: String,
    tvs: List<PairedTv>,
    selected: String?,
    other: String?,
    phoneNote: String,
    phoneEnabled: Boolean,
    onSelect: (String) -> Unit
) {
    SectionCard(title) {
        for (tv in tvs) {
            EndpointRow(
                name = tv.name,
                note = null,
                selected = tv.id == selected,
                blocked = tv.id == other,
                onClick = { onSelect(tv.id) }
            )
        }
        EndpointRow(
            name = "This phone",
            note = phoneNote,
            selected = selected == PHONE_SYNC_ID,
            blocked = !phoneEnabled || other == PHONE_SYNC_ID,
            onClick = { onSelect(PHONE_SYNC_ID) }
        )
    }
}

@Composable
private fun EndpointRow(name: String, note: String?, selected: Boolean, blocked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !blocked, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            enabled = !blocked,
            colors = RadioButtonDefaults.colors(
                selectedColor = Tv.Accent,
                unselectedColor = Tv.Ink3,
                disabledSelectedColor = Tv.Ink3,
                disabledUnselectedColor = Tv.Surface3
            )
        )
        Column {
            Text(name, style = TvType.Body, color = if (blocked) Tv.Ink3 else Tv.Ink)
            if (note != null) Text(note, style = TvType.Small, color = Tv.Ink2)
        }
    }
}
