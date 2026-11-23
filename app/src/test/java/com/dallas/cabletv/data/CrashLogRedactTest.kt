package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogRedactTest {
    @Test
    fun xtreamPathCredentialsAreHidden() {
        val out = CrashLog.redact("Failed http://h:80/movie/bob/secret/12.mp4 and http://h/live/bob/secret/5.ts")
        assertFalse(out.contains("bob"))
        assertFalse(out.contains("secret"))
        assertTrue(out.contains("/movie/***/***/12.mp4"))
    }

    @Test
    fun queryValuesAreHidden() {
        val out = CrashLog.redact("GET http://h/get.php?username=bob&password=pw&type=m3u&mac=00:1A:79:00:00:00&token=abc&api_key=k1 failed")
        assertEquals("GET http://h/get.php?username=***&password=***&type=m3u&mac=***&token=***&api_key=*** failed", out)
    }

    @Test
    fun authorizationHeaderIsHidden() {
        val out = CrashLog.redact("headers\nAuthorization: Bearer abc123\nnext")
        assertFalse(out.contains("abc123"))
        assertTrue(out.contains("next"))
    }

    @Test
    fun plainTextIsUntouched() {
        val text = "java.lang.IllegalStateException: boom at com.dallas.cabletv.Foo.bar(Foo.kt:12)"
        assertEquals(text, CrashLog.redact(text))
    }
}
