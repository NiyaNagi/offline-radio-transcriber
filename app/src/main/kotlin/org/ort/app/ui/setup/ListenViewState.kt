package org.ort.app.ui.setup

import org.ort.capture.android.CaptureGain

/**
 * [ListenScreen]'s whole view-state (`Setup-Listen.dc.html`) — P39 (D58) merged the three that used to
 * exist here (`InputViewState`, `VerifyViewState`, and the bare `LevelCheckState?` the level step took)
 * into one, because the three screens they backed are one screen now.
 *
 * [inputVerified] is deliberately **not** derived from [check]. A process death between verifying and
 * continuing leaves [check] `null` while [SetupStore.inputVerified] is true, and deriving it would make
 * a resumed screen silently re-ask for a verification that already happened; reading the stored fact
 * makes the resume honest. It is also the one gate with no acknowledged escape (constitution IV) —
 * see [SetupSnapshot.inputVerified].
 */
public data class ListenViewState(
    val routes: List<InputRouteOption>,
    val selectedId: String?,
    /** D33, FR-CAP-9 — the chosen mode's own `operatorLabel` for the preset chip, `null` once the
     * operator has overridden the audio-route preset, **or** once [presetChipStateFor] finds the mode's
     * preferred route was never enumerated at all (R-816: [presetUnavailableText] carries the honest
     * text for that case instead; the two are mutually exclusive). */
    val presetLabel: String? = null,
    val presetUnavailableText: String? = null,
    /** The chosen route's own label, as the route check names the device it is talking about. */
    val inputLabel: String = "",
    val inputVerified: Boolean = false,
    /** `null` until the check emits — the route-check section is absent entirely until the operator
     * asks, never a checklist of four empty rings nobody started. */
    val check: RouteCheckState? = null,
    /** Whether the operator has actually tapped `Verify this input`. Distinct from `check != null`
     * because the first emission is not instantaneous, and a checklist that appears a frame after the
     * tap reads as a button that did nothing. */
    val checkRunning: Boolean = false,
    val level: LevelCheckState? = null,
    val gainDb: Int = CaptureGain.MIN_GAIN_DB,
)
