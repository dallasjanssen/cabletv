package com.dallas.cabletv.data

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * What is on screen, as Google Home, the Google TV Now Playing panel and Home Assistant see it.
 * [kind] is live, replay, movie or episode. [title] is the show or movie, or the channel name when
 * the guide has nothing for the channel.
 */
data class NowPlayingInfo(
    val kind: String,
    val title: String,
    val channel: String? = null,
    val channelNumber: Int? = null,
    val channelId: String? = null,
    /** The show's start and end, unix milliseconds, when the guide knows them. */
    val start: Long? = null,
    val end: Long? = null,
    /** Channel logo or poster address. */
    val image: String? = null,
    val series: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val year: Int? = null
) {
    /** The second line under the title. */
    val subtitle: String
        get() = when (kind) {
            KIND_LIVE -> listOfNotNull(channel, channelNumber?.let { "Channel $it" }).joinToString(" · ")
            KIND_REPLAY -> listOfNotNull(channel, "Replay").joinToString(" · ")
            KIND_EPISODE -> listOfNotNull(
                series,
                if (season != null && episode != null) "S$season E$episode" else null
            ).joinToString(" · ")
            else -> year?.toString() ?: "Movie"
        }

    companion object {
        const val KIND_LIVE = "live"
        const val KIND_REPLAY = "replay"
        const val KIND_MOVIE = "movie"
        const val KIND_EPISODE = "episode"
    }
}

/** What a live channel shows: the show on now from the guide, or just the channel. */
fun liveNowPlaying(ch: LiveChannel, onNow: Program?, replay: Program? = null): NowPlayingInfo {
    val show = replay ?: onNow
    return NowPlayingInfo(
        kind = if (replay != null) NowPlayingInfo.KIND_REPLAY else NowPlayingInfo.KIND_LIVE,
        title = show?.title?.takeIf { it.isNotBlank() } ?: ch.name,
        channel = ch.name,
        channelNumber = ch.number.takeIf { it > 0 },
        channelId = ch.id,
        start = show?.start,
        end = show?.end,
        image = ch.logo?.takeIf { it.isNotBlank() }
    )
}

/** What a movie or an episode shows. */
fun vodNowPlaying(item: VodItem): NowPlayingInfo {
    val episode = item.type.equals("Episode", ignoreCase = true)
    return NowPlayingInfo(
        kind = if (episode) NowPlayingInfo.KIND_EPISODE else NowPlayingInfo.KIND_MOVIE,
        title = item.name,
        series = item.seriesName?.takeIf { episode && it.isNotBlank() },
        season = item.season.takeIf { episode },
        episode = item.episode.takeIf { episode },
        year = item.year,
        image = item.posterUrl.takeIf { it.isNotBlank() }
    )
}

const val HA_PLAYING = "playing"
const val HA_PAUSED = "paused"
const val HA_IDLE = "idle"

/** What the reporter currently says is playing. The phone companion watches this, so it hears what Home Assistant hears. */
data class NowPlayingSnapshot(val state: String, val info: NowPlayingInfo?)

/** What a webhook message is about, in its "event" field. */
const val HA_EVENT_NOW_PLAYING = "now_playing"
const val HA_EVENT_FAVORITES = "favorites"

/** A favorite channel as Home Assistant lists it. [number] is zero when the channel has none. */
data class FavoriteChannel(val name: String, val number: Int)

/**
 * The favorites to send: the channels in [channels] whose id is in [favoriteIds], so hidden channels
 * stay out. Names are what Home Assistant tunes by, so a name that appears twice is sent once.
 * Sorted by channel number, with unnumbered channels last, then by name.
 */
fun favoriteChannels(channels: List<LiveChannel>, favoriteIds: Set<String>): List<FavoriteChannel> =
    channels.asSequence()
        .filter { it.id in favoriteIds && it.name.isNotBlank() }
        .map { FavoriteChannel(it.name.trim(), it.number.coerceAtLeast(0)) }
        .distinctBy { it.name.lowercase() }
        .sortedWith(compareBy<FavoriteChannel>({ it.number <= 0 }, { it.number }, { it.name.lowercase() }))
        .toList()

/** The favorites message. Home Assistant keeps one list per TV and combines them. */
fun favoritesJson(device: String, favorites: List<FavoriteChannel>): String {
    val list = JSONArray()
    favorites.forEach { f ->
        val item = JSONObject().put("name", f.name)
        if (f.number > 0) item.put("number", f.number)
        list.put(item)
    }
    return JSONObject()
        .put("event", HA_EVENT_FAVORITES)
        .put("device", device)
        .put("favorites", list)
        .toString()
}

/**
 * The message sent to the Home Assistant webhook. Fields with nothing to say are left out, and
 * times are UTC in ISO 8601, which Home Assistant reads with as_datetime.
 */
fun nowPlayingJson(device: String, state: String, info: NowPlayingInfo?): String {
    val json = JSONObject()
    json.put("event", HA_EVENT_NOW_PLAYING)
    json.put("device", device)
    json.put("state", state)
    if (info != null && state != HA_IDLE) {
        json.put("kind", info.kind)
        json.put("title", info.title)
        info.channel?.let { json.put("channel", it) }
        info.channelNumber?.let { json.put("channel_number", it) }
        info.channelId?.let { json.put("channel_id", it) }
        info.series?.let { json.put("series", it) }
        info.season?.let { json.put("season", it) }
        info.episode?.let { json.put("episode", it) }
        info.year?.let { json.put("year", it) }
        info.start?.let { json.put("start", Instant.ofEpochMilli(it).toString()) }
        info.end?.let { json.put("end", Instant.ofEpochMilli(it).toString()) }
        info.image?.let { json.put("image", it) }
    }
    return json.toString()
}

