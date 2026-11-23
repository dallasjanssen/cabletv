package com.dallas.cabletv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dallas.cabletv.R
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.Program
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue

/**
 * Design tokens. A dark graphite background keeps the picture the brightest thing on screen.
 * Amber marks the brand and what is selected, red marks live, and whatever has focus turns white.
 */
object Tv {
    val Bg = Color(0xFF0D0F13)
    val Surface = Color(0xFF171A20)
    val Surface2 = Color(0xFF20242C)
    val Surface3 = Color(0xFF2B3039)
    val Ink = Color(0xFFF3F4F6)
    val Ink2 = Color(0xFFB3B9C4)
    val Ink3 = Color(0xFF7D8594)
    val Accent = Color(0xFFF6A723)
    val AccentSoft = Color(0x2EF6A723)
    val Live = Color(0xFFFF5252)
    val Ok = Color(0xFF3DD68C)
    val OkSoft = Color(0x1F3DD68C)
    val Focus = Color.White
    val FocusInk = Color(0xFF0D0F13)
    val FocusInk2 = Color(0xFF4B5260)
    val Scrim = Color(0xF00D0F13)
    val Glass = Color(0x21FFFFFF)
    val Track = Color(0x2EFFFFFF)
}

val Figtree = FontFamily(
    Font(R.font.figtree_regular, FontWeight.Normal),
    Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
    Font(R.font.figtree_bold, FontWeight.Bold),
    Font(R.font.figtree_extrabold, FontWeight.ExtraBold)
)

/**
 * Type scale for Android TV's 960 by 540 dp screen. The smallest text is 11 sp for badges,
 * body text is 14 sp, and the largest title is 32 sp. Phones use the same scale.
 */
object TvType {
    val Display = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 35.sp, letterSpacing = (-0.5).sp)
    val Hero = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 31.sp, letterSpacing = (-0.4).sp)
    val Headline = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp)
    val Title = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 22.sp)
    val Section = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp)
    val Body = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 19.sp)
    val BodyStrong = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp)
    val Small = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp)
    val SmallStrong = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 17.sp)
    val Caption = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
    val CaptionStrong = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp)
    val Badge = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.6.sp)
}

/** True inside a control that is focused and drawn white, so text inside can switch to dark. */
val LocalFocused = compositionLocalOf { false }

/** Secondary and tertiary text colors that stay readable on a focused white control. */
val ink2: Color @Composable get() = if (LocalFocused.current) Tv.FocusInk2 else Tv.Ink2
val ink3: Color @Composable get() = if (LocalFocused.current) Tv.FocusInk2 else Tv.Ink3

@Composable
fun TvTheme(content: @Composable () -> Unit) {
    val type = Typography(
        displayLarge = TvType.Display, headlineLarge = TvType.Hero, headlineMedium = TvType.Headline,
        titleLarge = TvType.Title, titleMedium = TvType.Section, bodyLarge = TvType.Body, bodyMedium = TvType.Body,
        bodySmall = TvType.Caption, labelLarge = TvType.BodyStrong, labelMedium = TvType.CaptionStrong, labelSmall = TvType.Badge
    )
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Tv.Accent, onPrimary = Tv.FocusInk, background = Tv.Bg, onBackground = Tv.Ink,
            surface = Tv.Surface, onSurface = Tv.Ink, surfaceVariant = Tv.Surface2, onSurfaceVariant = Tv.Ink2, error = Tv.Live
        ),
        typography = type
    ) {
        CompositionLocalProvider(LocalContentColor provides Tv.Ink, LocalTextStyle provides TvType.Body, content = content)
    }
}

/** Side margin for screen content: generous on a TV, tighter on a phone. */
@Composable
fun gutter(narrow: Boolean): Dp = if (narrow) 16.dp else 40.dp

/** How a control shows focus. Fill turns it white. Ring draws a white outline and lifts it, for pictures. */
enum class FocusLook { Fill, Ring }

/**
 * The one focusable building block. Every button, row, card and guide cell uses it, so focus looks
 * the same everywhere and works with a remote, a keyboard and touch.
 */
