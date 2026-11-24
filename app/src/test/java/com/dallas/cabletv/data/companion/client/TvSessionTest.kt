package com.dallas.cabletv.data.companion.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSessionTest {
    private val fingerprint = ByteArray(32) { it.toByte() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val servers = mutableListOf<FakeTv>()

    @After
    fun tearDown() {
        scope.cancel()
        servers.forEach { it.close() }
    }

    private fun tvServer(paired: Boolean = true): FakeTv = FakeTv { m, reply ->
        when (m.optString("type")) {
            "hello" -> reply(FakeTv.answer(m, "welcome").put("paired", paired).put("tv_id", "t").put("tv_name", "Den TV"))
            "get_state" -> reply(
                FakeTv.answer(m, "state").put("screen", "live").put("state", "playing").put("title", "News")
            )
            "get_favorites" -> reply(FakeTv.answer(m, "favorites").put("favorites", org.json.JSONArray()))
            else -> reply(FakeTv.answer(m, "ok"))
        }
    }.also { servers.add(it) }

    private fun session(server: FakeTv, fp: ByteArray = fingerprint) = TvSession(
        PairedTv("t", "Den TV", com.dallas.cabletv.data.companion.CompanionProtocol.toHex(fp), "127.0.0.1", server.port),
        server.connector(fingerprint), "Pixel", 1, scope
    )

    private fun blocking(block: suspend () -> Unit) {
        runBlocking { withTimeout(30_000) { block() } }
    }

    @Test
    fun goesOnlineAndKnowsWhatIsPlaying() = blocking {
        val s = session(tvServer())
        s.start()
        val online = s.status.first { it is SessionStatus.Online } as SessionStatus.Online
        assertEquals("Den TV", online.welcome.tvName)
        assertEquals("News", s.state.first { it != null }!!.title)
        assertEquals("ok", s.request(CompanionRequests.key("BACK")).getString("type"))
        s.stop()
    }

    @Test
    fun aChangedCertificateStopsTheSessionWithTheIdentityMessage() = blocking {
        val s = session(tvServer(), fp = ByteArray(32) { 5 })
        s.start()
        val status = s.status.first { it is SessionStatus.IdentityChanged } as SessionStatus.IdentityChanged
        assertEquals("This TV's identity changed. Pair it again.", status.message)
        s.stop()
    }

    @Test
    fun aTvThatForgotThePhoneIsReportedAsNotPaired() = blocking {
        val s = session(tvServer(paired = false))
        s.start()
        s.status.first { it == SessionStatus.NotPaired }
        s.stop()
    }

    @Test
    fun anUnreachableTvShowsOfflineAndRequestsExplainWhy() = blocking {
        val server = tvServer()
        val s = session(server)
        server.close()
        s.start()
        val offline = s.status.first { it is SessionStatus.Offline } as SessionStatus.Offline
        assertEquals("Can't reach Den TV. Is Cable TV open on it?", offline.message)
        val failure = runCatching { s.request(CompanionRequests.getState()) }.exceptionOrNull() as CompanionException
        assertEquals("Can't reach Den TV. Is Cable TV open on it?", failure.message)
        s.stop()
    }

    @Test
    fun reconnectsAfterTheConnectionDrops() = blocking {
        val server = tvServer()
        val s = session(server)
        s.start()
        s.status.first { it is SessionStatus.Online }
        server.dropConnections()
        s.status.first { it is SessionStatus.Offline }
        // The server is still listening, so the next try (after the first backoff) goes online again.
        withTimeout(10_000) { s.status.first { it is SessionStatus.Online } }
        assertTrue(server.received.count { it.optString("type") == "hello" } >= 2)
        s.stop()
    }

    @Test
    fun stopClosesTheConnectionAndStopsRetrying() = blocking {
        val server = tvServer()
        val s = session(server)
        s.start()
        s.status.first { it is SessionStatus.Online }
        s.stop()
        delay(300)
        val hellos = server.received.count { it.optString("type") == "hello" }
        delay(1_500)
        assertEquals(hellos, server.received.count { it.optString("type") == "hello" })
        assertTrue(runCatching { s.request(JSONObject().put("type", "ping")) }.isFailure)
    }
}
