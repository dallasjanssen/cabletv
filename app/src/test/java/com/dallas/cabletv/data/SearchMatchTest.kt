package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatchTest {

    @Test
    fun accentsAreIgnored() {
        assertTrue(SearchMatcher("tele").matches("Télé 7"))
        assertTrue(SearchMatcher("télé").matches("Tele Matin"))
        assertTrue(SearchMatcher("cafe").matches("Café Society"))
    }

    @Test
    fun punctuationAndSpacesAreIgnored() {
        assertTrue(SearchMatcher("ae").matches("A&E"))
        assertTrue(SearchMatcher("a&e").matches("A & E HD"))
        assertTrue(SearchMatcher("spiderman").matches("Spider-Man: Homecoming"))
    }

    @Test
    fun caseIsIgnored() {
        assertTrue(SearchMatcher("NEWS").matches("Evening news"))
    }

    @Test
    fun differentTextDoesNotMatch() {
        assertFalse(SearchMatcher("sport").matches("Weather"))
    }

    @Test
    fun queryWithoutLettersMatchesNothing() {
        val matcher = SearchMatcher("&&")
        assertEquals("", matcher.folded)
        assertFalse(matcher.matches("A&E"))
    }

    @Test
    fun foldKeepsLettersAndDigitsOnly() {
        assertEquals("tele7", foldForSearch("Télé 7"))
        assertEquals("ae", foldForSearch("A&E"))
        assertTrue(SearchMatcher("tele").matchesFolded(foldForSearch("TÉLÉ 7")))
    }

    @Test
    fun programmeSearchIgnoresAccents() {
        val schedule = mapOf("fr" to listOf(Program("Télé Matin", 1_000L, 2_000L)))
        assertEquals("Télé Matin", matchProgrammes(schedule, "tele", nowMs = 0L).single().second.title)
    }
}
