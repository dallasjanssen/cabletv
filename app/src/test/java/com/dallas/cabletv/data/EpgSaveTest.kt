package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EpgSaveTest {

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
