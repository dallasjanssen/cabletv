package com.dallas.cabletv.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phosphor palette from the original cable box: deep blue glass, green LED
 * program text, amber numerals. Amber marks channel numbers and selection,
 * green marks what is playing and the focus ring.
 */
object Cable {
    val Canvas = Color(0xFF05070F)
    val Rail = Color(0xFF0A1024)
    val Surface = Color(0xFF0C1A36)
    val SurfaceHi = Color(0xFF14306B)
    val Accent = Color(0xFFFFB347)
    val Led = Color(0xFF3DFF8C)
    val Live = Color(0xFFFF4D5E)
    val Ink = Color(0xFFE8F4FF)
    val Ink2 = Color(0xFFB8C8E6)
    val Ink3 = Color(0xFF6A7FA6)
    val Track = Color(0xFF1A2A4A)
}

/** Every style is monospace, like a character display. Sizes stay large for the couch. */
private val CableTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 48.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 36.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 30.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 24.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
)

@Composable
fun CableTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cable.Led,
            onPrimary = Cable.Canvas,
            background = Cable.Canvas,
            onBackground = Cable.Ink,
            surface = Cable.Surface,
            onSurface = Cable.Ink,
            surfaceVariant = Cable.SurfaceHi,
            onSurfaceVariant = Cable.Ink2,
            error = Cable.Live
        ),
        typography = CableTypography,
        content = content
    )
}

/** Faint horizontal scan lines, like an old CRT. */
fun Modifier.scanlines(): Modifier = this.drawWithContent {
    drawContent()
    var y = 0f
    while (y < size.height) {
        drawLine(
            color = Color.Black.copy(alpha = 0.18f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1.5f
        )
        y += 4f
    }
}

/**
 * The one focus treatment used everywhere. A focused item grows slightly and
 * gets a green ring, so the remote's position reads from across the room.
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    focusScale: Float = 1.04f,
    content: @Composable BoxScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) focusScale else 1f,
        label = "focusScale"
    )
    val ring by animateColorAsState(
        targetValue = if (focused) Cable.Led else Color.Transparent,
        label = "focusRing"
    )
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .border(3.dp, ring, shape)
            .clickable(onClick = onClick),
        content = content
    )
}

/** Top bar for every screen: back on the left, title, then the clock. */
@Composable
fun CableHeader(title: String, onBack: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Cable.Surface)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) {
                Text(
                    text = "< BACK",
                    color = Cable.Accent,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Spacer(Modifier.width(16.dp))
        }
        Text(
            text = title,
            color = Cable.Led,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f)
        )
        Clock()
    }
}

@Composable
fun Pill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    textColor: Color = Color.White
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = textColor,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(6.dp)
            .background(Cable.Track)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .background(Cable.Led)
        )
    }
}

@Composable
fun EmptyState(title: String, body: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = Cable.Accent,
            textAlign = TextAlign.Center
        )
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = Cable.Ink2,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Time of day in the device's style, for example 7:05 PM. */
fun formatTime(millis: Long): String =
    SimpleDateFormat("h:mm a", Locale.US).format(Date(millis))

@Composable
fun Clock(modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Text(
        text = formatTime(now),
        style = MaterialTheme.typography.titleMedium,
        color = Cable.Accent,
        modifier = modifier
    )
}
