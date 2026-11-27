package com.dallas.cabletv.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * The programme schedule from XMLTV feeds, kept in the guide database (see [GuideDatabase]). The feeds
 * come from the Xtream login, the guide link inside an M3U playlist, or a Guide URL. Programmes from
 * about two days behind to two days ahead are kept, so catch-up has something to show. Channels are
 * matched by their epg_channel_id or the playlist's tvg-id, ignoring case and punctuation. A channel
 * name can also match the guide's display name.
 *
 * Listings are read from the database when they are needed, so a screen shows only what it asks for.
 * Call [open] once at startup. Until then every read gives nothing.
 */
object EpgCache {

    private const val DAY_MS = 24 * 3_600_000L
    /** Two days ahead, so tomorrow evening is on the guide. */
    const val AHEAD_MS = 48 * 3_600_000L
    /** Two days behind, so catch-up has listings to play. */
    const val BEHIND_MS = 48 * 3_600_000L

    /** Serializes downloads and their writes. Reads never wait for it, because the database is in WAL mode. */
    private val writeLock = Any()
    private val memoLock = Any()

    @Volatile
    private var store: GuideDatabase? = null

    /** Now and next for each channel, per minute, so a screen that asks again does not reread the database. */
    private val nowNextMemo = object : LinkedHashMap<String, NowNext?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NowNext?>?): Boolean =
            size > MEMO_LIMIT
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /** Opens the guide database. Call once at startup, before the guide is read. */
    fun open(context: Context) {
        val app = context.applicationContext
        // The copy kept as a file before the database existed is no longer read.
        File(app.cacheDir, "guide/listings.bin").delete()
        synchronized(writeLock) {
            if (store == null) store = GuideDatabase(app)
        }
    }

    /** Removes every saved listing. Used when no live login is set, so the saved guide belongs to nobody. */
    fun clear() {
        synchronized(writeLock) {
            val db = store?.writableDatabase ?: return
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM program")
                db.execSQL("DELETE FROM alias")
                db.execSQL("DELETE FROM meta")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        clearMemo()
    }

    /**
     * Downloads every guide and replaces the saved listings with what loaded. [key] says which logins the
     * feeds belong to (see [guideCacheKey]). A feed that fails is left out and reported. A feed cut off part
     * way is used, and its channels keep their earlier listings only when [key] is the same logins as before.
     * Returns the addresses that did not load.
     */
    fun refreshAll(urls: List<String>, key: String): List<String> {
        val unique = urls.distinct()
        val s = store ?: return unique
        synchronized(writeLock) {
            val db = s.writableDatabase
            val now = System.currentTimeMillis()
            val from = now - BEHIND_MS
            val to = now + AHEAD_MS
            val names = mutableListOf<Pair<String, String>>()
            val failed = mutableListOf<String>()
            var cutShort = false
            val sameLogins = meta(db, "key") == key
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM staged")
                val stage = Stager(db)
                try {
                    for (url in unique) {
                        runCatching {
                            val req = Request.Builder().url(url).header("User-Agent", PLAYER_USER_AGENT).build()
                            http.newCall(req).execute().use { resp ->
                                if (!resp.isSuccessful) error("XMLTV returned HTTP ${resp.code}")
                                val body = resp.body ?: error("Empty XMLTV response")
                                stageFeed(body.byteStream(), from, to, stage, names)
                            }
                        }.onSuccess { complete ->
                            if (!complete) {
                                cutShort = true
                                failed += url
                            }
                        }.onFailure { failed += url }
                    }
                } finally {
                    stage.close()
                }
                if (stage.count > 0) {
                    commit(
                        db,
                        merge = sameLogins && cutShort,
                        names = names,
                        key = key,
                        from = from,
                        to = to,
                        stampSaved = failed.size < unique.size
                    )
                    db.setTransactionSuccessful()
                }
            } finally {
                db.endTransaction()
            }
            clearMemo()
            return failed
        }
    }

    /**
     * When the saved listings were last downloaded in full for these logins, or null when there are none.
     * The app shows them straight away and waits before the next download.
     */
    fun savedAt(key: String): Long? = read(null) { db ->
        if (meta(db, "key") != key || !hasAnyProgram(db)) return@read null
        meta(db, "saved_at")?.toLongOrNull()
    }

    /** Now and next for a channel from the saved schedule, or null when the guide has nothing for it. */
    fun nowNext(epgId: String?, name: String? = null): NowNext? {
        val now = System.currentTimeMillis()
        val memoKey = "${epgId.orEmpty()}\u0000${name.orEmpty()}\u0000${now / 60_000L}"
        synchronized(memoLock) {
            if (nowNextMemo.containsKey(memoKey)) return nowNextMemo[memoKey]
        }
        val answer = read(null) { db -> nowNextIn(db, epgId, name, now) }
        synchronized(memoLock) { nowNextMemo[memoKey] = answer }
        return answer
    }

    /**
     * Every programme kept for a channel in the window around now, oldest first. Empty when the guide has
     * nothing for it. [name] is used when the id is missing, or when the id's listings do not cover now and
     * the guide's display name does. Descriptions are left out unless [withDesc] is set, since most screens
     * need only titles and times.
     */
    fun schedule(epgId: String?, name: String? = null, withDesc: Boolean = false): List<Program> {
        val now = System.currentTimeMillis()
        return read(emptyList()) { db -> chosenListings(db, epgId, name, now, withDesc)?.rows.orEmpty() }
    }

    /** The description of the programme that starts at [start] on this channel, or empty when there is none. */
    fun descriptionFor(epgId: String?, name: String?, start: Long): String = read("") { db ->
        listOfNotNull(keyFor(db, epgId), keyFor(db, name))
            .firstNotNullOfOrNull { key -> descriptionOf(db, key, start)?.takeIf { it.isNotEmpty() } }
            .orEmpty()
    }

    /**
     * Shows on now or later whose titles contain [query], ignoring case, accents and punctuation.
     * Soonest first. [keys], when given, limits the search to those guide channels. The titles are read
     * from the database one at a time, so only the hits are kept.
     */
    fun programmesMatching(
        query: String,
        nowMs: Long = System.currentTimeMillis(),
        limit: Int = 30,
        keys: Set<String>? = null
    ): List<Pair<String, Program>> = read(emptyList()) { db ->
        val matcher = SearchMatcher(query)
        if (matcher.folded.length < 2 || limit <= 0) return@read emptyList()
        val hits = ArrayList<Pair<String, Program>>()
        db.rawQuery("SELECT ch, start, stop, title FROM program WHERE stop > ? ORDER BY start", arrayOf(nowMs.toString())).use { c ->
            while (c.moveToNext()) {
                val ch = c.getString(0)
                if (keys != null && ch !in keys) continue
                val title = c.getString(3)
                if (matcher.matches(title)) hits += ch to Program(title, c.getLong(1), c.getLong(2))
                if (hits.size >= limit) break
            }
        }
        hits
    }

    /**
     * The guide channel whose listings [schedule] shows for this channel, or null when there is none.
     * Search uses it, so a show is found on the same channels the guide shows it on.
     */
    fun scheduleKey(epgId: String?, name: String? = null, now: Long = System.currentTimeMillis()): String? =
        read(null) { db -> chosenListings(db, epgId, name, now, false)?.takeIf { it.rows.isNotEmpty() }?.key }

    /**
     * Programmes that already aired on a channel inside [withinMs], newest first.
     * The guide keeps about two days, so a longer archive still shows those two days.
     */
    fun pastPrograms(epgId: String?, withinMs: Long = DAY_MS, name: String? = null): List<Program> {
        val now = System.currentTimeMillis()
        return read(emptyList()) { db ->
            finishedWithin(chosenListings(db, epgId, name, now, false)?.rows.orEmpty(), now, withinMs)
        }
    }

    // Database access

    /** Runs [block] on the database, or returns [default] when it is not open or cannot be read. */
    private inline fun <T> read(default: T, block: (SQLiteDatabase) -> T): T {
        val s = store ?: return default
        return try {
            block(s.readableDatabase)
        } catch (e: SQLiteException) {
            DebugLog.log("guide", "Guide could not be read", e)
            default
        }
    }

    private fun clearMemo() {
        synchronized(memoLock) { nowNextMemo.clear() }
    }

    /**
     * Reads one feed into the staging table, keeping only the window from [from] to [to]. Returns false when
     * the feed broke part way and what came before the break is kept. Throws when nothing was read.
     */
    private fun stageFeed(
        input: InputStream,
        from: Long,
        to: Long,
        stage: Stager,
        names: MutableList<Pair<String, String>>
    ): Boolean {
        val before = stage.count
        return try {
            readFeed(input, from, to, names) { ch, p -> stage.add(ch, p) }
            true
        } catch (e: Exception) {
            if (stage.count == before) throw e
            false
        }
    }

    /**
     * Moves the staged programmes into the schedule in one go. Stop times are filled in, the window is
     * applied, and the channel names are rebuilt. With [merge], channels the staged feeds did not reach keep
     * their saved programmes.
     */
    private fun commit(
        db: SQLiteDatabase,
        merge: Boolean,
        names: List<Pair<String, String>>,
        key: String,
        from: Long,
        to: Long,
        stampSaved: Boolean
    ) {
        // A programme with no stop time ends when the next one on its channel starts, or half an hour later.
        db.execSQL(
            "UPDATE staged SET stop = COALESCE((SELECT MIN(s2.start) FROM staged AS s2 " +
                "WHERE s2.ch = staged.ch AND s2.start > staged.start), staged.start + $OPEN_END_MS) WHERE stop <= 0"
        )
        db.execSQL("DELETE FROM staged WHERE stop <= ? OR start >= ?", arrayOf<Any>(from, to))
        if (merge) {
            db.execSQL("DELETE FROM program WHERE ch IN (SELECT DISTINCT ch FROM staged)")
        } else {
            db.execSQL("DELETE FROM program")
        }
        db.execSQL("INSERT INTO program (ch, start, stop, title, desc) SELECT ch, start, stop, title, desc FROM staged")
        db.execSQL("DELETE FROM program WHERE stop <= ? OR start >= ?", arrayOf<Any>(from, to))
        db.execSQL("DELETE FROM staged")

        val ids = HashSet<String>()
        db.rawQuery("SELECT DISTINCT ch FROM program", null).use { c ->
            while (c.moveToNext()) ids += c.getString(0)
        }
        val aliases = buildEpgAliases(ids, names)
        db.execSQL("DELETE FROM alias")
        val insert = db.compileStatement("INSERT INTO alias (name, ch) VALUES (?, ?)")
        try {
            for ((name, ch) in aliases) {
                insert.bindString(1, name)
                insert.bindString(2, ch)
                insert.executeInsert()
            }
        } finally {
            insert.close()
        }

        db.execSQL("INSERT OR REPLACE INTO meta (k, v) VALUES ('key', ?)", arrayOf<Any>(key))
        if (stampSaved) {
            db.execSQL("INSERT OR REPLACE INTO meta (k, v) VALUES ('saved_at', ?)", arrayOf<Any>(System.currentTimeMillis().toString()))
        }
    }

    private fun meta(db: SQLiteDatabase, k: String): String? =
        db.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(k)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun hasAnyProgram(db: SQLiteDatabase): Boolean =
        db.rawQuery("SELECT 1 FROM program LIMIT 1", null).use { it.moveToFirst() }

    private fun hasChannel(db: SQLiteDatabase, ch: String): Boolean =
        db.rawQuery("SELECT 1 FROM program WHERE ch = ? LIMIT 1", arrayOf(ch)).use { it.moveToFirst() }

    /** The id stored in the schedule for an id or display name, or null. */
    private fun keyFor(db: SQLiteDatabase, key: String?): String? {
        if (key.isNullOrBlank()) return null
        if (hasChannel(db, key)) return key
        val norm = normalizeEpgKey(key)
        if (norm.isEmpty()) return null
        return db.rawQuery("SELECT ch FROM alias WHERE name = ?", arrayOf(norm)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    /** The programmes of one guide channel in the window around [now]. Descriptions only when [withDesc]. */
    private fun windowRows(db: SQLiteDatabase, key: String, now: Long, withDesc: Boolean): List<Program> {
        val sql = if (withDesc) {
            "SELECT start, stop, title, desc FROM program WHERE ch = ? AND stop > ? AND start < ? ORDER BY start"
        } else {
            "SELECT start, stop, title FROM program WHERE ch = ? AND stop > ? AND start < ? ORDER BY start"
        }
        val args = arrayOf(key, (now - BEHIND_MS).toString(), (now + AHEAD_MS).toString())
        return db.rawQuery(sql, args).use { c ->
            val out = ArrayList<Program>(c.count)
            while (c.moveToNext()) {
                out += Program(c.getString(2), c.getLong(0), c.getLong(1), if (withDesc) c.getString(3) else "")
            }
            out
        }
    }

    private fun descriptionOf(db: SQLiteDatabase, key: String, start: Long): String? =
        db.rawQuery("SELECT desc FROM program WHERE ch = ? AND start = ?", arrayOf(key, start.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** The guide channel whose listings a channel uses, with those listings for the window around now. */
    private class Chosen(val key: String, val rows: List<Program>)

    /** Picks the id's listings, or the display name's, by the rule in [chooseListings]. */
    private fun chosenListings(db: SQLiteDatabase, epgId: String?, name: String?, now: Long, withDesc: Boolean): Chosen? {
        val idKey = keyFor(db, epgId)
        val nameKey = keyFor(db, name)
        val byId = idKey?.let { windowRows(db, it, now, withDesc) }.orEmpty()
        val byName = nameKey?.let { windowRows(db, it, now, withDesc) }.orEmpty()
        val useId = chooseListings(byId, byName, now) === byId
        val key = (if (useId) idKey else nameKey) ?: return null
        return Chosen(key, if (useId) byId else byName)
    }

    private fun nowNextIn(db: SQLiteDatabase, epgId: String?, name: String?, now: Long): NowNext? {
        val chosen = chosenListings(db, epgId, name, now, false) ?: return null
        val list = chosen.rows
        val current = list.firstOrNull { now >= it.start && now < it.end }
        val next = list.firstOrNull { it.start > now }
        if (current == null && next == null) return null
        return NowNext(
            current?.let { withDescription(db, chosen.key, it) },
            next?.let { withDescription(db, chosen.key, it) }
        )
    }

    private fun withDescription(db: SQLiteDatabase, key: String, p: Program): Program {
        val text = descriptionOf(db, key, p.start) ?: return p
        return p.copy(desc = text)
    }

    /**
     * Streams an XMLTV feed, calling [sink] for each programme in the window. A gzip body is inflated first.
     * A feed that breaks part way (a cut off download, a bad character) keeps what was read before the break.
     */
    private fun readFeed(
        input: InputStream,
        from: Long,
        to: Long,
        names: MutableList<Pair<String, String>>,
        sink: (String, Program) -> Unit
    ) {
        val parser = Xml.newPullParser()
        parser.setInput(ungzipIfNeeded(input), null)

        var channel: String? = null
        var start = 0L
        var stop = 0L
        var title = ""
        var desc = ""
        var inTitle = false
        var inDesc = false
        var inChannel = false
        var channelId: String? = null
        var inDisplay = false
        var display = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> {
                        inChannel = true
                        channelId = parser.getAttributeValue(null, "id")
                        display = ""
                    }
                    "display-name" -> if (inChannel) {
                        inDisplay = true
                        display = ""
                    }
                    "programme" -> {
                        channel = parser.getAttributeValue(null, "channel")
                        start = xmltvTimeMs(parser.getAttributeValue(null, "start"))
                        stop = xmltvTimeMs(parser.getAttributeValue(null, "stop") ?: parser.getAttributeValue(null, "end"))
                        title = ""
                        desc = ""
                    }
                    // Only the first title and description are used when a programme has several languages.
                    "title" -> inTitle = title.isEmpty()
                    "desc" -> inDesc = desc.isEmpty()
                }
                XmlPullParser.TEXT -> {
                    if (inTitle) title += parser.text
                    // Long descriptions are cut short, so a big feed does not fill the memory.
                    if (inDesc && desc.length <= MAX_DESC) desc += parser.text
                    if (inDisplay) display += parser.text
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "title" -> inTitle = false
                    "desc" -> inDesc = false
                    "display-name" -> {
                        val id = channelId
                        if (inChannel && id != null && display.isNotBlank()) names += display.trim() to id
                        inDisplay = false
                        display = ""
                    }
                    "channel" -> inChannel = false
                    "programme" -> {
                        val ch = channel
                        // A programme with no stop time is kept for now. The commit gives it one.
                        val open = stop <= 0L && start > from - OPEN_END_SLACK_MS
                        if (ch != null && start > 0L && start < to && (stop > from || open)) {
                            sink(ch, Program(title.trim(), start, if (open) 0L else stop, clipDesc(desc)))
                        }
                        channel = null
                    }
                }
            }
            event = parser.next()
        }
    }

    /** Writes programmes into the staging table. One row per channel and start time; the better copy stays. */
    private class Stager(private val db: SQLiteDatabase) {
        private val insert = db.compileStatement("INSERT INTO staged (ch, start, stop, title, desc) VALUES (?, ?, ?, ?, ?)")

        /** Programmes kept so far, across every feed of this download. */
        var count = 0L
            private set

        fun add(ch: String, p: Program) {
            insert.bindString(1, ch)
            insert.bindLong(2, p.start)
            insert.bindLong(3, p.end)
            insert.bindString(4, p.title)
            insert.bindString(5, p.desc)
            try {
                insert.executeInsert()
                count++
                return
            } catch (e: SQLiteConstraintException) {
                // The same show is already kept, from an earlier feed or an earlier entry. Keep the better copy.
            }
            db.rawQuery("SELECT stop, desc FROM staged WHERE ch = ? AND start = ?", arrayOf(ch, p.start.toString())).use { c ->
                if (!c.moveToFirst()) return
                if (!betterProgram(Program("", p.start, c.getLong(0), c.getString(1)), p)) return
            }
            db.execSQL(
                "UPDATE staged SET stop = ?, title = ?, desc = ? WHERE ch = ? AND start = ?",
                arrayOf<Any>(p.end, p.title, p.desc, ch, p.start)
            )
        }

        fun close() {
            insert.close()
        }
    }
}

