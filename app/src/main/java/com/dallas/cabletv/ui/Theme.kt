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
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Design tokens. Dark, low-glare surfaces with one cool accent so the
 * picture is always the brightest thing on screen.
 */
object Cable {
    val Canvas = Color(0xFF0A0C11)
    val Rail = Color(0xFF0E1117)
    val Surface = Color(0xFF161B24)
    val SurfaceHi = Color(0xFF232B3A)
    val Accent = Color(0xFF5AB8FF)
    val Live = Color(0xFFFF4D5E)
    val Ink = Color(0xFFF3F5F9)
    val Ink2 = Color(0xFFA7B0C2)
    val Ink3 = Color(0xFF6B7487)
    val Track = Color(0xFF2A3242)
}

/** Sizes are tuned for a couch: nothing below 16sp. */
private val CableTypography = Typography(
    headlineLarge = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
)

@Composable
fun CableTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cable.Accent,
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

/**
 * The one focus treatment used everywhere. A focused item grows slightly and
 * gets a clear accent ring, so the remote's position is readable from across
 * the room. Works for D-pad, touch and mouse.
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
        targetValue = if (focused) Cable.Accent else Color.Transparent,
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
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Cable.Track)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .background(Cable.Accent)
        )
    }
}

@Composable
fun PageHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = Cable.Ink
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = Cable.Ink2
            )
        }
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
            color = Cable.Ink,
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
        style = MaterialTheme.typography.labelLarge,
        color = Cable.Ink2,
        modifier = modifier
    )
}