@Composable
fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    look: FocusLook = FocusLook.Fill,
    color: Color = Color.Transparent,
    focusScale: Float = if (look == FocusLook.Ring) 1.07f else 1.0f,
    enabled: Boolean = true,
    onFocus: ((Boolean) -> Unit)? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusScale else 1f, tween(140), label = "scale")
    val fill = look == FocusLook.Fill && focused
    val bg by animateColorAsState(if (fill) Tv.Focus else color, tween(110), label = "bg")
    val ring = look == FocusLook.Ring && focused
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (ring) Modifier.shadow(14.dp, shape) else Modifier)
            .onFocusChanged {
                if (focused != it.isFocused) {
                    focused = it.isFocused
                    onFocus?.invoke(it.isFocused)
                }
            }
            .clip(shape)
            .background(bg)
            .then(if (look == FocusLook.Ring) Modifier.border(2.dp, if (ring) Tv.Focus else Color.Transparent, shape) else Modifier)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = contentAlignment
    ) {
        if (fill) {
            CompositionLocalProvider(LocalFocused provides true, LocalContentColor provides Tv.FocusInk) { content() }
        } else {
            CompositionLocalProvider(LocalFocused provides false) { content() }
        }
    }
}

/** A rounded button. Translucent at rest, white when focused. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onFocus: ((Boolean) -> Unit)? = null
) {
    Focusable(
        onClick = onClick,
        modifier = modifier.height(36.dp).alpha(if (enabled) 1f else 0.45f),
        shape = CircleShape,
        color = Tv.Glass,
        focusScale = 1.05f,
        enabled = enabled,
        onFocus = onFocus,
        contentAlignment = Alignment.Center
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text, style = TvType.BodyStrong, maxLines = 1)
        }
    }
}

/** A small rounded option. Selected chips are amber. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Focusable(
        onClick = onClick,
        modifier = modifier.height(30.dp),
        shape = CircleShape,
        color = if (selected) Tv.AccentSoft else Color(0x1AFFFFFF),
        focusScale = 1.05f,
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = TvType.SmallStrong,
            color = if (LocalFocused.current) Tv.FocusInk else if (selected) Tv.Accent else Tv.Ink2,
            modifier = Modifier.padding(horizontal = 14.dp),
            maxLines = 1
        )
    }
}

@Composable
fun ProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = Tv.Live,
    track: Color = Tv.Track,
    height: Dp = 4.dp
) {
    Box(modifier.height(height).clip(RoundedCornerShape(height / 2)).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

@Composable
fun LivePill(modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(5.dp)).background(Tv.Live).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
        Text("LIVE", style = TvType.Badge, color = Color.White)
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = TvType.CaptionStrong,
        color = ink2,
        modifier = modifier.clip(RoundedCornerShape(5.dp)).background(Color(0x1FFFFFFF)).padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

/** A green or amber status line, for "Connected" and "Not set up" in Settings. */
@Composable
fun StatusChip(text: String, ok: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape).background(if (ok) Tv.OkSoft else Tv.AccentSoft).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Tv.Ok else Tv.Accent))
        Text(text, style = TvType.SmallStrong, color = if (ok) Tv.Ok else Tv.Accent)
    }
}

/** A remote key drawn as a small chip, for hints such as "OK  More options". */
@Composable
fun KeyHint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            key,
            style = TvType.Badge,
            color = Tv.Ink,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0x24FFFFFF)).padding(horizontal = 6.dp, vertical = 3.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = TvType.Caption, color = Tv.Ink3)
    }
}

/** A stable color for a channel, so channels without a logo still get a recognizable tile. */
fun hueOf(name: String): Float = (name.hashCode().absoluteValue % 360).toFloat()

/**
 * A channel's logo. Provider logos sit on a neutral tile. Channels without one, or whose logo fails
 * to load, get a colored tile with the name set in two lines.
 */
@Composable
fun ChannelLogo(ch: LiveChannel, modifier: Modifier = Modifier, compact: Boolean = false) {
    var failed by remember(ch.logo) { mutableStateOf(false) }
    val hasLogo = !ch.logo.isNullOrBlank() && !failed
    val hue = remember(ch.name) { hueOf(ch.name) }
    Box(
        modifier
            .clip(RoundedCornerShape(if (compact) 5.dp else 8.dp))
            .background(
                if (hasLogo) {
                    Brush.linearGradient(listOf(Tv.Surface3, Tv.Surface2))
                } else {
                    Brush.linearGradient(listOf(Color.hsl(hue, 0.42f, 0.24f), Color.hsl((hue + 30f) % 360f, 0.5f, 0.11f)))
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (hasLogo) {
            AsyncImage(
                model = ch.logo,
                contentDescription = ch.name,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize().padding(if (compact) 3.dp else 8.dp)
            )
        } else {
            val words = ch.name.trim().split(Regex("\\s+"))
            val first = words.firstOrNull().orEmpty()
            val rest = words.drop(1).joinToString(" ")
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 3.dp)) {
                Text(
                    first,
                    style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.ExtraBold, fontSize = if (compact) 10.sp else 16.sp, lineHeight = if (compact) 11.sp else 17.sp),
                    color = Color.hsl(hue, 0.85f, 0.88f),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center
                )
                if (rest.isNotEmpty()) {
                    Text(
                        rest.uppercase(),
                        style = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 6.sp else 9.sp, letterSpacing = 1.sp),
                        color = Color.hsl(hue, 0.85f, 0.88f).copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        textAlign = TextAlign.Center
                    )
                }
            }
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
        Text(title, style = TvType.Headline, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(6.dp))
            Text(body, style = TvType.Body, color = Tv.Ink2, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 460.dp))
        }
    }
}

