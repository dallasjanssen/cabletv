package com.dallas.cabletv.ui.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dallas.cabletv.data.AppUpdater
import com.dallas.cabletv.ui.Tv
import com.dallas.cabletv.ui.TvType

/**
 * Shown before the phone starts watching when a newer build is out. The person can install it
 * first, watch the current build, or stay on My TVs.
 */
@Composable
fun UpdateOfferDialog(
    release: AppUpdater.Release,
    busy: Boolean,
    note: String?,
    onInstall: () -> Unit,
    onWatch: () -> Unit,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = { if (!busy) onCancel() }) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Tv.Bg)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("A newer build is out", style = TvType.Section, color = Tv.Ink)
            Text(
                "Build ${release.versionCode} is ready. Install it before you watch, or watch the build you have now.",
                style = TvType.Body,
                color = Tv.Ink2
            )
            note?.let { SectionCard(null) { MessageLine(it, error = true) } }
            PrimaryButton(
                if (busy) "Downloading..." else "Install and then watch",
                onClick = onInstall,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            )
            SecondaryButton("Watch this build", onClick = onWatch, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton("Cancel", onClick = onCancel, enabled = !busy)
            }
        }
    }
}
