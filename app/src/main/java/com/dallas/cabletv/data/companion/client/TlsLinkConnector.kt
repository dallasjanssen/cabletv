package com.dallas.cabletv.data.companion.client

import com.dallas.cabletv.data.companion.CompanionIdentity
import com.dallas.cabletv.data.companion.CompanionLineChannel
import com.dallas.cabletv.data.companion.IdentityRole
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

/**
 * Opens the phone's secured connection to a TV: TLS 1.2 or newer, presenting the phone's own
 * certificate. The TV's certificate is accepted by the handshake and handed back as a fingerprint,
 * and the caller compares it with the paired one before sending anything. Blocking, so call it off
 * the main thread.
 */
object TlsLinkConnector : LinkConnector {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val HANDSHAKE_TIMEOUT_MS = 10_000

    override fun connect(host: String, port: Int): Link {
        val factory = try {
            CompanionIdentity.sslContext(IdentityRole.PHONE).socketFactory
        } catch (e: Exception) {
            throw IOException("No identity for this phone", e)
        }
        val plain = Socket()
        try {
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            val ssl = factory.createSocket(plain, host, port, true) as SSLSocket
            ssl.enabledProtocols = ssl.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            ssl.soTimeout = HANDSHAKE_TIMEOUT_MS
            ssl.startHandshake()
            // Reads wait for the TV's messages for as long as the pings keep the connection alive.
            ssl.soTimeout = 0
            val certificate = ssl.session.peerCertificates.firstOrNull() ?: throw IOException("The TV showed no certificate")
            val fingerprint = CompanionIdentity.fingerprint(certificate)
            return object : Link {
                override val channel = CompanionLineChannel(ssl.inputStream, ssl.outputStream)
                override val tvFingerprint: ByteArray = fingerprint
                override fun close() {
                    runCatching { ssl.close() }
                }
            }
        } catch (e: IOException) {
            runCatching { plain.close() }
            throw e
        } catch (e: RuntimeException) {
            runCatching { plain.close() }
            throw IOException(e.message, e)
        }
    }
}
