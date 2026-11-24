package com.dallas.cabletv.data.companion.client

import com.dallas.cabletv.data.companion.CompanionLineChannel
import org.json.JSONObject
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A stand-in TV for tests: a plain local socket that reads one message per line and lets the test
 * decide the answer. There is no TLS, so the "certificate fingerprint" is whatever the connector
 * is told it is.
 */
class FakeTv(private val onMessage: (message: JSONObject, reply: (JSONObject) -> Unit) -> Unit) : Closeable {
    private val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
    private val sockets = CopyOnWriteArrayList<Socket>()
    val port: Int get() = server.localPort

    /** Every message the phone sent, in order. */
    val received = CopyOnWriteArrayList<JSONObject>()

    init {
        thread(isDaemon = true, name = "fake-tv-accept") {
            try {
                while (true) {
                    val socket = server.accept()
                    sockets.add(socket)
                    thread(isDaemon = true, name = "fake-tv-conn") { serve(socket) }
                }
            } catch (e: Exception) {
                // Closed by the test.
            }
        }
    }

    private fun serve(socket: Socket) {
        val channel = CompanionLineChannel(socket.getInputStream(), socket.getOutputStream())
        try {
            while (true) {
                val message = channel.read() ?: break
                received.add(message)
                onMessage(message) { channel.write(it) }
            }
        } catch (e: Exception) {
            // The phone closed the connection.
        }
    }

    /** Ends every open connection, as a TV that is switched off would. */
    fun dropConnections() {
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
    }

    override fun close() {
        runCatching { server.close() }
        dropConnections()
    }

    /** A connector that reaches this TV and reports [fingerprint] as the TV's certificate. */
    fun connector(fingerprint: ByteArray): LinkConnector = LinkConnector { host, port ->
        val socket = Socket(host, port)
        object : Link {
            override val channel = CompanionLineChannel(socket.getInputStream(), socket.getOutputStream())
            override val tvFingerprint: ByteArray = fingerprint
            override fun close() {
                runCatching { socket.close() }
            }
        }
    }

    companion object {
        /** The reply to [message] with its id copied into "re". */
        fun answer(message: JSONObject, type: String): JSONObject =
            JSONObject().put("type", type).put("re", message.optInt("id"))

        /** A TV that answers hello, ping and everything else with a plain ok. */
        fun welcoming(paired: Boolean = true, tvId: String = "tv-1", tvName: String = "Living Room TV") = FakeTv { m, reply ->
            when (m.optString("type")) {
                "hello" -> reply(
                    answer(m, "welcome").put("paired", paired).put("tv_name", tvName).put("tv_id", tvId)
                        .put("app_build", 1).put("pin_set", false).put("protocol", 1)
                )
                "ping" -> reply(answer(m, "pong"))
                else -> reply(answer(m, "ok"))
            }
        }
    }
}
