package com.dallas.cabletv.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Chooses where live channels come from. Xtream Codes is preferred because it
 * carries categories and guide data. A plain M3U URL is the fallback.
 */
object ChannelSource {
    fun load(s: Settings): List<LiveChannel> =
        if (s.hasXtream) {
            XtreamClient(s.xtreamServer, s.xtreamUser, s.xtreamPass).liveChannels()
        } else {
            M3uParser.parse(fetchText(s.m3uUrl))
        }
}

private val playlistHttp = OkHttpClient.Builder()
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

fun fetchText(url: String): String {
    val req = Request.Builder().url(url).header("User-Agent", "CableTV/1.0").build()
    playlistHttp.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) error("Playlist returned HTTP ${resp.code}")
        return resp.body?.string() ?: error("Playlist was empty")
    }
}
