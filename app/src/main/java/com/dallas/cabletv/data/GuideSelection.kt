package com.dallas.cabletv.data

import java.text.Normalizer

/** What OK does on a guide cell. */
enum class GuideSelection {
    /** Tune the channel live. Used for the current show without an archive, and for a cell with no listing. */
    WatchLive,
    /** The show is on now and the channel has an archive: offer live, or from the start. */
    LiveOrStart,
    /** Play this showing from the start. It has ended and the channel has an archive. */
    Replay,
    /** The show has not started. */
    Later,
    /** The show has ended and this channel has no archive. */
    Unavailable
}

/**
 * OK on a programme. The show on now tunes live, or offers live or from the start when the
 * channel has catch-up. A finished show plays from the start when the channel has catch-up.
 * A later listing is left for a reminder. A finished listing without catch-up does not tune the live channel.
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
    if (now >= start && now < end) return if (catchup) GuideSelection.LiveOrStart else GuideSelection.WatchLive
    if (catchup && end > start) return GuideSelection.Replay
    return GuideSelection.Unavailable
}

/**
 * Programmes still on or still to come whose titles contain [query].
 * Soonest first. Matching ignores case, accents, spaces and punctuation (see [SearchMatcher]).
 * [keys], when given, limits the search to those channel ids.
 */
fun matchProgrammes(
    schedule: Map<String, List<Program>>,
    query: String,
    nowMs: Long,
    limit: Int = 30,
    keys: Set<String>? = null
): List<Pair<String, Program>> {
    val matcher = SearchMatcher(query)
    if (matcher.folded.length < 2 || limit <= 0) return emptyList()
    val hits = ArrayList<Pair<String, Program>>()
    for ((id, list) in schedule) {
        if (keys != null && id !in keys) continue
        for (p in list) {
            if (p.end > nowMs && matcher.matches(p.title)) hits += id to p
        }
    }
    hits.sortBy { it.second.start }
    return if (hits.size > limit) hits.subList(0, limit).toList() else hits
}

/**
 * Text as search compares it: accents removed, lowercase, letters and digits only.
 * "Télé 7" reads "tele7" and "A&E" reads "ae".
 */
fun foldForSearch(text: String): String {
    val out = StringBuilder(text.length)
    foldInto(text, out)
    return out.toString()
}

private fun foldInto(text: String, out: StringBuilder) {
    // Plain ASCII has no accents, so the slower normalizer is skipped for it.
    val src = if (text.all { it.code < 0x80 }) text else Normalizer.normalize(text, Normalizer.Form.NFD)
    for (c in src) {
        if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
        if (c.isLetterOrDigit()) out.append(c.lowercaseChar())
    }
}

/**
 * One search, matched against many titles. Case, accents, spaces and punctuation are ignored on
 * both sides, so "tele" finds "Télé 7" and "ae" finds "A&E". Not shared between threads.
 */
class SearchMatcher(query: String) {
    /** The query as it is compared. Empty when it has no letters or digits. */
    val folded: String = foldForSearch(query)
    private val buffer = StringBuilder()

    fun matches(text: String): Boolean {
        if (folded.isEmpty()) return false
        buffer.setLength(0)
        foldInto(text, buffer)
        return buffer.indexOf(folded) >= 0
    }

    /** For text already passed through [foldForSearch]. */
    fun matchesFolded(foldedText: String): Boolean = folded.isNotEmpty() && foldedText.contains(folded)
}
