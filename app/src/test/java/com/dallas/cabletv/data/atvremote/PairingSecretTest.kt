package com.dallas.cabletv.data.atvremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger

/**
 * The vector was worked out with Python's hashlib the way the reference library does it: the
 * shortest big-endian bytes of each number, no sign byte. Both moduli have their top bit set, which
 * is the case where BigInteger.toByteArray adds a zero byte.
 */
class PairingSecretTest {
    private val clientN = BigInteger(
        "8096aa6e111ae73f84fd8307c071223c41f3a0e5cbd49268abf49970e5f94c7486f0b5ba4f3ca26aa51a0d495d7ee604bd18def371b6b1d157cb4b6ecffd7769",
        16
    )
    private val serverN = BigInteger(
        "da20fe58f9668764eeec736f9b07a96a857699565af9c98f926430c176ae7dd0dd404e06aa136d1c6086b7013c2f08030a4896fc7dfadebf0e64e2f4c9095f1f",
        16
    )
    private val e = BigInteger.valueOf(65537)

    @Test
    fun secretMatchesTheReferenceCalculation() {
        val hash = PairingSecret.compute(clientN, e, serverN, e, "85abcd")
        assertEquals("857113b2f87d7bb0b654043fbc6183ae4510eeae04fde09bad6041bd36e1142c", hash.toHex())
    }

    @Test
    fun codeIsCaseInsensitive() {
        val upper = PairingSecret.compute(clientN, e, serverN, e, "85ABCD")
        assertEquals("857113b2f87d7bb0b654043fbc6183ae4510eeae04fde09bad6041bd36e1142c", upper.toHex())
    }

    @Test
    fun noSignByteIsHashed() {
        assertEquals(65, clientN.toByteArray().size)
        assertEquals(64, PairingSecret.unsignedBytes(clientN).size)
        assertEquals("010001", PairingSecret.unsignedBytes(e).toHex())
        assertEquals(0, PairingSecret.unsignedBytes(BigInteger.ZERO).size)
        assertEquals("ff", PairingSecret.unsignedBytes(BigInteger.valueOf(255)).toHex())
        assertEquals("0100", PairingSecret.unsignedBytes(BigInteger.valueOf(256)).toHex())
        // With sign bytes the hash would be 4921376a..., which the TV would not accept.
        try {
            PairingSecret.compute(clientN, e, serverN, e, "49abcd")
            fail("Expected BAD_CODE")
        } catch (ex: AtvRemoteException) {
            assertEquals(AtvRemoteException.Reason.BAD_CODE, ex.reason)
        }
    }

    @Test
    fun wrongCheckByteIsBadCode() {
        try {
            PairingSecret.compute(clientN, e, serverN, e, "86abcd")
            fail("Expected BAD_CODE")
        } catch (ex: AtvRemoteException) {
            assertEquals(AtvRemoteException.Reason.BAD_CODE, ex.reason)
        }
    }

    @Test
    fun wrongTailIsBadCode() {
        try {
            PairingSecret.compute(clientN, e, serverN, e, "85abce")
            fail("Expected BAD_CODE")
        } catch (ex: AtvRemoteException) {
            assertEquals(AtvRemoteException.Reason.BAD_CODE, ex.reason)
        }
    }

    @Test
    fun codeFormat() {
        assertTrue(PairingSecret.isWellFormed("85abcd"))
        assertTrue(PairingSecret.isWellFormed("0A1b2C"))
        assertFalse(PairingSecret.isWellFormed("85abc"))
        assertFalse(PairingSecret.isWellFormed("85abcde"))
        assertFalse(PairingSecret.isWellFormed("85abcg"))
        assertFalse(PairingSecret.isWellFormed(""))
        assertFalse(PairingSecret.isWellFormed("85 abc"))
        for (bad in listOf("", "85abc", "85abcg", "+5abcd")) {
            try {
                PairingSecret.compute(clientN, e, serverN, e, bad)
                fail("Expected BAD_CODE for '$bad'")
            } catch (ex: AtvRemoteException) {
                assertEquals(AtvRemoteException.Reason.BAD_CODE, ex.reason)
            }
        }
    }
}
