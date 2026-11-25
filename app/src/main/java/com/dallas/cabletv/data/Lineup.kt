package com.dallas.cabletv.data

import java.text.Normalizer

/*
 * The DIRECTV-style lineup: the channel numbers the person wants, and how the provider's channels
 * are placed on them. Everything here is plain logic with no Android classes, so it can be tested
 * on its own. Storage is in LineupStore.kt, and the OpenRouter pass is in LineupBuilder.kt.
 */

/** One row of the lineup the person supplied. [altNumber] is the second number some channels carry. */
data class LineupChannel(
    val number: Int,
    val altNumber: Int?,
    val name: String,
    val genre: String,
    val pack: String
)

/**
 * A provider channel the model placed. [number] is null when the model found no lineup channel for
 * it, so it sits in its genre block. [source] is "model".
 */
data class LineupAssignment(
    val providerId: String,
    val providerName: String,
    val number: Int?,
    val genre: String,
    val source: String,
    val confidence: Double
)

/**
 * The whole saved lineup. Overrides and assignments are keyed on the provider's channel id, so a
 * list that changes order does not move anything. [overrides] are the person's own picks and always win.
 */
data class LineupDoc(
    val revision: Int = 0,
    val builtAtMs: Long = 0L,
    val lineup: List<LineupChannel> = emptyList(),
    val assignments: Map<String, LineupAssignment> = emptyMap(),
    val overrides: Map<String, Int> = emptyMap(),
    val genreBlocks: Map<String, Int> = DEFAULT_GENRE_BLOCKS,
    val openRouterKey: String = "",
    val openRouterModel: String = ""
) {
    val isEmpty: Boolean get() = lineup.isEmpty()
}

/** The genres a channel can sit in. Each one gets a block of numbers for channels the lineup does not place. */
val GENRES: List<String> = listOf(
    "News", "Sports", "Movies", "Entertainment", "Kids", "Lifestyle", "Documentary",
    "Music", "Religion", "Shopping", "Spanish", "International", "Other"
)

/** Blocks of 50 from 1000. The lineup uses no numbers from 1000 to 1699, so these never clash with it. */
val DEFAULT_GENRE_BLOCKS: Map<String, Int> = GENRES.withIndex().associate { (i, genre) -> genre to 1000 + i * 50 }

/** A model answer at or above this confidence places the channel. Lower answers leave it in its genre block. */
const val MODEL_MIN_CONFIDENCE = 0.8

/** The lineup rows that loaded, and how many rows were skipped because they had no number or name. */
data class LineupParse(val channels: List<LineupChannel>, val skipped: Int)

/** Reads the lineup CSV: number, alt_number, name, genre, package. A header row is skipped. Quotes are honoured. */
fun parseLineupCsv(text: String): LineupParse {
    val out = mutableListOf<LineupChannel>()
    var skipped = 0
    text.removePrefix("﻿").lines().forEachIndexed { i, line ->
        if (line.isBlank()) return@forEachIndexed
        val f = csvFields(line)
        if (i == 0 && f.firstOrNull()?.trim()?.equals("number", ignoreCase = true) == true) return@forEachIndexed
        val number = f.getOrNull(0)?.trim()?.toIntOrNull()
        val name = f.getOrNull(2)?.trim().orEmpty()
        if (number == null || number <= 0 || name.isEmpty()) {
            skipped++
            return@forEachIndexed
        }
        out += LineupChannel(
            number = number,
            altNumber = f.getOrNull(1)?.trim()?.toIntOrNull()?.takeIf { it > 0 },
            name = name,
            genre = f.getOrNull(3)?.trim().orEmpty().ifBlank { "Other" },
            pack = f.getOrNull(4)?.trim().orEmpty()
        )
    }
    return LineupParse(out, skipped)
}

/** Splits one CSV line into fields. A doubled quote inside quotes is a literal quote. */
internal fun csvFields(line: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                cur.append('"')
                i++
            }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> {
                out += cur.toString()
                cur.clear()
            }
            else -> cur.append(c)
        }
        i++
    }
    out += cur.toString()
    return out
}

