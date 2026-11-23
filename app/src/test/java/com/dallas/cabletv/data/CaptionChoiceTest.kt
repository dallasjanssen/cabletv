package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionChoiceTest {
    @Test
    fun offIsBlank() {
        assertEquals("", storedCaptionChoice(enabled = false, language = "en"))
        assertEquals("", storedCaptionChoice(enabled = false, language = null))
    }

    @Test
    fun aLanguageIsKept() {
        assertEquals("en", storedCaptionChoice(enabled = true, language = "en"))
        assertEquals("en", storedCaptionChoice(enabled = true, language = " en "))
    }

    @Test
    fun aTrackWithNoLanguageStillTurnsCaptionsOn() {
        assertEquals(CAPTIONS_ON, storedCaptionChoice(enabled = true, language = null))
        assertEquals(CAPTIONS_ON, storedCaptionChoice(enabled = true, language = ""))
        assertEquals(CAPTIONS_ON, storedCaptionChoice(enabled = true, language = "und"))
    }
}
