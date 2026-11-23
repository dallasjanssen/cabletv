package com.dallas.cabletv.data

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

/** The recordings folder and the schedule, both next to the app's files. */
class RecordingStore(private val root: File) {
    private val dir = File(root, "recordings").apply { mkdirs() }
    private val index = File(dir, "index.json")
    private val scheduleFile = File(dir, "schedule.json")

    fun directory(): File = dir

    fun list(): List<Recording> {
        if (!index.exists()) return emptyList()
        val arr = JSONArray(index.readText())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Recording(o.optString("id"), o.optString("title"), o.optString("channel"), o.optString("file"), o.optLong("started"), o.optLong("ended"))
        }.sortedByDescending { it.started }
    }

    fun add(recording: Recording) {
        val all = list().filter { it.id != recording.id } + recording
        write(index, all.map { it.toJson() })
    }

    fun delete(id: String) {
        val item = list().firstOrNull { it.id == id } ?: return
        File(dir, item.fileName).delete()
        write(index, list().filter { it.id != id }.map { it.toJson() })
    }

    fun file(recording: Recording): File = File(dir, recording.fileName)

    fun schedule(): List<ScheduledRecording> {
        if (!scheduleFile.exists()) return emptyList()
        val arr = JSONArray(scheduleFile.readText())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ScheduledRecording(
                o.optString("id"), o.optString("channelId"), o.optString("channel"),
                o.optString("title"), o.optString("url"), o.optLong("start"), o.optLong("end")
            )
        }
    }

    fun addSchedule(item: ScheduledRecording) {
        val all = schedule().filter { it.id != item.id } + item
        write(scheduleFile, all.map { it.toJson() })
    }

    fun removeSchedule(id: String) {
        write(scheduleFile, schedule().filter { it.id != id }.map { it.toJson() })
    }

    private fun write(file: File, rows: List<JSONObject>) {
        val arr = JSONArray()
        rows.forEach { arr.put(it) }
        file.writeText(arr.toString())
    }

    private fun Recording.toJson() = JSONObject()
        .put("id", id).put("title", title).put("channel", channelName)
        .put("file", fileName).put("started", started).put("ended", ended)

    private fun ScheduledRecording.toJson() = JSONObject()
        .put("id", id).put("channelId", channelId).put("channel", channelName)
        .put("title", title).put("url", url).put("start", start).put("end", end)
}

/** A scheduled showing that should be recording at [nowMs] and is not already done. */
fun dueSchedule(items: List<ScheduledRecording>, nowMs: Long, finished: Set<String>): List<ScheduledRecording> =
    items.filter { it.id !in finished && nowMs >= it.start && nowMs < it.end }
