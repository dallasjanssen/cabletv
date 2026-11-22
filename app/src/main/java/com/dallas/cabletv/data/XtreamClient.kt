package com.dallas.cabletv.data

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Talks to an Xtream Codes style IPTV panel using its JSON API.
 * Channels and categories come from player_api.php. The full guide comes from
 * xmltv.php, and the short per-channel guide is a fallback.
 */
class XtreamClient(server: String, private val user: String, private val pass: String) {

    private val base = server.trim().trimEnd('/')

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Path segments need %20 for spaces, not the "+" that form encoding produces. */
    private fun encPath(value: String): String = enc(value).replace("+", "%20")

    private fun creds(): String = "username=${enc(user)}&password=${enc(pass)}"

    private fun open(url: String): Response {
        val req = Request.Builder().url(url).header("User-Agent", "CableTV/1.0").build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) {
            resp.close()
            error("HTTP ${resp.code}")
        }
        return resp
    }

    private fun get(url: String): String =
        open(url).use { resp -> resp.body?.string() ?: error("Empty response from provider") }

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
        val cats = JSONArray(api("get_live_categories"))
        for (i in 0 until cats.length()) {
            val c = cats.getJSONObject(i)
            categories[c.optString("category_id")] = c.optString("category_name")
        }

        val arr = JSONArray(api("get_live_streams"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val id = o.optString("stream_id")
            LiveChannel(
                id = id,
                number = o.optInt("num", i + 1),
                name = o.optString("name"),
                logo = o.optString("stream_icon").takeIf { it.isNotBlank() },
                group = categories[o.optString("category_id")] ?: "Other",
                streamUrl = "$base/live/${encPath(user)}/${encPath(pass)}/$id.ts",
                epgId = o.optString("epg_channel_id").takeIf { it.isNotBlank() }
            )
        }.sortedBy { it.number }
    }

    /** Streams the full XMLTV guide. The caller must close the stream. */
    fun xmltvStream(): InputStream {
        val resp = open("$base/xmltv.php?${creds()}")
        return resp.body?.byteStream() ?: error("Empty guide from provider")
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
                description = decodeB64(o.optString("description")).takeIf { it.isNotBlank() }
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
