package com.dallas.cabletv.ui.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dallas.cabletv.data.companion.client.SyncConflict
import com.dallas.cabletv.ui.Tv
import com.dallas.cabletv.ui.TvType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Lists the settings that differ between a copy and a TV. The copy's value is used for each one
 * unless the person picks the TV's. Settings that match are copied without asking.
 */
@Composable
fun SyncReviewDialog(model: CompanionModel, plan: SyncPlan) {
    val scope = rememberCoroutineScope()
    var keepTv by remember(plan) { mutableStateOf(emptySet<String>()) }
    var busy by remember(plan) { mutableStateOf(false) }
    var done by remember(plan) { mutableStateOf<List<String>?>(null) }
    var error by remember(plan) { mutableStateOf<String?>(null) }

    fun close() {
        if (!busy) model.dismissSyncReview()
    }

    Dialog(onDismissRequest = { close() }) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Tv.Bg)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(plan.title, style = TvType.Section, color = Tv.Ink)

            val finished = done
            if (finished != null) {
                SectionCard("Done") {
                    finished.forEach { MessageLine(it, error = false) }
                    PrimaryButton("Close", onClick = { close() }, modifier = Modifier.fillMaxWidth())
                }
                return@Dialog
            }

            if (plan.conflicts.isEmpty()) {
                Text("Nothing differs. Everything will be copied.", style = TvType.Body, color = Tv.Ink2)
            } else {
                Text(
                    "${plan.conflicts.size} settings differ. Each one uses the copy unless you choose to keep the TV's value. Settings that match are copied without asking.",
                    style = TvType.Small,
                    color = Tv.Ink2
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuietButton("Use copy for all", onClick = { keepTv = emptySet() }, enabled = !busy)
                    QuietButton(
                        "Keep TV for all",
                        onClick = { keepTv = plan.conflicts.map { it.id }.toSet() },
                        enabled = !busy
                    )
                }
            }

            plan.conflicts.forEach { c ->
                ConflictCard(c, keepTv = c.id in keepTv, enabled = !busy) { useTv ->
                    keepTv = if (useTv) keepTv + c.id else keepTv - c.id
                }
            }

            error?.let { MessageLine(it, error = true) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton("Cancel", onClick = { close() }, enabled = !busy)
                PrimaryButton(
                    if (busy) "Applying..." else "Apply",
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                done = model.applySync(plan, keepTv)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = model.errorText(e) ?: "Nothing was changed."
                            } finally {
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                )
            }
        }
    }
}

@Composable
private fun ConflictCard(c: SyncConflict, keepTv: Boolean, enabled: Boolean, onChoose: (useTv: Boolean) -> Unit) {
    SectionCard(c.label) {
        Text("Copy: ${c.copyText}", style = TvType.Small, color = Tv.Ink2)
        Text("This TV: ${c.tvText}", style = TvType.Small, color = Tv.Ink2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (keepTv) {
                SecondaryButton("Use copy", onClick = { onChoose(false) }, enabled = enabled)
                PrimaryButton("Keep TV", onClick = { onChoose(true) }, enabled = enabled)
            } else {
                PrimaryButton("Use copy", onClick = { onChoose(false) }, enabled = enabled)
                SecondaryButton("Keep TV", onClick = { onChoose(true) }, enabled = enabled)
            }
        }
    }
}