/**
 * Keeps the remote inside a panel or menu. Without this, pressing past the last button could move
 * focus to whatever is behind the panel.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.trapFocus(): Modifier = this
    .focusProperties {
        // Only arrow keys are held back. The app can still move focus out when a panel closes.
        exit = { dir ->
            when (dir) {
                FocusDirection.Left, FocusDirection.Right, FocusDirection.Up, FocusDirection.Down,
                FocusDirection.Next, FocusDirection.Previous -> FocusRequester.Cancel
                else -> FocusRequester.Default
            }
        }
    }
    .focusGroup()

/** A dark panel that slides over the picture from one side. */
@Composable
fun SidePanel(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .trapFocus()
            .fillMaxHeight()
            .background(Tv.Scrim)
            .padding(top = 26.dp, bottom = 18.dp)
    ) {
        Text(title, style = TvType.Title, modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp))
        content()
    }
}

/** A section label in small capitals, such as AUDIO or GROUPS TO SHOW. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = TvType.CaptionStrong.copy(letterSpacing = 0.8.sp),
        color = Tv.Ink3,
        modifier = modifier
    )
}

// ---------------------------------------------------------------- Time

private val timeFormat get() = SimpleDateFormat("h:mm a", Locale.US)
private val shortFormat get() = SimpleDateFormat("h:mm", Locale.US)

/** Time of day, for example 7:05 PM. */
fun formatTime(millis: Long): String = timeFormat.format(Date(millis))

/** A programme's times, for example 7:30 to 9:00 PM. */
fun formatRange(p: Program): String = "${shortFormat.format(Date(p.start))} to ${formatTime(p.end)}"

/** How far through a programme we are, from 0 to 1. */
fun progressOf(p: Program, now: Long = System.currentTimeMillis()): Float {
    val total = (p.end - p.start).coerceAtLeast(1L)
    return ((now - p.start).toFloat() / total).coerceIn(0f, 1f)
}

fun isOnNow(p: Program, now: Long = System.currentTimeMillis()): Boolean = now >= p.start && now < p.end

/** Minutes as words, for example 52 min or 1 h 5 min. */
fun minutesText(minutes: Long): String =
    if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

fun timeLeft(p: Program, now: Long = System.currentTimeMillis()): String =
    "${minutesText(((p.end - now) / 60_000L).coerceAtLeast(0L))} left"

