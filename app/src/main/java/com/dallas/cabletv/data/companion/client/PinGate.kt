package com.dallas.cabletv.data.companion.client

import com.dallas.cabletv.data.companion.CompanionProtocol
import java.util.concurrent.ConcurrentHashMap

/** The parental PIN of each TV, kept in memory only, until the app closes. */
class PinMemory {
    private val pins = ConcurrentHashMap<String, String>()

    fun get(tvId: String): String? = pins[tvId]
    fun set(tvId: String, pin: String) {
        pins[tvId] = pin
    }
    fun clear(tvId: String) {
        pins.remove(tvId)
    }
}

/** The person closed the PIN dialog without entering a PIN. */
class PinCancelledException : Exception("PIN cancelled")

/**
 * Runs [call] with the TV's PIN. It starts with the PIN remembered for [tvId], if any. When the TV
 * answers pin_required or wrong_pin it forgets the old PIN and asks again through [askPin], which
 * returns null when the person cancels (this throws [PinCancelledException]). A PIN that worked is
 * remembered. Other errors, including pin_wait, go to the caller untouched.
 *
 * [askPin] gets true when the last PIN was wrong, so the dialog can say so.
 */
suspend fun <T> withPin(
    tvId: String,
    memory: PinMemory,
    askPin: suspend (wasWrong: Boolean) -> String?,
    call: suspend (pin: String?) -> T
): T {
    var pin = memory.get(tvId)
    while (true) {
        try {
            val result = call(pin)
            if (pin != null) memory.set(tvId, pin)
            return result
        } catch (e: CompanionException) {
            val needsPin = e.kind == CompanionException.Kind.REMOTE &&
                (e.code == CompanionProtocol.Errors.PIN_REQUIRED || e.code == CompanionProtocol.Errors.WRONG_PIN)
            if (!needsPin) throw e
            memory.clear(tvId)
            pin = askPin(e.code == CompanionProtocol.Errors.WRONG_PIN) ?: throw PinCancelledException()
        }
    }
}
