package com.dallas.cabletv.data.atvremote

import com.dallas.cabletv.data.companion.CompanionIdentity
import com.dallas.cabletv.data.companion.IdentityRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * Pairs with the TV's Android TV Remote Service on port 6467. One use per instance: call
 * [start], then [finish] with the code the TV shows, then [close].
 *
 * The blocking socket work runs on Dispatchers.IO. [close] closes the socket, which also frees a
 * call that is waiting on the TV.
 */
class AtvPairing(private val host: String, private val port: Int = PAIRING_PORT) : Closeable {
    private val lock = Any()
    private var socket: SSLSocket? = null
    private var reader: FrameReader? = null
    private var started = false
    private var closed = false

    /** Connects and asks the TV to show a 6-character code. Returns once the TV shows it. */
    suspend fun start(clientName: String) {
        withContext(Dispatchers.IO) {
            if (started) fail(AtvRemoteException.Reason.PROTOCOL, "Pairing was already started.")
            val sock = openSocket()
            val frames = FrameReader()
            reader = frames
            try {
                send(sock, Polo.pairingRequest(clientName))
                while (true) {
                    val msg = Polo.parse(read(sock, frames))
                    if (msg.status != Polo.STATUS_OK) fail(AtvRemoteException.Reason.PROTOCOL, "The TV refused to pair (${msg.status}).")
                    when (msg.kind) {
                        Polo.Kind.PAIRING_REQUEST_ACK -> send(sock, Polo.options())
                        Polo.Kind.OPTIONS -> send(sock, Polo.configuration())
                        Polo.Kind.CONFIGURATION_ACK -> {
                            started = true
                            return@withContext
                        }
                        else -> fail(AtvRemoteException.Reason.PROTOCOL, "The TV sent something unexpected while pairing.")
                    }
                }
            } catch (e: Throwable) {
                close()
                throw e
            }
        }
    }

    /**
     * Sends the code the person typed. Returns once the TV accepts it. A code that is not 6
     * hexadecimal characters, or whose first two characters do not match the rest, throws BAD_CODE
     * without anything being sent.
     */
    suspend fun finish(code: String) {
        withContext(Dispatchers.IO) {
            val trimmed = code.trim()
            if (!PairingSecret.isWellFormed(trimmed)) {
                fail(AtvRemoteException.Reason.BAD_CODE, "The code is 6 letters and numbers, 0 to 9 and A to F.")
            }
            val sock = synchronized(lock) { socket }
            val frames = reader
            if (!started || sock == null || frames == null) {
                fail(AtvRemoteException.Reason.PROTOCOL, "Pairing has not been started.")
                return@withContext
            }
            val secret = try {
                val session = sock.session
                val local = session.localCertificates?.firstOrNull() as? X509Certificate
                    ?: CompanionIdentity.certificate(IdentityRole.ATV_CLIENT)
                val server = session.peerCertificates.firstOrNull() as? X509Certificate
                    ?: fail(AtvRemoteException.Reason.PROTOCOL, "The TV did not show a certificate.")
                val localKey = local.publicKey as? RSAPublicKey
                val serverKey = server.publicKey as? RSAPublicKey
                if (localKey == null || serverKey == null) {
                    fail(AtvRemoteException.Reason.PROTOCOL, "The TV or this phone does not use an RSA key.")
                }
                PairingSecret.compute(localKey.modulus, localKey.publicExponent, serverKey.modulus, serverKey.publicExponent, trimmed)
            } catch (e: SSLException) {
                close()
                fail(AtvRemoteException.Reason.PROTOCOL, "The connection to the TV was lost.")
            }
            try {
                send(sock, Polo.secret(secret))
                while (true) {
                    val msg = Polo.parse(read(sock, frames))
                    when {
                        msg.status == Polo.STATUS_BAD_SECRET -> fail(AtvRemoteException.Reason.BAD_CODE, "The TV did not accept that code.")
                        msg.status != Polo.STATUS_OK -> fail(AtvRemoteException.Reason.PROTOCOL, "The TV refused to pair (${msg.status}).")
                        msg.kind == Polo.Kind.SECRET_ACK -> return@withContext
                        // A repeated acknowledgement from the earlier steps is harmless.
                        msg.kind == Polo.Kind.CONFIGURATION_ACK -> Unit
                        else -> fail(AtvRemoteException.Reason.PROTOCOL, "The TV sent something unexpected while pairing.")
                    }
                }
            } catch (e: Throwable) {
                close()
                throw e
            }
        }
    }

    override fun close() {
        val s = synchronized(lock) {
            closed = true
            socket.also { socket = null }
        }
        runCatching { s?.close() }
    }

    private fun openSocket(): SSLSocket {
        val sock = try {
            val factory = CompanionIdentity.sslContext(IdentityRole.ATV_CLIENT).socketFactory
            val s = factory.createSocket() as SSLSocket
            synchronized(lock) {
                if (closed) {
                    s.close()
                    fail(AtvRemoteException.Reason.PROTOCOL, "Pairing was closed.")
                }
                socket = s
            }
            s
        } catch (e: AtvRemoteException) {
            throw e
        } catch (e: Exception) {
            fail(AtvRemoteException.Reason.PROTOCOL, "This phone could not make its pairing key.")
        }
        try {
            sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            sock.soTimeout = READ_TIMEOUT_MS
            sock.startHandshake()
        } catch (e: IOException) {
            close()
            fail(AtvRemoteException.Reason.UNREACHABLE, "Could not reach the TV.")
        }
        return sock
    }

    private fun send(sock: SSLSocket, message: ByteArray) {
        try {
            sock.outputStream.write(Framing.frame(message))
            sock.outputStream.flush()
        } catch (e: IOException) {
            fail(AtvRemoteException.Reason.PROTOCOL, "The connection to the TV was lost.")
        }
    }

    private fun read(sock: SSLSocket, frames: FrameReader): ByteArray {
        try {
            return frames.readFrom(sock.inputStream)
                ?: fail(AtvRemoteException.Reason.PROTOCOL, "The TV closed the connection.")
        } catch (e: SocketTimeoutException) {
            fail(AtvRemoteException.Reason.PROTOCOL, "The TV did not answer.")
        } catch (e: IOException) {
            fail(AtvRemoteException.Reason.PROTOCOL, "The connection to the TV was lost.")
        } catch (e: ProtoException) {
            fail(AtvRemoteException.Reason.PROTOCOL, "The TV sent something this phone cannot read.")
        }
    }

    private fun fail(reason: AtvRemoteException.Reason, message: String): Nothing =
        throw AtvRemoteException(reason, message)

    companion object {
        const val PAIRING_PORT = 6467
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 15_000
    }
}
