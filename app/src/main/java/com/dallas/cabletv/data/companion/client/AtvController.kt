package com.dallas.cabletv.data.companion.client

import com.dallas.cabletv.data.atvremote.AtvPairing
import com.dallas.cabletv.data.atvremote.AtvRemote
import com.dallas.cabletv.data.atvremote.AtvRemoteException
import com.dallas.cabletv.data.atvremote.AtvState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps a connection to a TV's own Android TV remote service while the TV's screen is open. It
 * reconnects with a growing wait when the connection drops. When the TV says it does not know this
 * phone, [onNotPaired] is called once and the controller stops. Key presses are sent off the main
 * thread.
 */
class AtvController(
    private val scope: CoroutineScope,
    private val onNotPaired: () -> Unit
) {
    private val _state = MutableStateFlow(AtvState())

    /** The remote's state: connected, power, volume. */
    val state: StateFlow<AtvState> = _state

    @Volatile private var current: AtvRemote? = null
    private var job: Job? = null

    /** Starts connecting to the TV at [host]. Does nothing while already running. */
    @Synchronized
    fun start(host: String) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { run(host) }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        current?.let { runCatching { it.close() } }
        current = null
        _state.value = AtvState()
    }

    /** A short press of an Android key code. Ignored while not connected. */
    fun sendKey(keyCode: Int) {
        val remote = current ?: return
        scope.launch(Dispatchers.IO) { runCatching { remote.sendKey(keyCode) } }
    }

    /** Opens an app link on the TV, such as cabletv://live. Ignored while not connected. */
    fun openLink(link: String) {
        val remote = current ?: return
        scope.launch(Dispatchers.IO) { runCatching { remote.launch(link) } }
    }

    private suspend fun run(host: String) {
        var attempt = 0
        while (scope.isActive) {
            val remote = AtvRemote(host)
            try {
                remote.connect()
                attempt = 0
                current = remote
                _state.value = remote.state.value
                coroutineScope {
                    val follow = launch { remote.state.collect { _state.value = it } }
                    remote.state.first { !it.connected }
                    follow.cancel()
                }
            } catch (e: AtvRemoteException) {
                if (e.reason == AtvRemoteException.Reason.NOT_PAIRED) {
                    onNotPaired()
                    return
                }
            } finally {
                current = null
                _state.value = AtvState()
                runCatching { remote.close() }
            }
            delay(Backoff.delayMs(attempt++))
        }
    }
}

/**
 * Pairs the phone with a TV's Android TV remote service. [start] makes the TV show a code, and
 * [finish] sends the code the person read from the TV screen.
 */
class AtvPairingFlow(private val host: String) {
    private var pairing: AtvPairing? = null

    /** Connects and returns once the TV shows its code. Throws [AtvRemoteException]. */
    suspend fun start(clientName: String) {
        close()
        val p = AtvPairing(host)
        pairing = p
        try {
            p.start(clientName)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /** Sends [code]. Throws [AtvRemoteException] with BAD_CODE when the code is wrong. */
    suspend fun finish(code: String) {
        val p = pairing ?: throw AtvRemoteException(AtvRemoteException.Reason.UNREACHABLE, "Not connected")
        p.finish(code.trim())
        close()
    }

    fun close() {
        pairing?.let { runCatching { it.close() } }
        pairing = null
    }
}