/** Longest programme description kept, in characters. */
private const val MAX_DESC = 400
/** The most now-and-next answers kept in memory. Each one is small. */
private const val MEMO_LIMIT = 2_000
/** A programme with no stop time and no next programme is given half an hour. */
private const val OPEN_END_MS = 30 * 60_000L
/** How far before the kept window a programme with no stop time may start and still be read. */
private const val OPEN_END_SLACK_MS = 12 * 3_600_000L

internal fun clipDesc(raw: String): String {
    val text = raw.trim()
    return if (text.length <= MAX_DESC) text else text.take(MAX_DESC).trimEnd() + "…"
}

/**
 * Whether [incoming] should replace [kept] when both start at the same time on one channel. A copy with a
 * description beats one without, and then a copy with a stop time beats one without.
 */
internal fun betterProgram(kept: Program, incoming: Program): Boolean =
    (kept.desc.isBlank() && incoming.desc.isNotBlank()) ||
        (kept.desc.isBlank() == incoming.desc.isBlank() && kept.end <= 0L && incoming.end > 0L)

/**
 * Letters and digits only, so `TNT.us` and `tnt us` can meet. Called for every channel, so it avoids a regex.
 */
internal fun normalizeEpgKey(raw: String): String {
    val out = StringBuilder(raw.length)
    for (c in raw) {
        val l = c.lowercaseChar()
        if (l in 'a'..'z' || l in '0'..'9') out.append(l)
    }
    return out.toString()
}

