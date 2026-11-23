package com.dallas.cabletv.data

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Talks to an Xtream Codes style IPTV panel using its JSON API.
 * Channels, categories and the short guide all come from player_api.php.
 */
class XtreamClient(server: String, private val user: String, private val pass: String) {

    private val base = server.trim().trimEnd('/')

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun creds(): String = "username=${enc(user)}&password=${enc(pass)}"

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "CableTV/1.0").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return resp.body?.string() ?: error("Empty response from provider")
        }
    }

    private fun api(action: String? = null, extra: String = ""): String {
        val sb = StringBuilder("$base/player_api.php?${creds()}")
        if (action != null) sb.append("&action=").append(action)
        if (extra.isNotEmpty()) sb.append('&').append(extra)
        return get(sb.toString())
    }

    fun login(): Boolean =
        JSONObject(api()).optJSONObject("user_info")?.optInt("auth", 0) == 1

    fun liveChannels(): List<LiveChannel> {
        val categories = mutableMapOf<String, String>()
        val cats = org.json.JSONArray(api("get_live_categories"))
        for (i in 0 until cats.length()) {
            val c = cats.getJSONObject(i)
            categories[c.optString("category_id")] = c.optString("category_name")
        }

        val arr = org.json.JSONArray(api("get_live_streams"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val id = o.optString("stream_id")
            LiveChannel(
                id = id,
                number = o.optInt("num", i + 1),
                name = o.optString("name"),
                logo = o.optString("stream_icon").takeIf { it.isNotBlank() },
                group = categories[o.optString("category_id")] ?: "Other",
                streamUrl = "$base/live/${enc(user)}/${enc(pass)}/$id.ts",
                epgId = o.optString("epg_channel_id").takeIf { it.isNotBlank() },
                catchup = o.optString("tv_archive") == "1"
            )
        }.sortedBy { it.number }
    }

    /** Newest movies on the provider, by the date the panel added them. */
    fun vodMovies(limit: Int = 60): List<VodItem> {
        val arr = org.json.JSONArray(api("get_vod_streams"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val id = o.optString("stream_id")
            val ext = o.optString("container_extension").ifBlank { "mp4" }
            VodItem(
                id = id,
                name = o.optString("name"),
                year = null,
                overview = null,
                posterUrl = o.optString("stream_icon"),
                streamUrl = "$base/movie/${enc(user)}/${enc(pass)}/$id.$ext",
                type = "Movie",
                source = "xtream",
                addedAt = o.optLong("added")
            )
        }.sortedByDescending { it.addedAt }.take(limit)
    }

    /** Newest series on the provider. Episodes load separately when one is opened. */
    fun vodSeries(limit: Int = 60): List<VodItem> {
        val arr = org.json.JSONArray(api("get_series"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            VodItem(
                id = o.optString("series_id"),
                name = o.optString("name"),
                year = o.optString("releaseDate").take(4).toIntOrNull(),
                overview = o.optString("plot").takeIf { it.isNotBlank() },
                posterUrl = o.optString("cover"),
                streamUrl = "",
                type = "Series",
                source = "xtream",
                addedAt = o.optLong("last_modified")
            )
        }.sortedByDescending { it.addedAt }.take(limit)
    }

    /** Episodes of one series, in season and episode order. */
    fun seriesEpisodes(seriesId: String): List<VodItem> {
        val info = JSONObject(api("get_series_info", "series_id=$seriesId"))
        val bySeason = info.optJSONObject("episodes") ?: return emptyList()
        val showName = info.optJSONObject("info")?.optString("name")?.takeIf { it.isNotBlank() }
        val keyed = mutableListOf<Triple<Int, Int, VodItem>>()
        val seasons = bySeason.keys()
        while (seasons.hasNext()) {
            val seasonKey = seasons.next()
            val list = bySeason.optJSONArray(seasonKey) ?: continue
            val season = seasonKey.toIntOrNull() ?: 0
            for (i in 0 until list.length()) {
                val e = list.getJSONObject(i)
                val id = e.optString("id")
                val ext = e.optString("container_extension").ifBlank { "mp4" }
                val number = e.optInt("episode_num", 0)
                val title = e.optString("title")
                val epInfo = e.optJSONObject("info")
                val item = VodItem(
                    id = id,
                    name = title.ifBlank { "Episode $number" },
                    year = null,
                    overview = epInfo?.optString("plot")?.takeIf { it.isNotBlank() },
                    posterUrl = epInfo?.optString("movie_image").orEmpty(),
                    streamUrl = "$base/series/${enc(user)}/${enc(pass)}/$id.$ext",
                    type = "Episode",
                    source = "xtream",
                    season = season,
                    episode = number,
                    seriesName = showName,
                    runtimeMinutes = epInfo?.optInt("duration_secs", 0)?.takeIf { it > 0 }?.let { it / 60 }
                )
                keyed += Triple(season, number, item)
            }
        }
        return keyed
            .sortedWith(compareBy<Triple<Int, Int, VodItem>>({ it.first }, { it.second }))
            .map { it.third }
    }

    /** Replay URL for a programme that already aired on a channel with catch-up. Times are UTC. */
    fun timeshiftUrl(streamId: String, startMs: Long, stopMs: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val minutes = ((stopMs - startMs) / 60_000L).coerceAtLeast(1L)
        val stamp = fmt.format(java.util.Date(startMs))
        return "$base/timeshift/${enc(user)}/${enc(pass)}/$minutes/$stamp/$streamId.ts"
    }

    fun nowNext(streamId: String): NowNext {
        val listings = JSONObject(api("get_short_epg", "stream_id=$streamId&limit=2"))
            .optJSONArray("epg_listings") ?: return NowNext(null, null)

        val programs = (0 until listings.length()).map { i ->
            val o = listings.getJSONObject(i)
            Program(
                title = decodeB64(o.optString("title")),
                start = o.optLong("start_timestamp") * 1000L,
                end = o.optLong("stop_timestamp") * 1000L,
                desc = decodeB64(o.optString("description")).trim()
            )
        }

        val now = System.currentTimeMillis()
        return NowNext(
            now = programs.firstOrNull { now in it.start until it.end },
            next = programs.firstOrNull { it.start > now }
        )
    }

    private fun decodeB64(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        s
    }
}