private val countryPrefixRe = Regex("^[A-Za-z]{2}\\s*[|:\\-]\\s*")
private val bracketRe = Regex("\\([^)]*\\)")
private val qualityTagRe = Regex("\\b(fhd|uhd|hd|sd|4k|raw|hevc|h265|h264)\\b")
private val accentRe = Regex("\\p{M}+")
private val nonAlnumRe = Regex("[^a-z0-9]+")
private val trailingTheRe = Regex(",\\s*the$")
private val leadingTheRe = Regex("^the ")

/**
 * A name in the form used to match channels: no country prefix, no accents, no brackets, no quality
 * tags such as HD, and no punctuation. "UK| BBC One HD" and "BBC One" both become "bbc one".
 */
fun normalizeChannelName(raw: String): String {
    var s = countryPrefixRe.replace(raw.trim(), "")
    s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(accentRe, "")
    s = s.lowercase().replace("&", " and ").replace("+", " plus ")
    s = trailingTheRe.replace(s, " ")
    s = bracketRe.replace(s, " ")
    s = qualityTagRe.replace(s, " ")
    s = nonAlnumRe.replace(s, " ").trim()
    return leadingTheRe.replace(s, "")
}

/** A guess at the genre from the provider's own category, for channels nothing else placed. */
fun guessGenre(group: String): String {
    val g = group.lowercase()
    fun any(vararg words: String) = words.any { g.contains(it) }
    return when {
        any("deporte", "espanol", "español", "spanish", "latino", "latin") -> "Spanish"
        any("sport", "nfl", "nba", "mlb", "nhl", "golf", "tennis", "soccer", "racing") -> "Sports"
        any("news", "weather", "finance", "business") -> "News"
        any("movie", "cinema", "film") -> "Movies"
        any("kid", "child", "cartoon", "junior", "anime") -> "Kids"
        any("music") -> "Music"
        any("document", "nature", "history", "science", "crime") -> "Documentary"
        any("relig", "church", "faith", "christian", "gospel") -> "Religion"
        any("shop", "sale") -> "Shopping"
        any("cook", "food", "lifestyle", "home", "travel", "garden") -> "Lifestyle"
        any("entertain", "comedy", "drama", "reality") -> "Entertainment"
        else -> "Other"
    }
}

/** The lineup, looked up by number and by normalized name. Built once per pass. */
class LineupIndex(lineup: List<LineupChannel>) {
    private val byName: Map<String, List<LineupChannel>> = lineup.groupBy { normalizeChannelName(it.name) }

    val byNumber: Map<Int, LineupChannel> = lineup.distinctBy { it.number }.associateBy { it.number }

    /** Every number the lineup uses, primary and alternate. */
    val allNumbers: Set<Int> = lineup.flatMap { listOfNotNull(it.number, it.altNumber) }.toSet()

    /** The lineup channel with this name. Main-pack channels win ties, then the lower number. */
    fun match(name: String): LineupChannel? =
        byName[normalizeChannelName(name)].orEmpty()
            .minWithOrNull(compareBy<LineupChannel>({ it.pack != "Main" }, { it.number }))
}

/** Where one provider channel stands. [number] is null when it sits in its genre block. */
data class Placement(val number: Int?, val genre: String, val source: String)

/**
 * Works out where a provider channel goes, in priority order: the person's override, then a name
 * match, then the model's assignment, then nothing. A channel with nothing is put in its genre.
 */
fun placementFor(ch: LiveChannel, doc: LineupDoc, index: LineupIndex): Placement {
    doc.overrides[ch.id]?.let { picked ->
        index.byNumber[picked]?.let { return Placement(it.number, it.genre, "override") }
    }
    index.match(ch.name)?.let { return Placement(it.number, it.genre, "name") }
    doc.assignments[ch.id]?.let { return Placement(it.number, it.genre, "model") }
    return Placement(null, guessGenre(ch.group), "none")
}

/** Channels that have no override, name match or model assignment yet. These are the ones to send to the model. */
fun channelsNeedingModel(channels: List<LiveChannel>, doc: LineupDoc): List<LiveChannel> {
    if (doc.isEmpty) return emptyList()
    val index = LineupIndex(doc.lineup)
    return channels.filter { ch ->
        ch.id !in doc.overrides && ch.id !in doc.assignments && index.match(ch.name) == null
    }
}

