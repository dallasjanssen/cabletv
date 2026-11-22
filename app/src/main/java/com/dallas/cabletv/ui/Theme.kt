package com.dallas.cabletv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Phosphor-style palette: deep blue glass, green LED text, amber numerals. */
object CableColors {
    val Bg = Color(0xFF05070F)
    val Panel = Color(0xFF0C1A36)
    val Highlight = Color(0xFF1F4FA8)
    val Led = Color(0xFF3DFF8C)
    val Amber = Color(0xFFFFB347)
    val Text = Color(0xFFE8F4FF)
    val Dim = Color(0xFF6A7FA6)
}

@Composable
fun CableTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = CableColors.Bg,
            surface = CableColors.Panel,
            primary = CableColors.Led,
            onPrimary = CableColors.Bg,
            onBackground = CableColors.Text,
            onSurface = CableColors.Text
        ),
        content = content
    )
}

/** Faint horizontal scan lines, like an old CRT. */
fun Modifier.scanlines(): Modifier = this.drawWithContent {
    drawContent()
    var y = 0f
    while (y < size.height) {
        drawLine(
            color = Color.Black.copy(alpha = 0.22f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1.5f
        )
        y += 4f
    }
}

@Composable
fun Clock() {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Text(
        text = SimpleDateFormat("h:mm a", Locale.US).format(Date(now)),
        color = CableColors.Amber,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp
    )
}

@Composable
fun CableHeader(title: String, onBack: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(CableColors.Panel)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) {
                Text("< BACK", color = CableColors.Amber, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.width(16.dp))
        }
        Text(
            text = title,
            color = CableColors.Led,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            modifier = Modifier.weight(1f)
        )
        Clock()
    }
}

/** A full-width row that highlights when focused with the remote or a D-pad. */
@Composable
fun CableRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) CableColors.Highlight else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
fun CenterMessage(text: String) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = CableColors.Amber,
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        color = CableColors.Amber,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        modifier = Modifier.padding(top = 16.dp)
    )
}
