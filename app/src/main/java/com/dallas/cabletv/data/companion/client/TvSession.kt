package com.dallas.cabletv.data.companion.client

import com.dallas.cabletv.data.companion.CompanionProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Where a TV's screen stands: connecting, connected, or waiting to try again. */
sealed interface SessionStatus {
    data object Connecting : SessionStatus

    data class Online(val welcome: Welcome) : SessionStatus

    /** Not reachable right now. The session keeps trying with a growing wait. */
    data class Offline(val message: String) : SessionStatus

    /** The TV showed a different certificate. The session stops: the person has to pair again. */
    data class IdentityChanged(val message: String) : SessionStatus

    /** The TV no longer knows this phone. The session stops: the person has to pair again. */
    data object NotPaired : SessionStatus
}

/**
 * Keeps a connection to one paired TV for as long as its screen is open: connects, follows the
 * TV's state, and reconnects with a growing wait when the connection drops. Stop it when the
 * screen closes. [connector] and the name are passed in so tests can use a plain socket.
 */
class TvSession(
    private val tv: PairedTv,
    private val connector: LinkConnector,
    private val phoneName: String,
    private val appBuild: Int,
    private val scope: CoroutineScope
) : TvRequester {
    private val _status = MutableStateFlow<SessionStatus>(SessionStatus.Connecting)
    val status: StateFlow<SessionStatus> = _status

    private val _state = MutableStateFlow<TvState?>(null)

    /** What the TV last said is on screen, or null while not connected. */
    val state: StateFlow<TvState?> = _state

    @Volatile private var connection: TvConnection? = null
    private var job: Job? = null

    /** Starts connecting. Calling it again does nothing while the session runs. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    /** Stops the session and closes the connection. */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        connection?.close()
        connection = null
    }

    override suspend fun request(message: JSONObject): JSONObject {
        val current = connection ?: throw CompanionException.unreachable(tv.name)
        return current.request(message)
    }

    private suspend fun run() {
        var attempt = 0
        while (scope.isActive) {
            _status.value = SessionStatus.Connecting
            val c = TvConnection(tv.host, tv.port, tv.name, tv.fingerprintBytes(), connector, phoneName, appBuild)
            try {
                val welcome = c.open()
                if (!welcome.paired) {
                    _status.value = SessionStatus.NotPaired
                    return
                }
                attempt = 0
                connection = c
                runCatching { _state.value = TvState.fromJson(c.request(CompanionRequests.getState())) }
                _status.value = SessionStatus.Online(welcome)
                coroutineScope {
                    val follow = launch { c.states.collect { _state.value = it } }
                    val reason = c.closedReason.filterNotNull().first()
                    follow.cancel()
                    _status.value = SessionStatus.Offline(reason.message ?: CompanionException.lost(tv.name).message.orEmpty())
                }
            } catch (e: CompanionException) {
                if (e.kind == CompanionException.Kind.IDENTITY_CHANGED) {
                    _status.value = SessionStatus.IdentityChanged(e.message.orEmpty())
                    return
                }
                if (e.code == CompanionProtocol.Errors.NOT_PAIRED) {
                    _status.value = SessionStatus.NotPaired
                    return
                }
                _status.value = SessionStatus.Offline(e.message.orEmpty())
            } finally {
                connection = null
                _state.value = null
                c.close()
            }
            delay(Backoff.delayMs(attempt++))
        }
    }
}
