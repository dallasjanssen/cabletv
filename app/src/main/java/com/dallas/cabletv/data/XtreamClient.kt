package com.dallas.cabletv.data

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to an Xtream Codes style IPTV panel.
 * Account info decides the live format, the catch-up clock, and whether the login is still valid.
 */
class XtreamClient(server: String, user: String, pass: String) {

    private val base = normalizeXtreamServerUrl(server)
    private val user = user.trim()
    private val pass = pass.trim()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val probeHttp = http.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val portalLock = Any()
    private var portalCache: PortalInfo? = null
    private var catchupVariant: CatchupVariant? = null
    private var catchupMissKey: String? = null
    private var catchupMissAt: Long = 0L

    private fun creds(): String = "username=${enc(user)}&password=${enc(pass)}"

    private fun get(url: String, client: OkHttpClient = http): String {
        val req = Request.Builder().url(url).header("User-Agent", PLAYER_USER_AGENT).build()
        client.newCall(req).execute().use { resp ->
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

    /** Throws when the account is missing, rejected, or expired. */
    fun requireActive() {
        val info = readPortal() ?: error("Could not read the provider account")
        remember(info)
        when (info.status) {
            PortalStatus.ACTIVE -> return
            PortalStatus.EXPIRED -> error("Provider subscription has expired")
            PortalStatus.INACTIVE -> error("Live TV login failed. Check server, user and password.")
            PortalStatus.UNAVAILABLE -> error("Could not read the provider account")
        }
    }

    fun liveChannels(): List<LiveChannel> {
        val info = portalForStreams()
        val categories = mutableMapOf<String, String>()
        val cats = JSONArray(api("get_live_categories"))
        for (i in 0 until cats.length()) {
            val c = cats.getJSONObject(i)
            categories[c.optString("category_id")] = c.optString("category_name")
        }

        val arr = JSONArray(api("get_live_streams"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val id = o.optString("stream_id")
            val (primary, fallback) = liveStreamUrls(base, user, pass, id, info.formats)
            val (catchup, days) = catchupOffered(jsonValue(o, "tv_archive"), jsonValue(o, "tv_archive_duration"))
            LiveChannel(
                id = id,
                number = o.optInt("num", i + 1),
                name = o.optString("name"),
                logo = o.optString("stream_icon").takeIf { it.isNotBlank() },
                group = categories[o.optString("category_id")] ?: "Other",
                streamUrl = primary,
                epgId = o.optString("epg_channel_id").takeIf { it.isNotBlank() },
                catchup = catchup,
                archiveDays = days,
                fallbackStreamUrl = fallback
            )
        }.sortedBy { it.number }
    }

    /** Newest movies on the provider, by the date the panel added them. */
    fun vodMovies(limit: Int = 60): List<VodItem> {
        val categories = categoryNames("get_vod_categories")
        val arr = JSONArray(api("get_vod_streams"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val id = o.optString("stream_id")
            VodItem(
                id = id,
                name = o.optString("name"),
                year = null,
                overview = null,
                posterUrl = o.optString("stream_icon"),
                streamUrl = contentStreamUrl(
                    base, user, pass, "movie", id,
                    o.optString("container_extension"),
                    o.optString("direct_source")
                ),
                type = "Movie",
                source = "xtream",
                addedAt = recentlyAddedEpochSeconds(jsonLong(o, "added"), jsonLong(o, "last_modified"), series = false),
                category = categories[o.optString("category_id")] ?: "Movies"
            )
        }.sortedByDescending { it.addedAt }.take(limit)
    }

    /** Plot, year and backdrop for one movie. The list call does not include them. */
    fun movieDetails(streamId: String): VodItem? = try {
        val json = JSONObject(api("get_vod_info", "vod_id=$streamId"))
        val info = json.optJSONObject("info") ?: return null
        val movieData = json.optJSONObject("movie_data")
        VodItem(
            id = streamId,
            name = info.optString("name"),
            year = info.optString("releasedate").take(4).toIntOrNull() ?: info.optString("year").toIntOrNull(),
            overview = info.optString("plot").takeIf { it.isNotBlank() } ?: info.optString("description").takeIf { it.isNotBlank() },
            posterUrl = info.optString("movie_image"),
            streamUrl = "",
            type = "Movie",
            source = "xtream",
            backdropUrl = info.optString("backdrop_path").takeIf { it.startsWith("http") }
                ?: movieData?.optString("stream_icon")?.takeIf { it.startsWith("http") },
            runtimeMinutes = info.optInt("duration_secs", 0).takeIf { it > 0 }?.div(60)
        )
    } catch (e: Exception) {
        null
    }

    /** Newest series on the provider. Episodes load separately when one is opened. */
    fun vodSeries(limit: Int = 60): List<VodItem> {
        val categories = categoryNames("get_series_categories")
        val arr = JSONArray(api("get_series"))
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
                addedAt = recentlyAddedEpochSeconds(jsonLong(o, "added"), jsonLong(o, "last_modified"), series = true),
                category = categories[o.optString("category_id")] ?: "Shows"
            )
        }.sortedByDescending { it.addedAt }.take(limit)
    }

    private fun categoryNames(action: String): Map<String, String> = try {
        val arr = JSONArray(api(action))
        buildMap {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                put(o.optString("category_id"), o.optString("category_name"))
            }
        }
    } catch (e: Exception) {
        emptyMap()
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
                val number = e.optInt("episode_num", 0)
                val title = e.optString("title")
                val epInfo = e.optJSONObject("info")
                val item = VodItem(
                    id = id,
                    name = title.ifBlank { "Episode $number" },
                    year = null,
                    overview = epInfo?.optString("plot")?.takeIf { it.isNotBlank() },
                    posterUrl = epInfo?.optString("movie_image").orEmpty(),
                    streamUrl = contentStreamUrl(
                        base, user, pass, "series", id,
                        e.optString("container_extension"),
                        e.optString("direct_source")
                    ),
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

    /**
     * Replay address for a programme that already aired.
     * The working catch-up shape is probed once and remembered. A miss is remembered
     * for a minute for that programme, then the plain timeshift address is used.
     */
    fun resolveTimeshiftUrl(streamId: String, startMs: Long, stopMs: Long): String {
        val info = portalForStreams()
        synchronized(portalLock) {
            catchupVariant?.let { return buildCatchup(it, info.timezone, streamId, startMs, stopMs) }
            val key = "$streamId@$startMs"
            if (catchupMissKey == key && System.currentTimeMillis() - catchupMissAt < 60_000L) {
                return buildCatchup(CatchupVariant.REST_TS, info.timezone, streamId, startMs, stopMs)
            }
        }
        val chosen = runCatching {
            chooseCatchupVariant(info.formats) { variant ->
                probeStatus(buildCatchup(variant, info.timezone, streamId, startMs, stopMs))
            }
        }.getOrNull()
        synchronized(portalLock) {
            if (chosen != null) {
                catchupVariant = chosen
            } else {
                catchupMissKey = "$streamId@$startMs"
                catchupMissAt = System.currentTimeMillis()
            }
        }
        return buildCatchup(chosen ?: CatchupVariant.REST_TS, info.timezone, streamId, startMs, stopMs)
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

    private fun buildCatchup(
        variant: CatchupVariant,
        timezone: String?,
        streamId: String,
        startMs: Long,
        stopMs: Long
    ): String = catchupUrl(base, user, pass, streamId, startMs, stopMs, timezone, variant)

    private fun probeStatus(url: String): Int = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", PLAYER_USER_AGENT)
            .header("Range", "bytes=0-1023")
            .build()
        probeHttp.newCall(req).execute().use { it.code }
    } catch (e: Exception) {
        -1
    }

    private fun portalForStreams(): PortalInfo {
        synchronized(portalLock) {
            portalCache?.let { cached ->
                when (cached.status) {
                    PortalStatus.EXPIRED -> error("Provider subscription has expired")
                    PortalStatus.INACTIVE -> error("Live TV login was rejected")
                    else -> return cached
                }
            }
        }
        val info = readPortal() ?: PortalInfo(PortalStatus.ACTIVE, emptyList(), null)
        when (info.status) {
            PortalStatus.EXPIRED -> error("Provider subscription has expired")
            PortalStatus.INACTIVE -> error("Live TV login was rejected")
            else -> remember(info)
        }
        return info
    }

    private fun remember(info: PortalInfo) {
        synchronized(portalLock) { portalCache = info }
    }

    /**
     * Account info from whichever action this panel actually implements.
     * get_account_info, a bare call, and get_profile are tried. An active or
     * expired answer wins over a rejection from an action the panel does not mean.
     * The search stops once the panel clock is known, or the account is expired.
     */
    private fun readPortal(): PortalInfo? {
        var found: PortalInfo? = null
        for (action in listOf("get_account_info", null, "get_profile")) {
            val candidate = readAccountAction(action) ?: continue
            val info = preferAccount(found, candidate)
            found = info
            if (info.status == PortalStatus.EXPIRED || info.timezone != null) break
        }
        return found
    }

    private fun readAccountAction(action: String?): PortalInfo? = try {
        val json = JSONObject(api(action))
        val userInfo = json.optJSONObject("user_info")
        val status = userInfo?.let {
            resolvePortalStatus(jsonValue(it, "auth"), it.optString("status"), jsonValue(it, "exp_date"))
        }
        if (userInfo == null || status == null || status == PortalStatus.UNAVAILABLE) {
            null
        } else {
            val server = json.optJSONObject("server_info")
            PortalInfo(
                status = status,
                formats = formatsOf(userInfo),
                timezone = resolveServerTimezone(
                    server?.optString("timezone"),
                    server?.optString("time_now"),
                    server?.let { jsonValue(it, "timestamp_now") }
                ),
                expiresAt = expirationEpochMillis(jsonValue(userInfo, "exp_date")) ?: 0L
            )
        }
    } catch (e: Exception) {
        null
    }

    /** Expired beats everything. Active beats a rejection. Missing timezone and formats are filled in. */
    private fun preferAccount(current: PortalInfo?, candidate: PortalInfo): PortalInfo {
        if (current == null) return candidate
        val status = when {
            current.status == PortalStatus.EXPIRED || candidate.status == PortalStatus.EXPIRED -> PortalStatus.EXPIRED
            current.status == PortalStatus.ACTIVE || candidate.status == PortalStatus.ACTIVE -> PortalStatus.ACTIVE
            else -> current.status
        }
        return PortalInfo(
            status = status,
            formats = current.formats.ifEmpty { candidate.formats },
            timezone = current.timezone ?: candidate.timezone,
            expiresAt = current.expiresAt.takeIf { it > 0L } ?: candidate.expiresAt
        )
    }

    private fun formatsOf(userInfo: JSONObject): List<String> {
        val raw = jsonValue(userInfo, "allowed_output_formats")
        return when (raw) {
            is JSONArray -> (0 until raw.length()).mapNotNull { raw.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
            is String -> raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }

    private fun jsonLong(o: JSONObject, key: String): Long? = when (val value = jsonValue(o, key)) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    private fun jsonValue(o: JSONObject, key: String): Any? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.get(key)
    }

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun decodeB64(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        s
    }

    /** When the subscription ends, or 0 when the panel did not say. */
    fun subscriptionExpiryMs(): Long = readPortal()?.expiresAt ?: 0L

    private data class PortalInfo(
        val status: PortalStatus,
        val formats: List<String>,
        val timezone: String?,
        val expiresAt: Long = 0L
    )
}
