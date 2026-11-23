package com.dallas.cabletv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A four-digit PIN pad for the parental lock. It works with the on-screen keys, which suit a
 * remote, and with the number keys. [onComplete] gets the PIN once four digits are entered.
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

    BackHandler { onCancel() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstKey.requestFocus() }
    }

    fun press(key: String) {
        when (key) {
            "cancel" -> onCancel()
            "del" -> digits = digits.dropLast(1)
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

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xD1050608))
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (val code = ev.nativeKeyEvent.keyCode) {
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                        press((code - KeyEvent.KEYCODE_0).toString())
                        true
                    }
                    // The number pad on a keyboard, or on remotes that send those codes.
                    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                        press((code - KeyEvent.KEYCODE_NUMPAD_0).toString())
                        true
                    }
                    KeyEvent.KEYCODE_DEL -> {
                        press("del")
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.trapFocus().width(340.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = TvType.Headline, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = TvType.Small, color = Tv.Ink2, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(4) { i ->
                    val on = i < digits.length
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(if (on) Tv.Accent else Color.Transparent)
                            .border(2.dp, if (on) Tv.Accent else Tv.Ink3, CircleShape)
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("cancel", "0", "del"))
            rows.forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEachIndexed { c, key ->
                        Focusable(
                            onClick = { press(key) },
                            modifier = Modifier
                                .size(64.dp, 44.dp)
                                .then(if (r == 0 && c == 0) Modifier.focusRequester(firstKey) else Modifier),
                            shape = RoundedCornerShape(10.dp),
                            color = Tv.Surface2,
                            focusScale = 1.06f,
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                when (key) {
                                    "cancel" -> "Cancel"
                                    "del" -> "Delete"
                                    else -> key
                                },
                                style = if (key.length > 1) TvType.CaptionStrong else TvType.Section
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}