/** A playback position, for example 1:05:10 or 42:10. */
fun formatPosition(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** The current time, updated every [periodMs]. */
@Composable
fun rememberNow(periodMs: Long = 30_000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs - (System.currentTimeMillis() % periodMs).coerceAtMost(periodMs - 1))
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun Clock(modifier: Modifier = Modifier) {
    val now = rememberNow(15_000L)
    Text(formatTime(now), style = TvType.BodyStrong, color = Tv.Ink2, modifier = modifier)
}

// ---------------------------------------------------------------- Scrolling

/**
 * Scrolls a list so the focused row sits near the top, the way TV home screens move. [offset]
 * leaves room above the row, for its heading.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberPivotSpec(offset: Dp): BringIntoViewSpec {
    val px = with(LocalDensity.current) { offset.toPx() }
    return remember(px) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - px
        }
    }
}

/** Leaves scrolling to the screen, for lists that move a whole row at a time. */
@OptIn(ExperimentalFoundationApi::class)
val NoScroll: BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** Keeps a focused card fully on screen with some room on each side, scrolling as little as possible. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberEdgeSpec(padding: Dp): BringIntoViewSpec {
    val px = with(LocalDensity.current) { padding.toPx() }
    return remember(px) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val lead = offset - px
                val trail = offset + size - (containerSize - px)
                return when {
                    lead < 0f -> lead
                    trail > 0f -> minOf(trail, lead)
                    else -> 0f
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Top bar

/** The sections in the top bar. */
enum class Tab(val label: String) { Home("Home"), Guide("Guide"), OnDemand("Movies & Shows"), Search("Search"), Settings("Settings") }

/** What every tabbed screen needs from the app: switching sections, going back, and the profile. */
class NavActions(
    val select: (Tab) -> Unit,
    val back: () -> Unit,
    val profileName: String,
    val onProfile: () -> Unit
)

/**
 * The top bar with the section tabs, the profile and the clock. Moving up into it lands on the
 * current section. Back from a screen's content moves to the bar first, and Back again leaves.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun TabScaffold(
    tab: Tab,
    nav: NavActions,
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
    content: @Composable BoxScope.(narrow: Boolean) -> Unit
) {
    val selectedTab = remember { FocusRequester() }
    val showSelected = remember { BringIntoViewRequester() }
    var navHasFocus by remember { mutableStateOf(false) }
    // On a phone the tabs can be wider than the screen, so the current one is scrolled into view.
    LaunchedEffect(tab) { runCatching { showSelected.bringIntoView() } }
    BackHandler(enabled = backEnabled) {
        if (navHasFocus) nav.back() else runCatching { selectedTab.requestFocus() }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val narrow = maxWidth < 600.dp
        content(narrow)
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = gutter(narrow)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!narrow) {
                Brand()
                Spacer(Modifier.width(20.dp))
            }
            Row(
                Modifier
                    .weight(1f)
                    .onFocusChanged { navHasFocus = it.hasFocus }
                    .focusProperties { enter = { selectedTab } }
                    .focusGroup(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Tab.entries.forEach { t ->
                        NavTab(
                            label = if (narrow && t == Tab.OnDemand) "Movies" else t.label,
                            selected = t == tab,
                            modifier = if (t == tab) Modifier.bringIntoViewRequester(showSelected).focusRequester(selectedTab) else Modifier,
                            onClick = { nav.select(t) }
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                ProfileButton(nav.profileName, compact = narrow, onClick = nav.onProfile)
            }
            if (!narrow) {
                Spacer(Modifier.width(14.dp))
                Clock()
            }
        }
    }
}

@Composable
fun Brand(modifier: Modifier = Modifier, large: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(if (large) 11.dp else 9.dp)
                .shadow(6.dp, RoundedCornerShape(3.dp), ambientColor = Tv.Accent, spotColor = Tv.Accent)
                .clip(RoundedCornerShape(3.dp))
                .background(Tv.Accent)
        )
        Spacer(Modifier.width(7.dp))
        Text("Cable", style = TvType.Title.copy(fontWeight = FontWeight.ExtraBold, fontSize = if (large) 22.sp else 18.sp))
    }
}

@Composable
private fun NavTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Focusable(onClick = onClick, modifier = modifier.height(34.dp), shape = CircleShape, focusScale = 1.05f, contentAlignment = Alignment.Center) {
        val focused = LocalFocused.current
        Text(
            label,
            style = TvType.BodyStrong,
            color = if (focused) Tv.FocusInk else if (selected) Tv.Ink else Tv.Ink2,
            maxLines = 1,
            modifier = Modifier
                .padding(horizontal = 13.dp)
                .drawBehind {
                    if (selected && !focused) {
                        val y = size.height + 6.dp.toPx()
                        drawLine(Tv.Accent, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
        )
    }
}

@Composable
private fun ProfileButton(name: String, compact: Boolean, onClick: () -> Unit) {
    Focusable(onClick = onClick, modifier = Modifier.height(34.dp), shape = CircleShape, focusScale = 1.05f, contentAlignment = Alignment.Center) {
        Row(Modifier.padding(start = 4.dp, end = if (compact) 4.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(name, Modifier.size(26.dp))
            if (!compact) {
                Spacer(Modifier.width(8.dp))
                Text(name, style = TvType.SmallStrong, color = ink2, maxLines = 1)
            }
        }
    }
}

private val avatarColors = listOf(Tv.Accent, Color(0xFF7CC4FF), Color(0xFFC7A6FF), Color(0xFF7BE0B0), Color(0xFFFF9C8A))

/** A round initial for a profile. Each name keeps the same color. */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(CircleShape).background(avatarColors[name.hashCode().absoluteValue % avatarColors.size]),
        contentAlignment = Alignment.Center
    ) {
        Text(name.take(1).uppercase(), style = TvType.CaptionStrong.copy(fontWeight = FontWeight.ExtraBold), color = Tv.FocusInk)
    }
}