private val sdRe = Regex("(^|[^A-Za-z0-9])SD([^A-Za-z0-9]|$)")
private val hdRe = Regex("(^|[^A-Za-z0-9])(HD|FHD|UHD|4K)([^A-Za-z0-9]|$)", RegexOption.IGNORE_CASE)

/** An SD copy of a channel, so an HD copy wins a shared number when both are in the list. */
private fun isStandardDefinition(name: String): Boolean = sdRe.containsMatchIn(name) && !hdRe.containsMatchIn(name)

/**
 * The provider's channels with their lineup numbers applied. Ids and everything else stay the same.
 *
 * Numbers are claimed in order: overrides, then name matches, then model placements. When two
 * channels claim one number, the HD copy wins over the SD copy, then the earlier one in the list.
 * Channels that lose, or have no number, get the next free number in their genre block.
 */
fun applyLineup(channels: List<LiveChannel>, doc: LineupDoc): List<LiveChannel> {
    if (doc.isEmpty) return channels
    val index = LineupIndex(doc.lineup)

    data class Claim(val pos: Int, val rank: Int, val sd: Boolean, val number: Int, val genre: String)

    val placements = channels.map { placementFor(it, doc, index) }
    val claims = channels.indices.mapNotNull { pos ->
        val p = placements[pos]
        val n = p.number ?: return@mapNotNull null
        val rank = when (p.source) { "override" -> 0; "name" -> 1; else -> 2 }
        Claim(pos, rank, isStandardDefinition(channels[pos].name), n, p.genre)
    }.sortedWith(compareBy<Claim>({ it.rank }, { it.sd }, { it.pos }))

    val numbered = HashMap<Int, Int>()    // channel position -> number
    val claimed = HashSet<Int>()
    for (c in claims) {
        if (c.number in claimed) continue
        claimed += c.number
        numbered[c.pos] = c.number
    }

    val reserved = HashSet<Int>().apply { addAll(index.allNumbers); addAll(claimed) }
    val nextInBlock = HashMap<String, Int>()
    val out = channels.mapIndexed { pos, ch ->
        val number = numbered[pos] ?: run {
            val genre = placements[pos].genre
            val start = doc.genreBlocks[genre] ?: doc.genreBlocks["Other"] ?: 1600
            var n = nextInBlock[genre] ?: start
            while (n in reserved) n++
            reserved += n
            nextInBlock[genre] = n + 1
            n
        }
        ch.copy(number = number)
    }
    return out.sortedWith(compareBy<LiveChannel>({ it.number }, { it.name.lowercase() }))
}

/** A model answer for one channel after the checks. Null number means it stays in its genre block. */
data class ModelPlacement(val providerId: String, val number: Int?, val genre: String, val confidence: Double)

/**
 * Reads the model's JSON answer and keeps only what can be trusted. A number must be one the lineup
 * has, and its confidence must reach [MODEL_MIN_CONFIDENCE]. An unknown genre becomes "Other".
 * Answers for ids that were not sent are dropped.
 */
fun parseModelAnswers(text: String, sentIds: Set<String>, lineupNumbers: Set<Int>): List<ModelPlacement> {
    val objectRe = Regex("\\{[^{}]*\\}")
    val idRe = Regex("\"id\"\\s*:\\s*\"([^\"]*)\"")
    val numberRe = Regex("\"number\"\\s*:\\s*(null|\\d+)")
    val genreRe = Regex("\"genre\"\\s*:\\s*\"([^\"]*)\"")
    val confidenceRe = Regex("\"confidence\"\\s*:\\s*([0-9]*\\.?[0-9]+)")

    return objectRe.findAll(text).mapNotNull { m ->
        val obj = m.value
        val id = idRe.find(obj)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
        if (id !in sentIds) return@mapNotNull null
        val number = numberRe.find(obj)?.groupValues?.get(1)?.toIntOrNull()
        val genre = genreRe.find(obj)?.groupValues?.get(1)?.trim().orEmpty()
            .let { g -> GENRES.firstOrNull { it.equals(g, ignoreCase = true) } ?: "Other" }
        val confidence = confidenceRe.find(obj)?.groupValues?.get(1)?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.0
        val accepted = number?.takeIf { it in lineupNumbers && confidence >= MODEL_MIN_CONFIDENCE }
        ModelPlacement(id, accepted, genre, confidence)
    }.toList()
}

