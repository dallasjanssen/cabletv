package com.dallas.cabletv.data.companion.client

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** One programme as the TV's guide lists it. Times are epoch milliseconds, and 0 when the TV did not say. */
data class BrowseProgram(
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val desc: String,
    val onNow: Boolean
) {
    companion object {
        fun fromJson(json: JSONObject): BrowseProgram = BrowseProgram(
            title = json.optString("title"),
            startMs = instantMs(json.optString("start")),
            endMs = instantMs(json.optString("end")),
            desc = json.optString("desc"),
            onNow = json.optBoolean("on_now")
        )
    }
}

/** A channel with what is on now and what is next. [now] and [next] are null when the guide has nothing. */
data class GuideChannel(
    val id: String,
    val number: Int,
    val name: String,
    val locked: Boolean,
    val now: BrowseProgram?,
    val next: BrowseProgram?
) {
    companion object {
        fun fromJson(json: JSONObject): GuideChannel = GuideChannel(
            id = json.optString("id"),
            number = json.optInt("number"),
            name = json.optString("name"),
            locked = json.optBoolean("locked"),
            now = json.optJSONObject("now")?.let { BrowseProgram.fromJson(it) },
            next = json.optJSONObject("next")?.let { BrowseProgram.fromJson(it) }
        )
    }
}

/** A movie or show. [mediaType] and [source] go back to the TV with the request that opens it. */
data class TitleItem(
    val id: String,
    val source: String,
    val mediaType: String,
    val name: String,
    val year: Int?,
    val locked: Boolean
) {
    companion object {
        fun fromJson(json: JSONObject): TitleItem = TitleItem(
            id = json.optString("id"),
            source = json.optString("source"),
            mediaType = json.optString("media_type"),
            name = json.optString("name"),
            year = json.optInt("year", 0).takeIf { it > 0 },
            locked = json.optBoolean("locked")
        )
    }
}

/** One channel's row in the guide grid: its programmes in the window the TV sent. */
data class GridChannel(
    val id: String,
    val number: Int,
    val name: String,
    val locked: Boolean,
    val programs: List<BrowseProgram>
) {
    companion object {
        fun fromJson(json: JSONObject): GridChannel = GridChannel(
            id = json.optString("id"),
            number = json.optInt("number"),
            name = json.optString("name"),
            locked = json.optBoolean("locked"),
            programs = json.optJSONArray("programs").objects { BrowseProgram.fromJson(it) }
        )
    }
}

/**
 * The guide grid: the category names, the window the rows cover, how many channels the category
 * has, and the channels loaded so far.
 */
data class GridPage(
    val groups: List<String>,
    val group: String,
    val startMs: Long,
    val endMs: Long,
    val total: Int,
    val channels: List<GridChannel>
) {
    companion object {
        fun fromJson(json: JSONObject): GridPage = GridPage(
            groups = json.optJSONArray("groups").strings(),
            group = json.optString("group"),
            startMs = json.optLong("start_ms"),
            endMs = json.optLong("end_ms"),
            total = json.optInt("total"),
            channels = json.optJSONArray("channels").objects { GridChannel.fromJson(it) }
        )
    }
}

/** What a search found. */
data class SearchResults(val channels: List<GuideChannel>, val titles: List<TitleItem>)

/** The movies or shows loaded so far, and how many the TV has in all. */
data class LibraryPage(val items: List<TitleItem>, val total: Int)

/** Each object in the array, in order. Entries that are not objects are skipped. */
fun <T> JSONArray?.objects(parse: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it)?.let(parse) }
}

/** Each text entry in the array, in order. Entries that are not text are read as they come. */
fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it) }
}

private fun instantMs(text: String): Long = runCatching { Instant.parse(text).toEpochMilli() }.getOrDefault(0L)
