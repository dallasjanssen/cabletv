package com.dallas.cabletv.data.companion.server

import com.dallas.cabletv.data.companion.CompanionProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingManagerTest {
    private var clock = 1_000_000L
    private var nextCode = "482913"
    private val manager = PairingManager(now = { clock }, newCode = { nextCode })

    private val tvFp = ByteArray(32) { 1 }
    private val phoneFp = ByteArray(32) { 2 }

    private fun proof(code: String) = CompanionProtocol.pairingProof(code, tvFp, phoneFp)

    @Test
    fun startShowsTheCodeAndTheName() {
        assertNull(manager.offer.value)
        val offer = manager.start("phone-a", "Pixel 10")
        assertEquals(offer, manager.offer.value)
        assertEquals("Pixel 10", offer.phoneName)
        assertEquals("482913", offer.code)
        assertEquals("482 913", offer.displayCode)
        assertEquals(clock + CompanionProtocol.PAIR_CODE_LIFETIME_MS, offer.expiresAt)
    }

    @Test
    fun theRightProofPairsAndClosesTheCode() {
        manager.start("phone-a", "Pixel 10")
        val result = manager.finish("phone-a", proof("482913"), tvFp, phoneFp)
        assertEquals(PairResult.Success("Pixel 10"), result)
        assertNull(manager.offer.value)
        // The code cannot be used twice.
        assertEquals(PairResult.Expired, manager.finish("phone-a", proof("482913"), tvFp, phoneFp))
    }

    @Test
    fun aWrongProofCountsDownThenLocks() {
        manager.start("phone-a", "Pixel 10")
        for (left in 4 downTo 1) {
            assertEquals(PairResult.WrongCode(left), manager.finish("phone-a", proof("000000"), tvFp, phoneFp))
            assertNotNull(manager.offer.value)
        }
        assertEquals(PairResult.Locked, manager.finish("phone-a", proof("000000"), tvFp, phoneFp))
        assertNull(manager.offer.value)
        // Even the right code is refused once it is closed.
        assertEquals(PairResult.Expired, manager.finish("phone-a", proof("482913"), tvFp, phoneFp))
    }

    @Test
    fun aMissingProofIsWrong() {
        manager.start("phone-a", "Pixel 10")
        assertEquals(PairResult.WrongCode(4), manager.finish("phone-a", null, tvFp, phoneFp))
    }

    @Test
    fun theCodeExpiresAfterTwoMinutes() {
        manager.start("phone-a", "Pixel 10")
        clock += CompanionProtocol.PAIR_CODE_LIFETIME_MS - 1
        manager.expireIfDue()
        assertNotNull(manager.offer.value)
        clock += 1
        manager.expireIfDue()
        assertNull(manager.offer.value)
    }

    @Test
    fun anExpiredCodeIsRefusedEvenWithoutTheScreenNoticing() {
        manager.start("phone-a", "Pixel 10")
        clock += CompanionProtocol.PAIR_CODE_LIFETIME_MS
        assertEquals(PairResult.Expired, manager.finish("phone-a", proof("482913"), tvFp, phoneFp))
        assertNull(manager.offer.value)
    }

    @Test
    fun cancelClosesTheCode() {
        manager.start("phone-a", "Pixel 10")
        manager.cancel()
        assertNull(manager.offer.value)
        assertEquals(PairResult.Expired, manager.finish("phone-a", proof("482913"), tvFp, phoneFp))
    }

    @Test
    fun aNewStartReplacesTheOldCodeAndResetsTheTries() {
        manager.start("phone-a", "Pixel 10")
        manager.finish("phone-a", proof("000000"), tvFp, phoneFp)
        manager.finish("phone-a", proof("000000"), tvFp, phoneFp)
        nextCode = "111222"
        manager.start("phone-a", "Pixel 10")
        assertEquals("111222", manager.offer.value?.code)
        assertEquals(PairResult.WrongCode(4), manager.finish("phone-a", proof("482913"), tvFp, phoneFp))
        assertEquals(PairResult.Success("Pixel 10"), manager.finish("phone-a", proof("111222"), tvFp, phoneFp))
    }

    @Test
    fun anotherPhoneCannotUseUpTheTries() {
        manager.start("phone-a", "Pixel 10")
        repeat(10) {
            assertEquals(PairResult.Expired, manager.finish("phone-b", proof("000000"), tvFp, phoneFp))
        }
        assertEquals(PairResult.Success("Pixel 10"), manager.finish("phone-a", proof("482913"), tvFp, phoneFp))
    }

    @Test
    fun aNewStartFromAnotherPhoneTakesOver() {
        manager.start("phone-a", "Pixel 10")
        nextCode = "999888"
        manager.start("phone-b", "Galaxy")
        assertEquals("Galaxy", manager.offer.value?.phoneName)
        assertEquals(PairResult.Expired, manager.finish("phone-a", proof("482913"), tvFp, phoneFp))
        assertTrue(manager.finish("phone-b", proof("999888"), tvFp, phoneFp) is PairResult.Success)
    }

    @Test
    fun theProofCoversBothCertificates() {
        manager.start("phone-a", "Pixel 10")
        val otherTv = ByteArray(32) { 9 }
        // A proof made against another certificate, as someone in the middle would see, does not match.
        val forged = CompanionProtocol.pairingProof("482913", otherTv, phoneFp)
        assertEquals(PairResult.WrongCode(4), manager.finish("phone-a", forged, tvFp, phoneFp))
    }

    @Test
    fun leadingZerosKeepTheirGrouping() {
        nextCode = "004207"
        assertEquals("004 207", manager.start("phone-a", "Pixel 10").displayCode)
    }
}
