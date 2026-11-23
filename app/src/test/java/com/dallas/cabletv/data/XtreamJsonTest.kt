package com.dallas.cabletv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class XtreamJsonTest {
    @Test
    fun strReturnsNullForMissingNullBlankAndTheWordNull() {
        val o = JSONObject("""{"a":null,"b":"  ","c":"null","d":"x","e":12}""")
        assertNull(o.str("a"))
        assertNull(o.str("b"))
        assertNull(o.str("c"))
        assertNull(o.str("missing"))
        assertEquals("x", o.str("d"))
        assertEquals("12", o.str("e"))
    }

    @Test
    fun plainTitlesAreNotDecoded() {
        assertEquals("News", decodeMaybeBase64("News"))
        assertEquals("Test", decodeMaybeBase64("Test"))
        assertEquals("The Evening Show", decodeMaybeBase64("The Evening Show"))
    }

    @Test
    fun realBase64IsDecoded() {
        assertEquals("Evening News", decodeMaybeBase64("RXZlbmluZyBOZXdz"))
    }

    @Test
    fun catchupStampUsesAsciiDigitsInAnyLocale() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar", "EG"))
            assertEquals("2023-11-14:22-13", formatCatchupStart(1_700_000_000_000L, "UTC"))
        } finally {
            Locale.setDefault(old)
        }
    }
}
