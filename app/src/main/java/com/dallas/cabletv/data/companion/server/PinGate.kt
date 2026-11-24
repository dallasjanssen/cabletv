package com.dallas.cabletv.data.companion.server

/** The answer to a PIN check for a request that needs the PIN. */
sealed interface PinResult {
    /** The request may go on: there is no PIN, or the right one was given. */
    data object Allowed : PinResult

    /** The TV has a PIN and the request did not carry one. */
    data object Required : PinResult

    /** The PIN did not match. */
    data object Wrong : PinResult

    /** Too many wrong PINs. Nothing is accepted until [seconds] have passed. */
    data class Wait(val seconds: Int) : PinResult
}

/**
 * The PIN rules for settings and sync requests. The TV's parental PIN protects them, and this
 * keeps its own count: five wrong PINs in a row, from any phone, pause every attempt for 30
 * seconds, even a correct one. A request with no PIN does not count as a wrong one. Safe to call
 * from any thread.
 */
class PinGate(
    private val hasPin: () -> Boolean,
    private val matches: (String) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()
    private var wrongInARow = 0
    private var blockedUntil = 0L

    fun check(pin: String?): PinResult {
        if (!hasPin()) return PinResult.Allowed
        synchronized(lock) {
            val t = now()
            if (t < blockedUntil) return PinResult.Wait(secondsLeft(t))
            if (pin.isNullOrEmpty()) return PinResult.Required
            if (matches(pin)) {
                wrongInARow = 0
                return PinResult.Allowed
            }
            wrongInARow++
            if (wrongInARow >= MAX_WRONG) {
                wrongInARow = 0
                blockedUntil = t + WAIT_MS
                return PinResult.Wait(secondsLeft(t))
            }
            return PinResult.Wrong
        }
    }

    private fun secondsLeft(t: Long): Int = (((blockedUntil - t) + 999) / 1000).toInt().coerceAtLeast(1)

    companion object {
        const val MAX_WRONG = 5
        const val WAIT_MS = 30_000L
    }
}
