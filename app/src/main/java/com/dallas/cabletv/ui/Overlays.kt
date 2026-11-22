package com.dallas.cabletv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import android.view.KeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C

/**
 * A search box that the D-pad can leave. Up and down move focus to the neighboring control,
 * instead of staying trapped in the text field, and the keyboard's Done key closes the keyboard.
 */
@Composable
fun TvSearchField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        focusManager.moveFocus(FocusDirection.Down)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        focusManager.moveFocus(FocusDirection.Up)
                        true
                    }
                    else -> false
                }
            }
    )
}

/** Audio and subtitle chooser, drawn over the video. */
@Composable
fun TrackMenu(playback: Playback, onDismiss: () -> Unit) {
    val audio = remember { playback.trackOptions(C.TRACK_TYPE_AUDIO) }
    val subs = remember { playback.trackOptions(C.TRACK_TYPE_TEXT) }
    val noSubsSelected = subs.none { it.selected }
    // Focus starts on the first row so the remote can drive the menu straight away.
    val firstRow = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstRow.requestFocus() } }

    Column(
        Modifier
            .width(420.dp)
            .background(CableColors.PanelDeep.copy(alpha = 0.96f))
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("AUDIO", color = CableColors.Led, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.sp)
        CableRow(modifier = Modifier.focusRequester(firstRow), onClick = {
            playback.selectTrack(null, C.TRACK_TYPE_AUDIO)
            onDismiss()
        }) {
            Text("DEFAULT", color = CableColors.Text, fontSize = 17.sp)
        }
        if (audio.isEmpty()) Text("No alternate audio", color = CableColors.Dim, fontSize = 14.sp)
        audio.forEach { opt ->
            CableRow(onClick = {
                playback.selectTrack(opt, C.TRACK_TYPE_AUDIO)
                onDismiss()
            }) {
                Text(
                    (if (opt.selected) "> " else "  ") + opt.label,
                    color = CableColors.Text,
                    fontSize = 17.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("SUBTITLES", color = CableColors.Led, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.sp)
        CableRow(onClick = {
            playback.selectTrack(null, C.TRACK_TYPE_TEXT)
            onDismiss()
        }) {
            Text((if (noSubsSelected) "> " else "  ") + "OFF", color = CableColors.Text, fontSize = 17.sp)
        }
        if (subs.isEmpty()) Text("No subtitles in this stream", color = CableColors.Dim, fontSize = 14.sp)
        subs.forEach { opt ->
            CableRow(onClick = {
                playback.selectTrack(opt, C.TRACK_TYPE_TEXT)
                onDismiss()
            }) {
                Text(
                    (if (opt.selected) "> " else "  ") + opt.label,
                    color = CableColors.Text,
                    fontSize = 17.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onDismiss) { Text("CLOSE") }
    }
}

/** Shown over the video when a stream is reconnecting or has given up. */
@Composable
fun SignalOverlay(playback: Playback) {
    val reconnecting = playback.reconnecting
    val error = playback.error
    when {
        reconnecting != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(reconnecting, color = CableColors.Text, fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("NO SIGNAL", color = CableColors.Text, fontSize = 32.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(error, color = CableColors.Dim, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { playback.retry() }) { Text("RETRY") }
            }
        }
        playback.buffering -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("BUFFERING...", color = CableColors.Text, fontSize = 18.sp)
        }
    }
}
