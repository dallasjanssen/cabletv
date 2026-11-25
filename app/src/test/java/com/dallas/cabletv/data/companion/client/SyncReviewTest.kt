package com.dallas.cabletv.data.companion.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncReviewTest {

    private fun obj(vararg pairs: Pair<String, SyncNode>) = SyncObj(pairs.toMap())
    private fun s(v: String) = SyncLeaf(v)
    private fun profile(name: String, hidden: String) = obj("name" to s(name), "hidden_filters" to s(hidden))

    private val copy = obj(
        "logins" to obj("xtream_server" to s("http://new"), "xtream_pass" to s("p1")),
        "channels" to obj(
            "hidden_filters" to s("UK"),
            "profiles" to SyncList(listOf(profile("Main", ""), profile("Kids", "NEWS")))
        ),
        "lineup" to obj("revision" to SyncLeaf(3), "openRouterKey" to s("k-copy"))
    )

    private val tv = obj(
        "logins" to obj("xtream_server" to s("http://old"), "xtream_pass" to s("p1")),
        "channels" to obj(
            "hidden_filters" to s("UK"),
            "profiles" to SyncList(listOf(profile("Main", "SPORT"), profile("Kids", "NEWS"), profile("Guest", "")))
        ),
        "lineup" to obj("revision" to SyncLeaf(9), "openRouterKey" to s("k-tv"))
    )

    private val conflicts = findConflicts(copy, tv)
    private fun ids() = conflicts.map { it.id }.toSet()

    @Test
    fun findsOnlyRealDifferences() {
        assertEquals(
            setOf("logins/xtream_server", "channels/profiles/@Main/hidden_filters", "lineup/openRouterKey"),
            ids()
        )
    }

    @Test
    fun revisionStampsAreNotConflicts() {
        assertFalse(ids().any { it.contains("revision") })
    }

    @Test
    fun profilesOnlyOnTheTvAreNotConflicts() {
        assertFalse(ids().any { it.contains("Guest") })
    }

    @Test
    fun sensitiveValuesAreHidden() {
        val key = conflicts.first { it.id == "lineup/openRouterKey" }
        assertTrue(key.sensitive)
        assertEquals("(hidden)", key.copyText)
        assertEquals("(hidden)", key.tvText)
        assertFalse(conflicts.first { it.id == "logins/xtream_server" }.sensitive)
    }

    @Test
    fun labelsNameTheProfile() {
        val main = conflicts.first { it.id == "channels/profiles/@Main/hidden_filters" }
        assertEquals("Hidden channels (Main)", main.label)
        assertEquals("(empty)", main.copyText)
        assertEquals("SPORT", main.tvText)
    }

    @Test
    fun mergeKeepsTheCopyUnlessTheTvIsChosen() {
        val keep = conflicts.filter { it.id == "logins/xtream_server" }.map { it.path }.toSet()
        val merged = mergeSync(copy, tv, keep) as SyncObj
        assertEquals(s("http://old"), getSyncAt(merged, listOf("logins", "xtream_server")))
        assertEquals(s("k-copy"), getSyncAt(merged, listOf("lineup", "openRouterKey")))
    }

    @Test
    fun mergeWithNoChoicesIsTheCopyUnchanged() {
        assertEquals(copy, mergeSync(copy, tv, emptySet()))
    }

    @Test
    fun keepingAProfileSettingPutsTheTvValueInTheRightProfile() {
        val keep = conflicts.filter { it.id.startsWith("channels/profiles/@Main") }.map { it.path }.toSet()
        val merged = mergeSync(copy, tv, keep)
        assertEquals(s("SPORT"), getSyncAt(merged, listOf("channels", "profiles", "@Main", "hidden_filters")))
        assertEquals(s("NEWS"), getSyncAt(merged, listOf("channels", "profiles", "@Kids", "hidden_filters")))
    }

    @Test
    fun identicalSetupsHaveNoConflicts() {
        assertTrue(findConflicts(copy, copy).isEmpty())
    }

    @Test
    fun favoritesLikeListsCompareAsOneValue() {
        val a = obj("favorites" to obj("profiles" to SyncList(listOf(obj("name" to s("Main"), "favorites" to SyncList(listOf(s("1"), s("2"))))))))
        val b = obj("favorites" to obj("profiles" to SyncList(listOf(obj("name" to s("Main"), "favorites" to SyncList(listOf(s("1"))))))))
        val found = findConflicts(a, b)
        assertEquals(listOf("favorites/profiles/@Main/favorites"), found.map { it.id })
        assertEquals("2 items", found[0].copyText)
        assertEquals("1 items", found[0].tvText)
    }
}
