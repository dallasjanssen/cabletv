package com.dallas.cabletv.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A Stalker / Ministra portal, the kind a MAG set-top box signs into with a MAC address.
 * Channels come from the ITV list. Playback links are created when a channel is tuned,
 * because the portal's addresses expire.
 */
class StalkerClient(portal: String, mac: String) {
    private val base = portal.trim().trimEnd('/')
    private val macAddress = normalizeStalkerMac(mac)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()
    private var token: String = ""

    /** Confirms the portal accepts this MAC. */
    fun connect() {
        handshake()
    }

    /** Now and next from the portal's short listing. */
    fun shortEpg(channelId: String): NowNext {
        val id = channelId.removePrefix("stk:")
        val arr = get("type=itv&action=get_short_epg&ch_id=$id").optJSONArray("data") ?: return NowNext(null, null)
        val programs = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val start = epgStart(o) ?: return@mapNotNull null
            val duration = o.optLong("duration", 0L).takeIf { it > 0 } ?: 30 * 60L
            Program(
                title = o.optString("name").ifBlank { "Programme" },
                start = start,
                end = start + duration * 1000,
                desc = o.optString("descr")
            )
        }.sortedBy { it.start }
        val now = System.currentTimeMillis()
        val current = programs.lastOrNull { it.start <= now && now < it.end }
        val next = programs.firstOrNull { it.start > (current?.end ?: now) }
        return NowNext(current, next)
    }

    fun channels(): List<LiveChannel> {
        handshake()
        val genres = genres()
        val arr = get("type=itv&action=get_all_channels").optJSONArray("data")
            ?: get("type=itv&action=get_ordered_list&genre=*&p=1").optJSONArray("data")
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            val cmd = o.optString("cmd")
            LiveChannel(
                id = "stk:$id",
                number = o.optString("number").toIntOrNull() ?: (i + 1),
                name = o.optString("name").ifBlank { "Channel ${i + 1}" },
                logo = o.optString("logo").takeIf { it.isNotBlank() },
                group = genres[o.optString("tv_genre_id")] ?: "Stalker",
                streamUrl = cmd,
                epgId = o.optString("xmltv_id").takeIf { it.isNotBlank() } ?: id,
                origin = "stalker"
            )
        }
    }

    /** The playable address for a channel cmd. The portal prefixes it with ffmpeg or ffrt. */
    fun resolveLink(cmd: String): String {
        if (token.isEmpty()) handshake()
        val encoded = java.net.URLEncoder.encode(cmd, "UTF-8")
        val js = get("type=itv&action=create_link&cmd=$encoded&series=&forced_storage=undefined&disable_ad=0&download=0")
        val raw = js.optString("cmd").ifBlank { error("The portal did not return a stream") }
        return raw.replace(Regex("^(ffmpeg|ffrt)\\s+"), "").trim()
    }

    private fun handshake() {
        token = ""
        val js = get("type=stb&action=handshake&token=&JsHttpRequest=1-xml")
        token = js.optString("token").ifBlank { error("The portal did not accept the MAC address") }
    }

    private fun genres(): Map<String, String> = try {
        val arr = get("type=itv&action=get_genres").optJSONArray("data") ?: return emptyMap()
        buildMap {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                put(o.optString("id"), o.optString("title").ifBlank { o.optString("name") })
            }
        }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun get(query: String): JSONObject {
        if (token.isEmpty() && !query.contains("action=handshake")) handshake()
        val root = if (base.endsWith(".php")) base else "$base/portal.php"
        val joiner = if (root.contains("?")) "&" else "?"
        val req = Request.Builder()
            .url("$root$joiner$query")
            .header("User-Agent", STALKER_AGENT)
            .header("X-User-Agent", "Model: MAG254; Link: WiFi")
            .header("Cookie", cookie())
            .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Portal returned HTTP ${resp.code}")
            val body = JSONObject(resp.body?.string().orEmpty())
            return body.optJSONObject("js") ?: body
        }
    }

    private fun cookie(): String {
        val tokenPart = if (token.isEmpty()) "" else "; token=$token"
        return "mac=$macAddress; stb_lang=en; timezone=Europe/London$tokenPart"
    }

    private fun epgStart(o: JSONObject): Long? {
        val unix = o.optLong("start_timestamp", 0L)
        if (unix > 1_000_000_000L) return unix * 1000
        val text = o.optString("time").ifBlank { o.optString("start") }
        if (text.isBlank()) return null
        return runCatching {
            java.time.LocalDateTime.parse(text.replace(" ", "T"))
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    companion object {
        private const val STALKER_AGENT =
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
    }
}

/** Accepts a MAC with or without colons or dashes, and writes it in the form portals expect. */
fun normalizeStalkerMac(value: String): String {
    val hex = value.trim().uppercase().replace(Regex("[^0-9A-F]"), "")
    require(hex.length == 12) { "The MAC address needs 12 hex digits" }
    return hex.chunked(2).joinToString(":")
}
