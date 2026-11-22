package com.dallas.cabletv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.dallas.cabletv.data.PhoneSignInServer
import com.dallas.cabletv.data.localIpv4
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/** What the TV shows while it waits for the phone: the address, the code, and the QR image. */
private class PhoneCode(val url: String, val pin: String, val qr: Bitmap)

/**
 * Lets the user sign in from a phone. The TV shows a QR code. The phone opens a page
 * on the home network, the user types the details there, and they arrive on the TV.
 * [onReceived] gets the field names used by the sign-in page.
 */
@Composable
fun PhoneSignInPanel(onReceived: (Map<String, String>) -> Unit) {
    val scope = rememberCoroutineScope()
    val running = remember { AtomicReference<PhoneSignInServer?>(null) }
    var code by remember { mutableStateOf<PhoneCode?>(null) }
    var status by remember { mutableStateOf("Sign in from your phone instead of typing on the TV.") }

    // Closing the sign-in screen always stops the server.
    DisposableEffect(Unit) {
        onDispose { running.getAndSet(null)?.stop() }
    }

    fun close(message: String) {
        running.getAndSet(null)?.stop()
        code = null
        status = message
    }

    fun open() {
        running.getAndSet(null)?.stop()
        val pin = (100000..999999).random().toString()
        val server = PhoneSignInServer(pin) { fields ->
            // Runs on the server thread, so hand the result to the UI thread.
            scope.launch {
                close("Details received. Check them, then press SAVE.")
                onReceived(fields)
            }
        }
        val port = runCatching { server.start() }.getOrNull()
        val ip = localIpv4()
        if (port == null || ip == null) {
            server.stop()
            status = "Could not start the sign-in page. Check that the TV is connected to your network."
            return
        }
        running.set(server)
        val url = "http://$ip:$port/?pin=$pin"
        code = PhoneCode(url = url, pin = pin, qr = qrBitmap(url))
        status = "Scan the code with your phone, then fill in the details there."
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = Cable.Ink2
        )
        val shown = code
        if (shown == null) {
            CableButton(label = "SHOW QR CODE", primary = true, onClick = { open() })
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                Image(
                    bitmap = shown.qr.asImageBitmap(),
                    contentDescription = "Sign-in QR code",
                    modifier = Modifier.size(260.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "CODE  ${shown.pin}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Cable.Accent
                    )
                    Text(
                        text = shown.url,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Cable.Ink2
                    )
                    CableButton(
                        label = "CLOSE",
                        onClick = { close("Sign-in closed. Nothing was changed.") }
                    )
                }
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
            pixels[y * size + x] = if (matrix.get(x, y)) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
