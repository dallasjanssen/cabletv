package com.dallas.cabletv.data.companion.server

import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.Program
import com.dallas.cabletv.data.VodItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseJsonTest {
    private val hour = 3_600_000L
    private val now = 10 * hour

    private fun program(title: String, startHour: Double, endHour: Double) =
        Program(title, (startHour * hour).toLong(), (endHour * hour).toLong())

    private fun channel(id: String, name: String) =
        LiveChannel(id = id, number = 1, name = name, logo = null, group = "", streamUrl = "", epgId = null)

    private fun vod(name: String) =
        VodItem(id = name, name = name, year = null, overview = null, posterUrl = "", streamUrl = "", type = "Movie")

    @Test
    fun scheduleKeepsWhatIsOnAndWhatFollowsInsideTheWindow() {
        val programs = listOf(
            program("Ended", 8.0, 9.0),
            program("On now", 9.5, 10.5),
            program("Later", 11.0, 12.0),
            program("Too late", 30.0, 31.0)
        )
        assertEquals(listOf("On now", "Later"), BrowseJson.scheduleWindow(programs, now).map { it.title })
    }

    @Test
    fun programJsonMarksWhatIsOnNow() {
        assertTrue(BrowseJson.program(program("On now", 9.5, 10.5), now).getBoolean("on_now"))
        assertFalse(BrowseJson.program(program("Later", 11.0, 12.0), now).getBoolean("on_now"))
    }

    @Test
    fun channelSearchMatchesNameWithoutCaseOrPunctuation() {
        val channels = listOf(channel("1", "ESPN HD"), channel("2", "BBC One"))
        assertEquals(listOf("1"), BrowseJson.matchingChannels(channels, "espn").map { it.id })
        assertEquals(listOf("2"), BrowseJson.matchingChannels(channels, "bbc-one").map { it.id })
    }

    @Test
    fun libraryPagesAreSortedByNameAndHeldToSaneBounds() {
        val items = listOf(vod("b"), vod("a"), vod("c"))
        assertEquals(listOf("b"), BrowseJson.libraryPage(items, offset = 1, limit = 1).map { it.name })
        assertEquals(listOf("a"), BrowseJson.libraryPage(items, offset = -5, limit = 0).map { it.name })
    }
}
