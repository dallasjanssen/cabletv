package com.dallas.cabletv.data.companion.server

import com.dallas.cabletv.data.companion.CompanionProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A pairing code waiting to be typed on a phone. The TV shows [code] and [phoneName]. */
data class PairingOffer(val phoneName: String, val code: String, val expiresAt: Long) {
    /** The code in two groups of three, such as 482 913, as the TV shows it. */
    val displayCode: String get() = code.chunked(3).joinToString(" ")
}

/** How a try at finishing a pairing ended. */
sealed interface PairResult {
    /** The proof matched. The phone is paired and the code is closed. */
    data class Success(val phoneName: String) : PairResult

    /** The proof did not match. The code stays open while [attemptsLeft] is above zero. */
    data class WrongCode(val attemptsLeft: Int) : PairResult

    /** There is no open code for this phone: it ran out, was cancelled, or was replaced. */
    data object Expired : PairResult

    /** Too many wrong tries. The code is closed. */
    data object Locked : PairResult
}

/**
 * The one pairing in progress on this TV. A phone asks for a code with [start], the TV shows
 * [offer], and the phone answers with [finish]. A new [start] replaces the old code. The code
 * lasts [CompanionProtocol.PAIR_CODE_LIFETIME_MS], allows [CompanionProtocol.PAIR_MAX_WRONG]
 * wrong tries, and belongs to the phone that asked for it, so another phone cannot use up its
 * tries. Safe to call from any thread.
 */
class PairingManager(
    private val now: () -> Long = System::currentTimeMillis,
    private val newCode: () -> String = { CompanionProtocol.newPairCode() }
) {
    private class Session(val phoneKey: String, val offer: PairingOffer, var wrong: Int = 0)

    private val lock = Any()
    private var session: Session? = null
    private val offerFlow = MutableStateFlow<PairingOffer?>(null)

    /** What the TV shows right now, or null when no code is open. */
    val offer: StateFlow<PairingOffer?> get() = offerFlow

    /** Opens a code for the phone identified by [phoneKey], replacing any earlier one. */
    fun start(phoneKey: String, phoneName: String): PairingOffer {
        val offer = PairingOffer(
            phoneName = phoneName,
            code = newCode(),
            expiresAt = now() + CompanionProtocol.PAIR_CODE_LIFETIME_MS
        )
        synchronized(lock) {
            session = Session(phoneKey, offer)
            offerFlow.value = offer
        }
        return offer
    }

    /**
     * Checks the phone's [proof] against the open code. [tvFingerprint] is the certificate the TV
     * presented in this connection and [phoneFingerprint] the one the phone presented.
     */
    fun finish(phoneKey: String, proof: String?, tvFingerprint: ByteArray, phoneFingerprint: ByteArray): PairResult =
        synchronized(lock) {
            expireIfDue()
            val s = session
            if (s == null || s.phoneKey != phoneKey) return PairResult.Expired
            val expected = CompanionProtocol.pairingProof(s.offer.code, tvFingerprint, phoneFingerprint)
            if (CompanionProtocol.proofMatches(expected, proof)) {
                close()
                return PairResult.Success(s.offer.phoneName)
            }
            s.wrong++
            if (s.wrong >= CompanionProtocol.PAIR_MAX_WRONG) {
                close()
                return PairResult.Locked
            }
            PairResult.WrongCode(CompanionProtocol.PAIR_MAX_WRONG - s.wrong)
        }

    /** Closes the code, for the Cancel button, Back, or the app leaving the screen. */
    fun cancel() {
        synchronized(lock) { close() }
    }

    /** Closes the code when its time is up. The screen calls this when the wait ends. */
    fun expireIfDue() {
        synchronized(lock) {
            val s = session ?: return
            if (now() >= s.offer.expiresAt) close()
        }
    }

    private fun close() {
        session = null
        offerFlow.value = null
    }
}
