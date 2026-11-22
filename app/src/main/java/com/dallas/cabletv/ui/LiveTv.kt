package com.dallas.cabletv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.dallas.cabletv.CableViewModel
import com.dallas.cabletv.data.ChannelLogic
import com.dallas.cabletv.data.EpgRepository
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.NowNext
import com.dallas.cabletv.data.Program
import kotlinx.coroutines.delay

private const val BANNER_MS = 5000L

@Composable
fun LiveTvScreen(vm: CableViewModel, onExit: () -> Unit, onGuide: () -> Unit) {
    val channels by vm.channels.collectAsState()
    val loading by vm.channelsLoading.collectAsState()
    val error by vm.channelError.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val guideVersion by vm.guideVersion.collectAsState()
    val playback = vm.playback
    val current = playback.liveChannel

    var listOpen by remember { mutableStateOf(false) }
    var trackOpen by remember { mutableStateOf(false) }
    var showBanner by remember { mutableStateOf(false) }
    var bannerTick by remember { mutableIntStateOf(0) }
    var digits by remember { mutableStateOf("") }
    var group by remember { mutableStateOf(ChannelLogic.ALL_GROUP) }
    var search by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    fun tuneTo(ch: LiveChannel) {
        vm.tune(ch)
        bannerTick++
    }

    fun stepChannel(delta: Int) {
        if (channels.isEmpty()) return
        val idx = channels.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
        tuneTo(channels[ChannelLogic.step(idx, delta, channels.size)])
    }

    fun lastChannel() {
        val id = vm.lastChannelId.value ?: return
        channels.firstOrNull { it.id == id }?.let { tuneTo(it) }
    }

    // Start playing when the list first arrives, or when nothing is tuned yet.
    LaunchedEffect(channels, current) {
        if (current == null && channels.isNotEmpty()) playback.tuneLive(channels.first())
    }

    LaunchedEffect(bannerTick) {
        if (bannerTick > 0) {
            showBanner = true
            delay(BANNER_MS)
            showBanner = false
        }
    }

    // Number keys tune once the user pauses typing.
    LaunchedEffect(digits) {
        if (digits.isNotEmpty()) {
            delay(1500)
            val idx = ChannelLogic.indexOfNumber(channels, ChannelLogic.parseTypedNumber(digits) ?: -1)
            if (idx >= 0) tuneTo(channels[idx])
            digits = ""
            bannerTick++
        }
    }

    LaunchedEffect(listOpen, trackOpen) {
        if (!listOpen && !trackOpen) focus.requestFocus()
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    BackHandler(enabled = listOpen || trackOpen) {
        listOpen = false
        trackOpen = false
    }
    BackHandler(enabled = !listOpen && !trackOpen) { onExit() }

    val nowNext: NowNext? = remember(current?.id, guideVersion) {
        current?.let { vm.epg.nowNext(it) }
    }

    val filtered = remember(channels, group, favorites, search) {
        ChannelLogic.filter(channels, group, favorites, search)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown || listOpen || trackOpen) return@onPreviewKeyEvent false
                when (val code = ev.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_CHANNEL_UP -> { stepChannel(1); true }
                    KeyEvent.KEYCODE_CHANNEL_DOWN -> { stepChannel(-1); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { stepChannel(1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { stepChannel(-1); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { listOpen = true; true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_INFO -> {
                        bannerTick++
                        true
                    }
                    KeyEvent.KEYCODE_LAST_CHANNEL -> { lastChannel(); true }
                    KeyEvent.KEYCODE_GUIDE -> { onGuide(); true }
                    KeyEvent.KEYCODE_MENU -> { trackOpen = true; true }
                    KeyEvent.KEYCODE_BUTTON_Y -> {
                        current?.let { vm.toggleFavorite(it.id) }
                        bannerTick++
                        true
                    }
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                        if (digits.length < 4) digits += (code - KeyEvent.KEYCODE_0).toString()
                        true
                    }
                    else -> false
                }
            }
            .clickable { bannerTick++ }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = playback.player
                }
            },
            update = { it.player = playback.player },
            modifier = Modifier.fillMaxSize()
        )

        when {
            channels.isEmpty() -> CenterMessage(
                when {
                    loading -> "SEARCHING FOR CHANNELS..."
                    error != null -> "ERROR: $error"
                    else -> "NO CHANNELS. OPEN SETTINGS TO ADD A SOURCE."
                }
            )
            else -> SignalOverlay(playback)
        }

        if (current != null) {
            Tag(
                "LIVE",
                color = CableColors.Text,
                bg = CableColors.Live,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
            )
        }

        if (digits.isNotEmpty()) {
            Text(
                digits,
                color = CableColors.Text,
                fontWeight = FontWeight.Bold,
                fontSize = 48.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(CableColors.PanelDeep.copy(alpha = 0.9f))
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            )
        }

        AnimatedVisibility(
            visible = showBanner && current != null && !listOpen && !trackOpen,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            if (current != null) {
                ChannelBanner(
                    channel = current,
                    nn = nowNext,
                    favorite = current.id in favorites
                )
            }
        }

        AnimatedVisibility(
            visible = listOpen,
            modifier = Modifier.align(Alignment.CenterStart),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            ChannelListPanel(
                channels = filtered,
                groups = ChannelLogic.groups(channels, favorites),
                selectedGroup = group,
                onGroup = { group = it },
                search = search,
                onSearch = { search = it },
                favorites = favorites,
                currentId = current?.id,
                onPick = { ch ->
                    tuneTo(ch)
                    listOpen = false
                },
                onToggleFavorite = { vm.toggleFavorite(it) },
                epg = vm.epg,
                guideVersion = guideVersion
            )
        }

        AnimatedVisibility(
            visible = trackOpen,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            TrackMenu(playback = playback, onDismiss = { trackOpen = false })
        }
    }
}

