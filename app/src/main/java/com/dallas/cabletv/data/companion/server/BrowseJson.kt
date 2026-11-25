package com.dallas.cabletv.data.companion.server

import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.NowNext
import com.dallas.cabletv.data.Program
import com.dallas.cabletv.data.SearchMatcher
import com.dallas.cabletv.data.VodItem
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * The JSON for the phone's Guide, Search and Library tabs. Plain Kotlin, so tests run it on the JVM.
 * Times are ISO 8601 text, as the now playing message sends them.
 */
object BrowseJson {
    /** How long the guide grid's window is, in milliseconds: four hours, as the TV's guide shows them. */
    const val GRID_WINDOW_MS = 4 * 3_600_000L

    /** Most channel rows one grid page sends. */
    const val GRID_PAGE_MAX = 60

    /** Most channels a search lists, and most titles it lists. */
    const val SEARCH_LIMIT = 40

    /** Most titles one library page sends. */
    const val LIBRARY_PAGE_MAX = 100

    fun program(p: Program, now: Long): JSONObject {
        val json = JSONObject()
            .put("title", p.title)
            .put("start", Instant.ofEpochMilli(p.start).toString())
            .put("end", Instant.ofEpochMilli(p.end).toString())
            .put("on_now", now >= p.start && now < p.end)
        if (p.desc.isNotBlank()) json.put("desc", p.desc)
        return json
    }

    fun channel(ch: LiveChannel, locked: Boolean, nowNext: NowNext?, now: Long): JSONObject {
        val json = JSONObject()
            .put("id", ch.id)
            .put("number", ch.number)
            .put("name", ch.name)
            .put("locked", locked)
        nowNext?.now?.let { json.put("now", program(it, now)) }
        nowNext?.next?.let { json.put("next", program(it, now)) }
        return json
    }

    fun title(item: VodItem, locked: Boolean): JSONObject {
        val json = JSONObject()
            .put("id", item.id)
            .put("source", item.source)
            .put("media_type", item.type)
            .put("name", item.name)
            .put("locked", locked)
        item.year?.let { json.put("year", it) }
        return json
    }

    /**
     * One channel's row of the grid: the programmes that overlap the window from [startMs] to [endMs],
     * in the order given. An empty list means the guide has nothing for the channel.
     */
    fun gridRow(ch: LiveChannel, locked: Boolean, programs: List<Program>, startMs: Long, endMs: Long, now: Long): JSONObject {
        val inWindow = programs.filter { it.end > it.start && it.end > startMs && it.start < endMs }
        return JSONObject()
            .put("id", ch.id)
            .put("number", ch.number)
            .put("name", ch.name)
            .put("locked", locked)
            .put("programs", JSONArray(inWindow.map { program(it, now) }))
    }

    /** Channels whose name matches [query], in list order, up to [SEARCH_LIMIT]. */
    fun matchingChannels(channels: List<LiveChannel>, query: String): List<LiveChannel> {
        val matcher = SearchMatcher(query)
        return channels.filter { matcher.matches(it.name) }.take(SEARCH_LIMIT)
    }

    /** Movies or shows whose name matches [query], in list order, up to [SEARCH_LIMIT]. */
    fun matchingTitles(items: List<VodItem>, query: String): List<VodItem> {
        val matcher = SearchMatcher(query)
        return items.filter { matcher.matches(it.name) }.take(SEARCH_LIMIT)
    }

    /** One page of [items] sorted by name. The offset is at least 0 and the limit is 1 to [LIBRARY_PAGE_MAX]. */
    fun libraryPage(items: List<VodItem>, offset: Int, limit: Int): List<VodItem> =
        items.sortedBy { it.name.lowercase() }
            .drop(offset.coerceAtLeast(0))
            .take(limit.coerceIn(1, LIBRARY_PAGE_MAX))
}
