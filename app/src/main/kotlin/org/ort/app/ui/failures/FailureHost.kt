@file:Suppress("MatchingDeclarationName") // FailureHostActions is one of several public declarations here.

package org.ort.app.ui.failures

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    // Round 14 (ui-conformance-plan WP3, coordinator-directed cross-package addendum — R-448's own
    // own report named these missing: no callback here navigated to the parent its own new "‹
    // <parent>" header now names). `onOpenEarlierNights` is new; `onOpenStorageSettings` (F19) and
    // this new `onOpenModels` (F21/F22 — the same real `SettingsScreenId.ASSETS` route R-139's
    // "Install a model" and round 12's `Improve-Done` `Install` both already use) are the two
    // "reuse existing" halves of that report.
    public val onOpenEarlierNights: () -> Unit = {},
    public val onOpenModels: () -> Unit = {},
    /** FR-AST-4 (register R-448 follow-up): F21's "Activate now" option — a direct
     * [org.ort.app.ui.data.ModelsController.activateStaged] call. Safe to wire unconditionally: that
     * function itself refuses, with no effect, while a session is still live, so this button does
     * nothing while F21 is even showing and takes real effect only once the session it was staged
     * behind has actually ended — never a forced mid-session activation. */
    public val onActivateStagedAsset: () -> Unit = {},
    /** E2-G05 (F23, `Fail-Bluetooth-Audio.dc.html`): "Switch to a wired input" → S04 — WPE is
     * adding the destination; defaulted to a no-op so both packages compile independently until
     * it is wired. Deliberately its own callback, not [onChooseAnotherInput]: F23's own action
     * names a specific route (wired), not "pick anything else" the way F2's generic recovery does. */
    public val onSwitchToWiredInput: () -> Unit = {},
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
 * making room for itself. The `content`/`contentTopPadding` contract itself is unchanged by
 * R-300's own fix below (see [BannerOverlay]'s kdoc) — WP4 and every other destination-owning
 * package keep reading it exactly as before.
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
    // FR-AST-4 (register R-448 follow-up): F21's own radio selection — purely local UI state, never
    // round-tripped through the polled `AssetSwapViewState` (which always maps `selectedOption = 0`,
    // the honest default every fresh snapshot has no way to remember on its own).
    var assetSwapSelectedOption by remember { mutableStateOf(0) }

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

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        content(bannerHeight)

        // Register R-300 (halt): a banner tall enough (`Fail-Storage`'s "How this unfolded" card,
        // at font scale 2.0) used to push the destination's own title/controls almost entirely off
        // screen — `contentTopPadding` faithfully reported the banner's *real* height, but nothing
        // bounded that height in the first place. `maxHeight` (this `BoxWithConstraints`'s own,
        // i.e. the full viewport) is threaded down so [BannerOverlay] can cap itself.
        FailurePresentationOverlay(
            presentation = presentation,
            actions = actions,
            dismiss = dismiss,
            onBannerHeightChanged = { bannerHeight = it },
            viewportHeight = maxHeight,
            assetSwapSelectedOption = assetSwapSelectedOption,
            onAssetSwapSelectOption = { assetSwapSelectedOption = it },
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

/** Register R-300: the fraction of the current viewport's height ([FailureHost]'s own
 * `BoxWithConstraints`) a banner may ever occupy — `Fail-Storage`'s own "How this unfolded" card
 * was tall enough, at font scale 2.0, to consume nearly the whole screen otherwise
 * (`storage-warn/N04-capture-status-banner@2x-pass3.png`), squeezing the destination's own title
 * and controls (`capture-status-stop`) into a sliver behind the pinned live bar. 40% leaves the
 * destination's own header, title and at least one real control genuinely reachable underneath, on
 * every device this app targets. */
private const val BANNER_MAX_HEIGHT_FRACTION = 0.4f

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
 *
 * Register R-300 (halt): that reported height used to be *unbounded* — a genuinely tall banner
 * (`Fail-Storage`'s "How this unfolded" card at font scale 2.0) pushed the destination almost
 * entirely off screen. [viewportHeight] ([FailureHost]'s own `BoxWithConstraints` height, scaled
 * here by [BANNER_MAX_HEIGHT_FRACTION]) caps this box's own height — content taller than that now
 * scrolls *inside the banner itself* (`Modifier.verticalScroll`, clipped to the cap) rather than
 * growing the box, and [onHeightMeasured] therefore never reports more than the cap either, so
 * `contentTopPadding` downstream is bounded the same way. `heightIn(max = …)` sits *outside*
 * `onGloballyPositioned` in the chain (so the reported size already reflects the cap) but
 * *outside* `verticalScroll` too (so the cap constrains the scrollable viewport, not just its
 * virtual content) — see the modifier order below.
 */
@Composable
private fun BoxScope.BannerOverlay(
    onHeightMeasured: (Dp) -> Unit,
    viewportHeight: Dp,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .failureScreenInset()
            .padding(top = HEADER_HEIGHT)
            .heightIn(max = viewportHeight * BANNER_MAX_HEIGHT_FRACTION)
            // `onGloballyPositioned` reports the size of the node *at this point in the chain* —
            // everything to its right (the scroll, padding(lg) and the content) but nothing to its
            // left, so this deliberately sits between `heightIn` and `verticalScroll`: it measures
            // `min(lg + content + lg, the cap above)`, excluding `HEADER_HEIGHT` (already accounted
            // for by the content column's own natural position, right after its header).
            .onGloballyPositioned { coordinates ->
                onHeightMeasured(with(density) { coordinates.size.height.toDp() })
            }
            .verticalScroll(rememberScrollState())
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
    viewportHeight: Dp,
    assetSwapSelectedOption: Int,
    onAssetSwapSelectOption: (Int) -> Unit,
) {
    if (TAKEOVER_PRESENTATIONS.contains(presentation::class.java)) {
        if (isTakeoverShown(presentation, dismiss)) {
            onBannerHeightChanged(0.dp)
            TakeoverOrScreen(presentation, actions, dismiss, assetSwapSelectedOption, onAssetSwapSelectOption)
        }
        return
    }
    FailureBannerOverlay(presentation, actions, dismiss, onBannerHeightChanged, viewportHeight)
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
    viewportHeight: Dp,
) {
    when (presentation) {
        is FailurePresentation.Disconnect -> BannerOverlay(onBannerHeightChanged, viewportHeight) {
            FailDisconnectBanner(
                state = presentation.state,
                onRetry = actions.onRetryInput,
                onChooseAnotherInput = actions.onChooseAnotherInput,
            )
        }
        is FailurePresentation.BluetoothAudioDropped -> BannerOverlay(onBannerHeightChanged, viewportHeight) {
            FailBluetoothAudioDroppedBanner(
                state = presentation.state,
                onRetryNow = actions.onRetryInput,
                onSwitchToWiredInput = actions.onSwitchToWiredInput,
            )
        }
        is FailurePresentation.Level -> BannerOverlay(onBannerHeightChanged, viewportHeight) {
            FailLevelBanner(state = presentation.state)
        }
        is FailurePresentation.Killed -> if (presentation.state.stoppedAtLabel != dismiss.killedLabel) {
            BannerOverlay(onBannerHeightChanged, viewportHeight) {
                FailKilledBanner(
                    state = presentation.state,
                    onOpenBatterySettings = actions.onOpenBatteryExemptionSettings,
                    onDismiss = { dismiss.onDismissKilled(presentation.state.stoppedAtLabel) },
                )
            }
        } else {
            onBannerHeightChanged(0.dp)
        }
        is FailurePresentation.StorageWarning -> BannerOverlay(onBannerHeightChanged, viewportHeight) {
            FailStorageWarningBanner(
                state = presentation.state,
                onFreeUpSpace = actions.onOpenStorageSettings,
                onOpenRetentionSettings = actions.onOpenRetentionSettings,
            )
        }
        is FailurePresentation.StorageAudioPaused -> BannerOverlay(onBannerHeightChanged, viewportHeight) {
            FailStorageAudioPausedBanner(
                state = presentation.state,
                onFreeUpSpace = actions.onOpenStorageSettings,
                onOpenRetentionSettings = actions.onOpenRetentionSettings,
            )
        }
        is FailurePresentation.Thermal -> BannerOverlay(onBannerHeightChanged, viewportHeight) {
            FailThermalBanner(state = presentation.state)
        }
        is FailurePresentation.Backlog -> BannerOverlay(onBannerHeightChanged, viewportHeight) {
            FailBacklogBanner(state = presentation.state)
        }
        is FailurePresentation.Rig -> BannerOverlay(onBannerHeightChanged, viewportHeight) {
            FailRigBanner(
                state = presentation.state,
                onReconnect = actions.onReconnectRig,
                onSetFrequencyByHand = actions.onSetFrequencyByHand,
            )
        }
        is FailurePresentation.Call -> if (presentation.state.durationLabel != dismiss.callLabel) {
            BannerOverlay(onBannerHeightChanged, viewportHeight) {
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
    assetSwapSelectedOption: Int,
    onAssetSwapSelectOption: (Int) -> Unit,
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
            // R-448 (round 14): the board's own "‹ Storage and retention" header reuses this same
            // dismiss — its real target is `actions.onOpenStorageSettings`, the same real
            // `Settings-Storage` route `FailStorageHaltScreen`'s own `onFreeUpSpace` above uses.
            FailReconcileScreen(state = presentation.state, onImport = {}, onLeaveAsIs = actions.onOpenStorageSettings)
        is FailurePresentation.Migration ->
            FailMigrationScreen(state = presentation.state, onRebuildNow = {}, onSaveDiagnosticBundle = {})
        is FailurePresentation.AssetSwap ->
            // R-448: the board's own "‹ Models and lexicon" header reuses this same dismiss.
            // FR-AST-4 follow-up: `selectedOption` is overridden with this host's own locally-held
            // choice (the mapped state always maps `0`, a fresh snapshot's own honest default —
            // see [FailureHost]'s own `assetSwapSelectedOption` comment); `Done` performs whichever
            // of the two real actions is currently selected — index 1 ("Activate now") really calls
            // `ModelsController.activateStaged` (via `actions.onActivateStagedAsset`, safe even
            // mid-session — see that action's own kdoc), anything else just leaves for Settings ›
            // Assets exactly as before this follow-up.
            FailAssetSwapScreen(
                state = presentation.state.copy(selectedOption = assetSwapSelectedOption),
                onSelectOption = onAssetSwapSelectOption,
                onDone = {
                    if (assetSwapSelectedOption == ASSET_SWAP_ACTIVATE_NOW_OPTION) {
                        actions.onActivateStagedAsset()
                    } else {
                        actions.onOpenModels()
                    }
                },
            )
        is FailurePresentation.Calibration -> FailCalibrationScreen(
            state = presentation.state,
            onInstall = {},
            // R-448: F22's own new `onBack` — the board's own "‹ Models and lexicon" header.
            onBack = actions.onOpenModels,
        )
        is FailurePresentation.Clock -> FailClockScreen(
            state = presentation.state,
            // R-448: the board's own "‹ Earlier nights" header reuses this same dismiss — real
            // navigation added alongside the existing dismiss, not in place of it, since dismissing
            // the takeover is still correct regardless of where `onOpenEarlierNights` then lands.
            onContinue = {
                dismiss.onDismissClock(presentation.state.windowLabel)
                actions.onOpenEarlierNights()
            },
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

/** FR-AST-4: [assetSwapViewState][FailureMapper]'s own option order — index 1, "Activate now", is
 * the one real action distinct from the default "wait" (index 0). */
private const val ASSET_SWAP_ACTIVATE_NOW_OPTION = 1
