package com.dallas.cabletv.data.atvremote

import com.dallas.cabletv.data.companion.CompanionIdentity
import com.dallas.cabletv.data.companion.IdentityRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * A connection to the TV's Android TV Remote Service on port 6466, after pairing. It answers the
 * TV's setup messages and pings, keeps [state] up to date, and sends keys and app links.
 *
 * All socket work runs on Dispatchers.IO in a scope this instance owns. Writes go through a queue
 * with one writer, so [sendKey] and [launch] never block the caller.
 */
class AtvRemote(private val host: String, private val port: Int = REMOTE_PORT) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(AtvState())

    /** What is known about the TV. [AtvState.connected] drops when the connection ends. */
    val state: StateFlow<AtvState> = mutableState.asStateFlow()

    private val lock = Any()
    private var link: Link? = null
    private var closed = false

    /** One connection: its socket and the queue its writer drains. */
    private class Link(val socket: SSLSocket) {
        val queue = Channel<ByteArray>(QUEUE_SIZE)
    }

    /**
     * Connects and waits until the TV says it is ready. Throws [AtvRemoteException] with
     * NOT_PAIRED when the TV does not know this phone, UNREACHABLE when it cannot be reached, and
     * PROTOCOL for anything else that goes wrong.
     */
    suspend fun connect() {
        val mine = withContext(Dispatchers.IO) {
            dropLink(null)
            mutableState.value = AtvState()
            openLink()
        }
        val ready = CompletableDeferred<Unit>()
        scope.launch { runWriter(mine) }
        scope.launch { runReader(mine, ready) }
        try {
            withTimeout(READY_TIMEOUT_MS) { ready.await() }
        } catch (e: TimeoutCancellationException) {
            dropLink(mine)
            throw AtvRemoteException(AtvRemoteException.Reason.PROTOCOL, "The TV did not get ready.")
        } catch (e: Throwable) {
            dropLink(mine)
            throw e
        }
    }

    /** A short press of an Android key code, such as [AtvKeys.POWER]. Ignored while not connected. */
    fun sendKey(keyCode: Int) = enqueue(RemoteWire.key(keyCode))

    /** Opens an app link on the TV, such as "cabletv://live". Ignored while not connected. */
    fun launch(link: String) = enqueue(RemoteWire.appLink(link))

    override fun close() {
        synchronized(lock) { closed = true }
        dropLink(null)
        scope.cancel()
    }

    private fun enqueue(message: ByteArray) {
        if (!mutableState.value.connected) return
        val current = synchronized(lock) { link } ?: return
        // A full queue means the TV has stopped reading. Dropping a key press is better than waiting.
        current.queue.trySend(Framing.frame(message))
    }

    private fun openLink(): Link {
        val sock = try {
            CompanionIdentity.sslContext(IdentityRole.ATV_CLIENT).socketFactory.createSocket() as SSLSocket
        } catch (e: Exception) {
            throw AtvRemoteException(AtvRemoteException.Reason.PROTOCOL, "This phone could not load its remote key.")
        }
        val made = Link(sock)
        synchronized(lock) {
            if (closed) {
                runCatching { sock.close() }
                throw AtvRemoteException(AtvRemoteException.Reason.PROTOCOL, "The remote was closed.")
            }
            link = made
        }
        try {
            sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            sock.soTimeout = IDLE_TIMEOUT_MS
            sock.startHandshake()
        } catch (e: SSLException) {
            // A TV that does not know our certificate refuses the handshake.
            dropLink(made)
            throw AtvRemoteException(AtvRemoteException.Reason.NOT_PAIRED, "The TV does not know this phone yet.")
        } catch (e: IOException) {
            dropLink(made)
            throw AtvRemoteException(AtvRemoteException.Reason.UNREACHABLE, "Could not reach the TV.")
        }
        return made
    }

    /** Sends queued messages in order until the connection ends. */
    private suspend fun runWriter(mine: Link) {
        try {
            val out = mine.socket.outputStream
            for (bytes in mine.queue) {
                out.write(bytes)
                out.flush()
            }
        } catch (e: IOException) {
            dropLink(mine)
        }
    }

    /** Reads and answers the TV's messages until the connection ends. */
    private fun runReader(mine: Link, ready: CompletableDeferred<Unit>) {
        val frames = FrameReader()
        var activeFeatures = RemoteWire.WANTED_FEATURES
        var failure: AtvRemoteException? = null
        try {
            val input = mine.socket.inputStream
            while (true) {
                val bytes = frames.readFrom(input) ?: break
                val msg = RemoteWire.parse(bytes)
                when (msg) {
                    is RemoteWire.Incoming.Configure -> {
                        activeFeatures = RemoteWire.activeFeatures(msg.features)
                        reply(mine, RemoteWire.configureReply(activeFeatures))
                    }
                    is RemoteWire.Incoming.SetActive -> reply(mine, RemoteWire.setActiveReply(activeFeatures))
                    is RemoteWire.Incoming.Ping -> reply(mine, RemoteWire.pingResponse(msg.value))
                    is RemoteWire.Incoming.Start -> {
                        publish(mine) { RemoteWire.apply(it, msg).copy(connected = true) }
                        ready.complete(Unit)
                    }
                    else -> publish(mine) { RemoteWire.apply(it, msg) }
                }
            }
        } catch (e: SocketTimeoutException) {
            failure = AtvRemoteException(AtvRemoteException.Reason.PROTOCOL, "The TV stopped answering.")
        } catch (e: ProtoException) {
            failure = AtvRemoteException(AtvRemoteException.Reason.PROTOCOL, "The TV sent something this phone cannot read.")
        } catch (e: IOException) {
            // Includes a refused client certificate, which TLS 1.3 reports on the first read.
        }
        // The TV closing the connection before it said it was ready is how an unknown phone shows up.
        ready.completeExceptionally(
            failure ?: AtvRemoteException(AtvRemoteException.Reason.NOT_PAIRED, "The TV does not know this phone yet.")
        )
        dropLink(mine)
    }

    private fun reply(mine: Link, message: ByteArray) {
        mine.queue.trySend(Framing.frame(message))
    }

    /** Updates [state], unless [mine] is no longer the current connection. */
    private fun publish(mine: Link, change: (AtvState) -> AtvState) {
        synchronized(lock) {
            if (link === mine) mutableState.value = change(mutableState.value)
        }
    }

    /** Ends [only], or whatever connection there is when [only] is null. Marks the state disconnected. */
    private fun dropLink(only: Link?) {
        val dropped = synchronized(lock) {
            val current = link
            if (current == null || (only != null && current !== only)) {
                null
            } else {
                link = null
                mutableState.value = mutableState.value.copy(connected = false)
                current
            }
        } ?: return
        dropped.queue.close()
        runCatching { dropped.socket.close() }
    }

    companion object {
        const val REMOTE_PORT = 6466
        const val CONNECT_TIMEOUT_MS = 5_000

        /** The TV pings every few seconds, so silence for this long means the connection is dead. */
        const val IDLE_TIMEOUT_MS = 16_000
        const val READY_TIMEOUT_MS = 10_000L
        private const val QUEUE_SIZE = 64
    }
}
