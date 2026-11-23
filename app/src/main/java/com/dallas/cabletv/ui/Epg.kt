package com.dallas.cabletv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import com.dallas.cabletv.data.EpgCache
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.NowNext
import com.dallas.cabletv.data.Program
import com.dallas.cabletv.data.StalkerClient
import com.dallas.cabletv.data.XtreamClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Guide data for every screen. The full schedule comes from [EpgCache]. Channels it has nothing for
 * fall back to the provider's short listing, fetched a few at a time so the provider is not flooded.
 * Reading [version] makes a screen redraw when the schedule is downloaded again.
 */
class GuideData(private val scope: CoroutineScope) {
    var epg: XtreamClient? = null
    var stalker: StalkerClient? = null
        set(value) {
            if (field != value) {
                field = value
                short.clear()
                pending.clear()
            }
        }

    var version by mutableIntStateOf(0)
        private set

    private val short = mutableStateMapOf<String, Pair<Long, NowNext>>()
    private val pending = HashSet<String>()
    private val gate = Semaphore(3)

    /** Drops the short now-and-next cache so those channels ask the provider again. */
    fun clearShort() {
        short.clear()
        pending.clear()
    }

    /** Call after the full schedule is downloaded, so screens pick it up. */
    fun scheduleUpdated() {
        version++
    }

    /** What is on now and next, or null while it loads or when there is no guide for the channel. */
    fun nowNext(ch: LiveChannel): NowNext? {
        @Suppress("UNUSED_VARIABLE") val v = version
        val fromGuide = EpgCache.nowNext(ch.epgId, ch.name)
        if (fromGuide?.now != null) return fromGuide
        return shortListing(ch) ?: fromGuide
    }

    /** What is on now from data already downloaded. Never asks the provider, so it is safe for long lists. */
    fun peekNow(ch: LiveChannel): Program? {
        @Suppress("UNUSED_VARIABLE") val v = version
        return EpgCache.nowNext(ch.epgId, ch.name)?.now ?: short[ch.id]?.second?.now
    }

    /** Every known programme for a channel, oldest first. */
    fun schedule(ch: LiveChannel): List<Program> {
        @Suppress("UNUSED_VARIABLE") val v = version
        val full = EpgCache.schedule(ch.epgId, ch.name)
        val now = System.currentTimeMillis()
        if (full.any { now >= it.start && now < it.end }) return full
        // The downloaded grid can miss the current hour. The provider's short listing fills that hole.
        val nn = shortListing(ch) ?: return full
        val extra = listOfNotNull(nn.now, nn.next).filter { p -> p.end > p.start && full.none { it.start == p.start } }
        if (extra.isEmpty()) return full
        return (full + extra).sortedBy { it.start }
    }

    private fun shortListing(ch: LiveChannel): NowNext? {
        val cached = short[ch.id]
        val now = System.currentTimeMillis()
        // The short listing only covers now and next, so it is fetched again once "now" has ended.
        val stale = cached == null || now - cached.first > 15 * 60_000L || (cached.second.now?.end ?: Long.MAX_VALUE) < now
        if (stale) request(ch)
        return cached?.second
    }

    private fun request(ch: LiveChannel) {
        val xtream = epg
        val portal = stalker
        // A playlist channel's id is not an Xtream stream id, so only the matching login is asked.
        if (ch.origin == "playlist") return
        if (ch.origin == "stalker" && portal == null) return
        if (ch.origin != "stalker" && xtream == null) return
        if (!pending.add(ch.id)) return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                gate.withPermit {
                    runCatching {
                        if (ch.origin == "stalker") portal!!.shortEpg(ch.id) else xtream!!.nowNext(ch.id)
                    }.getOrNull()
                }
            }
            pending.remove(ch.id)
            // A failed lookup is remembered as empty, so it is retried later instead of on every redraw.
            short[ch.id] = System.currentTimeMillis() to (result ?: NowNext(null, null))
        }
    }
}
