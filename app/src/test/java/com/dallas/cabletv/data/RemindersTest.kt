package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemindersTest {
    private val now = 10_000_000L

    private fun r(i: Int, start: Long) = Reminder("c$i", "Channel $i", "Show $i", start)

    @Test
    fun oldRemindersArePruned() {
        val list = listOf(r(1, now - 11 * 60_000L), r(2, now - 5 * 60_000L), r(3, now + 1000))
        assertEquals(listOf("c2", "c3"), pruneReminders(list, now).map { it.channelId })
    }

    @Test
    fun fullListDropsOldestNotNew() {
        val list = (1..MAX_REMINDERS).map { r(it, now + it * 1000L) }
        val fresh = r(99, now + 999_000L)
        val (result, stored) = toggleReminderIn(list, fresh, now)
        assertTrue(stored)
        assertEquals(MAX_REMINDERS, result.size)
        assertTrue(result.any { it.channelId == "c99" })
        assertFalse(result.any { it.channelId == "c1" })
    }

    @Test
    fun pastRemindersDoNotBlockNewOnes() {
        val list = (1..MAX_REMINDERS).map { r(it, now - 3_600_000L - it) }
        val fresh = r(99, now + 60_000L)
        val (result, stored) = toggleReminderIn(list, fresh, now)
        assertTrue(stored)
        assertEquals(listOf("c99"), result.map { it.channelId })
    }

    @Test
    fun togglingTwiceRemoves() {
        val fresh = r(1, now + 60_000L)
        val (one, first) = toggleReminderIn(emptyList(), fresh, now)
        assertTrue(first)
        val (two, second) = toggleReminderIn(one, fresh, now)
        assertFalse(second)
        assertTrue(two.isEmpty())
    }

    @Test
    fun reminderLongPastIsNotStored() {
        val (result, stored) = toggleReminderIn(emptyList(), r(1, now - 3_600_000L), now)
        assertFalse(stored)
        assertTrue(result.isEmpty())
    }
}
