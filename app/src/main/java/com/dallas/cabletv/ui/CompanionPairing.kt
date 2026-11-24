package com.dallas.cabletv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dallas.cabletv.data.companion.server.PairingOffer

/**
 * A phone asked to pair. The code is shown over whatever is on screen, in large digits grouped
 * three and three, with the phone's name. Cancel has focus and Back cancels, so the remote only
 * ever lands on the one button. The caller closes it on success, expiry or cancel, and puts focus
 * back on the screen underneath.
 */
@Composable
fun PairingPrompt(offer: PairingOffer, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val cancel by rememberUpdatedState(onCancel)
    val cancelFocus = remember { FocusRequester() }
    BackHandler { cancel() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { cancelFocus.requestFocus() }
    }
    // Taps are handled without making the backdrop or the card focusable. A tap outside the card means Cancel.
    Box(
        modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .pointerInput(Unit) { detectTapGestures { cancel() } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .trapFocus()
                .padding(16.dp)
                .widthIn(max = 460.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Tv.Surface3)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Pair a phone", style = TvType.Title)
            Spacer(Modifier.height(6.dp))
            Text(
                offer.phoneName,
                style = TvType.BodyStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Enter this code on the phone.",
                style = TvType.Small,
                color = Tv.Ink2,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
            Text(
                offer.displayCode,
                style = TvType.Display.copy(fontSize = 56.sp, lineHeight = 60.sp, letterSpacing = 4.sp),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            PillButton("Cancel", { cancel() }, modifier = Modifier.focusRequester(cancelFocus))
        }
    }
}
