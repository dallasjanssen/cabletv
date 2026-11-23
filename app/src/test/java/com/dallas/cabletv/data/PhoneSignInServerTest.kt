package com.dallas.cabletv.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PhoneSignInServerTest {
    private var server: PhoneSignInServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    /** Sends raw text and returns the status line of the reply. */
    private fun status(port: Int, request: String): String {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            s.getOutputStream().flush()
            return s.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine() ?: ""
        }
    }

    private fun post(port: Int, body: String) =
        status(port, "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: ${body.length}\r\n\r\n$body")

    @Test
    fun hugeContentLengthIsRefused() {
        val s = PhoneSignInServer("123456", onReceive = {})
        server = s
        val port = s.start(0)
        val reply = status(port, "POST / HTTP/1.1\r\nContent-Length: 2000000000\r\n\r\n")
        assertTrue(reply, reply.contains("413"))
    }

    @Test
    fun negativeContentLengthIsRefused() {
        val s = PhoneSignInServer("123456", onReceive = {})
        server = s
        val port = s.start(0)
        val reply = status(port, "POST / HTTP/1.1\r\nContent-Length: -5\r\n\r\n")
        assertTrue(reply, reply.contains("400"))
    }

    @Test
    fun rightCodeDeliversDetails() {
        val got = CountDownLatch(1)
        var details: Map<String, String> = emptyMap()
        val s = PhoneSignInServer("123456", onReceive = { details = it; got.countDown() })
        server = s
        val port = s.start(0)
        val reply = post(port, "pin=123456&xs=http%3A%2F%2Fhost&xu=bob")
        assertTrue(reply, reply.contains("200"))
        assertTrue(got.await(5, TimeUnit.SECONDS))
        assertEquals("http://host", details["xs"])
        assertEquals("bob", details["xu"])
    }

    @Test
    fun wrongCodesLockTheServer() {
        val ended = CountDownLatch(1)
        var reason: SignInEnd? = null
        val s = PhoneSignInServer("123456", onReceive = {}, onEnd = { reason = it; ended.countDown() }, maxWrongCodes = 3)
        server = s
        val port = s.start(0)
        repeat(3) { post(port, "pin=000000") }
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertEquals(SignInEnd.TooManyWrongCodes, reason)
    }

    @Test
    fun serverExpires() {
        val ended = CountDownLatch(1)
        var reason: SignInEnd? = null
        val s = PhoneSignInServer("123456", onReceive = {}, onEnd = { reason = it; ended.countDown() }, lifetimeMs = 300L)
        server = s
        s.start(0)
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertEquals(SignInEnd.Expired, reason)
    }
}
