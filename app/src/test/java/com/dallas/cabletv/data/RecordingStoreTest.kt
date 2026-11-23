package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecordingStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun rec(id: String, file: String = "$id.ts", started: Long = 1000L) =
        Recording(id, "Title $id", "Channel", file, started, started + 500)

    @Test
    fun addListAndDelete() {
        val store = RecordingStore(tmp.root)
        File(store.directory(), "a.ts").writeText("x")
        store.add(rec("a"))
        store.add(rec("b", started = 2000L))
        assertEquals(listOf("b", "a"), store.list().map { it.id })
        store.delete("a")
        assertEquals(listOf("b"), store.list().map { it.id })
        assertFalse(File(store.directory(), "a.ts").exists())
    }

    @Test
    fun corruptIndexReadsEmptyAndIsRenamed() {
        val store = RecordingStore(tmp.root)
        val index = File(store.directory(), "index.json")
        index.writeText("[{\"id\":\"a\",\"tit")
        assertTrue(store.list().isEmpty())
        assertTrue(File(store.directory(), "index.json.bad").exists())
        store.add(rec("c"))
        assertEquals(listOf("c"), store.list().map { it.id })
    }

    @Test
    fun corruptScheduleReadsEmpty() {
        val store = RecordingStore(tmp.root)
        File(store.directory(), "schedule.json").writeText("not json")
        assertTrue(store.schedule(0L).isEmpty())
    }

    @Test
    fun pastScheduleEntriesAreDropped() {
        val store = RecordingStore(tmp.root)
        store.addSchedule(ScheduledRecording("old", "c", "C", "Old", "u", 0L, 5_000L))
        store.addSchedule(ScheduledRecording("new", "c", "C", "New", "u", 9_000L, 20_000L))
        assertEquals(listOf("new"), store.schedule(10_000L).map { it.id })
    }

    @Test
    fun writeLeavesNoTempFile() {
        val store = RecordingStore(tmp.root)
        store.add(rec("a"))
        assertFalse(File(store.directory(), "index.json.tmp").exists())
    }

    @Test
    fun orphansAreDeletedButIndexedAndActiveFilesStay() {
        val store = RecordingStore(tmp.root)
        val dir = store.directory()
        File(dir, "kept.ts").writeText("x")
        File(dir, "orphan.ts").writeText("x")
        File(dir, "active.ts").writeText("x")
        store.add(rec("kept"))
        store.deleteOrphans("active.ts")
        assertTrue(File(dir, "kept.ts").exists())
        assertTrue(File(dir, "active.ts").exists())
        assertFalse(File(dir, "orphan.ts").exists())
    }
}
