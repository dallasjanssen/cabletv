package com.dallas.cabletv.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Jellyfin client: sign in, browse the library, search, resume playback, and
 * report progress back to the server so Continue Watching stays accurate.
 */
class JellyfinClient(server: String, private val token: String, private val userId: String) {

    private val base = server.trim().trimEnd('/')
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val CLIENT_AUTH =
            "MediaBrowser Client=\"CableTV\", Device=\"Android\", DeviceId=\"cabletv-android\", Version=\"1.0\""

        private const val FIELDS = "Overview,Genres,RunTimeTicks,UserData"

        /** Returns Pair(accessToken, userId). */
        fun login(server: String, username: String, password: String): Pair<String, String> {
            val body = JSONObject()
                .put("Username", username)
                .put("Pw", password)
                .toString()
                .toRequestBody("application/json".toMediaType())

            val req = Request.Builder()
                .url("${server.trim().trimEnd('/')}/Users/AuthenticateByName")
                .header("Authorization", CLIENT_AUTH)
                .post(body)
                .build()

            OkHttpClient().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Jellyfin login failed (HTTP ${resp.code})")
                val json = JSONObject(resp.body!!.string())
                return json.getString("AccessToken") to json.getJSONObject("User").getString("Id")
            }
        }

        private fun q(value: String): String = URLEncoder.encode(value, "UTF-8")
    }

    private fun authHeader(): String = "$CLIENT_AUTH, Token=\"$token\""

    private fun getJson(path: String): JSONObject {
        val req = Request.Builder().url("$base$path").header("Authorization", authHeader()).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Jellyfin returned HTTP ${resp.code}")
            return JSONObject(resp.body!!.string())
        }
    }

    private fun postJson(path: String, body: JSONObject) {
        val req = Request.Builder()
            .url("$base$path")
            .header("Authorization", authHeader())
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Jellyfin returned HTTP ${resp.code}")
        }
    }

    private fun mapItems(arr: JSONArray): List<VodItem> =
        (0 until arr.length()).map { i -> mapItem(arr.getJSONObject(i)) }

    private fun mapItem(o: JSONObject): VodItem {
        val id = o.getString("Id")
        val userData = o.optJSONObject("UserData")
        val genresArr = o.optJSONArray("Genres")
        val genres = if (genresArr == null) emptyList()
        else (0 until genresArr.length()).map { genresArr.getString(it) }
        return VodItem(
            id = id,
            name = o.optString("Name"),
            year = o.optInt("ProductionYear", 0).takeIf { it > 0 },
            overview = o.optString("Overview").takeIf { it.isNotBlank() },
            posterUrl = "$base/Items/$id/Images/Primary?maxHeight=480&api_key=$token",
            streamUrl = directStreamUrl(id),
            type = o.optString("Type"),
            seriesId = o.optString("SeriesId").takeIf { it.isNotBlank() },
            seasonNumber = o.optInt("ParentIndexNumber", -1).takeIf { it >= 0 },
            episodeNumber = o.optInt("IndexNumber", -1).takeIf { it >= 0 },
            resumeTicks = userData?.optLong("PlaybackPositionTicks", 0L) ?: 0L,
            runtimeTicks = o.optLong("RunTimeTicks", 0L),
            mediaSourceId = o.optJSONArray("MediaSources")?.optJSONObject(0)?.optString("Id")?.takeIf { it.isNotBlank() },
            genres = genres
        )
    }

    /** Direct play. Fastest, but needs the TV to decode the file's codecs. */
    fun directStreamUrl(itemId: String): String =
        "$base/Videos/$itemId/stream?static=true&api_key=$token"

    /** Server-side transcode to H.264 and AAC HLS. Used when direct play fails. */
    fun transcodeStreamUrl(itemId: String, mediaSourceId: String?): String {
        val source = mediaSourceId?.let { "&MediaSourceId=${q(it)}" }.orEmpty()
        return "$base/Videos/$itemId/master.m3u8?VideoCodec=h264&AudioCodec=aac" +
            "&MaxStreamingBitrate=20000000$source&api_key=$token"
    }

    fun recentlyAdded(types: String = "Movie,Series", limit: Int = 60): List<VodItem> =
        mapItems(
            getJson(
                "/Users/$userId/Items?IncludeItemTypes=$types&Recursive=true" +
                    "&SortBy=DateCreated&SortOrder=Descending&Limit=$limit&Fields=$FIELDS"
            ).getJSONArray("Items")
        )

    fun continueWatching(limit: Int = 20): List<VodItem> =
        mapItems(
            getJson("/Users/$userId/Items/Resume?Limit=$limit&MediaTypes=Video&Fields=$FIELDS")
                .getJSONArray("Items")
        )

    fun nextUp(limit: Int = 20): List<VodItem> =
        mapItems(
            getJson("/Shows/NextUp?UserId=$userId&Limit=$limit&Fields=$FIELDS").getJSONArray("Items")
        )

    /** One page of a library tab, with optional search text and genre filter. */
    fun library(
        tab: VodTab,
        search: String = "",
        genre: String = "",
        start: Int = 0,
        limit: Int = 60
    ): List<VodItem> {
        val sb = StringBuilder(
            "/Users/$userId/Items?IncludeItemTypes=${tab.types}&Recursive=true" +
                "&SortBy=SortName&SortOrder=Ascending&StartIndex=$start&Limit=$limit&Fields=$FIELDS"
        )
        if (search.isNotBlank()) sb.append("&SearchTerm=").append(q(search.trim()))
        if (genre.isNotBlank()) sb.append("&Genres=").append(q(genre))
        return mapItems(getJson(sb.toString()).getJSONArray("Items"))
    }

    fun genres(tab: VodTab): List<String> {
        val arr = getJson("/Genres?IncludeItemTypes=${tab.types}&Recursive=true&UserId=$userId")
            .getJSONArray("Items")
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("Name") }.sorted()
    }

    fun seasons(seriesId: String): List<VodItem> =
        mapItems(
            getJson("/Shows/$seriesId/Seasons?UserId=$userId&Fields=$FIELDS").getJSONArray("Items")
        )

    fun episodes(seriesId: String, seasonId: String? = null): List<VodItem> {
        val season = seasonId?.let { "&SeasonId=${q(it)}" }.orEmpty()
        return mapItems(
            getJson("/Shows/$seriesId/Episodes?UserId=$userId$season&Fields=$FIELDS").getJSONArray("Items")
        )
    }

    /** Reports that playback has started. Use the same playSessionId for later updates. */
    fun reportStart(item: VodItem, positionMs: Long, playSessionId: String, transcoded: Boolean) {
        postJson("/Sessions/Playing", playbackBody(item, positionMs, playSessionId, transcoded))
    }

    fun reportProgress(item: VodItem, positionMs: Long, playSessionId: String, transcoded: Boolean, paused: Boolean) {
        postJson(
            "/Sessions/Playing/Progress",
            playbackBody(item, positionMs, playSessionId, transcoded).put("IsPaused", paused)
        )
    }

    fun reportStopped(item: VodItem, positionMs: Long, playSessionId: String, transcoded: Boolean) {
        postJson("/Sessions/Playing/Stopped", playbackBody(item, positionMs, playSessionId, transcoded))
    }

    private fun playbackBody(item: VodItem, positionMs: Long, playSessionId: String, transcoded: Boolean): JSONObject =
        JSONObject()
            .put("ItemId", item.id)
            .put("MediaSourceId", item.mediaSourceId ?: JSONObject.NULL)
            .put("PlaySessionId", playSessionId)
            .put("PositionTicks", positionMs * TICKS_PER_MS)
            .put("CanSeek", true)
            .put("PlayMethod", if (transcoded) "Transcode" else "DirectPlay")

    fun newPlaySessionId(): String = UUID.randomUUID().toString()
}
