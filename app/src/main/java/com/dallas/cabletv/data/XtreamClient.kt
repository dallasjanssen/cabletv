package com.dallas.cabletv.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A string field, or null when the key is missing, JSON null, blank, or the text "null". Android's optString gives "null" for a JSON null. */
internal fun JSONObject.str(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = opt(key)?.toString()?.trim() ?: return null
    return v.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
}

/**
 * Panels may send titles as base64. Decodes only when the text looks like base64 and the
 * bytes are valid UTF-8 text, so a plain title such as "News" is returned unchanged.
 */
internal fun decodeMaybeBase64(s: String): String {
    val t = s.trim()
    val compact = t.filterNot { it.isWhitespace() }
    if (compact.length < 4 || compact.length % 4 != 0) return s
    if (!compact.all { it.isLetterOrDigit() && it.code < 128 || it == '+' || it == '/' || it == '=' }) return s
    return try {
        val bytes = java.util.Base64.getDecoder().decode(compact)
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
        if (text.any { it.isISOControl() && !it.isWhitespace() }) s else text
    } catch (e: Exception) {
        s
    }
}

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
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val portalLock = Any()
    private var portalCache: PortalInfo? = null
    private var catchupVariant: CatchupVariant? = null
    private val catchupMisses = HashMap<String, Long>()

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
            categories[c.str("category_id") ?: ""] = c.str("category_name") ?: ""
        }

        val arr = JSONArray(api("get_live_streams"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val id = o.str("stream_id") ?: ""
            val (primary, fallback) = liveStreamUrls(base, user, pass, id, info.formats)
            val (catchup, days) = catchupOffered(jsonValue(o, "tv_archive"), jsonValue(o, "tv_archive_duration"))
            LiveChannel(
                id = id,
                number = o.optInt("num", i + 1),
                name = o.str("name") ?: "",
                logo = o.str("stream_icon"),
                group = categories[o.str("category_id") ?: ""] ?: "Other",
                streamUrl = primary,
                epgId = o.str("epg_channel_id"),
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
            val id = o.str("stream_id") ?: ""
            VodItem(
                id = id,
                name = o.str("name") ?: "",
                year = null,
                overview = null,
                posterUrl = o.str("stream_icon") ?: "",
                streamUrl = contentStreamUrl(
                    base, user, pass, "movie", id,
                    o.str("container_extension"),
                    o.str("direct_source")
                ),
                type = "Movie",
                source = "xtream",
                addedAt = recentlyAddedEpochSeconds(jsonLong(o, "added"), jsonLong(o, "last_modified"), series = false),
                category = categories[o.str("category_id") ?: ""] ?: "Movies"
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
            name = info.str("name") ?: "",
            year = info.str("releasedate")?.take(4)?.toIntOrNull() ?: info.str("year")?.toIntOrNull(),
            overview = info.str("plot") ?: info.str("description"),
            posterUrl = info.str("movie_image") ?: "",
            streamUrl = "",
            type = "Movie",
            source = "xtream",
            backdropUrl = info.str("backdrop_path")?.takeIf { it.startsWith("http") }
                ?: movieData?.str("stream_icon")?.takeIf { it.startsWith("http") },
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
                id = o.str("series_id") ?: "",
                name = o.str("name") ?: "",
                year = o.str("releaseDate")?.take(4)?.toIntOrNull(),
                overview = o.str("plot"),
                posterUrl = o.str("cover") ?: "",
                streamUrl = "",
                type = "Series",
                source = "xtream",
                addedAt = recentlyAddedEpochSeconds(jsonLong(o, "added"), jsonLong(o, "last_modified"), series = true),
                category = categories[o.str("category_id") ?: ""] ?: "Shows"
            )
        }.sortedByDescending { it.addedAt }.take(limit)
    }

    private fun categoryNames(action: String): Map<String, String> = try {
        val arr = JSONArray(api(action))
        buildMap {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                put(o.str("category_id") ?: "", o.str("category_name") ?: "")
            }
        }
    } catch (e: Exception) {
        emptyMap()
    }

    /** Episodes of one series, in season and episode order. */
    fun seriesEpisodes(seriesId: String): List<VodItem> {
        val info = JSONObject(api("get_series_info", "series_id=$seriesId"))
        val raw = info.opt("episodes")
        val showName = info.optJSONObject("info")?.str("name")
        val keyed = mutableListOf<Triple<Int, Int, VodItem>>()

        fun add(e: JSONObject, season: Int) {
            val id = e.str("id") ?: return
            val number = e.optInt("episode_num", 0)
            val epInfo = e.optJSONObject("info")
            val item = VodItem(
                id = id,
                name = e.str("title") ?: "Episode $number",
                year = null,
                overview = epInfo?.str("plot"),
                posterUrl = epInfo?.str("movie_image") ?: "",
                streamUrl = contentStreamUrl(
                    base, user, pass, "series", id,
                    e.str("container_extension"),
                    e.str("direct_source")
                ),
                type = "Episode",
                source = "xtream",
                season = season,
                episode = number,
                seriesName = showName,
                runtimeMinutes = epInfo?.optInt("duration_secs", 0)?.takeIf { it > 0 }?.let { it / 60 },
                seriesId = seriesId
            )
            keyed += Triple(season, number, item)
        }

        fun addList(list: JSONArray, season: Int?, fallback: Int = 0) {
            for (i in 0 until list.length()) {
                val e = list.optJSONObject(i) ?: continue
                add(e, season ?: e.optInt("season", fallback))
            }
        }

        when (raw) {
            is JSONObject -> {
                val seasons = raw.keys()
                while (seasons.hasNext()) {
                    val seasonKey = seasons.next()
                    val list = raw.optJSONArray(seasonKey) ?: continue
                    addList(list, seasonKey.toIntOrNull())
                }
            }
            is JSONArray -> {
                for (i in 0 until raw.length()) {
                    when (val item = raw.opt(i)) {
                        // An array of season arrays: the position is the season number minus one.
                        is JSONArray -> addList(item, null, i + 1)
                        is JSONObject -> add(item, item.optInt("season", 0))
                    }
                }
            }
            else -> return emptyList()
        }
        return keyed
            .sortedWith(compareBy<Triple<Int, Int, VodItem>>({ it.first }, { it.second }))
            .map { it.third }
    }

    /**
     * Replay address for a programme that already aired.
     * The working catch-up shape is probed once and remembered. A miss is remembered
     * for five minutes for that channel, then the plain timeshift address is used.
     */
    fun resolveTimeshiftUrl(streamId: String, startMs: Long, stopMs: Long): String {
        val info = portalForStreams()
        synchronized(portalLock) {
            catchupVariant?.let { return buildCatchup(it, info.timezone, streamId, startMs, stopMs) }
            val missAt = catchupMisses[streamId]
            if (missAt != null && System.currentTimeMillis() - missAt < 300_000L) {
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
                catchupMisses[streamId] = System.currentTimeMillis()
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
                title = decodeB64(o.str("title") ?: ""),
                start = o.optLong("start_timestamp") * 1000L,
                end = o.optLong("stop_timestamp") * 1000L,
                desc = decodeB64(o.str("description") ?: "").trim()
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
        // When the panel did not answer, use a guess for this call only and ask again next time.
        val info = readPortal() ?: return PortalInfo(PortalStatus.ACTIVE, emptyList(), null)
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
            resolvePortalStatus(jsonValue(it, "auth"), it.str("status"), jsonValue(it, "exp_date"))
        }
        if (userInfo == null || status == null || status == PortalStatus.UNAVAILABLE) {
            null
        } else {
            val server = json.optJSONObject("server_info")
            PortalInfo(
                status = status,
                formats = formatsOf(userInfo),
                timezone = resolveServerTimezone(
                    server?.str("timezone"),
                    server?.str("time_now"),
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
            is JSONArray -> (0 until raw.length()).mapNotNull { idx ->
                if (raw.isNull(idx)) null else raw.optString(idx).trim().takeIf { s -> s.isNotEmpty() && !s.equals("null", true) }
            }
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

    private fun decodeB64(s: String): String = decodeMaybeBase64(s)

    /** When the subscription ends, or 0 when the panel did not say. */
    fun subscriptionExpiryMs(): Long = readPortal()?.expiresAt ?: 0L

    private data class PortalInfo(
        val status: PortalStatus,
        val formats: List<String>,
        val timezone: String?,
        val expiresAt: Long = 0L
    )
}
