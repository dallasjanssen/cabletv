package com.dallas.cabletv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One finished or in-progress recording on disk. */
data class Recording(
    val id: String,
    val title: String,
    val channelName: String,
    val fileName: String,
    val started: Long,
    val ended: Long = 0L
)

data class ScheduledRecording(
    val id: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val url: String,
    val start: Long,
    val end: Long
)

/**
 * The recordings folder and the schedule.
 * A folder from player options is used when it is set and that directory can be created,
 * or already exists, and a file can be written there. Anything else stays in the private
 * recordings folder under [root]. Callers pass the app's files directory as [root].
 */
class RecordingStore(private val root: File) {
    private val dir = resolveRecordingsDir(root)
    private val index = File(dir, "index.json")
    private val scheduleFile = File(dir, "schedule.json")

    fun directory(): File = dir

    fun list(): List<Recording> = synchronized(LOCK) {
        readRows(index).mapNotNull { o ->
            Recording(o.optString("id"), o.optString("title"), o.optString("channel"), o.optString("file"), o.optLong("started"), o.optLong("ended"))
        }.sortedByDescending { it.started }
    }

    fun add(recording: Recording) = synchronized(LOCK) {
        val all = list().filter { it.id != recording.id } + recording
        write(index, all.map { it.toJson() })
    }

    fun delete(id: String) = synchronized(LOCK) {
        val all = list()
        val item = all.firstOrNull { it.id == id } ?: return@synchronized
        File(dir, item.fileName).delete()
        write(index, all.filter { it.id != id }.map { it.toJson() })
    }

    fun file(recording: Recording): File = File(dir, recording.fileName)

    /** Scheduled recordings that have not ended yet. */
    fun schedule(nowMs: Long = System.currentTimeMillis()): List<ScheduledRecording> = synchronized(LOCK) {
        readRows(scheduleFile).map { o ->
            ScheduledRecording(
                o.optString("id"), o.optString("channelId"), o.optString("channel"),
                o.optString("title"), o.optString("url"), o.optLong("start"), o.optLong("end")
            )
        }.filter { it.end >= nowMs }
    }

    fun addSchedule(item: ScheduledRecording) = synchronized(LOCK) {
        val all = schedule().filter { it.id != item.id } + item
        write(scheduleFile, all.map { it.toJson() })
    }

    fun removeSchedule(id: String) = synchronized(LOCK) {
        write(scheduleFile, schedule().filter { it.id != id }.map { it.toJson() })
    }

    /** Deletes .ts files that no index entry points to, except [activeFile]. Left behind by a crash. */
    fun deleteOrphans(activeFile: String?) = synchronized(LOCK) {
        val known = list().map { it.fileName }.toSet()
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".ts") && f.name !in known && f.name != activeFile) f.delete()
        }
    }

    /** Reads a JSON list of objects. A file that cannot be parsed is renamed to .bad and read as empty. */
    private fun readRows(file: File): List<JSONObject> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (e: Exception) {
            runCatching {
                val bad = File(file.parentFile, file.name + ".bad")
                bad.delete()
                file.renameTo(bad)
            }
            emptyList()
        }
    }

    /** Writes to a temp file first, then renames it over the real one. */
    private fun write(file: File, rows: List<JSONObject>) {
        val arr = JSONArray()
        rows.forEach { arr.put(it) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun Recording.toJson() = JSONObject()
        .put("id", id).put("title", title).put("channel", channelName)
        .put("file", fileName).put("started", started).put("ended", ended)

    private fun ScheduledRecording.toJson() = JSONObject()
        .put("id", id).put("channelId", channelId).put("channel", channelName)
        .put("title", title).put("url", url).put("start", start).put("end", end)

    private companion object {
        /** One lock for every store, because the worker thread and the UI thread both write. */
        val LOCK = Any()
    }
}

/** A scheduled showing that should be recording at [nowMs] and is not already done. */
fun dueSchedule(items: List<ScheduledRecording>, nowMs: Long, finished: Set<String>): List<ScheduledRecording> =
    items.filter { it.id !in finished && nowMs >= it.start && nowMs < it.end }

/**
 * Player options keep the folder next to the app's files. Callers only pass that files
 * directory, so the store reads the option itself. When the application object cannot be
 * asked, which is how unit tests run, the same preference file is tried, then no folder.
 */
private fun recordingsFolderFromOptions(filesDir: File): String {
    val fromStore = runCatching { folderFromPlayerOptions() }
    if (fromStore.isSuccess) return fromStore.getOrThrow().trim()
    return folderFromPrefsFile(filesDir).trim()
}

private fun folderFromPlayerOptions(): String {
    val app = Class.forName("android.app.ActivityThread")
        .getMethod("currentApplication")
        .invoke(null) as? Context
        ?: error("no application")
    return PlayerOptionsStore(app).load().recordingsFolder
}

/** The same preference, read from disk when the application object is not available yet. */
private fun folderFromPrefsFile(filesDir: File): String {
    val parent = filesDir.parentFile ?: return ""
    val prefs = File(parent, "shared_prefs/cabletv_player_options.xml")
    if (!prefs.isFile) return ""
    return runCatching {
        val raw = REC_FOLDER_XML.find(prefs.readText())?.groupValues?.getOrNull(1) ?: return ""
        unescapeXml(raw)
    }.getOrDefault("")
}

private fun unescapeXml(raw: String): String =
    raw.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

/**
 * [custom] when that path can be created, or already exists and can be written.
 * A bad path returns null so the caller keeps the private folder. Nothing here throws.
 */
private fun writableDirectory(custom: String): File? = try {
    if (custom.isEmpty()) {
        null
    } else {
        val dir = File(custom)
        if (!dir.exists()) {
            if (dir.mkdirs() && dir.isDirectory) dir else null
        } else if (dir.isDirectory && dir.canWrite()) {
            dir
        } else {
            null
        }
    }
} catch (e: Exception) {
    null
}

private fun resolveRecordingsDir(root: File): File {
    val fallback = File(root, "recordings")
    val chosen = writableDirectory(recordingsFolderFromOptions(root))
    if (chosen != null) return chosen
    runCatching { fallback.mkdirs() }
    return fallback
}

/** Name used by PlayerOptionsStore for the recordings folder. */
private val REC_FOLDER_XML = Regex("""<string name="rec_folder">([^<]*)</string>""")