/** The message sent to the model for one batch. The lineup is listed in full so answers can only name real numbers. */
fun lineupPrompt(batch: List<LiveChannel>, lineup: List<LineupChannel>): String {
    val sb = StringBuilder()
    sb.append("LINEUP (number | name | genre):\n")
    lineup.forEach { sb.append(it.number).append(" | ").append(it.name).append(" | ").append(it.genre).append('\n') }
    sb.append("\nPROVIDER CHANNELS (id | name | provider category):\n")
    batch.forEach { sb.append(it.id).append(" | ").append(it.name).append(" | ").append(it.group).append('\n') }
    sb.append("\nFor every provider channel, find the lineup channel it is the same as. ")
    sb.append("Only use a number from LINEUP. If there is no match, use null. ")
    sb.append("Give a genre from: ").append(GENRES.joinToString(", ")).append(". ")
    sb.append("Reply with only a JSON array, one object per provider channel: ")
    sb.append("{\"id\": \"...\", \"number\": 123 or null, \"genre\": \"...\", \"confidence\": 0.0 to 1.0}.")
    return sb.toString()
}

/** How many channels go to the model in one request. */
const val MODEL_BATCH_SIZE = 60

/**
 * A new lineup from a CSV. The old assignments are dropped, because their numbers came from the old
 * lineup. The person's overrides are kept, so a pick still applies when its number is in the new lineup.
 */
fun withLineupCsv(doc: LineupDoc, text: String, now: Long): LineupDoc =
    doc.copy(
        revision = doc.revision + 1,
        builtAtMs = now,
        lineup = parseLineupCsv(text).channels,
        assignments = emptyMap()
    )

/**
 * Sends the channels that still need a placement to [ask], one batch at a time, and returns the doc
 * with the answers added. A batch that throws stops the pass and nothing is returned, so a failed
 * build never leaves half a lineup behind. Channels the model leaves out are asked about again next time.
 */
fun runModelPass(channels: List<LiveChannel>, doc: LineupDoc, now: Long, ask: (String) -> String): LineupDoc {
    val pending = channelsNeedingModel(channels, doc)
    if (pending.isEmpty()) return doc
    val numbers = doc.lineup.map { it.number }.toSet()
    val assignments = LinkedHashMap(doc.assignments)
    pending.chunked(MODEL_BATCH_SIZE).forEach { batch ->
        val names = batch.associate { it.id to it.name }
        val answer = ask(lineupPrompt(batch, doc.lineup))
        parseModelAnswers(answer, names.keys, numbers).forEach { p ->
            assignments[p.providerId] = LineupAssignment(
                providerId = p.providerId,
                providerName = names.getValue(p.providerId),
                number = p.number,
                genre = p.genre,
                source = "model",
                confidence = p.confidence
            )
        }
    }
    return doc.copy(revision = doc.revision + 1, builtAtMs = now, assignments = assignments)
}

/**
 * Progress of the model pass that runs on the TV in the background. The phone reads it to show
 * progress. One pass runs at a time.
 */
object LineupBuildStatus {
    enum class State { IDLE, RUNNING, DONE, FAILED }

    data class Snapshot(val state: State, val message: String, val done: Int, val total: Int)

    private val lock = Any()
    private var current = Snapshot(State.IDLE, "", 0, 0)

    fun get(): Snapshot = synchronized(lock) { current }

    /** Starts a pass. False when one is already running. */
    fun start(): Boolean = synchronized(lock) {
        if (current.state == State.RUNNING) return false
        current = Snapshot(State.RUNNING, "Starting.", 0, 0)
        true
    }

    fun setTotal(total: Int) = synchronized(lock) { current = current.copy(total = total, message = "Asking the model.") }

    /** One model request has finished. */
    fun advance() = synchronized(lock) { current = current.copy(done = current.done + 1) }

    fun finish(message: String) = synchronized(lock) { current = Snapshot(State.DONE, message, current.done, current.total) }

    fun fail(message: String) = synchronized(lock) { current = Snapshot(State.FAILED, message, current.done, current.total) }
}
