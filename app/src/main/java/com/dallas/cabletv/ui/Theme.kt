package com.dallas.cabletv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Palette taken from the 2000s cable guide: deep navy grid, lighter blue
 * info panels, a bright yellow selection, and a light blue time bar.
 */
object CableColors {
    val Bg = Color(0xFF040B22)
    val Panel = Color(0xFF1B3F8C)
    val PanelDeep = Color(0xFF0E2766)
    val Cell = Color(0xFF1D4BA8)
    val CellAlt = Color(0xFF1A3F94)
    val Highlight = Color(0xFF2E63C4)
    val TimeBar = Color(0xFF4F86DA)
    val Selected = Color(0xFFF6D23B)
    val OnSelected = Color(0xFF111111)
    val Led = Color(0xFF8FD3FF)
    val Amber = Color(0xFFF6D23B)
    val Text = Color(0xFFFFFFFF)
    val Dim = Color(0xFFA9C4F0)
    val Live = Color(0xFFE3262E)
    val Good = Color(0xFF7FE38F)
}

@Composable
fun CableTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = CableColors.Bg,
            surface = CableColors.Panel,
            primary = CableColors.Selected,
            onPrimary = CableColors.OnSelected,
            secondary = CableColors.TimeBar,
            onSecondary = CableColors.Text,
            onBackground = CableColors.Text,
            onSurface = CableColors.Text,
            outline = CableColors.Dim
        ),
        content = content
    )
}

@Composable
fun Clock(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Text(
        text = SimpleDateFormat("h:mm a", Locale.US).format(Date(now)),
        color = CableColors.Text,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        modifier = modifier
    )
}

/** Title bar used at the top of each full screen. */
@Composable
fun CableHeader(title: String, onBack: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(CableColors.PanelDeep)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) {
                Text("< BACK", color = CableColors.Led, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = title.uppercase(),
            color = CableColors.Text,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
        Clock()
    }
}

/**
 * A full-width row. The focused row gets a bright yellow edge so it reads
 * clearly from a couch.
 */
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
            .border(
                width = if (focused) 4.dp else 0.dp,
                color = if (focused) CableColors.Selected else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** Colored key badge plus label, like the A and B buttons at the bottom of the guide. */
@Composable
fun KeyLegend(key: String, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(26.dp)
                .background(color, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(key, color = CableColors.OnSelected, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(label, color = CableColors.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
fun LegendBar(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(CableColors.PanelDeep)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
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
            color = CableColors.Text,
            fontSize = 20.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        color = CableColors.Led,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        modifier = Modifier.padding(top = 16.dp)
    )
}

/** A small rounded tag, for things like HD, NR and the LIVE marker. */
@Composable
fun Tag(
    text: String,
    color: Color = CableColors.Text,
    bg: Color = CableColors.PanelDeep,
    modifier: Modifier = Modifier
) {
    Text(
        text,
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
