package com.dallas.cabletv.data.companion.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.dallas.cabletv.data.HomeAssistantReporter
import com.dallas.cabletv.data.companion.CompanionIdentity
import com.dallas.cabletv.data.companion.CompanionLineChannel
import com.dallas.cabletv.data.companion.CompanionProtocol
import com.dallas.cabletv.data.companion.IdentityRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Socket
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * The TV's side of the phone companion. While the app is on screen it listens for paired phones
 * over TLS, announces itself on the network, and answers each message through the [MessageRouter].
 * [start] and [stop] return at once: the work runs on a thread of its own, one after the other, so
 * the main thread never waits on the network. Pushes what is playing to paired phones, at most once
 * a second, whenever the screen or what is playing changes.
 */
class CompanionServer(
    private val context: Context,
    private val router: MessageRouter,
    private val backend: CompanionBackend,
    private val pairing: PairingManager,
    private val hooks: CompanionHooks
) {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(daemonThreads("companion-control"))
    private var running: Running? = null

    /** Starts listening, unless it already is. */
    fun start() {
        worker.execute {
            if (running != null) return@execute
            val next = Running()
            try {
                next.begin()
                running = next
            } catch (e: Exception) {
                // No port or no keys. The TV works as before, and the next time the app comes on screen tries again.
                next.end()
            }
        }
    }

    /** Stops listening, closes every connection and the pairing code, and takes the network name down. */
    fun stop() {
        worker.execute {
            running?.end()
            running = null
            pairing.cancel()
        }
    }

    /** Stops, and lets go of the thread. The server cannot be started again. */
    fun release() {
        stop()
        worker.shutdown()
    }

    /** Closes the open connections of the phone with this certificate fingerprint (hex), after it was removed. */
    fun closeConnections(fingerprint: String) {
        worker.execute { running?.closeFor(fingerprint.lowercase()) }
    }

    /** One run of the server, from start to stop. */
    private inner class Running {
        private val connections = CopyOnWriteArraySet<Connection>()

        /** Sockets accepted and not yet closed, counted from the moment they arrive, even before the handshake. */
        private val open = AtomicInteger(0)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val handlers: ExecutorService = Executors.newCachedThreadPool(daemonThreads("companion-conn"))
        private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(daemonThreads("companion-push"))
        private var server: SSLServerSocket? = null
        private var nsd: NsdManager? = null
        private var nsdListener: NsdManager.RegistrationListener? = null

        private val throttle = PushThrottle(
            minIntervalMs = 1_000L,
            now = System::currentTimeMillis,
            schedule = { delayMs, task -> runCatching { timer.schedule(task, delayMs, TimeUnit.MILLISECONDS) } },
            send = { pushState() }
        )

        fun begin() {
            val factory = CompanionIdentity.sslContext(IdentityRole.TV).serverSocketFactory
            val socket = (try {
                factory.createServerSocket(CompanionProtocol.DEFAULT_PORT)
            } catch (e: IOException) {
                factory.createServerSocket(0)
            }) as SSLServerSocket
            socket.needClientAuth = true
            socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            server = socket
            Thread({ acceptLoop(socket) }, "companion-accept").apply { isDaemon = true }.start()
            register(socket.localPort)
            scope.launch {
                combine(hooks.screen, HomeAssistantReporter.nowPlaying) { _, _ -> }.collect { throttle.poke() }
            }
        }

        fun end() {
            nsdListener?.let { listener -> runCatching { nsd?.unregisterService(listener) } }
            runCatching { server?.close() }
            connections.forEach { it.close() }
            connections.clear()
            scope.cancel()
            handlers.shutdownNow()
            timer.shutdownNow()
        }

        fun closeFor(fingerprint: String) {
            connections.filter { it.info.phoneKey == fingerprint }.forEach { it.close() }
        }

        private fun register(port: Int) {
            val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            val info = NsdServiceInfo().apply {
                serviceName = backend.tvName
                serviceType = CompanionProtocol.SERVICE_TYPE
                setPort(port)
                setAttribute(CompanionProtocol.TXT_ID, backend.tvId)
                setAttribute(CompanionProtocol.TXT_VERSION, CompanionProtocol.PROTOCOL_VERSION.toString())
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            }
            // Without the network name a phone can still add the TV by address, so a failure here is not fatal.
            if (runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess) {
                nsd = manager
                nsdListener = listener
            }
        }

        private fun acceptLoop(socket: SSLServerSocket) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    if (socket.isClosed) return
                    runCatching { Thread.sleep(250) }
                    continue
                }
                if (open.incrementAndGet() > CompanionProtocol.MAX_CONNECTIONS) {
                    open.decrementAndGet()
                    runCatching { client.close() }
                    continue
                }
                val accepted = runCatching { handlers.execute { serve(client) } }
                if (accepted.isFailure) {
                    open.decrementAndGet()
                    runCatching { client.close() }
                }
            }
        }

        /** Runs one connection from handshake to close, on its own thread. */
        private fun serve(raw: Socket) {
            var conn: Connection? = null
            try {
                val socket = raw as SSLSocket
                socket.keepAlive = true
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                socket.startHandshake()
                socket.soTimeout = CompanionProtocol.IDLE_TIMEOUT_MS.toInt()
                val phoneCert = socket.session.peerCertificates.firstOrNull() ?: return
                val tvCert = socket.session.localCertificates?.firstOrNull() ?: return
                val info = ConnectionInfo(CompanionIdentity.fingerprint(tvCert), CompanionIdentity.fingerprint(phoneCert))
                val channel = CompanionLineChannel(socket.inputStream, socket.outputStream)
                conn = Connection(socket, channel, info)
                connections.add(conn)
                while (true) {
                    val msg = channel.read() ?: break
                    channel.write(router.handle(info, msg))
                }
            } catch (e: Exception) {
                // The phone left, went quiet for too long, or sent something broken. Either way this connection is over.
            } finally {
                conn?.let { connections.remove(it) }
                (conn?.socket ?: raw).let { runCatching { it.close() } }
                open.decrementAndGet()
            }
        }

        /** Sends the current state to every paired phone. Each write goes through its own thread, so a stuck phone cannot hold up the rest. */
        private fun pushState() {
            val message = runCatching { backend.stateMessage() }.getOrNull() ?: return
            connections.filter { backend.isPaired(it.info.phoneKey) }.forEach { conn ->
                runCatching {
                    handlers.execute {
                        try {
                            conn.channel.write(message)
                        } catch (e: Exception) {
                            conn.close()
                        }
                    }
                }
            }
        }
    }

    private class Connection(val socket: SSLSocket, val channel: CompanionLineChannel, val info: ConnectionInfo) {
        fun close() {
            runCatching { socket.close() }
        }
    }

    private companion object {
        /** A phone that does not finish the TLS handshake in this time is dropped, so it cannot hold a place. */
        const val HANDSHAKE_TIMEOUT_MS = 10_000

        fun daemonThreads(name: String) = java.util.concurrent.ThreadFactory { r ->
            Thread(r, name).apply { isDaemon = true }
        }
    }
}
