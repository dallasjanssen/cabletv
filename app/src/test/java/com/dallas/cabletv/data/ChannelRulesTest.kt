package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelRulesTest {

    private fun ch(name: String, group: String = "News") =
        LiveChannel(id = "id-$name", number = 1, name = name, logo = null, group = group, streamUrl = "u", epgId = null)

    @Test
    fun prefixHidesOnlyAtAWordBoundary() {
        assertTrue(isHidden(ch("UK| BBC One"), listOf("UK")))
        assertFalse(isHidden(ch("UKRAINE NEWS"), listOf("UK")))
    }

    @Test
    fun categoryMatchesExactly() {
        assertTrue(isHidden(ch("CNN", "Adult"), listOf("ADULT")))
        assertFalse(isHidden(ch("CNN", "Adults"), listOf("ADULT")))
    }

    @Test
    fun blankCategoryCountsAsOther() {
        assertTrue(isHidden(ch("Mystery", ""), listOf("OTHER")))
    }

    @Test
    fun nothingHiddenWithNoTerms() {
        assertFalse(isHidden(ch("UK| BBC One"), emptyList()))
    }

    @Test
    fun countryPrefixAndDisplayName() {
        assertEquals("US", countryPrefixOf("us: CNN"))
        assertEquals("CNN", displayName("US: CNN"))
        assertEquals("Sky News", displayName("Sky News"))
        assertNull(countryPrefixOf("Sky News"))
    }

    @Test
    fun prefixesNeedTwoChannelsToBeOffered() {
        val list = listOf(ch("UK| A"), ch("UK| B"), ch("FR| C"))
        val (prefixes, _) = channelFilters(list)
        assertEquals(listOf("UK"), prefixes.map { it.term })
        assertEquals(2, prefixes.first().count)
    }
}
