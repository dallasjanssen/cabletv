package com.dallas.cabletv.data.companion.client

import com.dallas.cabletv.data.companion.CompanionProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TvPairingTest {
    private val tvFingerprint = ByteArray(32) { (it + 1).toByte() }
    private val phoneFingerprint = ByteArray(32) { (it + 100).toByte() }
    private val code = "482913"
    private var wrongLeft = 4
    private var expired = false
    private val servers = mutableListOf<FakeTv>()

    @After
    fun tearDown() {
        servers.forEach { it.close() }
    }

    /** A TV that checks the proof the way the real one does. */
    private fun pairingTv(): FakeTv = FakeTv { m, reply ->
        when (m.optString("type")) {
            "hello" -> reply(
                FakeTv.answer(m, "welcome").put("paired", false).put("tv_id", "tv-9").put("tv_name", "Den TV")
            )
            "pair_start" -> reply(FakeTv.answer(m, "ok"))
            "pair_finish" -> {
                val expected = CompanionProtocol.pairingProof(code, tvFingerprint, phoneFingerprint)
                when {
                    expired -> reply(FakeTv.answer(m, "error").put("code", "code_expired").put("message", "Expired."))
                    CompanionProtocol.proofMatches(expected, m.optString("proof")) ->
                        reply(FakeTv.answer(m, "paired").put("tv_name", "Den TV").put("tv_id", "tv-9"))
                    else -> {
                        wrongLeft -= 1
                        reply(FakeTv.answer(m, "error").put("code", "wrong_code").put("message", "No.").put("attempts_left", wrongLeft))
                    }
                }
            }
        }
    }.also { servers.add(it) }

    private fun blocking(block: suspend CoroutineScope.() -> Unit) {
        runBlocking { withTimeout(20_000) { block() } }
    }

    @Test
    fun rightCodePairsAndGivesTheTvToStore() = blocking {
        val server = pairingTv()
        val pairing = TvPairing(server.connector(tvFingerprint), "Pixel 10", 1, phoneFingerprint)
        val target = pairing.start("127.0.0.1", server.port, "TV")
        assertEquals(PairingTarget("tv-9", "Den TV"), target)
        assertEquals("Pixel 10", server.received.first { it.optString("type") == "pair_start" }.getString("phone_name"))

        val tv = pairing.finish(" $code ")
        assertEquals("tv-9", tv.id)
        assertEquals("Den TV", tv.name)
        assertEquals(CompanionProtocol.toHex(tvFingerprint), tv.fingerprint)
        assertEquals("127.0.0.1", tv.host)
        assertEquals(server.port, tv.port)
        assertFalse(tv.atvPaired)
        pairing.close()
    }

    @Test
    fun wrongCodeReportsTriesLeftAndAllowsAnotherTry() = blocking {
        val server = pairingTv()
        val pairing = TvPairing(server.connector(tvFingerprint), "Pixel", 1, phoneFingerprint)
        pairing.start("127.0.0.1", server.port, "TV")
        try {
            pairing.finish("000000")
            fail("wrong code")
        } catch (e: CompanionException) {
            assertEquals("wrong_code", e.code)
            assertEquals(3, e.attemptsLeft)
            assertEquals("That code did not match. 3 tries left.", pairingErrorText(e))
        }
        assertEquals("tv-9", pairing.finish(code).id)
        pairing.close()
    }

    @Test
    fun expiredCodeIsReportedPlainly() = blocking {
        val server = pairingTv()
        val pairing = TvPairing(server.connector(tvFingerprint), "Pixel", 1, phoneFingerprint)
        pairing.start("127.0.0.1", server.port, "TV")
        expired = true
        try {
            pairing.finish(code)
            fail("expired")
        } catch (e: CompanionException) {
            assertTrue(pairingErrorText(e).contains("expired"))
        }
        pairing.close()
    }

    @Test
    fun aTvThatShowsAnotherCertificateGivesAnotherProofSoItFails() = blocking {
        val server = pairingTv()
        // The phone sees a different certificate than the TV believes it presented.
        val pairing = TvPairing(server.connector(ByteArray(32) { 7 }), "Pixel", 1, phoneFingerprint)
        pairing.start("127.0.0.1", server.port, "TV")
        try {
            pairing.finish(code)
            fail("proof covers the certificate")
        } catch (e: CompanionException) {
            assertEquals("wrong_code", e.code)
        }
        pairing.close()
    }

    @Test
    fun finishBeforeStartSaysTheTvIsUnreachable() = blocking {
        val pairing = TvPairing(FakeTv.welcoming().also { servers.add(it) }.connector(tvFingerprint), "Pixel", 1, phoneFingerprint)
        try {
            pairing.finish(code)
            fail("not started")
        } catch (e: CompanionException) {
            assertEquals(CompanionException.Kind.UNREACHABLE, e.kind)
        }
    }

    @Test
    fun onlySixDigitsCountAsACompleteCode() {
        assertTrue(TvPairing.isCompleteCode("482913"))
        assertTrue(TvPairing.isCompleteCode(" 000001 "))
        assertFalse(TvPairing.isCompleteCode("48291"))
        assertFalse(TvPairing.isCompleteCode("4829130"))
        assertFalse(TvPairing.isCompleteCode("48a913"))
    }
}