internal fun epgIdsMatch(channelId: String?, storedId: String): Boolean {
    if (channelId.isNullOrBlank()) return false
    if (channelId == storedId) return true
    val a = normalizeEpgKey(channelId)
    val b = normalizeEpgKey(storedId)
    return a.isNotEmpty() && a == b
}

/**
 * Maps a normalized id or display name to the channel id stored in the schedule.
 * A key that would point at two channels is left out.
 */
internal fun buildEpgAliases(ids: Set<String>, names: List<Pair<String, String>>): Map<String, String> {
    val aliases = HashMap<String, String>()
    val blocked = HashSet<String>()
    fun add(raw: String, id: String) {
        val key = normalizeEpgKey(raw)
        if (key.isEmpty() || key in blocked) return
        val existing = aliases[key]
        if (existing == null) aliases[key] = id
        else if (existing != id) {
            aliases.remove(key)
            blocked += key
        }
    }
    ids.forEach { add(it, it) }
    names.forEach { (name, id) -> add(name, id) }
    return aliases
}

/** The id's listings, unless they miss the current show and the name's listings have it. */
internal fun chooseListings(byId: List<Program>, byName: List<Program>, now: Long): List<Program> {
    if (byId.any { now >= it.start && now < it.end }) return byId
    if (byName.any { now >= it.start && now < it.end }) return byName
    return byId.ifEmpty { byName }
}

