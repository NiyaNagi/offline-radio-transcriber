@file:Suppress("MatchingDeclarationName") // FailureHostActions is one of several public declarations here.

package org.ort.app.ui.failures

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.ort.app.ui.components.Toast
import org.ort.app.ui.theme.OrtSpacing

/**
 * Every recovery action [FailureHost] can wire to a real callback (this package's report says
 * exactly which reach a real platform surface today and which stay documented no-op stubs at
 * their default — `ReaderActivity.kt`'s own kdoc says the same). Bundled into one type so
 * [FailureHost]'s own parameter list stays short (detekt's `LongParameterList`) rather than
 * growing by one every time a board gains a new recovery action.
 */
public data class FailureHostActions(
    public val onChooseAnotherInput: () -> Unit = {},
    public val onRetryInput: () -> Unit = {},
    public val onEndSession: () -> Unit = {},
    public val onOpenBatteryExemptionSettings: () -> Unit = {},
    public val onOpenStorageSettings: () -> Unit = {},
    public val onOpenRetentionSettings: () -> Unit = {},
    public val onRequestUsbPermission: () -> Unit = {},
    public val onSetFrequencyByHand: () -> Unit = {},
    public val onReconnectRig: () -> Unit = {},
)

/**
 * WP11b, register R-100/R-101/R-103: the one place every real failure signal this package reads
 * becomes what the operator sees. Mounted once, in `ReaderActivity.kt`, above `OrtNavHost` — the
 * full-screen takeovers and the toast slot the brief asks for. Banners render as an overlay
 * anchored near the top of whatever destination is current: this package owns no individual
 * screen's content file (`NowContent.kt`/`CaptureStatusContent.kt`/`LogContent.kt`/... belong to
 * WP4/WP5/WP8), so an overlay pinned across every destination is the only placement achievable
 * without editing a file outside this package's row — see this package's report.
 *
 * Polls [FailureSignalsPolling] every [POLL_INTERVAL_MILLIS] (matching the reader's existing 2 s
 * cadence — `ReaderPolling.kt`/`LiveBarPolling.kt`), maps the snapshot through
 * [FailureMapper.map], and diffs consecutive snapshots through [RecoveryAnnouncer.diff] for the
 * toast queue. F5/F15 are the only two dismissable presentations (guide's "dismissable only where
 * the board says so" — both report a past, already-resolved event; every other banner clears
 * itself the moment its own signal stops matching, never by a manual dismiss).
 */
