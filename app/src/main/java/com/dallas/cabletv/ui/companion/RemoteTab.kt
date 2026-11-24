package com.dallas.cabletv.ui.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.dallas.cabletv.data.companion.client.Favorite
import com.dallas.cabletv.data.companion.client.PairedTv
import com.dallas.cabletv.data.companion.client.RemoteKey
import com.dallas.cabletv.data.companion.client.SessionStatus
import com.dallas.cabletv.data.companion.client.keyAvailable
import com.dallas.cabletv.data.companion.client.nowPlaying
import com.dallas.cabletv.ui.Tv
import com.dallas.cabletv.ui.TvIcons
import com.dallas.cabletv.ui.TvType

/** The Remote tab: what is playing, the keys, power and volume, and favorites. */
@Composable
fun RemoteTab(m: TvScreenModel, tv: PairedTv) {
    val status by m.status.collectAsState()
    val state by m.state.collectAsState()
    val atv by m.atvState.collectAsState()
    val favorites by m.favorites.collectAsState()
    val message by m.message.collectAsState()
    val online = status is SessionStatus.Online
    val atvOn = atv.connected

    fun ok(key: RemoteKey) = keyAvailable(key, atvOn, online)

    @Composable
    fun Key(label: String, key: RemoteKey, modifier: Modifier, description: String = label) {
        KeyLabel(label, { m.press(key) }, modifier, enabled = ok(key), description = description)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        NowPlayingCard(nowPlaying(state))
        if (message != null) MessageLine(message.orEmpty(), error = true)

        if (!online) {
            SectionCard(null) {
                val text = when (status) {
                    is SessionStatus.Offline -> "Cable TV is closed or the TV is off."
                    else -> "Connecting to ${tv.name}..."
                }
                Text(text, style = TvType.Body, color = Tv.Ink2)
                if (atvOn) PrimaryButton("Open Cable TV", onClick = { m.openCableTv() }, modifier = Modifier.fillMaxWidth())
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Key("Power", RemoteKey.POWER, Modifier.weight(1f))
            Key("Vol -", RemoteKey.VOLUME_DOWN, Modifier.weight(1f), "Volume down")
            Key("Mute", RemoteKey.MUTE, Modifier.weight(1f))
            Key("Vol +", RemoteKey.VOLUME_UP, Modifier.weight(1f), "Volume up")
        }
        if (!tv.atvPaired) {
            SectionCard("Power and volume") {
                Text(
                    "Pair the TV's own remote to use power, volume and Home, and to open Cable TV when it is closed.",
                    style = TvType.Body,
                    color = Tv.Ink2
                )
                SecondaryButton("Set up power and volume", onClick = { m.startAtvPairing() }, modifier = Modifier.fillMaxWidth())
            }
        } else if (!atvOn) {
            Text("Power and volume are not connected yet.", style = TvType.Caption, color = Tv.Ink3)
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DPad(::ok, m) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key("Back", RemoteKey.BACK, Modifier.weight(1f))
            Key("Home", RemoteKey.HOME, Modifier.weight(1f))
            Key("Guide", RemoteKey.GUIDE, Modifier.weight(1f))
            Key("Info", RemoteKey.INFO, Modifier.weight(1f))
            Key("Last", RemoteKey.LAST, Modifier.weight(1f), "Last channel")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Key("Ch +", RemoteKey.CHANNEL_UP, Modifier.weight(1f), "Channel up")
            Key("Ch -", RemoteKey.CHANNEL_DOWN, Modifier.weight(1f), "Channel down")
            Key("Play/Pause", RemoteKey.PLAY_PAUSE, Modifier.weight(2f))
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (rowStart in listOf(1, 4, 7)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (d in rowStart..rowStart + 2) Key(d.toString(), RemoteKey.digit(d), Modifier.weight(1f))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.weight(1f))
                Key("0", RemoteKey.digit(0), Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
            }
        }

        FavoritesCard(favorites, enabled = online || atvOn, online = online) { m.openChannel(it) }
    }
}

@Composable
private fun DPad(ok: (RemoteKey) -> Boolean, m: TvScreenModel) {
    @Composable
    fun Arrow(key: RemoteKey, degrees: Float, description: String, modifier: Modifier) {
        KeyTile(description, { m.press(key) }, modifier, enabled = ok(key), height = 64.dp) {
            Icon(TvIcons.ChevronRight, contentDescription = null, tint = Tv.Ink, modifier = Modifier.rotate(degrees).size(26.dp))
        }
    }
    Column(
        Modifier.widthIn(max = 300.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            Arrow(RemoteKey.UP, 270f, "Up", Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Arrow(RemoteKey.LEFT, 180f, "Left", Modifier.weight(1f))
            KeyLabel("OK", { m.press(RemoteKey.OK) }, Modifier.weight(1f), enabled = ok(RemoteKey.OK), height = 64.dp, accent = true)
            Arrow(RemoteKey.RIGHT, 0f, "Right", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            Arrow(RemoteKey.DOWN, 90f, "Down", Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun NowPlayingCard(np: com.dallas.cabletv.data.companion.client.NowPlaying) {
    SectionCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(Tv.Surface3),
                contentAlignment = Alignment.Center
            ) {
                if (np.image != null) {
                    AsyncImage(
                        model = np.image,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(6.dp)
                    )
                } else {
                    Icon(TvIcons.Tv, contentDescription = null, tint = Tv.Ink3, modifier = Modifier.size(28.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("NOW PLAYING", style = TvType.Badge, color = Tv.Ink3)
                Text(np.title, style = TvType.Title, color = Tv.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                np.detail?.let { Text(it, style = TvType.Small, color = Tv.Ink2, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (np.paused) Text("Paused", style = TvType.SmallStrong, color = Tv.Accent)
            }
        }
    }
}

@Composable
private fun FavoritesCard(favorites: List<Favorite>?, enabled: Boolean, online: Boolean, onOpen: (Favorite) -> Unit) {
    var showAll by remember { mutableStateOf(false) }
    SectionCard("Favorites") {
        when {
            favorites == null -> Text(if (online) "Loading..." else "Favorites load when the TV is connected.", style = TvType.Body, color = Tv.Ink3)
            favorites.isEmpty() -> Text("No favorites yet. Add some on the TV.", style = TvType.Body, color = Tv.Ink3)
            else -> {
                val shown = if (showAll) favorites else favorites.take(30)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (f in shown) FavoriteRow(f, enabled) { onOpen(f) }
                }
                if (favorites.size > shown.size) QuietButton("Show all ${favorites.size}", onClick = { showAll = true })
            }
        }
    }
}

@Composable
private fun FavoriteRow(f: Favorite, enabled: Boolean, onClick: () -> Unit) {
    val tap = rememberTap()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (enabled) Modifier.clickable { tap(); onClick() } else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Tv.Surface3), contentAlignment = Alignment.Center) {
            if (f.logo != null) {
                AsyncImage(model = f.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
            } else {
                Text(f.name.take(2).uppercase(), style = TvType.CaptionStrong, color = Tv.Ink2)
            }
        }
        Text(f.name, style = TvType.Body, color = if (enabled) Tv.Ink else Tv.Ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (f.number > 0) Text(f.number.toString(), style = TvType.Small, color = Tv.Ink3)
    }
}