private val COMPACT_XMLTV_TIME = Regex("""^(\d{14}|\d{12})(?:\s*([+-]\d{2}:?\d{2}|Z|UTC|GMT))?$""", RegexOption.IGNORE_CASE)
private val ISO_XMLTV_TIME = Regex("""^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})(?:\s*([+-]\d{2}:?\d{2}|Z|UTC|GMT))?$""", RegexOption.IGNORE_CASE)

private val XMLTV_OFFSET = Regex("""^([+-])(\d{2}):?(\d{2})$""")
private val SECONDS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US)
private val MINUTES_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm", Locale.US)
private val ISO_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

/** XMLTV start and stop, with or without seconds. A missing zone is UTC. A time this cannot read is 0. */
internal fun xmltvTimeMs(raw: String?): Long {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return 0L
    COMPACT_XMLTV_TIME.matchEntire(s)?.let { m ->
        val digits = m.groupValues[1]
        return xmltvStamp(digits, if (digits.length == 12) MINUTES_FORMAT else SECONDS_FORMAT, m.groupValues[2])
    }
    ISO_XMLTV_TIME.matchEntire(s)?.let { m ->
        return xmltvStamp("${m.groupValues[1]}T${m.groupValues[2]}", ISO_FORMAT, m.groupValues[3])
    }
    return 0L
}

