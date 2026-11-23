package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class EpgSaveTest {
    private val hour = 3_600_000L
    private val now = 1_700_000_000_000L

    private fun save(key: String, programs: Map<String, List<Program>>, aliases: Map<String, String> = emptyMap()): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { writeSavedGuide(it, key, now - hour / 2, programs, aliases) }
        return bytes.toByteArray()
    }

    private fun read(bytes: ByteArray, key: String): SavedGuide? =
        DataInputStream(ByteArrayInputStream(bytes)).use { readSavedGuide(it, key, now) }

    @Test
    fun listingsComeBackAsSaved() {
        val programs = mapOf(
            "bbc1.uk" to listOf(Program("News", now - hour, now, "The day's news"), Program("Film", now, now + 2 * hour, "")),
            "itv.uk" to listOf(Program("Quiz", now, now + hour))
        )
        val saved = read(save("k", programs, mapOf("bbcone" to "bbc1.uk")), "k")
        assertNotNull(saved)
        assertEquals(programs, saved!!.programs)
        assertEquals(mapOf("bbcone" to "bbc1.uk"), saved.aliases)
        assertEquals(now - hour / 2, saved.savedAt)
    }

    @Test
    fun aCopyForOtherLoginsIsIgnored() {
        val bytes = save("old", mapOf("a" to listOf(Program("A", now, now + hour))))
        assertNull(read(bytes, "new"))
    }

    @Test
    fun programmesOlderThanTheWindowAreDropped() {
        val tooOld = Program("Old", now - EpgCache.BEHIND_MS - 2 * hour, now - EpgCache.BEHIND_MS - hour)
        val kept = Program("Kept", now - hour, now + hour)
        val saved = read(save("k", mapOf("a" to listOf(tooOld, kept), "gone" to listOf(tooOld)), mapOf("g" to "gone")), "k")!!
        assertEquals(mapOf("a" to listOf(kept)), saved.programs)
        // An alias for a channel with nothing left is dropped too.
        assertTrue(saved.aliases.isEmpty())
    }

    @Test
    fun theKeyDependsOnTheLoginsAndHidesThem() {
        val a = Settings(xtreamServer = "http://panel", xtreamUser = "bob", xtreamPass = "secret")
        val key = guideCacheKey(a)
        assertEquals(key, guideCacheKey(a.copy(jellyfinServer = "http://jf")))
        assertNotEquals(key, guideCacheKey(a.copy(xtreamPass = "other")))
        assertNotEquals(key, guideCacheKey(a.copy(epgUrl = "http://guide/epg.xml")))
        assertFalse(key.contains("secret"))
        assertFalse(key.contains("bob"))
        assertEquals(64, key.length)
    }
}
