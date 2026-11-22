package com.dallas.cabletv.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Holds the full program guide. It is fetched from the user's XMLTV URL when one is set,
 * otherwise from the Xtream XMLTV feed. It is cached on disk for [MAX_AGE_MS], parsed into
 * memory, and expires there after the same time. Programs that ended more than an hour ago,
 * or start more than [LOOKAHEAD_MS] from now, are dropped to keep memory low.
 */
class EpgRepository(private val context: Context) {

    private val cacheFile: File get() = File(context.cacheDir, "epg.xml")
    private val tmpFile: File get() = File(context.cacheDir, "epg.xml.tmp")

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Only one download or parse runs at a time, so cache writes never overlap. */
    private val lock = Mutex()

    @Volatile
    private var guide: Map<String, List<Program>> = emptyMap()

    @Volatile
    private var aliases: Map<String, String> = emptyMap()

    @Volatile
    private var loadedAt: Long = 0L

    @Volatile
    private var loadedKey: String? = null

    /** Channel-id keyed guide. Empty until [refresh] has run. */
    fun guide(): Map<String, List<Program>> = guide

    /**
     * Loads the guide for these settings. Uses the disk cache when it is fresh and the
     * source has not changed, unless [force] is set. Returns the number of channels with listings.
     */
    suspend fun refresh(settings: Settings, force: Boolean = false): Result<Int> = lock.withLock {
        runCatching {
            val key = settings.epgKey
            val now = System.currentTimeMillis()
            val inMemoryFresh = loadedKey == key && guide.isNotEmpty() && now - loadedAt < MAX_AGE_MS
            if (!force && inMemoryFresh) return@runCatching guide.size

            val diskFresh = now - cacheStamp() < MAX_AGE_MS && cachedKey() == key && cacheFile.exists()
            if (force || !diskFresh) {
                downloadToCache(settings)
                markCache(key)
            }

            val parsed = cacheFile.inputStream().use {
                XmlTvParser.parseFull(it, keepAfterMs = now - KEEP_PAST_MS, keepUntilMs = now + LOOKAHEAD_MS)
            }
            guide = parsed.programs
            aliases = parsed.displayNames
            loadedKey = key
            loadedAt = System.currentTimeMillis()
            parsed.programs.size
        }
    }

    /** Programs for one channel, matched by EPG id, then channel id, then a display name. */
    fun programsFor(channel: LiveChannel): List<Program> {
        val g = guide
        if (g.isEmpty()) return emptyList()
        channel.epgId?.let { g[it] }?.let { return it }
        g[channel.id]?.let { return it }
        aliases[channel.name.lowercase()]?.let { id -> g[id]?.let { return it } }
        return emptyList()
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

    /** Downloads to a temp file, then renames it, so an interrupted download never replaces a good cache. */
    private fun downloadToCache(settings: Settings) {
        val url = settings.epgUrl.trim()
        if (url.isNotEmpty()) {
            val req = Request.Builder().url(url).header("User-Agent", "CableTV/1.0").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Guide returned HTTP ${resp.code}")
                val body = resp.body ?: error("Guide was empty")
                tmpFile.outputStream().use { body.byteStream().copyTo(it) }
            }
        } else if (settings.hasXtream) {
            XtreamClient(settings.xtreamServer, settings.xtreamUser, settings.xtreamPass)
                .xmltvStream()
                .use { input -> tmpFile.outputStream().use { input.copyTo(it) } }
        } else {
            error("No guide source")
        }
        if (!tmpFile.renameTo(cacheFile)) {
            cacheFile.delete()
            check(tmpFile.renameTo(cacheFile)) { "Could not save the guide cache" }
        }
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
        /** How far ahead the guide keeps listings. The grid can page further, but shows nothing beyond this. */
        const val LOOKAHEAD_MS = 2L * 24 * 60 * 60 * 1000
    }
}
