package com.dallas.cabletv.data.companion.client

import com.dallas.cabletv.data.companion.CompanionProtocol
import com.dallas.cabletv.data.companion.CompanionProtocol.Types
import java.io.Closeable

/** The TV found while starting to pair: who it is, so the phone can match it to a paired TV. */
data class PairingTarget(val tvId: String, val tvName: String)

/**
 * Pairs the phone with one TV. [start] connects, says hello and asks the TV to show a code. The
 * person types the code, and [finish] sends the proof built from the code and both certificate
 * fingerprints. The connection stays open between the two calls and closes with [close].
 */
class TvPairing(
    private val connector: LinkConnector,
    private val phoneName: String,
    private val appBuild: Int,
    /** SHA-256 fingerprint of this phone's own certificate. */
    private val phoneFingerprint: ByteArray
) : Closeable {
    private var connection: TvConnection? = null
    private var host: String = ""
    private var port: Int = 0
    private var target: PairingTarget? = null

    /**
     * Connects to [host] and [port] and asks the TV to show a code. [displayName] is how messages
     * name the TV until its own name is known. Throws [CompanionException].
     */
    suspend fun start(host: String, port: Int, displayName: String): PairingTarget {
        close()
        this.host = host
        this.port = port
        val c = TvConnection(host, port, displayName, null, connector, phoneName, appBuild)
        connection = c
        try {
            val welcome = c.open()
            c.request(CompanionRequests.pairStart(phoneName))
            return PairingTarget(welcome.tvId, welcome.tvName.ifBlank { displayName }).also { target = it }
        } catch (e: CompanionException) {
            c.close()
            throw e
        }
    }

    /**
     * Sends the code the person typed. Returns the paired TV on success. A wrong code throws a
     * REMOTE [CompanionException] with code wrong_code and the tries left, and the person can
     * type another. code_expired and locked mean the person must start again.
     */
    suspend fun finish(code: String): PairedTv {
        val c = connection ?: throw CompanionException.unreachable(target?.tvName ?: host)
        val tvFingerprint = c.tvFingerprint() ?: throw CompanionException.unreachable(target?.tvName ?: host)
        val proof = CompanionProtocol.pairingProof(code.trim(), tvFingerprint, phoneFingerprint)
        val reply = c.request(CompanionRequests.pairFinish(proof))
        if (reply.optString("type") != Types.PAIRED) {
            throw CompanionException(CompanionException.Kind.PROTOCOL, "The TV sent something this app did not expect.")
        }
        val known = target
        val id = reply.optString("tv_id").ifBlank { known?.tvId.orEmpty() }
        if (id.isBlank()) throw CompanionException(CompanionException.Kind.PROTOCOL, "The TV did not say who it is.")
        return PairedTv(
            id = id,
            name = reply.optString("tv_name").ifBlank { known?.tvName ?: "TV" },
            fingerprint = CompanionProtocol.toHex(tvFingerprint),
            host = host,
            port = port
        )
    }

    override fun close() {
        connection?.close()
        connection = null
    }

    companion object {
        /** True when [code] looks like a complete pairing code: six digits. */
        fun isCompleteCode(code: String): Boolean =
            code.trim().length == CompanionProtocol.PAIR_CODE_DIGITS && code.trim().all { it in '0'..'9' }
    }
}
