package com.dallas.cabletv.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal Jellyfin client: sign in, list libraries, and build playback URLs.
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

    private fun getJson(path: String): JSONObject {
        val req = Request.Builder()
            .url("$base$path")
            .header("Authorization", "$CLIENT_AUTH, Token=\"$token\"")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Jellyfin returned HTTP ${resp.code}")
            return JSONObject(resp.body!!.string())
        }
    }

    private fun mapItems(arr: JSONArray): List<VodItem> =
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val id = o.getString("Id")
            VodItem(
                id = id,
                name = o.optString("Name"),
                year = o.optInt("ProductionYear", 0).takeIf { it > 0 },
                overview = o.optString("Overview").takeIf { it.isNotBlank() },
                posterUrl = "$base/Items/$id/Images/Primary?maxHeight=480&api_key=$token",
                streamUrl = "$base/Videos/$id/stream?static=true&api_key=$token",
                type = o.optString("Type")
            )
        }

    fun recentlyAdded(limit: Int = 60): List<VodItem> =
        mapItems(
            getJson(
                "/Users/$userId/Items?IncludeItemTypes=Movie,Series&Recursive=true" +
                    "&SortBy=DateCreated&SortOrder=Descending&Limit=$limit&Fields=Overview"
            ).getJSONArray("Items")
        )

    fun continueWatching(limit: Int = 20): List<VodItem> =
        mapItems(
            getJson("/Users/$userId/Items/Resume?Limit=$limit&MediaTypes=Video&Fields=Overview")
                .getJSONArray("Items")
        )

    fun episodes(seriesId: String): List<VodItem> =
        mapItems(
            getJson("/Shows/$seriesId/Episodes?UserId=$userId&Fields=Overview").getJSONArray("Items")
        )
}
