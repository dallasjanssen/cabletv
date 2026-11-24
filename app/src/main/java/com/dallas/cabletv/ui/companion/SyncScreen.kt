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
import com.dallas.cabletv.data.companion.client.PairedTv
import com.dallas.cabletv.data.companion.client.SyncChoice
import com.dallas.cabletv.ui.Tv
import com.dallas.cabletv.ui.TvType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What the last Copy did. */
private data class SyncOutcome(val title: String, val lines: List<String>, val error: Boolean)

/** Sync: copy chosen parts from one paired TV to another and show what the target said. */
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

    // Start with two different TVs chosen, and drop a choice whose TV was removed.
    LaunchedEffect(tvs) {
        if (tvs.none { it.id == fromId }) fromId = tvs.firstOrNull()?.id
        if (tvs.none { it.id == toId } || toId == fromId) toId = tvs.firstOrNull { it.id != fromId }?.id
    }
    val from = tvs.firstOrNull { it.id == fromId }
    val to = tvs.firstOrNull { it.id == toId }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietButton("Back", onClick = onBack)
            Text("Sync", style = TvType.Headline, color = Tv.Ink)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (tvs.size < 2) {
                SectionCard("Sync needs two TVs") {
                    Text("Pair at least two TVs, then come back to copy settings between them.", style = TvType.Body, color = Tv.Ink2)
                }
                return@Column
            }
            TvChoice("Copy from", tvs, fromId, other = toId) { fromId = it; outcome = null }
            TvChoice("Copy to", tvs, toId, other = fromId) { toId = it; outcome = null }

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
                    "Logins include passwords. They travel encrypted from one TV to the other through this phone, which keeps nothing.",
                    style = TvType.Small,
                    color = Tv.Ink2
                )
            }

            PrimaryButton(
                if (busy) "Copying..." else "Copy",
                onClick = {
                    val source = from ?: return@PrimaryButton
                    val target = to ?: return@PrimaryButton
                    val sections = SyncChoice.ALL.map { it.key }.filter { it in picks }
                    busy = true
                    outcome = null
                    scope.launch {
                        outcome = try {
                            val messages = model.sync(source, target, sections)
                            SyncOutcome("Copied to ${target.name}.", messages, error = false)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            model.errorText(e)?.let { SyncOutcome("Nothing was copied.", listOf(it), error = true) }
                        }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && from != null && to != null && from.id != to.id && picks.isNotEmpty()
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

/** A list of TVs to pick one from. The TV chosen in the other list cannot be picked here. */
@Composable
private fun TvChoice(title: String, tvs: List<PairedTv>, selected: String?, other: String?, onSelect: (String) -> Unit) {
    SectionCard(title) {
        for (tv in tvs) {
            val blocked = tv.id == other
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !blocked) { onSelect(tv.id) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = tv.id == selected,
                    onClick = { onSelect(tv.id) },
                    enabled = !blocked,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = Tv.Accent,
                        unselectedColor = Tv.Ink3,
                        disabledSelectedColor = Tv.Ink3,
                        disabledUnselectedColor = Tv.Surface3
                    )
                )
                Text(tv.name, style = TvType.Body, color = if (blocked) Tv.Ink3 else Tv.Ink)
            }
        }
    }
}
