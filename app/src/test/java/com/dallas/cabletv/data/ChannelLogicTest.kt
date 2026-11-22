package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelLogicTest {

    private val channels = listOf(
        LiveChannel("1", 2, "CNN", null, "News", "u1", null),
        LiveChannel("2", 515, "Food HD", null, "Cooking", "u2", null),
        LiveChannel("3", 516, "MPTV", null, "Cooking", "u3", null),
        LiveChannel("4", 9, "ESPN", null, "Sports", "u4", null)
    )

    @Test
    fun groupsStartWithAllAndIncludeFavoritesWhenSet() {
        assertEquals(listOf("All", "Cooking", "News", "Sports"), ChannelLogic.groups(channels, emptySet()))
        assertEquals(
            listOf("All", "Favorites", "Cooking", "News", "Sports"),
            ChannelLogic.groups(channels, setOf("1"))
        )
    }

    @Test
    fun filtersByGroup() {
        val out = ChannelLogic.filter(channels, "Cooking", emptySet(), "")
        assertEquals(listOf("Food HD", "MPTV"), out.map { it.name })
    }

    @Test
    fun filtersByFavorites() {
        val out = ChannelLogic.filter(channels, ChannelLogic.FAVORITES_GROUP, setOf("4"), "")
        assertEquals(listOf("ESPN"), out.map { it.name })
    }

    @Test
    fun searchMatchesNameOrNumberCaseInsensitively() {
        assertEquals(listOf("Food HD"), ChannelLogic.filter(channels, "All", emptySet(), "food").map { it.name })
        assertEquals(listOf("ESPN"), ChannelLogic.filter(channels, "All", emptySet(), "9").map { it.name })
    }

    @Test
    fun stepWrapsAtBothEnds() {
        assertEquals(0, ChannelLogic.step(3, 1, 4))
        assertEquals(3, ChannelLogic.step(0, -1, 4))
        assertEquals(2, ChannelLogic.step(1, 1, 4))
        assertEquals(0, ChannelLogic.step(0, 1, 0))
    }

    @Test
    fun typedNumbersFindChannels() {
        assertEquals(1, ChannelLogic.indexOfNumber(channels, 515))
        assertEquals(-1, ChannelLogic.indexOfNumber(channels, 999))
        assertEquals(515, ChannelLogic.parseTypedNumber("515"))
        assertNull(ChannelLogic.parseTypedNumber(""))
        assertNull(ChannelLogic.parseTypedNumber("12345"))
    }

    @Test
    fun floorsToHalfHour() {
        val base = 1_800_000_000_000L - (1_800_000_000_000L % ChannelLogic.SLOT_MS)
        assertEquals(base, ChannelLogic.floorToSlot(base + 17 * 60_000L))
        assertEquals(base, ChannelLogic.floorToSlot(base))
    }

    @Test
    fun progressIsClampedBetweenZeroAndOne() {
        val p = Program("x", 1000L, 2000L)
        assertEquals(0f, ChannelLogic.progress(p, 0L), 0.0001f)
        assertEquals(0.5f, ChannelLogic.progress(p, 1500L), 0.0001f)
        assertEquals(1f, ChannelLogic.progress(p, 9999L), 0.0001f)
    }
}
