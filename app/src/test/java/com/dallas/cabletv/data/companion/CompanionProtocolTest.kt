package com.dallas.cabletv.data.companion

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom

class CompanionProtocolTest {

    private val tv = CompanionProtocol.fingerprint("tv certificate".toByteArray())
    private val phone = CompanionProtocol.fingerprint("phone certificate".toByteArray())

    @Test
    fun proofIsRepeatableAndCoversCodeAndBothCertificates() {
        val proof = CompanionProtocol.pairingProof("482913", tv, phone)
        assertEquals(64, proof.length)
        assertEquals(proof, CompanionProtocol.pairingProof("482913", tv, phone))
        assertEquals(proof, CompanionProtocol.pairingProof(" 482913 ", tv, phone))
        assertNotEquals(proof, CompanionProtocol.pairingProof("482914", tv, phone))
        assertNotEquals(proof, CompanionProtocol.pairingProof("482913", phone, tv))
        val other = CompanionProtocol.fingerprint("someone else".toByteArray())
        assertNotEquals(proof, CompanionProtocol.pairingProof("482913", tv, other))
    }

    @Test
    fun proofComparisonIgnoresCaseAndRejectsMissing() {
        val proof = CompanionProtocol.pairingProof("000001", tv, phone)
        assertTrue(CompanionProtocol.proofMatches(proof, proof.uppercase()))
        assertFalse(CompanionProtocol.proofMatches(proof, null))
        assertFalse(CompanionProtocol.proofMatches(proof, proof.dropLast(1)))
    }

    @Test
    fun pairCodesAreSixDigitsWithLeadingZeros() {
        val random = SecureRandom()
        repeat(200) {
            val code = CompanionProtocol.newPairCode(random)
            assertEquals(6, code.length)
            assertTrue(code.all { it.isDigit() })
        }
    }

    @Test
    fun hexRoundTrip() {
        val bytes = byteArrayOf(0, 1, 127, -128, -1)
        assertEquals("00017f80ff", CompanionProtocol.toHex(bytes))
        assertArrayEquals(bytes, CompanionProtocol.fromHex("00017F80FF"))
    }

    @Test
    fun linesRoundTripAndBlankLinesAreSkipped() {
        val out = ByteArrayOutputStream()
        val writer = CompanionLineChannel(ByteArrayInputStream(ByteArray(0)), out)
        writer.write(JSONObject().put("type", "hello").put("id", 1))
        writer.write(JSONObject().put("type", "key").put("key", "DPAD_UP").put("text", "line\nbreak"))
        val wire = "\n" + out.toString(Charsets.UTF_8.name()) + "\r\n"
        val reader = CompanionLineChannel(ByteArrayInputStream(wire.toByteArray()), ByteArrayOutputStream())
        assertEquals("hello", reader.read()!!.getString("type"))
        val second = reader.read()!!
        assertEquals("DPAD_UP", second.getString("key"))
        assertEquals("line\nbreak", second.getString("text"))
        assertNull(reader.read())
    }

    @Test
    fun lastLineWithoutNewlineIsRead() {
        val reader = CompanionLineChannel(ByteArrayInputStream("{\"type\":\"ping\"}".toByteArray()), ByteArrayOutputStream())
        assertEquals("ping", reader.read()!!.getString("type"))
        assertNull(reader.read())
    }

    @Test
    fun oversizedOrBrokenLinesAreRefused() {
        val big = ByteArray(CompanionProtocol.MAX_LINE_BYTES + 10) { 'a'.code.toByte() }
        try {
            CompanionLineChannel(ByteArrayInputStream(big), ByteArrayOutputStream()).read()
            fail("Expected IOException")
        } catch (e: IOException) {
            // Expected.
        }
        try {
            CompanionLineChannel(ByteArrayInputStream("not json\n".toByteArray()), ByteArrayOutputStream()).read()
            fail("Expected IOException")
        } catch (e: IOException) {
            // Expected.
        }
    }

    @Test
    fun remoteKeysUseAndroidKeyCodes() {
        assertEquals(19, CompanionProtocol.REMOTE_KEYS["DPAD_UP"])
        assertEquals(23, CompanionProtocol.REMOTE_KEYS["DPAD_CENTER"])
        assertEquals(7, CompanionProtocol.REMOTE_KEYS["0"])
        assertEquals(16, CompanionProtocol.REMOTE_KEYS["9"])
        assertEquals(229, CompanionProtocol.REMOTE_KEYS["LAST_CHANNEL"])
        assertNull(CompanionProtocol.REMOTE_KEYS["POWER"])
    }

    @Test
    fun errorAndReplyShapes() {
        val e = CompanionProtocol.error(7, CompanionProtocol.Errors.WRONG_PIN, "That PIN did not match.")
        assertEquals("error", e.getString("type"))
        assertEquals(7, e.getInt("re"))
        assertEquals("wrong_pin", e.getString("code"))
        assertFalse(CompanionProtocol.reply(null, "state").has("re"))
    }
}
