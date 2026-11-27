package com.dallas.cabletv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.dallas.cabletv.data.EpgCache
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.NowNext
import com.dallas.cabletv.data.Program
import com.dallas.cabletv.data.StalkerClient
import com.dallas.cabletv.data.XtreamClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** How long the rows must stay put before anything is asked. Paging quickly asks for nothing. */
private const val SETTLE_MS = 300L
/** Time for the rows still on screen to redraw and ask again, when frames cannot be counted. */
private const val CHECK_MS = 150L

/**
 * Guide data for every screen. The full schedule comes from [EpgCache]. Channels it has nothing for
 * fall back to the provider's short listing, fetched a few at a time so the provider is not flooded.
 * Reading [version] makes a screen redraw when the schedule is downloaded again.
 *
 * Rows ask for a short listing as they draw. Nothing is fetched until the rows have stayed put for
 * a moment, and then only for rows that are still on screen. Lookups still waiting for their turn are
 * dropped when their rows leave, and one channel is never asked twice at once.
 */
class GuideData(private val scope: CoroutineScope) {
    var epg: XtreamClient? = null
    var stalker: StalkerClient? = null
        set(value) {
            if (field != value) {
                field = value
                clearShort()
            }
        }

    var version by mutableIntStateOf(0)
        private set

    private val short = mutableStateMapOf<String, Pair<Long, NowNext>>()
    private val gate = Semaphore(3)

    // Everything below is only touched on the main thread: from composition, and from [scope].
    /** Read by every row that is waiting for a short listing. Changing it makes the rows still on screen ask again. */
    private var onScreenCheck by mutableIntStateOf(0)
    /** Channels asked for since the last check. */
    private val asked = HashSet<String>()
    /** Channels whose rows asked again during the check, so they are still on screen. */
    private val stillShown = LinkedHashMap<String, LiveChannel>()
    private var checking = false
    private var settle: Job? = null
    /** Lookups queued or running, by channel id. */
    private val inFlight = HashMap<String, Job>()
    /** Lookups that are already talking to the provider. They are left to finish. */
    private val running = HashSet<Job>()

    /** Drops the short now-and-next cache so those channels ask the provider again. */
    fun clearShort() {
        short.clear()
        settle?.cancel()
        settle = null
        checking = false
        asked.clear()
        stillShown.clear()
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
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
    fun schedule(ch: LiveChannel, withDesc: Boolean = false): List<Program> {
        @Suppress("UNUSED_VARIABLE") val v = version
        val full = EpgCache.schedule(ch.epgId, ch.name, withDesc)
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
        if (stale) {
            // Reading this ties the row to the on-screen check below.
            @Suppress("UNUSED_VARIABLE") val check = onScreenCheck
            request(ch)
        }
        return cached?.second
    }

    private fun canAsk(ch: LiveChannel): Boolean = when (ch.origin) {
        // A playlist channel's id is not an Xtream stream id, so only the matching login is asked.
        "playlist" -> false
        "stalker" -> stalker != null
        else -> epg != null
    }

    private fun request(ch: LiveChannel) {
        if (!canAsk(ch)) return
        if (checking) {
            stillShown[ch.id] = ch
            return
        }
        if (ch.id in inFlight) return
        // A row drawing again for another reason does not hold the others back.
        if (!asked.add(ch.id) && settle?.isActive == true) return
        settle?.cancel()
        settle = scope.launch {
            delay(SETTLE_MS)
            val ready = try {
                checking = true
                stillShown.clear()
                onScreenCheck++
                // Rows still on screen redraw now and ask again. Rows that scrolled away are gone and do not.
                if (runCatching { repeat(3) { withFrameNanos { } } }.isFailure) delay(CHECK_MS)
                stillShown.values.toList()
            } finally {
                checking = false
                asked.clear()
                stillShown.clear()
            }
            val keep = ready.mapTo(HashSet()) { it.id }
            val dropped = inFlight.filter { (id, job) -> id !in keep && job !in running }
            dropped.forEach { (id, job) ->
                job.cancel()
                inFlight.remove(id)
            }
            ready.forEach { fetch(it) }
        }
    }

    private fun fetch(ch: LiveChannel) {
        if (ch.id in inFlight || !canAsk(ch)) return
        val xtream = epg
        val portal = stalker
        val id = ch.id
        inFlight[id] = scope.launch {
            val me = coroutineContext[Job]
            try {
                gate.withPermit {
                    if (me != null) running += me
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            if (ch.origin == "stalker") portal!!.shortEpg(id) else xtream!!.nowNext(id)
                        }.getOrNull()
                    }
                    // A failed lookup is remembered as empty, so it is retried later instead of on every redraw.
                    short[id] = System.currentTimeMillis() to (result ?: NowNext(null, null))
                }
            } finally {
                if (me != null) running -= me
                if (inFlight[id] === me) inFlight.remove(id)
            }
        }
    }
}