/** Adds http:// to an address typed without one. An empty value stays empty, which turns reporting off. */
fun normalizeWebhookUrl(value: String): String {
    val t = value.trim()
    if (t.isEmpty() || Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(t)) return t
    return "http://$t"
}

/** True for an empty value, which turns reporting off, and for an http or https address. */
fun isWebhookUrl(value: String): Boolean =
    value.isEmpty() || value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)

/** The name this TV goes by in Android settings, such as Living Room TV. */
fun tvDeviceName(context: Context): String =
    runCatching { AndroidSettings.Global.getString(context.contentResolver, AndroidSettings.Global.DEVICE_NAME) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: Build.MODEL.orEmpty().ifBlank { "Android TV" }

/**
 * Tells a Home Assistant webhook what is playing, and which channels are favorites. Live TV and
 * movies each report as their own [owner], so one going quiet does not hide the other. Changes are
 * sent after a short pause, so flipping through channels sends only the one you stop on, and
 * nothing is sent twice in a row. Going idle is sent straight away, because the app may be closing.
 * Only used on the main thread.
 */
object HomeAssistantReporter {
    private const val SETTLE_MS = 1_500L
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val handler = Handler(Looper.getMainLooper())

    /** One kind of message, with its own wait and its own memory of what was last sent. */
    private class Outbox {
        var lastSent: String? = null
        var pending: Runnable? = null
    }

    private val nowPlayingBox = Outbox()
    private val favoritesBox = Outbox()

    private var url = ""
    private var device = ""
    private var owner: String? = null
    private var state = HA_IDLE
    private var info: NowPlayingInfo? = null
    private val snapshotFlow = MutableStateFlow(NowPlayingSnapshot(HA_IDLE, null))

    /** What is playing now, as a flow others can watch from any thread. It changes only when the message would. */
    val nowPlaying: StateFlow<NowPlayingSnapshot> get() = snapshotFlow

    /** Null until the channel list has loaded, so an empty list is never sent by mistake. */
    private var favorites: List<FavoriteChannel>? = null

    private fun currentBody(): String = nowPlayingJson(device, state, info)

    fun configure(webhookUrl: String, deviceName: String) {
        if (webhookUrl == url && deviceName == device) return
        url = webhookUrl
        device = deviceName
        // A new address gets the current state and favorites, even if the old one already had them.
        nowPlayingBox.lastSent = null
        favoritesBox.lastSent = null
        schedule(nowPlayingBox, currentBody(), SETTLE_MS)
        favorites?.let { schedule(favoritesBox, favoritesJson(device, it), SETTLE_MS) }
    }

    /** What [who] is showing now. */
    fun report(who: String, newState: String, newInfo: NowPlayingInfo?) {
        owner = who
        state = newState
        info = newInfo
        snapshotFlow.value = NowPlayingSnapshot(newState, newInfo)
        schedule(nowPlayingBox, currentBody(), SETTLE_MS)
    }

    /** [who] stopped showing anything. Ignored when the other one has taken over since. */
    fun clear(who: String) {
        if (owner != null && owner != who) return
        clearAll()
    }

    /** Nothing is on screen, for example because the app left the screen. */
    fun clearAll() {
        owner = null
        state = HA_IDLE
        info = null
        snapshotFlow.value = NowPlayingSnapshot(HA_IDLE, null)
        schedule(nowPlayingBox, currentBody(), 0L)
    }

    /** The favorite channels on this TV. Sent at start and whenever they change. */
    fun reportFavorites(list: List<FavoriteChannel>) {
        favorites = list
        schedule(favoritesBox, favoritesJson(device, list), SETTLE_MS)
    }

    /**
     * Sends [body] to [webhookUrl] and waits for the answer. For the test button, so call it off the
     * main thread. Home Assistant answers 200 even for a webhook id it does not know, so this shows
     * that it is reachable, not that an automation is listening.
     */
    fun sendNow(webhookUrl: String, body: String): Result<Int> = runCatching {
        http.newCall(request(webhookUrl, body)).execute().use { it.code }
    }

    /** The messages the test button sends: what is on screen now, then the favorites. Read on the main thread. */
    fun snapshot(): List<String> = listOfNotNull(currentBody(), favorites?.let { favoritesJson(device, it) })

    private fun schedule(box: Outbox, body: String, delayMs: Long) {
        box.pending?.let(handler::removeCallbacks)
        box.pending = null
        if (url.isBlank() || body == box.lastSent) return
        val task = Runnable {
            box.pending = null
            send(box, body)
        }
        box.pending = task
        if (delayMs <= 0L) task.run() else handler.postDelayed(task, delayMs)
    }

    private fun send(box: Outbox, body: String) {
        val target = url
        if (target.isBlank()) return
        box.lastSent = body
        val call = runCatching { http.newCall(request(target, body)) }.getOrElse {
            // A malformed address. Nothing more is tried until it changes.
            return
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Home Assistant was not reachable. The next report tries again.
                handler.post { if (box.lastSent == body) box.lastSent = null }
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    private fun request(target: String, body: String): Request =
        Request.Builder()
            .url(target)
            .header("User-Agent", "CableTV/1.0")
            .post(body.toRequestBody(JSON_TYPE))
            .build()
}
