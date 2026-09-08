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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
 *
 * Register R-178: [content] now takes the currently-showing banner's real, measured height (`0.dp`
 * when no banner shows — a takeover already covers the whole screen, and `None` has nothing to
 * clear) so the caller can pad its own content column by it — `ReaderActivity.kt` does exactly
 * that, passing it on to [org.ort.app.ui.navigation.OrtNavHost]'s own `contentTopPadding`. Without
 * this, a banner that grows taller (font scale 2.0 wraps its copy onto more lines) only visually
 * covered more of the destination content sitting at its fixed position underneath, rather than
 * making room for itself.
 */
@Composable
public fun FailureHost(
    sessionId: String?,
    modifier: Modifier = Modifier,
    actions: FailureHostActions = FailureHostActions(),
    content: @Composable (contentTopPadding: Dp) -> Unit,
) {
    val context = LocalContext.current
    var presentation by remember { mutableStateOf<FailurePresentation>(FailurePresentation.None) }
    var toasts by remember { mutableStateOf<List<RecoveryToast>>(emptyList()) }
    var dismissedKilledLabel by remember(sessionId) { mutableStateOf<String?>(null) }
    var dismissedCallLabel by remember(sessionId) { mutableStateOf<String?>(null) }
    var dismissedClockLabel by remember(sessionId) { mutableStateOf<String?>(null) }
    var dismissedInterruptedLabel by remember(sessionId) { mutableStateOf<String?>(null) }
    var bannerHeight by remember { mutableStateOf(0.dp) }

    LaunchedEffect(sessionId) {
        pollFailureSignals(context, sessionId) { mapped, newToasts ->
            presentation = mapped
            if (newToasts.isNotEmpty()) toasts = toasts + newToasts
        }
    }

    val dismiss = FailureDismissState(
        killedLabel = dismissedKilledLabel,
        onDismissKilled = { dismissedKilledLabel = it },
        callLabel = dismissedCallLabel,
        onDismissCall = { dismissedCallLabel = it },
        clockLabel = dismissedClockLabel,
        onDismissClock = { dismissedClockLabel = it },
        interruptedLabel = dismissedInterruptedLabel,
        onDismissInterrupted = { dismissedInterruptedLabel = it },
    )

    Box(modifier = modifier.fillMaxSize()) {
        content(bannerHeight)

        FailurePresentationOverlay(
            presentation = presentation,
            actions = actions,
            dismiss = dismiss,
            onBannerHeightChanged = { bannerHeight = it },
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
 *
 * Register R-178: [onHeightMeasured] reports this box's own height — deliberately measured
 * *inside* the `padding(top = HEADER_HEIGHT)` layer (that padding is already accounted for by the
 * destination content's own natural position, right after its header) but *including* the
 * `OrtSpacing.lg` padding around the banner's content, so the reported value is exactly how much
 * *extra* room the destination content needs to clear this banner, not the banner's absolute
 * position on screen.
 */
@Composable
private fun BoxScope.BannerOverlay(onHeightMeasured: (Dp) -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .failureScreenInset()
            .padding(top = HEADER_HEIGHT)
            // `onGloballyPositioned` reports the size of the node *at this point in the chain* —
            // everything to its right (padding(lg) and the content) but nothing to its left, so
            // this deliberately sits between the two `padding` calls: it measures `lg + content +
            // lg`, excluding `HEADER_HEIGHT` (already accounted for by the content column's own
            // natural position, right after its header — see this function's own kdoc).
            .onGloballyPositioned { coordinates ->
                onHeightMeasured(with(density) { coordinates.size.height.toDp() })
            }
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

/** Every [FailurePresentation] id that renders via [TakeoverOrScreen] — a full-screen takeover or
 * standalone screen, never a [BannerOverlay]. Pulled out into its own `Set` so both
 * [FailurePresentationOverlay]'s routing `when` and [isTakeoverShown]'s dismiss check read the
 * same membership rather than two `when` clauses that could drift apart. */
private val TAKEOVER_PRESENTATIONS: Set<Class<out FailurePresentation>> = setOf(
    FailurePresentation.Route::class.java,
    FailurePresentation.StorageHalt::class.java,
    FailurePresentation.Usb::class.java,
    FailurePresentation.Reconcile::class.java,
    FailurePresentation.Migration::class.java,
    FailurePresentation.AssetSwap::class.java,
    FailurePresentation.Calibration::class.java,
    FailurePresentation.Clock::class.java,
    FailurePresentation.Interrupted::class.java,
)

/** Register R-147: F14/F17 ([FailurePresentation.Clock]/[FailurePresentation.Interrupted]) are
 * takeovers that are also dismissable, keyed by their own distinguishing label (so a *new* clock
 * jump or interruption re-shows after a prior one was dismissed) — every other takeover id always
 * shows. */
private fun isTakeoverShown(presentation: FailurePresentation, dismiss: FailureDismissState): Boolean =
    when (presentation) {
        is FailurePresentation.Clock -> presentation.state.windowLabel != dismiss.clockLabel
        is FailurePresentation.Interrupted -> presentation.state.gapLabel != dismiss.interruptedLabel
        else -> true
    }

/** The one place every real failure signal becomes what the operator sees, split from
 * [FailureBannerOverlay] purely to keep both functions under detekt's `LongMethod` — routing
 * decides *which* of the two shapes (full-screen takeover, or a [BannerOverlay] near the top) a
 * presentation renders as; [FailureBannerOverlay] draws every banner id's own board copy. */
@Composable
private fun BoxScope.FailurePresentationOverlay(
    presentation: FailurePresentation,
    actions: FailureHostActions,
    dismiss: FailureDismissState,
    onBannerHeightChanged: (Dp) -> Unit,
) {
    if (TAKEOVER_PRESENTATIONS.contains(presentation::class.java)) {
        if (isTakeoverShown(presentation, dismiss)) {
            onBannerHeightChanged(0.dp)
            TakeoverOrScreen(presentation, actions, dismiss)
        }
        return
    }
    FailureBannerOverlay(presentation, actions, dismiss, onBannerHeightChanged)
}

/** Every banner-shaped [FailurePresentation] id — anything [FailurePresentationOverlay] did not
 * already route to [TakeoverOrScreen]. Deliberately not exhaustive over the full sealed interface
 * ([TAKEOVER_PRESENTATIONS]'s ids fall to `else`, unreachable in practice — the caller never routes
 * them here) — see [FailurePresentationOverlay]'s own kdoc for why the split exists. */
@Composable
private fun BoxScope.FailureBannerOverlay(
    presentation: FailurePresentation,
    actions: FailureHostActions,
    dismiss: FailureDismissState,
    onBannerHeightChanged: (Dp) -> Unit,
) {
    when (presentation) {
        is FailurePresentation.Disconnect -> BannerOverlay(onBannerHeightChanged) {
            FailDisconnectBanner(
                state = presentation.state,
                onRetry = actions.onRetryInput,
                onChooseAnotherInput = actions.onChooseAnotherInput,
            )
        }
        is FailurePresentation.Level -> BannerOverlay(onBannerHeightChanged) {
            FailLevelBanner(state = presentation.state)
        }
        is FailurePresentation.Killed -> if (presentation.state.stoppedAtLabel != dismiss.killedLabel) {
            BannerOverlay(onBannerHeightChanged) {
                FailKilledBanner(
                    state = presentation.state,
                    onOpenBatterySettings = actions.onOpenBatteryExemptionSettings,
                    onDismiss = { dismiss.onDismissKilled(presentation.state.stoppedAtLabel) },
                )
            }
        } else {
            onBannerHeightChanged(0.dp)
        }
        is FailurePresentation.StorageWarning -> BannerOverlay(onBannerHeightChanged) {
            FailStorageWarningBanner(
                state = presentation.state,
                onFreeUpSpace = actions.onOpenStorageSettings,
                onOpenRetentionSettings = actions.onOpenRetentionSettings,
            )
        }
        is FailurePresentation.StorageAudioPaused -> BannerOverlay(onBannerHeightChanged) {
            FailStorageAudioPausedBanner(
                state = presentation.state,
                onFreeUpSpace = actions.onOpenStorageSettings,
                onOpenRetentionSettings = actions.onOpenRetentionSettings,
            )
        }
        is FailurePresentation.Thermal -> BannerOverlay(onBannerHeightChanged) {
            FailThermalBanner(state = presentation.state)
        }
        is FailurePresentation.Backlog -> BannerOverlay(onBannerHeightChanged) {
            FailBacklogBanner(state = presentation.state)
        }
        is FailurePresentation.Rig -> BannerOverlay(onBannerHeightChanged) {
            FailRigBanner(
                state = presentation.state,
                onReconnect = actions.onReconnectRig,
                onSetFrequencyByHand = actions.onSetFrequencyByHand,
            )
        }
        is FailurePresentation.Call -> if (presentation.state.durationLabel != dismiss.callLabel) {
            BannerOverlay(onBannerHeightChanged) {
                FailCallBanner(
                    state = presentation.state,
                    onDismiss = { dismiss.onDismissCall(presentation.state.durationLabel) },
                )
            }
        } else {
            onBannerHeightChanged(0.dp)
        }
        FailurePresentation.None -> onBannerHeightChanged(0.dp)
        else -> Unit // TAKEOVER_PRESENTATIONS's ids — unreachable, see this function's own kdoc.
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
