package com.dallas.cabletv.data

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Holds the full program guide. It is fetched from the Xtream XMLTV feed or a
 * user supplied XMLTV URL, cached on disk for [MAX_AGE_MS], and parsed once
 * into memory. Programs that ended more than an hour ago are dropped.
 */
class EpgRepository(private val context: Context) {

    private val cacheFile: File get() = File(context.cacheDir, "epg.xml")

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var guide: Map<String, List<Program>> = emptyMap()

    @Volatile
    private var loadedAt: Long = 0L

    @Volatile
    private var loadedKey: String? = null

    /** Channel-id keyed guide. Empty until [refresh] has run. */
    fun guide(): Map<String, List<Program>> = guide

    /**
     * Loads the guide for these settings. Uses the disk cache when it is fresh
     * and the source has not changed, unless [force] is set.
     */
    fun refresh(settings: Settings, force: Boolean = false): Result<Int> = runCatching {
        val key = settings.epgKey
        val fresh = System.currentTimeMillis() - cacheStamp() < MAX_AGE_MS
        if (!force && loadedKey == key && guide.isNotEmpty()) return@runCatching guide.size

        val keepAfter = System.currentTimeMillis() - KEEP_PAST_MS
        val parsed = if (!force && fresh && cacheFile.exists() && cachedKey() == key) {
            cacheFile.inputStream().use { XmlTvParser.parse(it, keepAfter) }
        } else {
            downloadAndCache(settings, key)
            cacheFile.inputStream().use { XmlTvParser.parse(it, keepAfter) }
        }
        guide = parsed
        loadedKey = key
        loadedAt = System.currentTimeMillis()
        parsed.size
    }

    /** Programs for one channel, matched by EPG id first, then by channel id or name. */
    fun programsFor(channel: LiveChannel): List<Program> {
        val g = guide
        if (g.isEmpty()) return emptyList()
        channel.epgId?.let { g[it] }?.let { return it }
        g[channel.id]?.let { return it }
        return g.entries.firstOrNull { it.key.equals(channel.name, ignoreCase = true) }?.value.orEmpty()
    }

    /** Programs that overlap the window [fromMs, toMs). */
    fun window(channel: LiveChannel, fromMs: Long, toMs: Long): List<Program> =
        programsFor(channel).filter { it.end > fromMs && it.start < toMs }

    fun nowNext(channel: LiveChannel, nowMs: Long = System.currentTimeMillis()): NowNext {
        val list = programsFor(channel)
        return NowNext(
            now = list.firstOrNull { it.contains(nowMs) },
            next = list.firstOrNull { it.start > nowMs }
        )
    }

    fun hasData(): Boolean = guide.isNotEmpty()

    private fun downloadAndCache(settings: Settings, key: String) {
        val url = settings.epgUrl.trim()
        if (settings.hasXtream) {
            // The Xtream feed is streamed straight to disk to keep memory low.
            XtreamClient(settings.xtreamServer, settings.xtreamUser, settings.xtreamPass)
                .xmltvStream()
                .use { input -> cacheFile.outputStream().use { input.copyTo(it) } }
        } else if (url.isNotEmpty()) {
            val req = Request.Builder().url(url).header("User-Agent", "CableTV/1.0").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Guide returned HTTP ${resp.code}")
                val body = resp.body ?: error("Guide was empty")
                cacheFile.outputStream().use { body.byteStream().copyTo(it) }
            }
        } else {
            error("No guide source")
        }
        markCache(key)
    }

    private fun prefs() = context.getSharedPreferences("cabletv_epg", Context.MODE_PRIVATE)

    private fun cacheStamp(): Long = prefs().getLong("stamp", 0L)

    private fun cachedKey(): String? = prefs().getString("key", null)

    private fun markCache(key: String) {
        prefs().edit()
            .putLong("stamp", System.currentTimeMillis())
            .putString("key", key)
            .apply()
    }

    companion object {
        const val MAX_AGE_MS = 6L * 60 * 60 * 1000
        const val KEEP_PAST_MS = 60L * 60 * 1000
    }
}
