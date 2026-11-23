package com.dallas.cabletv.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A Stalker / Ministra portal, the kind a MAG set-top box signs into with a MAC address.
 * Channels come from the ITV list. Playback links are created when a channel is tuned,
 * because the portal's addresses expire.
 */
class StalkerClient(portal: String, mac: String) {
    private val apiUrl = stalkerApiUrl(portal)
    private val macAddress = normalizeStalkerMac(mac)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    // One handshake at a time. Callers that wait on the lock reuse the token it produced.
    private val lock = Any()
    @Volatile private var token: String = ""

    /** Raised for HTTP 401 and 403, so the caller can sign in again once. */
    private class AuthException(code: Int) : IllegalStateException("Portal returned HTTP $code")

    /** Confirms the portal accepts this MAC. */
    fun connect() {
        handshake(force = true)
    }

    /** Now and next from the portal's short listing. */
    fun shortEpg(channelId: String): NowNext {
        val id = channelId.removePrefix("stk:")
        val js = getChecked("type=itv&action=get_short_epg&ch_id=$id") { it.dataArray() != null }
        val arr = js.dataArray() ?: return NowNext(null, null)
        val programs = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val start = epgStart(o) ?: return@mapNotNull null
            val duration = o.optLong("duration", 0L).takeIf { it > 0 } ?: 30 * 60L
            Program(
                title = o.str("name") ?: "Programme",
                start = start,
                end = start + duration * 1000,
                desc = o.str("descr") ?: ""
            )
        }.sortedBy { it.start }
        val now = System.currentTimeMillis()
        val current = programs.lastOrNull { it.start <= now && now < it.end }
        val next = programs.firstOrNull { it.start > (current?.end ?: now) }
        return NowNext(current, next)
    }

    fun channels(): List<LiveChannel> {
        handshake(force = true)
        val genres = genres()
        val arr = get("type=itv&action=get_all_channels").dataArray()
            ?: get("type=itv&action=get_ordered_list&genre=*&p=1").dataArray()
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val cmd = o.str("cmd") ?: ""
            LiveChannel(
                id = "stk:$id",
                number = o.str("number")?.toIntOrNull() ?: (i + 1),
                name = o.str("name") ?: "Channel ${i + 1}",
                logo = o.str("logo"),
                group = genres[o.str("tv_genre_id") ?: ""] ?: "Stalker",
                streamUrl = cmd,
                epgId = o.str("xmltv_id") ?: id,
                origin = "stalker"
            )
        }
    }

    /** The playable address for a channel cmd. The portal prefixes it with ffmpeg or ffrt. */
    fun resolveLink(cmd: String): String {
        val encoded = java.net.URLEncoder.encode(cmd, "UTF-8")
        val js = getChecked("type=itv&action=create_link&cmd=$encoded&series=&forced_storage=undefined&disable_ad=0&download=0") {
            (it as? JSONObject)?.str("cmd") != null
        }
        val raw = (js as? JSONObject)?.str("cmd") ?: error("The portal did not return a stream")
        return raw.replace(Regex("^(ffmpeg|ffrt)\\s+"), "").trim()
    }

    /** Signs in. A caller that finds a token already made by another thread reuses it unless [force]. */
    private fun handshake(force: Boolean = false): String {
        synchronized(lock) {
            val current = token
            if (!force && current.isNotEmpty()) return current
            token = ""
            val js = request("type=stb&action=handshake&token=&JsHttpRequest=1-xml", "")
            val fresh = (js as? JSONObject)?.str("token") ?: error("The portal did not accept the MAC address")
            token = fresh
            return fresh
        }
    }

    /** Signs in again after [failed] stopped working, unless another thread already did. */
    private fun refreshToken(failed: String) {
        synchronized(lock) {
            if (token.isNotEmpty() && token != failed) return
            handshake(force = true)
        }
    }

    /** Runs a call. When the portal rejects the token or the answer lacks what [valid] wants, signs in again and retries once. */
    private fun getChecked(query: String, valid: (Any) -> Boolean): Any {
        val used = handshake()
        try {
            val js = request(query, used)
            if (valid(js)) return js
        } catch (e: AuthException) {
            // fall through to a fresh sign-in
        }
        refreshToken(used)
        return request(query, handshake())
    }

    private fun genres(): Map<String, String> = try {
        val arr = get("type=itv&action=get_genres").dataArray() ?: return emptyMap()
        buildMap {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.str("id") ?: continue
                put(id, o.str("title") ?: o.str("name") ?: "")
            }
        }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun get(query: String): Any = request(query, handshake())

    /** One HTTP call with the given token. Returns the "js" part, as an object or an array. */
    private fun request(query: String, tok: String): Any {
        val joiner = if (apiUrl.contains("?")) "&" else "?"
        val req = Request.Builder()
            .url("$apiUrl$joiner$query")
            .header("User-Agent", STALKER_AGENT)
            .header("X-User-Agent", "Model: MAG254; Link: WiFi")
            .header("Cookie", cookie(tok))
            .apply { if (tok.isNotEmpty()) header("Authorization", "Bearer $tok") }
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) throw AuthException(resp.code)
            if (!resp.isSuccessful) error("Portal returned HTTP ${resp.code}")
            val body = try {
                JSONObject(resp.body?.string().orEmpty())
            } catch (e: org.json.JSONException) {
                error(friendlyError(e))
            }
            return when (val js = body.opt("js")) {
                is JSONObject -> js
                is JSONArray -> js
                else -> body
            }
        }
    }

    /** The list under "data" when js is an object, or the list itself when js is an array. */
    private fun Any.dataArray(): JSONArray? = when (this) {
        is JSONArray -> this
        is JSONObject -> optJSONArray("data")
        else -> null
    }

    private fun cookie(tok: String): String {
        val tokenPart = if (tok.isEmpty()) "" else "; token=$tok"
        return "mac=$macAddress; stb_lang=en; timezone=Europe/London$tokenPart"
    }

    private fun epgStart(o: JSONObject): Long? {
        val unix = o.optLong("start_timestamp", 0L)
        if (unix > 1_000_000_000L) return unix * 1000
        val text = o.str("time") ?: o.str("start") ?: return null
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

/**
 * The API endpoint for a pasted portal address. A link ending in .php is used as is.
 * A link ending in /c (the web player) is reduced to its base: under stalker_portal the API
 * is server/load.php, anywhere else it is portal.php. Other addresses get portal.php added.
 */
fun stalkerApiUrl(portal: String): String {
    val trimmed = portal.trim()
    val noQuery = trimmed.substringBefore('?')
    if (noQuery.trimEnd('/').endsWith(".php", ignoreCase = true)) return trimmed.replaceFirst(Regex("/+$"), "")
    var base = trimmed.trimEnd('/')
    if (base.endsWith("/c", ignoreCase = true)) {
        base = base.dropLast(2).trimEnd('/')
        return if (base.contains("/stalker_portal", ignoreCase = true)) "$base/server/load.php" else "$base/portal.php"
    }
    return "$base/portal.php"
}