private fun xmltvStamp(text: String, format: DateTimeFormatter, zoneRaw: String): Long = try {
    val local = LocalDateTime.parse(text, format)
    local.toInstant(xmltvOffset(zoneRaw)).toEpochMilli()
} catch (e: DateTimeException) {
    0L
}

private fun xmltvOffset(raw: String): ZoneOffset {
    val z = raw.trim()
    if (z.isEmpty() || z.equals("Z", true) || z.equals("UTC", true) || z.equals("GMT", true)) return ZoneOffset.UTC
    val m = XMLTV_OFFSET.matchEntire(z) ?: return ZoneOffset.UTC
    val sign = if (m.groupValues[1] == "-") -1 else 1
    val seconds = sign * (m.groupValues[2].toInt() * 3600 + m.groupValues[3].toInt() * 60)
    return ZoneOffset.ofTotalSeconds(seconds)
}

/** A guide file downloaded without a gzip header is still gzip when it starts with the gzip magic. */
internal fun ungzipIfNeeded(input: InputStream): InputStream {
    val src = input.buffered()
    src.mark(4)
    val b0 = src.read()
    val b1 = src.read()
    src.reset()
    return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(src) else src
}

/**
 * Which logins and guide links a saved copy belongs to. A hash, so the database never holds the links,
 * which can carry the Xtream password.
 */
fun guideCacheKey(s: Settings): String {
    val text = listOf(s.xtreamServer, s.xtreamUser, s.xtreamPass, s.m3uUrl, s.epgUrl).joinToString("\u0000")
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { String.format(Locale.US, "%02x", it) }
}
