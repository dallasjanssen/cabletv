package com.dallas.cabletv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dallas.cabletv.CableViewModel
import com.dallas.cabletv.data.LiveChannel
import androidx.compose.foundation.clickable

enum class Screen { Menu, LiveTv, Guide, OnDemand, Settings }

@Composable
fun CableApp(vm: CableViewModel) {
    var screen by remember { mutableStateOf(Screen.Menu) }
    val channels by vm.channels.collectAsState()
    val guideStatus by vm.guideStatus.collectAsState()
    val settings by vm.settings.collectAsState()
    val liveChannel = vm.playback.liveChannel

    /** Leaving to the menu stops any live or On Demand playback. */
    fun goMenu() {
        vm.playback.stopLive()
        vm.playback.stopVod()
        screen = Screen.Menu
    }

    fun watch(ch: LiveChannel) {
        vm.tune(ch)
        screen = Screen.LiveTv
    }

    BackHandler(enabled = screen == Screen.Settings) { goMenu() }

    Box(Modifier.fillMaxSize().background(CableColors.Bg)) {
        when (screen) {
            Screen.Menu -> MenuScreen(
                channelCount = channels.size,
                guideStatus = guideStatus,
                guideHasData = vm.epg.hasData(),
                nowPlaying = liveChannel?.name,
                jellyfinReady = settings.hasJellyfin,
                onOpen = { target -> screen = target }
            )
            Screen.LiveTv -> LiveTvScreen(
                vm = vm,
                onExit = ::goMenu,
                onGuide = { screen = Screen.Guide }
            )
            Screen.Guide -> GuideScreen(
                vm = vm,
                onWatch = ::watch,
                onLive = { screen = Screen.LiveTv },
                onExit = ::goMenu
            )
            Screen.OnDemand -> OnDemandScreen(vm = vm, onExit = ::goMenu)
            Screen.Settings -> SettingsScreen(vm = vm, onExit = { screen = Screen.Menu })
        }
    }
}

@Composable
private fun MenuScreen(
    channelCount: Int,
    guideStatus: String?,
    guideHasData: Boolean,
    nowPlaying: String?,
    jellyfinReady: Boolean,
    onOpen: (Screen) -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocus() }

    val entries = listOf(
        Screen.LiveTv to "1   LIVE TV",
        Screen.Guide to "2   TV GUIDE",
        Screen.OnDemand to "3   ON DEMAND",
        Screen.Settings to "4   SETTINGS"
    )

    Column(Modifier.fillMaxSize()) {
        CableHeader("CABLE TV")
        Row(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Column(Modifier.weight(1f).border(2.dp, CableColors.Dim)) {
                entries.forEachIndexed { i, (target, label) ->
                    MenuRow(
                        label = label,
                        onClick = { onOpen(target) },
                        modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier
                    )
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .border(2.dp, CableColors.Dim)
                    .background(CableColors.Panel.copy(alpha = 0.6f))
                    .padding(24.dp)
            ) {
                Text(
                    "SYSTEM STATUS",
                    color = CableColors.Led,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                Spacer(Modifier.height(16.dp))
                StatusLine("CHANNELS LOADED", channelCount.toString())
                StatusLine(
                    "PROGRAM GUIDE",
                    when {
                        guideHasData -> "READY"
                        guideStatus != null -> guideStatus
                        else -> "NOT SET UP"
                    }
                )
                StatusLine("ON DEMAND", if (jellyfinReady) "CONNECTED" else "NOT SET UP")
                StatusLine("NOW PLAYING", nowPlaying?.uppercase() ?: "NOTHING")
                Spacer(Modifier.height(24.dp))
                Text(
                    "SELECT AN OPTION WITH THE REMOTE OR BY TAPPING IT.",
                    color = CableColors.Dim,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) CableColors.Highlight else Color.Transparent)
            .border(
                width = if (focused) 4.dp else 0.dp,
                color = if (focused) CableColors.Selected else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = CableColors.Text, fontWeight = FontWeight.Bold, fontSize = 24.sp)
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = CableColors.Dim, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            color = CableColors.Text,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
