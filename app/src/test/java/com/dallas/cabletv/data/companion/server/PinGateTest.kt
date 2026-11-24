package com.dallas.cabletv.data.companion.server

import org.junit.Assert.assertEquals
import org.junit.Test

class PinGateTest {
    private var clock = 5_000L
    private var hasPin = true
    private val gate = PinGate(hasPin = { hasPin }, matches = { it == "1234" }, now = { clock })

    @Test
    fun noPinMeansEverythingIsAllowed() {
        hasPin = false
        assertEquals(PinResult.Allowed, gate.check(null))
        assertEquals(PinResult.Allowed, gate.check("9999"))
    }

    @Test
    fun aPinIsRequiredWhenOneIsSet() {
        assertEquals(PinResult.Required, gate.check(null))
        assertEquals(PinResult.Required, gate.check(""))
    }

    @Test
    fun theRightPinIsAllowedAndAWrongOneIsNot() {
        assertEquals(PinResult.Allowed, gate.check("1234"))
        assertEquals(PinResult.Wrong, gate.check("0000"))
    }

    @Test
    fun aMissingPinDoesNotCountAsWrong() {
        repeat(20) { assertEquals(PinResult.Required, gate.check(null)) }
        assertEquals(PinResult.Allowed, gate.check("1234"))
    }

    @Test
    fun fiveWrongInARowPauseEverythingForThirtySeconds() {
        repeat(4) { assertEquals(PinResult.Wrong, gate.check("0000")) }
        assertEquals(PinResult.Wait(30), gate.check("0000"))
        // The right PIN is refused during the wait too.
        assertEquals(PinResult.Wait(30), gate.check("1234"))
        clock += 10_000
        assertEquals(PinResult.Wait(20), gate.check("1234"))
        assertEquals(PinResult.Wait(20), gate.check(null))
        clock += 19_999
        assertEquals(PinResult.Wait(1), gate.check("1234"))
        clock += 1
        assertEquals(PinResult.Allowed, gate.check("1234"))
    }

    @Test
    fun aRightPinInTheMiddleStartsTheCountAgain() {
        repeat(4) { gate.check("0000") }
        assertEquals(PinResult.Allowed, gate.check("1234"))
        repeat(4) { assertEquals(PinResult.Wrong, gate.check("0000")) }
        assertEquals(PinResult.Wait(30), gate.check("0000"))
    }

    @Test
    fun afterTheWaitTheCountStartsFresh() {
        repeat(5) { gate.check("0000") }
        clock += 30_000
        repeat(4) { assertEquals(PinResult.Wrong, gate.check("0000")) }
        assertEquals(PinResult.Wait(30), gate.check("0000"))
    }
}
