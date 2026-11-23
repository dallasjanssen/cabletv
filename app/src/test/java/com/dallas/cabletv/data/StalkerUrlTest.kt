package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StalkerUrlTest {
    @Test
    fun bareHostGetsPortalPhp() {
        assertEquals("http://host/portal.php", stalkerApiUrl("http://host"))
        assertEquals("http://host:8080/portal.php", stalkerApiUrl(" http://host:8080/ "))
    }

    @Test
    fun webPlayerPathIsReduced() {
        assertEquals("http://host/portal.php", stalkerApiUrl("http://host/c"))
        assertEquals("http://host/portal.php", stalkerApiUrl("http://host/c/"))
    }

    @Test
    fun stalkerPortalPathUsesLoadPhp() {
        assertEquals("http://host/stalker_portal/server/load.php", stalkerApiUrl("http://host/stalker_portal/c/"))
    }

    @Test
    fun phpEndpointsAreKept() {
        assertEquals("http://host/portal.php", stalkerApiUrl("http://host/portal.php"))
        assertEquals("http://host/stalker_portal/server/load.php", stalkerApiUrl("http://host/stalker_portal/server/load.php/"))
    }
}
