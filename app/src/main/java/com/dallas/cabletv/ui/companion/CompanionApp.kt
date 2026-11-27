package com.dallas.cabletv.ui.companion

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import com.dallas.cabletv.BuildConfig
import com.dallas.cabletv.data.AppUpdater
import com.dallas.cabletv.data.friendlyError
import com.dallas.cabletv.ui.Tv

private const val ROUTE_TVS = "tvs"
private const val ROUTE_SYNC = "sync"
private const val ROUTE_TV_PREFIX = "tv:"

/**
 * The phone app: My TVs, a TV's screen, and Sync. [onWatchOnPhone] switches to the existing Cable
 * TV app in the same activity. Dialogs for pairing and PINs sit above every screen.
 */
@Composable
fun CompanionApp(onWatchOnPhone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember { CompanionModel(context, scope) }
    DisposableEffect(model) { onDispose { model.close() } }
    var route by rememberSaveable { mutableStateOf(ROUTE_TVS) }

    BackHandler(enabled = route != ROUTE_TVS) { route = ROUTE_TVS }

    // The look for a newer build starts as soon as the app opens, so Watch rarely has to wait for it.
    // A slow or failed check never blocks watching: it resolves to "no build" within a few seconds.
    val launchCheck = remember { CompletableDeferred<AppUpdater.Release?>() }
    LaunchedEffect(Unit) {
        // A debug build is signed with another key, so there is nothing to offer it.
        val found = if (BuildConfig.DEBUG) null else withContext(Dispatchers.IO) {
            runCatching { AppUpdater.latest(BuildConfig.VERSION_CODE, callTimeoutSeconds = UPDATE_CHECK_SECONDS) }.getOrNull()
        }
        launchCheck.complete(found)
    }

    var checkingUpdate by remember { mutableStateOf(false) }
    var offeredUpdate by remember { mutableStateOf<AppUpdater.Release?>(null) }
    var installing by remember { mutableStateOf(false) }
    var updateNote by remember { mutableStateOf<String?>(null) }

    fun checkThenWatch() {
        if (checkingUpdate) return
        checkingUpdate = true
        scope.launch {
            // Usually the launch check is already done and this returns at once.
            val found = withTimeoutOrNull(UPDATE_CHECK_SECONDS * 1000) { launchCheck.await() }
            checkingUpdate = false
            if (found == null) onWatchOnPhone() else {
                updateNote = null
                offeredUpdate = found
            }
        }
    }

    fun installOffered(release: AppUpdater.Release) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            updateNote = "Allow installs from Cable TV in Android settings, then press Install again."
            return
        }
        installing = true
        updateNote = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AppUpdater.download(release, File(context.cacheDir, "updates")) }
            }
            result
                .onSuccess { apk ->
                    runCatching { AppUpdater.install(context, apk) }
                        .onSuccess { offeredUpdate = null }
                        .onFailure { updateNote = it.message ?: "The installer did not open." }
                }
                .onFailure { updateNote = "The download did not finish. ${friendlyError(it)}" }
            installing = false
        }
    }

    Box(Modifier.fillMaxSize().background(Tv.Bg).systemBarsPadding().imePadding()) {
        when {
            route == ROUTE_SYNC -> SyncScreen(model, onBack = { route = ROUTE_TVS })
            route.startsWith(ROUTE_TV_PREFIX) -> TvScreen(
                model,
                tvId = route.removePrefix(ROUTE_TV_PREFIX),
                onBack = { route = ROUTE_TVS }
            )
            else -> MyTvsScreen(
                model,
                onOpenTv = { route = ROUTE_TV_PREFIX + it },
                onSync = { route = ROUTE_SYNC },
                onWatchOnPhone = { checkThenWatch() }
            )
        }
    }

    offeredUpdate?.let { release ->
        UpdateOfferDialog(
            release = release,
            busy = installing,
            note = updateNote,
            onInstall = { installOffered(release) },
            onWatch = { offeredUpdate = null; onWatchOnPhone() },
            onCancel = { offeredUpdate = null }
        )
    }
    PairingDialogs(model)
    PinDialog(model)
    val review by model.syncReview.collectAsState()
    review?.let { SyncReviewDialog(model, it) }
}

/** The dialogs for pairing a TV: connecting, the code, and a failure. */
@Composable
private fun PairingDialogs(model: CompanionModel) {
    val ui by model.pairUi.collectAsState()
    when (val state = ui) {
        null -> Unit
        is PairUi.Connecting -> MessageDialog(
            title = "Pairing with ${state.tvName}",
            message = "Connecting. A code will appear on the TV.",
            confirmLabel = "Cancel",
            onConfirm = { model.cancelPairing() },
            onDismiss = { model.cancelPairing() },
            dismissLabel = "Close"
        )
        is PairUi.EnterCode -> key(state.error) {
            CodeDialog(
                title = "Pair with ${state.tvName}",
                message = "Type the 6 digit code shown on the TV.",
                length = 6,
                error = state.error,
                busy = state.busy,
                confirmLabel = "Pair",
                onConfirm = { model.submitPairingCode(it) },
                onDismiss = { model.cancelPairing() }
            )
        }
        is PairUi.Failed -> MessageDialog(
            title = "Could not pair",
            message = state.message,
            confirmLabel = "Try again",
            onConfirm = { model.retryPairing() },
            onDismiss = { model.cancelPairing() }
        )
    }
}

/** The PIN dialog, shown whenever a TV with a parental PIN asks for it. */
@Composable
private fun PinDialog(model: CompanionModel) {
    val prompt by model.pinPrompt.collectAsState()
    val p = prompt ?: return
    key(p) {
        CodeDialog(
            title = "${p.tvName} PIN",
            message = if (p.wasWrong) "That PIN did not match. Try again." else "Type the 4 digit parental PIN for this TV.",
            length = 4,
            secret = true,
            confirmLabel = "Continue",
            onConfirm = { p.answer.complete(it) },
            onDismiss = { p.answer.complete(null) }
        )
    }
}

/** How long the check before Watch on this phone may take. Past this it is skipped and watching starts. */
private const val UPDATE_CHECK_SECONDS = 8L
