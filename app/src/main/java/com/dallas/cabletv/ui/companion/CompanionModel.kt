package com.dallas.cabletv.ui.companion

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import com.dallas.cabletv.data.companion.CompanionIdentity
import com.dallas.cabletv.data.companion.CompanionProtocol
import com.dallas.cabletv.data.companion.IdentityRole
import com.dallas.cabletv.data.companion.client.CompanionException
import com.dallas.cabletv.data.companion.client.LinkConnector
import com.dallas.cabletv.data.companion.client.PairedTv
import com.dallas.cabletv.data.companion.client.PairedTvStore
import com.dallas.cabletv.data.companion.client.TvCopyStore
import com.dallas.cabletv.data.companion.client.PinCancelledException
import com.dallas.cabletv.data.companion.client.PinMemory
import com.dallas.cabletv.data.companion.client.SyncRunner
import com.dallas.cabletv.data.companion.client.TlsLinkConnector
import com.dallas.cabletv.data.companion.client.TvAddress
import com.dallas.cabletv.data.companion.client.TvConnection
import com.dallas.cabletv.data.companion.client.TvDiscovery
import com.dallas.cabletv.data.companion.client.TvPairing
import com.dallas.cabletv.data.companion.client.TvRequester
import com.dallas.cabletv.data.companion.client.pairingErrorText
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The pairing dialog's state. */
sealed interface PairUi {
    val tvName: String

    data class Connecting(override val tvName: String) : PairUi

    /** The TV shows a code. [error] is the last wrong try, if any. */
    data class EnterCode(override val tvName: String, val error: String? = null, val busy: Boolean = false) : PairUi

    data class Failed(override val tvName: String, val message: String) : PairUi
}

/** A PIN dialog that is waiting for an answer. Complete [answer] with the PIN, or null to cancel. */
class PinPrompt(val tvName: String, val wasWrong: Boolean, val answer: CompletableDeferred<String?>)

/**
 * What the whole phone app shares: the paired TVs, discovery, pairing, PINs kept in memory and
 * one-off connections for Sync. Everything that touches the network runs off the main thread.
 * [scope] is the screen's scope, so work stops when the app closes.
 */