@Composable
public fun FailureHost(
    sessionId: String?,
    modifier: Modifier = Modifier,
    actions: FailureHostActions = FailureHostActions(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var presentation by remember { mutableStateOf<FailurePresentation>(FailurePresentation.None) }
    var toasts by remember { mutableStateOf<List<RecoveryToast>>(emptyList()) }
    var dismissedKilledLabel by remember(sessionId) { mutableStateOf<String?>(null) }
    var dismissedCallLabel by remember(sessionId) { mutableStateOf<String?>(null) }
    var dismissedClockLabel by remember(sessionId) { mutableStateOf<String?>(null) }
    var dismissedInterruptedLabel by remember(sessionId) { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionId) {
        pollFailureSignals(context, sessionId) { mapped, newToasts ->
            presentation = mapped
            if (newToasts.isNotEmpty()) toasts = toasts + newToasts
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        content()

        FailurePresentationOverlay(
            presentation = presentation,
            actions = actions,
            dismissedKilledLabel = dismissedKilledLabel,
            onDismissKilled = { dismissedKilledLabel = it },
            dismissedCallLabel = dismissedCallLabel,
            onDismissCall = { dismissedCallLabel = it },
            dismissedClockLabel = dismissedClockLabel,
            onDismissClock = { dismissedClockLabel = it },
            dismissedInterruptedLabel = dismissedInterruptedLabel,
            onDismissInterrupted = { dismissedInterruptedLabel = it },
        )

        ToastSlot(toasts = toasts, onToastShown = { toasts = toasts.drop(1) })
    }
}

/** The polling loop itself, pulled out of the composable body so [FailureHost] stays short and
 * readable (detekt's `LongMethod`/`CyclomaticComplexMethod`) — a plain suspend loop, no Compose. */
private suspend fun pollFailureSignals(
    context: android.content.Context,
    sessionId: String?,
    onTick: (FailurePresentation, List<RecoveryToast>) -> Unit,
) {
    var previous: FailureSignals? = null
    while (true) {
        val signals = FailureSignalsPolling.current(context, sessionId)
        val mapped = FailureMapper.map(signals)

        val prior = previous
        val newToasts = if (prior != null) {
            val improvable = if (prior.shedLevel > 0 && signals.shedLevel == 0) {
                FailureSignalsPolling.improvableCount(context, sessionId)
            } else {
                null
            }
            RecoveryAnnouncer.diff(prior, signals, improvable)
        } else {
            emptyList()
        }
        onTick(mapped, newToasts)
        previous = signals
        delay(POLL_INTERVAL_MILLIS)
    }
}

@Composable
private fun ToastSlot(toasts: List<RecoveryToast>, onToastShown: () -> Unit, modifier: Modifier = Modifier) {
    val activeToast = toasts.firstOrNull() ?: return
    LaunchedEffect(activeToast.id, activeToast.message) {
        delay(TOAST_VISIBLE_MILLIS)
        onToastShown()
    }
    Toast(
        message = activeToast.message,
        onUndo = null,
        modifier = modifier.testTag("failure-toast-${activeToast.id}"),
    )
}

/** guide's own header row (`ScreenHeader.kt`, WP2): `Box(...).heightIn(min = 44.dp)` around one
 * 21dp icon row with 10dp vertical padding — the 44dp minimum is what actually governs its
 * height, so that is the token this package clears by. `ScreenHeader` exports no named height
 * constant to import, so this mirrors its own guaranteed minimum precisely, cited here rather than
 * silently re-derived. */
private val HEADER_HEIGHT: Dp = 44.dp

/** Every banner/card renders pinned near the top of the current destination, below both the
 * status bar ([failureScreenInset]) and the destination's own header row ([HEADER_HEIGHT]) — see
 * class kdoc for why an overlay is this package's only placement option. Register R-164 (halt):
 * without the header clearance, a banner covered the drawer icon, live dot and search entirely —
 * `backlog/T01-threads-live-header.png` — making the header unreachable while any banner showed.
 */
@Composable
private fun BoxScope.BannerOverlay(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .failureScreenInset()
            .padding(top = HEADER_HEIGHT)
            .padding(OrtSpacing.lg)
            .testTag("failure-banner-overlay"),
    ) {
        content()
    }
}

/** Register R-147: bundles the four dismissable-banner labels (F5/F15) and, now, the two
 * "OK, continue" full screens (F14/F17) into one value so [FailurePresentationOverlay] stays under
 * detekt's `LongParameterList` rather than growing a parameter for every dismissable presentation. */
private data class FailureDismissState(
    val killedLabel: String?,
    val onDismissKilled: (String) -> Unit,
    val callLabel: String?,
    val onDismissCall: (String) -> Unit,
    val clockLabel: String?,
    val onDismissClock: (String) -> Unit,
    val interruptedLabel: String?,
    val onDismissInterrupted: (String) -> Unit,
)

@Suppress("LongParameterList")
@Composable
private fun BoxScope.FailurePresentationOverlay(
    presentation: FailurePresentation,
    actions: FailureHostActions,
    dismissedKilledLabel: String?,
    onDismissKilled: (String) -> Unit,
    dismissedCallLabel: String?,
    onDismissCall: (String) -> Unit,
    dismissedClockLabel: String?,
    onDismissClock: (String) -> Unit,
    dismissedInterruptedLabel: String?,
    onDismissInterrupted: (String) -> Unit,
) {
    val dismiss = FailureDismissState(
        killedLabel = dismissedKilledLabel,
        onDismissKilled = onDismissKilled,
        callLabel = dismissedCallLabel,
        onDismissCall = onDismissCall,
        clockLabel = dismissedClockLabel,
        onDismissClock = onDismissClock,
        interruptedLabel = dismissedInterruptedLabel,
        onDismissInterrupted = onDismissInterrupted,
    )
    when (presentation) {
        is FailurePresentation.Route, is FailurePresentation.StorageHalt, is FailurePresentation.Usb,
        is FailurePresentation.Reconcile, is FailurePresentation.Migration, is FailurePresentation.AssetSwap,
        is FailurePresentation.Calibration,
        -> TakeoverOrScreen(presentation, actions, dismiss)

        is FailurePresentation.Clock -> if (presentation.state.windowLabel != dismiss.clockLabel) {
            TakeoverOrScreen(presentation, actions, dismiss)
        }
        is FailurePresentation.Interrupted -> if (presentation.state.gapLabel != dismiss.interruptedLabel) {
            TakeoverOrScreen(presentation, actions, dismiss)
        }

        is FailurePresentation.Disconnect -> BannerOverlay {
            FailDisconnectBanner(
                state = presentation.state,
                onRetry = actions.onRetryInput,
                onChooseAnotherInput = actions.onChooseAnotherInput,
            )
        }
        is FailurePresentation.Level -> BannerOverlay { FailLevelBanner(state = presentation.state) }
        is FailurePresentation.Killed -> if (presentation.state.stoppedAtLabel != dismiss.killedLabel) {
            BannerOverlay {
                FailKilledBanner(
                    state = presentation.state,
                    onOpenBatterySettings = actions.onOpenBatteryExemptionSettings,
                    onDismiss = { onDismissKilled(presentation.state.stoppedAtLabel) },
                )
            }
        }
        is FailurePresentation.StorageWarning -> BannerOverlay {
            FailStorageWarningBanner(
                state = presentation.state,
                onFreeUpSpace = actions.onOpenStorageSettings,
                onOpenRetentionSettings = actions.onOpenRetentionSettings,
            )
        }
        is FailurePresentation.StorageAudioPaused -> BannerOverlay {
            FailStorageAudioPausedBanner(
                state = presentation.state,
                onFreeUpSpace = actions.onOpenStorageSettings,
                onOpenRetentionSettings = actions.onOpenRetentionSettings,
            )
        }
        is FailurePresentation.Thermal -> BannerOverlay { FailThermalBanner(state = presentation.state) }
        is FailurePresentation.Backlog -> BannerOverlay { FailBacklogBanner(state = presentation.state) }
        is FailurePresentation.Rig -> BannerOverlay {
            FailRigBanner(
                state = presentation.state,
                onReconnect = actions.onReconnectRig,
                onSetFrequencyByHand = actions.onSetFrequencyByHand,
            )
        }
        is FailurePresentation.Call -> if (presentation.state.durationLabel != dismiss.callLabel) {
            BannerOverlay {
                FailCallBanner(
                    state = presentation.state,
                    onDismiss = { onDismissCall(presentation.state.durationLabel) },
                )
            }
        }
        FailurePresentation.None -> Unit
    }
}

/** F1/F14/F16/F17/F19/F20/F21/F22 — every full-screen takeover or standalone screen this package
 * owns. Register R-147: F14/F17 joined this group (were a small [BannerOverlay] card) — each is
 * dismissed the same way F5/F15 are, keyed by its own distinguishing label so a *new* clock jump
 * or interruption re-shows after a prior one was dismissed. */
@Composable
private fun TakeoverOrScreen(
    presentation: FailurePresentation,
    actions: FailureHostActions,
    dismiss: FailureDismissState,
) {
    when (presentation) {
        is FailurePresentation.Route -> FailRouteScreen(
            state = presentation.state,
            onChooseInputAgain = actions.onChooseAnotherInput,
            onEndSession = actions.onEndSession,
        )
        is FailurePresentation.StorageHalt -> FailStorageHaltScreen(
            state = presentation.state,
            onFreeUpSpace = actions.onOpenStorageSettings,
        )
        is FailurePresentation.Usb -> FailUsbScreen(
            state = presentation.state,
            onGrantPermission = actions.onRequestUsbPermission,
            onContinueWithoutRadio = actions.onSetFrequencyByHand,
        )
        is FailurePresentation.Reconcile ->
            FailReconcileScreen(state = presentation.state, onImport = {}, onLeaveAsIs = {})
        is FailurePresentation.Migration ->
            FailMigrationScreen(state = presentation.state, onRebuildNow = {}, onSaveDiagnosticBundle = {})
        is FailurePresentation.AssetSwap ->
            FailAssetSwapScreen(state = presentation.state, onSelectOption = {}, onDone = {})
        is FailurePresentation.Calibration -> FailCalibrationScreen(state = presentation.state, onInstall = {})
        is FailurePresentation.Clock -> FailClockScreen(
            state = presentation.state,
            onContinue = { dismiss.onDismissClock(presentation.state.windowLabel) },
        )
        is FailurePresentation.Interrupted -> FailInterruptedScreen(
            state = presentation.state,
            onContinue = { dismiss.onDismissInterrupted(presentation.state.gapLabel) },
        )
        else -> Unit
    }
}

private const val POLL_INTERVAL_MILLIS = 2_000L
private const val TOAST_VISIBLE_MILLIS = 4_000L
