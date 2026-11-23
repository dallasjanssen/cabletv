package com.dallas.cabletv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dallas.cabletv.data.PhoneSignInServer
import com.dallas.cabletv.data.localIpv4
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/** What the TV shows while it waits for the phone: the address, the code, and the QR image. */
private class PhoneCode(val url: String, val address: String, val pin: String, val qr: Bitmap)

/** The sign-in page on the home network, started and stopped with the screen that shows it. */
@Composable
private fun rememberPhoneSignIn(autoStart: Boolean, onReceived: (Map<String, String>) -> Unit): Triple<PhoneCode?, String?, () -> Unit> {
    val scope = rememberCoroutineScope()
    val running = remember { AtomicReference<PhoneSignInServer?>(null) }
    var code by remember { mutableStateOf<PhoneCode?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val latest by rememberUpdatedState(onReceived)

    DisposableEffect(Unit) {
        onDispose { running.getAndSet(null)?.stop() }
    }

    fun open() {
        running.getAndSet(null)?.stop()
        val pin = (100000..999999).random().toString()
        val server = PhoneSignInServer(pin) { fields ->
            // Runs on the server thread, so hand the result to the UI thread.
            scope.launch {
                running.getAndSet(null)?.stop()
                code = null
                latest(fields)
            }
        }
        val port = runCatching { server.start() }.getOrNull()
        val ip = localIpv4()
        if (port == null || ip == null) {
            server.stop()
            problem = "The setup page did not start. Check that the TV is connected to your Wi-Fi or network."
            return
        }
        problem = null
        running.set(server)
        val url = "http://$ip:$port/?pin=$pin"
        code = PhoneCode(url = url, address = "$ip:$port", pin = pin, qr = qrBitmap(url))
    }

    LaunchedEffect(autoStart) { if (autoStart) open() }
    return Triple(code, problem, ::open)
}

/** The QR code card used in Settings. It starts when the button is pressed. */
@Composable
fun PhoneSignInPanel(autoStart: Boolean, onReceived: (Map<String, String>) -> Unit) {
    val (code, problem, open) = rememberPhoneSignIn(autoStart, onReceived)
    if (code == null) {
        PillButton("Show the code", open, icon = TvIcons.Phone)
        problem?.let { Text(it, style = TvType.Small, color = Tv.Accent, modifier = Modifier.padding(top = 10.dp)) }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            QrImage(code.qr, 150.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Code", style = TvType.Caption, color = Tv.Ink3)
                Text(code.pin.chunked(3).joinToString(" "), style = TvType.Display.copy(fontSize = 30.sp, letterSpacing = 2.sp), color = Tv.Accent)
                Text("Or open ${code.address} on any phone or laptop on your Wi-Fi.", style = TvType.Small, color = Tv.Ink2, modifier = Modifier.widthIn(max = 300.dp))
                Spacer(Modifier.height(6.dp))
                PillButton("Get a new code", open)
            }
        }
    }
}

@Composable
private fun QrImage(qr: Bitmap, size: Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp)) {
        Image(
            bitmap = qr.asImageBitmap(),
            contentDescription = "Setup QR code",
            filterQuality = FilterQuality.None,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * The first screen when nothing is set up. The QR code is already showing, so setup starts with
 * the phone in hand. "Use the remote instead" opens Settings.
 */
@Composable
fun WelcomeScreen(
    busy: Boolean,
    message: String?,
    onReceived: (Map<String, String>) -> Unit,
    onUseRemote: () -> Unit
) {
    val (code, problem, open) = rememberPhoneSignIn(autoStart = true, onReceived = onReceived)
    val remoteButton = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { remoteButton.requestFocus() }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF1A2130), Tv.Bg), radius = 1400f, center = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, 0f)))
    ) {
        val narrow = maxWidth < 700.dp
        val steps: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.widthIn(max = 470.dp)) {
                Brand(large = true)
                Spacer(Modifier.height(4.dp))
                Text("Add your TV service", style = TvType.Display.copy(fontSize = 34.sp))
                Text("It takes about a minute, and you never have to type on the TV.", style = TvType.Body.copy(fontSize = 15.sp), color = Tv.Ink2)
                listOf(
                    "Point your phone camera at the code.",
                    "Enter your TV provider and Jellyfin details on your phone.",
                    "Your channels and guide appear here."
                ).forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(26.dp).clip(CircleShape).background(Tv.Surface3), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", style = TvType.CaptionStrong)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(s, style = TvType.Body)
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (message != null) Text(message, style = TvType.SmallStrong, color = if (busy) Tv.Ink2 else Tv.Accent)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Use the remote instead", onUseRemote, modifier = Modifier.focusRequester(remoteButton))
                    if (code == null && !busy) PillButton("Show a new code", open, icon = TvIcons.Phone)
                }
            }
        }
        val card: @Composable () -> Unit = {
            Column(
                Modifier.width(300.dp).clip(RoundedCornerShape(20.dp)).background(Tv.Surface).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when {
                    code != null -> {
                        QrImage(code.qr, 190.dp)
                        Text("Code", style = TvType.Caption, color = Tv.Ink3)
                        Text(code.pin.chunked(3).joinToString(" "), style = TvType.Display.copy(fontSize = 30.sp, letterSpacing = 2.sp), color = Tv.Accent)
                        Text("Or open ${code.address} on a phone on the same Wi-Fi.", style = TvType.Caption, color = Tv.Ink3, textAlign = TextAlign.Center)
                    }
                    busy -> Text("Checking your details", style = TvType.Section, textAlign = TextAlign.Center)
                    else -> Text(problem ?: "Details received.", style = TvType.Small, color = Tv.Ink2, textAlign = TextAlign.Center)
                }
            }
        }
        if (narrow) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                steps()
                card()
            }
        } else {
            Row(Modifier.fillMaxSize().padding(horizontal = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { steps() }
                Spacer(Modifier.width(40.dp))
                card()
            }
        }
    }
}

/** Draws a QR code as a bitmap with a black pattern on white. */
private fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
