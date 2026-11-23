package com.dallas.cabletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelLoadTest {
    @Test
    fun unknownHostIsFriendly() {
        assertEquals("The server name could not be found.", friendlyError(java.net.UnknownHostException("x.example")))
    }

    @Test
    fun timeoutIsFriendly() {
        assertEquals("The server did not answer.", friendlyError(java.net.SocketTimeoutException("timeout")))
    }

    @Test
    fun connectFailureIsFriendly() {
        assertEquals("Could not connect to the server.", friendlyError(java.net.ConnectException("refused")))
    }

    @Test
    fun htmlInsteadOfJsonIsFriendly() {
        val e = org.json.JSONException("Value <html> of type java.lang.String cannot be converted to JSONArray")
        assertTrue(friendlyError(e).startsWith("The server sent a web page"))
    }

    @Test
    fun httpCodesAreFriendly() {
        assertEquals("The server refused the login.", friendlyError(IllegalStateException("HTTP 403")))
        assertEquals("The server address was not found.", friendlyError(IllegalStateException("Playlist returned HTTP 404")))
        assertEquals("The server had a problem. Try again later.", friendlyError(IllegalStateException("HTTP 502")))
    }

    @Test
    fun wrappedCauseIsFound() {
        assertEquals("The server did not answer.", friendlyError(RuntimeException("wrapped", java.net.SocketTimeoutException())))
    }

    @Test
    fun otherMessagesPassThrough() {
        assertEquals("Provider subscription has expired", friendlyError(IllegalStateException("Provider subscription has expired")))
    }

    @Test
    fun noSourcesGivesAnEmptyReport() {
        val report = ChannelSource.loadReport(Settings())
        assertTrue(report.channels.isEmpty())
        assertTrue(report.problems.isEmpty())
    }
}