@Composable
private fun ChannelBanner(channel: LiveChannel, nn: NowNext?, favorite: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(CableColors.PanelDeep.copy(alpha = 0.94f))
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (channel.logo != null) {
                AsyncImage(
                    model = channel.logo,
                    contentDescription = channel.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(72.dp)
                        .background(CableColors.Text, RoundedCornerShape(4.dp))
                        .padding(4.dp)
                )
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        channel.number.toString(),
                        color = CableColors.Text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        channel.name.uppercase(),
                        color = CableColors.Text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (favorite) {
                        Spacer(Modifier.width(10.dp))
                        Text("FAV", color = CableColors.Selected, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
                val now = nn?.now
                if (now != null) {
                    Text(
                        now.title,
                        color = CableColors.Text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(formatRange(now), color = CableColors.Dim, fontSize = 15.sp)
                    ProgressBar(ChannelLogic.progress(now, System.currentTimeMillis()))
                } else {
                    Text("NO GUIDE DATA", color = CableColors.Dim, fontSize = 16.sp)
                }
                nn?.next?.let {
                    Text(
                        "NEXT  ${formatTime(it.start)}  ${it.title}",
                        color = CableColors.Dim,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Text(
                formatTime(System.currentTimeMillis()),
                color = CableColors.Text,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }
        Spacer(Modifier.height(12.dp))
        LegendBar {
            KeyLegend("CH", "CHANNEL", CableColors.Selected)
            KeyLegend("LEFT", "LIST", CableColors.Selected)
            KeyLegend("MENU", "AUDIO / SUBS", CableColors.TimeBar)
            KeyLegend("Y", "FAVORITE", CableColors.TimeBar)
            KeyLegend("GUIDE", "GUIDE", CableColors.Live)
        }
    }
}

@Composable
private fun ChannelListPanel(
    channels: List<LiveChannel>,
    groups: List<String>,
    selectedGroup: String,
    onGroup: (String) -> Unit,
    search: String,
    onSearch: (String) -> Unit,
    favorites: Set<String>,
    currentId: String?,
    onPick: (LiveChannel) -> Unit,
    onToggleFavorite: (String) -> Unit,
    epg: EpgRepository,
    guideVersion: Int
) {
    Column(
        Modifier
            .width(520.dp)
            .fillMaxSize()
            .background(CableColors.PanelDeep.copy(alpha = 0.96f))
            .padding(16.dp)
    ) {
        Text("CHANNELS", color = CableColors.Led, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(groups, key = { it }) { g ->
                GroupChip(label = g.uppercase(), selected = g == selectedGroup) { onGroup(g) }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = search,
            onValueChange = onSearch,
            singleLine = true,
            label = { Text("Search name or number") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(channels, key = { it.id }) { ch ->
                val nn = remember(ch.id, guideVersion) { epg.nowNext(ch) }
                ChannelListRow(
                    channel = ch,
                    selected = ch.id == currentId,
                    favorite = ch.id in favorites,
                    nowTitle = nn.now?.title,
                    onPick = { onPick(ch) },
                    onFavorite = { onToggleFavorite(ch.id) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LegendBar {
            KeyLegend("OK", "TUNE", CableColors.Selected)
            KeyLegend("BACK", "CLOSE", CableColors.TimeBar)
        }
    }
}

@Composable
private fun GroupChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        label,
        color = if (selected) CableColors.OnSelected else CableColors.Text,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    selected -> CableColors.Selected
                    focused -> CableColors.Highlight
                    else -> CableColors.Cell
                }
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun ChannelListRow(
    channel: LiveChannel,
    selected: Boolean,
    favorite: Boolean,
    nowTitle: String?,
    onPick: () -> Unit,
    onFavorite: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                when {
                    focused -> CableColors.Highlight
                    selected -> CableColors.Cell
                    else -> Color.Transparent
                }
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onPick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            channel.number.toString(),
            color = CableColors.Selected,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier.width(64.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                channel.name.uppercase(),
                color = CableColors.Text,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                nowTitle ?: "--",
                color = CableColors.Dim,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            if (favorite) "*" else "+",
            color = if (favorite) CableColors.Selected else CableColors.Dim,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            modifier = Modifier
                .clickable(onClick = onFavorite)
                .padding(horizontal = 12.dp)
        )
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(6.dp)
            .background(CableColors.Cell)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .background(CableColors.Selected)
        )
    }
}
