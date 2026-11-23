package com.dallas.cabletv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

/**
 * A four-digit PIN pad for the parental lock. It works with the on-screen keypad, which
 * suits a remote, and with the number keys. [onComplete] gets the PIN once four digits are entered.
 */
@Composable
fun PinPad(
    title: String,
    subtitle: String,
    onComplete: (String) -> Unit,
    onCancel: () -> Unit
) {
    var digits by remember { mutableStateOf("") }
    val firstKey = remember { FocusRequester() }

    // Registered after the app's own Back handler, so Back here cancels the PIN and nothing else.
    BackHandler { onCancel() }
    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }

    fun press(key: String) {
        when (key) {
            "CLR" -> digits = ""
            "DEL" -> digits = digits.dropLast(1)
            else -> if (digits.length < 4) {
                digits += key
                if (digits.length == 4) {
                    val entered = digits
                    digits = ""
                    onComplete(entered)
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Cable.Canvas.copy(alpha = 0.95f))
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (val code = ev.nativeKeyEvent.keyCode) {
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                        press((code - KeyEvent.KEYCODE_0).toString())
                        true
                    }
                    KeyEvent.KEYCODE_DEL -> {
                        press("DEL")
                        true
                    }
                    else -> false
                }
            }
            .padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = Cable.Led
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = Cable.Ink2
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = (digits + "_".repeat(4 - digits.length)).replace("", " ").trim(),
            style = MaterialTheme.typography.headlineLarge,
            color = Cable.Accent
        )
        Spacer(Modifier.height(28.dp))

        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("CLR", "0", "DEL")
        )
        rows.forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEachIndexed { c, key ->
                    val label = when (key) {
                        "CLR" -> "CLEAR"
                        "DEL" -> "DEL"
                        else -> key
                    }
                    val mod = if (r == 0 && c == 0) Modifier.focusRequester(firstKey) else Modifier
                    CableButton(
                        label = label,
                        primary = key == "0",
                        modifier = mod,
                        onClick = { press(key) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(12.dp))
        CableButton(label = "CANCEL", onClick = onCancel)
    }
}
