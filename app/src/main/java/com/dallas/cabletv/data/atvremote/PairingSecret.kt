package com.dallas.cabletv.data.atvremote

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Works out the secret the TV expects for the code it shows. The code is 6 hexadecimal characters.
 * The first two are the first byte of the secret, a check that the other four were typed right.
 */
object PairingSecret {
    /** True when [code] is exactly 6 hexadecimal characters, in either case. */
    fun isWellFormed(code: String): Boolean =
        code.length == 6 && code.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /**
     * SHA-256 over the client modulus, client exponent, server modulus, server exponent and the
     * bytes of the code after its first two characters. Numbers go in as the shortest big-endian
     * bytes with no sign byte, so 65537 is 01 00 01.
     *
     * Throws [AtvRemoteException] with BAD_CODE when the code is not well formed or its first byte
     * does not match the first byte of the hash, which means a typing mistake.
     */
    fun compute(
        clientModulus: BigInteger,
        clientExponent: BigInteger,
        serverModulus: BigInteger,
        serverExponent: BigInteger,
        code: String
    ): ByteArray {
        if (!isWellFormed(code)) {
            throw AtvRemoteException(AtvRemoteException.Reason.BAD_CODE, "The code is 6 letters and numbers, 0 to 9 and A to F.")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(unsignedBytes(clientModulus))
        digest.update(unsignedBytes(clientExponent))
        digest.update(unsignedBytes(serverModulus))
        digest.update(unsignedBytes(serverExponent))
        digest.update(hexBytes(code.substring(2)))
        val hash = digest.digest()
        if ((hash[0].toInt() and 0xFF) != code.substring(0, 2).toInt(16)) {
            throw AtvRemoteException(AtvRemoteException.Reason.BAD_CODE, "That code does not match. Check it and try again.")
        }
        return hash
    }

    /** The shortest big-endian bytes of a non-negative number. Zero is no bytes at all. */
    fun unsignedBytes(value: BigInteger): ByteArray {
        if (value.signum() == 0) return ByteArray(0)
        val raw = value.toByteArray()
        // toByteArray adds a leading zero when the top bit is set, so the number is not read as negative.
        return if (raw.size > 1 && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
    }

    private fun hexBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
