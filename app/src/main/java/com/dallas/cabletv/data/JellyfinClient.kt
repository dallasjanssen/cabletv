package com.dallas.cabletv.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Jellyfin client: sign in, browse and search the library, build playback URLs, and
 * report playback progress so the server keeps watched status and resume points in step.
 * Playback uses direct streams, so the TV needs to support the file's codecs.
 */
class JellyfinClient(server: String, private val token: String, private val userId: String) {

    private val base = server.trim().trimEnd('/')
    private val http = OkHttpClient()

    companion object {
        private const val CLIENT_AUTH =
            "MediaBrowser Client=\"CableTV\", Device=\"Android\", DeviceId=\"cabletv-android\", Version=\"1.0\""

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
    }

    private fun authHeader(): String = "$CLIENT_AUTH, Token=\"$token\""

    private fun getJson(path: String): JSONObject {
        val req = Request.Builder()
            .url("$base$path")
            .header("Authorization", authHeader())
            .build()
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
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val id = o.getString("Id")
            val type = o.optString("Type")
            val userData = o.optJSONObject("UserData")
            // Episodes use their show's backdrop. Movies and shows use their own.
            val backdropId = when {
                (o.optJSONArray("BackdropImageTags")?.length() ?: 0) > 0 -> id
                o.optString("ParentBackdropItemId").isNotBlank() -> o.optString("ParentBackdropItemId")
                else -> null
            }
            VodItem(
                id = id,
                name = o.optString("Name"),
                year = o.optInt("ProductionYear", 0).takeIf { it > 0 },
                overview = o.optString("Overview").takeIf { it.isNotBlank() },
                posterUrl = "$base/Items/$id/Images/Primary?maxHeight=480&api_key=$token",
                streamUrl = "$base/Videos/$id/stream?static=true&api_key=$token",
                type = type,
                played = userData?.optBoolean("Played") ?: false,
                backdropUrl = backdropId?.let { "$base/Items/$it/Images/Backdrop?maxWidth=1280&api_key=$token" },
                season = if (type == "Episode") o.optInt("ParentIndexNumber", 0).takeIf { it > 0 } else null,
                episode = if (type == "Episode") o.optInt("IndexNumber", 0).takeIf { it > 0 } else null,
                seriesName = o.optString("SeriesName").takeIf { it.isNotBlank() },
                runtimeMinutes = (o.optLong("RunTimeTicks", 0L) / 600_000_000L).toInt().takeIf { it > 0 },
                progress = userData?.optDouble("PlayedPercentage", 0.0)?.takeIf { it > 0.0 }?.let { (it / 100.0).toFloat() },
                category = o.optJSONArray("Genres")?.optString(0)?.takeIf { it.isNotBlank() }.orEmpty()
            )
        }

    fun recentlyAdded(limit: Int = 60): List<VodItem> =
        mapItems(
            getJson(
                "/Users/$userId/Items?IncludeItemTypes=Movie,Series&Recursive=true" +
                    "&SortBy=DateCreated&SortOrder=Descending&Limit=$limit&Fields=Overview,Genres"
            ).getJSONArray("Items")
        )

    fun continueWatching(limit: Int = 20): List<VodItem> =
        mapItems(
            getJson("/Users/$userId/Items/Resume?Limit=$limit&MediaTypes=Video&Fields=Overview,Genres")
                .getJSONArray("Items")
        )

    /** Movies and series whose names match the search text. */
    fun search(term: String, limit: Int = 40): List<VodItem> {
        val encoded = java.net.URLEncoder.encode(term, "UTF-8")
        return mapItems(
            getJson(
                "/Users/$userId/Items?searchTerm=$encoded&IncludeItemTypes=Movie,Series" +
                    "&Recursive=true&Limit=$limit&Fields=Overview,Genres"
            ).getJSONArray("Items")
        )
    }

    /** A browsable slice of the library, grouped later by [VodItem.category]. */
    fun browse(type: String, limit: Int = 400): List<VodItem> =
        mapItems(
            getJson(
                "/Users/$userId/Items?IncludeItemTypes=$type&Recursive=true" +
                    "&SortBy=SortName&SortOrder=Ascending&Limit=$limit&Fields=Overview,Genres"
            ).getJSONArray("Items")
        )

    fun episodes(seriesId: String): List<VodItem> =
        mapItems(
            getJson("/Shows/$seriesId/Episodes?UserId=$userId&Fields=Overview").getJSONArray("Items")
        )

    /** Tells the server playback started. Positions are in milliseconds, converted to Jellyfin ticks. */
    fun reportStart(itemId: String, positionMs: Long) = report("/Sessions/Playing", itemId, positionMs, false)

    /** Tells the server where playback is. Called every few seconds while watching. */
    fun reportProgress(itemId: String, positionMs: Long, paused: Boolean) =
        report("/Sessions/Playing/Progress", itemId, positionMs, paused)

    /** Tells the server playback stopped. The server uses the last position to set watched status. */
    fun reportStopped(itemId: String, positionMs: Long) =
        report("/Sessions/Playing/Stopped", itemId, positionMs, false)

    private fun report(path: String, itemId: String, positionMs: Long, paused: Boolean) {
        val body = JSONObject()
            .put("ItemId", itemId)
            .put("MediaSourceId", itemId)
            .put("PositionTicks", positionMs * 10_000L)
            .put("IsPaused", paused)
            .put("CanSeek", true)
            .put("PlayMethod", "DirectStream")
        postJson(path, body)
    }
}
