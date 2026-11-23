package com.dallas.cabletv.data

/** What OK does on a guide cell. */
enum class GuideSelection {
    /** Tune the channel live. Used for the current show, and for a cell with no listing. */
    WatchLive,
    /** Play this showing from the start. The channel has an archive. */
    Replay,
    /** The show has not started. */
    Later,
    /** The show has ended and this channel has no archive. */
    Unavailable
}

/**
 * OK on a programme. A listing that has already started plays from the start when the
 * channel has catch-up. A later listing is left for a reminder. A finished listing
 * without catch-up does not tune the live channel.
 */
fun guideSelection(
    start: Long,
    end: Long,
    now: Long,
    placeholder: Boolean,
    catchup: Boolean
): GuideSelection {
    if (placeholder) return GuideSelection.WatchLive
    if (start > now) return GuideSelection.Later
    if (catchup && end > start) return GuideSelection.Replay
    if (now >= start && now < end) return GuideSelection.WatchLive
    return GuideSelection.Unavailable
}

/**
 * Programmes still on or still to come whose titles contain [query].
 * Soonest first. Matching is case-insensitive.
 */
fun matchProgrammes(
    schedule: Map<String, List<Program>>,
    query: String,
    nowMs: Long,
    limit: Int = 30
): List<Pair<String, Program>> {
    val q = query.trim()
    if (q.length < 2 || limit <= 0) return emptyList()
    return schedule.flatMap { (id, list) ->
        list.filter { it.end > nowMs && it.title.contains(q, ignoreCase = true) }.map { id to it }
    }.sortedBy { it.second.start }.take(limit)
}