class CompanionModel(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    val store = PairedTvStore(appContext)

    /** This phone's copies of each TV's setup. */
    val copies = TvCopyStore(appContext)
    private val discovery = TvDiscovery(appContext)

    private val _syncReview = MutableStateFlow<SyncPlan?>(null)

    /** A sync waiting for the person to choose which differing settings to keep. */
    val syncReview: StateFlow<SyncPlan?> = _syncReview.asStateFlow()

    /** The parental PIN of each TV, until the app closes. */
    val pins = PinMemory()

    val connector: LinkConnector = TlsLinkConnector

    /** How this phone introduces itself: its name in Android settings, or its model. */
    val phoneName: String = runCatching {
        Settings.Global.getString(appContext.contentResolver, "device_name")
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL.orEmpty().ifBlank { "Phone" }

    val appBuild: Int = runCatching {
        PackageInfoCompat.getLongVersionCode(appContext.packageManager.getPackageInfo(appContext.packageName, 0)).toInt()
    }.getOrDefault(0)

    val tvs = store.tvs
    val found = discovery.found

    private val _pairUi = MutableStateFlow<PairUi?>(null)
    val pairUi: StateFlow<PairUi?> = _pairUi

    private val _notice = MutableStateFlow<String?>(null)

    /** A short line for My TVs, such as "Paired with Living Room TV." */
    val notice: StateFlow<String?> = _notice

    private val _pinPrompt = MutableStateFlow<PinPrompt?>(null)
    val pinPrompt: StateFlow<PinPrompt?> = _pinPrompt
    private val pinLock = Mutex()

    private var pairing: TvPairing? = null
    private var pairJob: Job? = null
    private var pairTarget: PairTarget? = null

    private data class PairTarget(val host: String, val port: Int, val name: String, val repairId: String?)

    init {
        // A paired TV found at a new address is remembered at that address.
        scope.launch {
            found.collect { list -> list.forEach { store.noteAddress(it.id, it.host, it.port) } }
        }
    }

    fun startDiscovery() = discovery.start()

    fun stopDiscovery() = discovery.stop()

    fun clearNotice() {
        _notice.value = null
    }

    fun remove(tv: PairedTv) {
        pins.clear(tv.id)
        store.remove(tv.id)
    }

    /**
     * Reads a typed address and starts pairing with the TV there. Returns a sentence to show when
     * the address cannot be used, and null when pairing has started.
     */
    fun addByAddress(hostText: String, portText: String): String? {
        return when (val parsed = TvAddress.parse(hostText, portText)) {
            is TvAddress.Result.Problem -> parsed.message
            is TvAddress.Result.Ok -> {
                startPairing(parsed.address.host, parsed.address.port, parsed.address.host, repairId = null)
                null
            }
        }
    }

    /**
     * Connects to a TV, asks it to show a code and opens the code dialog. With [repairId] the TV is
     * already paired and is being paired again, for example after its identity changed.
     */
    fun startPairing(host: String, port: Int, name: String, repairId: String?) {
        cancelPairing()
        pairTarget = PairTarget(host, port, name, repairId)
        _pairUi.value = PairUi.Connecting(name)
        pairJob = scope.launch {
            try {
                val phoneFingerprint = withContext(Dispatchers.IO) {
                    CompanionIdentity.fingerprint(CompanionIdentity.certificate(IdentityRole.PHONE))
                }
                val p = TvPairing(connector, phoneName, appBuild, phoneFingerprint)
                pairing = p
                val target = p.start(host, port, name)
                val known = store.find(target.tvId)
                if (known != null && known.id != repairId) {
                    store.noteAddress(known.id, host, port)
                    closePairing()
                    _pairUi.value = null
                    _notice.value = "${known.name} is already paired."
                    return@launch
                }
                _pairUi.value = PairUi.EnterCode(target.tvName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CompanionException) {
                closePairing()
                _pairUi.value = PairUi.Failed(name, e.message.orEmpty())
            } catch (e: Exception) {
                closePairing()
                _pairUi.value = PairUi.Failed(name, "This phone could not set up pairing. Try again.")
            }
        }
    }

    /** Sends the code the person typed. */
    fun submitPairingCode(code: String) {
        val p = pairing ?: return
        val name = _pairUi.value?.tvName ?: return
        if (!TvPairing.isCompleteCode(code)) {
            _pairUi.value = PairUi.EnterCode(name, error = "Type the 6 digits shown on the TV.")
            return
        }
        _pairUi.value = PairUi.EnterCode(name, busy = true)
        pairJob = scope.launch {
            try {
                val tv = p.finish(code)
                val before = store.find(tv.id)
                store.save(tv.copy(atvPaired = before?.atvPaired ?: false))
                closePairing()
                _pairUi.value = null
                _notice.value = "Paired with ${tv.name}."
            } catch (e: CancellationException) {
                throw e
            } catch (e: CompanionException) {
                val message = pairingErrorText(e)
                _pairUi.value = if (e.code == CompanionProtocol.Errors.WRONG_CODE) {
                    PairUi.EnterCode(name, error = message)
                } else {
                    closePairing()
                    PairUi.Failed(name, message)
                }
            } catch (e: Exception) {
                closePairing()
                _pairUi.value = PairUi.Failed(name, "Pairing did not finish. Try again.")
            }
        }
    }

    /** Starts over with the TV from the last attempt, to get a new code. */
    fun retryPairing() {
        val t = pairTarget ?: return
        startPairing(t.host, t.port, t.name, t.repairId)
    }

    fun cancelPairing() {
        pairJob?.cancel()
        pairJob = null
        closePairing()
        _pairUi.value = null
    }

    private fun closePairing() {
        pairing?.close()
        pairing = null
    }

    /**
     * Asks the person for [tvName]'s PIN and waits for the answer. Null means they cancelled.
     * [wasWrong] makes the dialog say the last PIN did not match.
     */
    suspend fun askPin(tvName: String, wasWrong: Boolean): String? = pinLock.withLock {
        val answer = CompletableDeferred<String?>()
        _pinPrompt.value = PinPrompt(tvName, wasWrong, answer)
        try {
            answer.await()
        } finally {
            _pinPrompt.value = null
        }
    }

    /**
     * Opens a connection to [tv] just for [block], and closes it after. A TV that does not know
     * this phone any more throws a not_paired error.
     */
    suspend fun <T> withTv(tv: PairedTv, block: suspend (TvRequester) -> T): T {
        val current = store.find(tv.id) ?: tv
        val c = TvConnection(current.host, current.port, current.name, current.fingerprintBytes(), connector, phoneName, appBuild)
        try {
            val welcome = c.open()
            if (!welcome.paired) {
                throw CompanionException(
                    CompanionException.Kind.REMOTE,
                    CompanionException.remoteText(CompanionProtocol.Errors.NOT_PAIRED, ""),
                    CompanionProtocol.Errors.NOT_PAIRED
                )
            }
            return block(c)
        } finally {
            c.close()
        }
    }

    /**
     * Copies [sections] from one TV to another and returns the target's messages. Each TV asks for
     * its PIN when it has one.
     */
    suspend fun sync(from: PairedTv, to: PairedTv, sections: List<String>): List<String> {
        require(from.id != to.id) { "Choose two different TVs." }
        val data = withTv(from) { runner().exportFrom(from, it, sections) }
        return startSync(data, to, sections, "Copy to ${to.name}")
    }

    /**
     * Copies [data] to [to]. When the TV already has different values for some of it, the copy waits
     * in [syncReview] so the person can choose. Otherwise it is applied straight away.
     */
    suspend fun startSync(data: JSONObject, to: PairedTv, sections: List<String>, title: String): List<String> {
        require(sections.isNotEmpty()) { "Choose what to copy." }
        val current = withTv(to) { runner().exportFrom(to, it, sections) }
        val plan = SyncPlan.build(title, to, data, current)
        if (plan.conflicts.isEmpty()) return applySync(plan, emptySet())
        _syncReview.value = plan
        return listOf("Some settings differ. Review them to finish.")
    }

    /** Applies [plan] with the settings in [keepTvIds] kept from the TV. The review stays open until it is closed. */
    suspend fun applySync(plan: SyncPlan, keepTvIds: Set<String>): List<String> =
        withTv(plan.target) { runner().importInto(plan.target, it, plan.merged(keepTvIds)) }

    /** Closes the review without changing anything more. */
    fun dismissSyncReview() {
        _syncReview.value = null
    }

    private fun runner() = SyncRunner(pins) { id, wasWrong ->
        askPin(store.find(id)?.name ?: "this TV", wasWrong)
    }

    /** What to show for a failed call. A cancelled PIN dialog says nothing worth showing. */
    fun errorText(e: Throwable): String? = when (e) {
        is CancellationException -> throw e
        is PinCancelledException -> null
        is CompanionException -> e.message
        is IllegalArgumentException -> e.message
        else -> "Something went wrong. Try again."
    }

    fun close() {
        cancelPairing()
        discovery.stop()
    }
}
