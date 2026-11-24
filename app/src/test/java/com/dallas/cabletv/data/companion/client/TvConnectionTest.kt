package com.dallas.cabletv.data.companion.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TvConnectionTest {
    private val fingerprint = ByteArray(32) { it.toByte() }
    private val servers = mutableListOf<FakeTv>()

    @After
    fun tearDown() {
        servers.forEach { it.close() }
    }

    private fun tv(server: FakeTv): FakeTv = server.also { servers.add(it) }

    private fun connection(
        server: FakeTv,
        expected: ByteArray? = fingerprint,
        timeoutMs: Long = 5_000,
        pingMs: Long = 60_000
    ) = TvConnection(
        host = "127.0.0.1", port = server.port, tvName = "Living Room TV", expectedFingerprint = expected,
        connector = server.connector(fingerprint), phoneName = "Pixel", appBuild = 3,
        requestTimeoutMs = timeoutMs, pingIntervalMs = pingMs
    )

    private fun blocking(block: suspend CoroutineScope.() -> Unit) {
        runBlocking { withTimeout(20_000) { block() } }
    }

    @Test
    fun helloGetsAWelcomeAndRequestsGetTheirOwnReplies() = blocking {
        val server = tv(FakeTv.welcoming())
        val c = connection(server)
        val welcome = c.open()
        assertTrue(welcome.paired)
        assertEquals("Living Room TV", welcome.tvName)
        assertEquals("tv-1", welcome.tvId)
        val first = server.received.first()
        assertEquals("hello", first.getString("type"))
        assertEquals("Pixel", first.getString("phone_name"))
        assertEquals(3, first.getInt("app_build"))
        assertEquals(1, first.getInt("id"))

        val reply = c.request(CompanionRequests.key("BACK"))
        assertEquals("ok", reply.getString("type"))
        assertEquals(2, reply.getInt("re"))
        c.close()
    }

    @Test
    fun repliesAreMatchedEvenWhenTheyArriveOutOfOrder() = blocking {
        val held = mutableListOf<JSONObject>()
        val server = tv(FakeTv { m, reply ->
            when (m.optString("type")) {
                "hello" -> reply(FakeTv.answer(m, "welcome").put("paired", true).put("tv_id", "t").put("tv_name", "T"))
                "slow" -> held.add(m)
                "fast" -> {
                    reply(FakeTv.answer(m, "ok").put("which", "fast"))
                    synchronized(held) { held.forEach { reply(FakeTv.answer(it, "ok").put("which", "slow")) } }
                }
            }
        })
        val c = connection(server)
        c.open()
        val slow = async(Dispatchers.Default) { c.request(JSONObject().put("type", "slow")) }
        while (held.isEmpty()) delay(5)
        val fast = c.request(JSONObject().put("type", "fast"))
        assertEquals("fast", fast.getString("which"))
        assertEquals("slow", slow.await().getString("which"))
        c.close()
    }

    @Test
    fun stateMessagesFromTheTvArriveOnTheFlow() = blocking {
        val server = tv(FakeTv { m, reply ->
            when (m.optString("type")) {
                "hello" -> {
                    reply(FakeTv.answer(m, "welcome").put("paired", true).put("tv_id", "t").put("tv_name", "T"))
                    reply(JSONObject().put("type", "state").put("screen", "live").put("state", "playing").put("title", "News"))
                }
            }
        })
        val c = connection(server)
        c.open()
        val state = c.states.first()
        assertEquals("News", state.title)
        assertEquals("live", state.screen)
        c.close()
    }

    @Test
    fun errorRepliesBecomeExceptionsWithTheirCode() = blocking {
        val server = tv(FakeTv { m, reply ->
            when (m.optString("type")) {
                "hello" -> reply(FakeTv.answer(m, "welcome").put("paired", true).put("tv_id", "t").put("tv_name", "T"))
                else -> reply(FakeTv.answer(m, "error").put("code", "pin_required").put("message", "Enter the PIN."))
            }
        })
        val c = connection(server)
        c.open()
        try {
            c.request(CompanionRequests.getSettings(null))
            fail("should have failed")
        } catch (e: CompanionException) {
            assertEquals(CompanionException.Kind.REMOTE, e.kind)
            assertEquals("pin_required", e.code)
            assertEquals("Enter the PIN.", e.message)
        }
        c.close()
    }

    @Test
    fun aDifferentCertificateIsRefusedBeforeAnythingIsSent() = blocking {
        val server = tv(FakeTv.welcoming())
        val other = ByteArray(32) { 9 }
        val c = connection(server, expected = other)
        try {
            c.open()
            fail("should have been refused")
        } catch (e: CompanionException) {
            assertEquals(CompanionException.Kind.IDENTITY_CHANGED, e.kind)
            assertEquals("This TV's identity changed. Pair it again.", e.message)
        }
        delay(150)
        assertTrue(server.received.isEmpty())
    }

    @Test
    fun noExpectedFingerprintMeansPairingMayLookAtTheCertificateItself() = blocking {
        val server = tv(FakeTv.welcoming(paired = false))
        val c = connection(server, expected = null)
        val welcome = c.open()
        assertEquals(false, welcome.paired)
        assertEquals(fingerprint.toList(), c.tvFingerprint()!!.toList())
        c.close()
    }

    @Test
    fun unreachableTvGivesThePlainMessage() = blocking {
        val server = FakeTv.welcoming()
        val connector = server.connector(fingerprint)
        val port = server.port
        server.close()
        val c = TvConnection("127.0.0.1", port, "Living Room TV", fingerprint, connector, "Pixel", 1)
        try {
            c.open()
            fail("should not connect")
        } catch (e: CompanionException) {
            assertEquals(CompanionException.Kind.UNREACHABLE, e.kind)
            assertEquals("Can't reach Living Room TV. Is Cable TV open on it?", e.message)
        }
    }

    @Test
    fun aTvThatNeverAnswersTimesOutAndLateRepliesAreIgnored() = blocking {
        val server = tv(FakeTv { m, reply ->
            if (m.optString("type") == "hello") {
                reply(FakeTv.answer(m, "welcome").put("paired", true).put("tv_id", "t").put("tv_name", "T"))
            }
        })
        val c = connection(server, timeoutMs = 200)
        c.open()
        try {
            c.request(CompanionRequests.getState())
            fail("should time out")
        } catch (e: CompanionException) {
            assertEquals(CompanionException.Kind.TIMEOUT, e.kind)
            assertTrue(e.message!!.contains("Is Cable TV open on it?"))
        }
        c.close()
    }

    @Test
    fun whenTheTvDropsTheConnectionWaitingRequestsFailAndTheReasonIsReported() = blocking {
        val server = tv(FakeTv { m, reply ->
            when (m.optString("type")) {
                "hello" -> reply(FakeTv.answer(m, "welcome").put("paired", true).put("tv_id", "t").put("tv_name", "T"))
                "state_please" -> Unit
            }
        })
        val c = connection(server)
        c.open()
        val waiting = async(Dispatchers.Default) {
            runCatching { c.request(JSONObject().put("type", "state_please")) }
        }
        while (server.received.size < 2) delay(5)
        server.dropConnections()
        val outcome = waiting.await()
        assertTrue(outcome.exceptionOrNull() is CompanionException)
        val reason = c.closedReason.filterNotNull().first()
        assertEquals(CompanionException.Kind.UNREACHABLE, reason.kind)
        try {
            c.request(CompanionRequests.getState())
            fail("a closed connection cannot send")
        } catch (e: CompanionException) {
            assertEquals(CompanionException.Kind.UNREACHABLE, e.kind)
        }
    }

    @Test
    fun pingsGoOutOnTheInterval() = blocking {
        val server = tv(FakeTv.welcoming())
        val c = connection(server, pingMs = 50)
        c.open()
        val deadline = System.currentTimeMillis() + 5_000
        while (server.received.count { it.optString("type") == "ping" } < 2 && System.currentTimeMillis() < deadline) {
            delay(20)
        }
        assertTrue(server.received.count { it.optString("type") == "ping" } >= 2)
        c.close()
    }

    @Test
    fun closeEndsThingsCleanly() = blocking {
        val server = tv(FakeTv.welcoming())
        val c = connection(server)
        c.open()
        c.close()
        assertEquals(CompanionException.Kind.CLOSED, c.closedReason.value!!.kind)
    }
}
