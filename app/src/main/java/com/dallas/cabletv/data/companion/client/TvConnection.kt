package com.dallas.cabletv.data.companion.client

import com.dallas.cabletv.data.companion.CompanionLineChannel
import com.dallas.cabletv.data.companion.CompanionProtocol
import com.dallas.cabletv.data.companion.CompanionProtocol.Types
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Anything that can send a request to a TV and wait for the reply. */
interface TvRequester {
    /** Sends [message] and returns the reply. Throws [CompanionException] when it fails or the TV answers with an error. */
    suspend fun request(message: JSONObject): JSONObject
}

/** An open, secured connection to a TV: its messages and the fingerprint of the certificate it showed. */
interface Link : Closeable {
    val channel: CompanionLineChannel

    /** SHA-256 fingerprint of the certificate the TV presented in this session. */
    val tvFingerprint: ByteArray
}

/** Opens a [Link] to a TV. It blocks, so callers use it off the main thread. Throws IOException when it cannot connect. */
fun interface LinkConnector {
    fun connect(host: String, port: Int): Link
}

/** How long to wait before the next try to reach a TV, after [attempt] tries that failed. */
object Backoff {
    fun delayMs(attempt: Int): Long = minOf(1_000L shl minOf(attempt.coerceAtLeast(0), 4), 15_000L)
}

/**
 * One connection to a TV. [open] connects, checks the TV's certificate when [expectedFingerprint]
 * is known, and says hello. After that [request] sends a message and waits for its reply, the
 * reader delivers the TV's own `state` messages on [states], and a ping goes out every
 * [pingIntervalMs]. When the connection ends for any reason [closedReason] says why, and
 * everything still waiting fails with the same reason. A connection is used once: connect again
 * with a new one.
 */
class TvConnection(
    private val host: String,
    private val port: Int,
    private val tvName: String,
    private val expectedFingerprint: ByteArray?,
    private val connector: LinkConnector,
    private val phoneName: String,
    private val appBuild: Int,
    private val requestTimeoutMs: Long = REQUEST_TIMEOUT_MS,
    private val pingIntervalMs: Long = CompanionProtocol.PING_INTERVAL_MS
) : TvRequester, Closeable {
    private val matcher = ReplyMatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val finished = AtomicBoolean(false)
    @Volatile private var link: Link? = null

    private val _states = MutableSharedFlow<TvState>(replay = 1, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The TV's own state messages, as they arrive. */
    val states: SharedFlow<TvState> = _states

    private val _closedReason = MutableStateFlow<CompanionException?>(null)

    /** Null while the connection is usable, and the reason once it has ended. */
    val closedReason: StateFlow<CompanionException?> = _closedReason

    /** The fingerprint of the certificate the TV showed, once connected. */
    fun tvFingerprint(): ByteArray? = link?.tvFingerprint

    /**
     * Connects and says hello. Throws [CompanionException]: UNREACHABLE when the TV cannot be
     * reached, and IDENTITY_CHANGED when it shows another certificate than the paired one, in which
     * case nothing is sent.
     */
    suspend fun open(): Welcome {
        val opened = withContext(Dispatchers.IO) {
            try {
                connector.connect(host, port)
            } catch (e: IOException) {
                throw CompanionException.unreachable(tvName)
            } catch (e: SecurityException) {
                throw CompanionException.unreachable(tvName)
            }
        }
        if (expectedFingerprint != null && !MessageDigest.isEqual(expectedFingerprint, opened.tvFingerprint)) {
            runCatching { opened.close() }
            val changed = CompanionException.identityChanged()
            finish(changed)
            throw changed
        }
        link = opened
        startReader(opened)
        startPing()
        try {
            val reply = request(CompanionRequests.hello(phoneName, appBuild))
            if (reply.optString("type") != Types.WELCOME) {
                throw CompanionException(CompanionException.Kind.PROTOCOL, "$tvName sent something this app did not expect.")
            }
            return Welcome.fromJson(reply)
        } catch (e: CompanionException) {
            finish(e)
            throw e
        }
    }

    override suspend fun request(message: JSONObject): JSONObject = requestWithin(message, requestTimeoutMs)

    /** Like [request], but waits at most [timeoutMs] for the reply. */
    suspend fun requestWithin(message: JSONObject, timeoutMs: Long): JSONObject {
        _closedReason.value?.let { throw it }
        val open = link ?: throw CompanionException.unreachable(tvName)
        val pending = matcher.prepare(message)
        try {
            withContext(Dispatchers.IO) { open.channel.write(message) }
        } catch (e: IOException) {
            matcher.cancel(pending.id)
            val lost = CompanionException.lost(tvName)
            finish(lost)
            throw lost
        }
        val reply = try {
            withTimeout(timeoutMs) { pending.reply.await() }
        } catch (e: TimeoutCancellationException) {
            matcher.cancel(pending.id)
            throw CompanionException.timeout(tvName)
        }
        if (reply.optString("type") == Types.ERROR) throw CompanionException.fromError(reply)
        return reply
    }

    /** Closes the connection. Anything still waiting fails with a CLOSED exception. */
    override fun close() {
        finish(CompanionException(CompanionException.Kind.CLOSED, "The connection to $tvName was closed."))
    }

    private fun startReader(open: Link) {
        scope.launch {
            try {
                while (true) {
                    val message = open.channel.read() ?: break
                    if (matcher.deliver(message)) continue
                    if (message.optString("type") == Types.STATE) _states.tryEmit(TvState.fromJson(message))
                }
            } catch (e: IOException) {
                // The connection broke. The same ending as a clean close.
            } catch (e: RuntimeException) {
                // A message this app cannot read. Treat the connection as lost rather than guess.
            }
            finish(CompanionException.lost(tvName))
        }
    }

    private fun startPing() {
        scope.launch {
            while (isActive) {
                delay(pingIntervalMs)
                try {
                    requestWithin(CompanionRequests.ping(), PING_TIMEOUT_MS)
                } catch (e: CompanionException) {
                    finish(if (e.kind == CompanionException.Kind.TIMEOUT) CompanionException.lost(tvName) else e)
                    return@launch
                }
            }
        }
    }

    /** Ends the connection once. The first reason is the one that sticks. */
    private fun finish(reason: CompanionException) {
        if (!finished.compareAndSet(false, true)) return
        _closedReason.value = reason
        matcher.failAll(reason)
        runCatching { link?.close() }
        scope.cancel()
    }

    companion object {
        /** How long a request waits for its reply. */
        const val REQUEST_TIMEOUT_MS = 15_000L
        private const val PING_TIMEOUT_MS = 10_000L
    }
}
