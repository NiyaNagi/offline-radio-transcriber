package org.ort.app.ui.setup

/** S04's whole view-state (`Setup-Input.dc.html`) — the enumerated routes and which one, if any,
 * is currently selected. Built by [InputRouteEnumerator]; [InputScreen] is a pure function of it.
 * [presetLabel] (D33, FR-CAP-9) is the chosen [org.ort.core.capture.CaptureMode]'s
 * [org.ort.core.capture.CaptureMode.operatorLabel] — the preset chip's "preset by <mode>" text —
 * and is `null` once the operator has overridden the audio-route preset ([SetupStore.modeOverriddenAudio]),
 * per the board's own "hidden when the operator overrode" rule. */
public data class InputViewState(
    val routes: List<InputRouteOption>,
    val selectedId: String?,
    val presetLabel: String? = null,
)

/** S05's whole view-state (`Setup-Verify.dc.html`) — the input being checked and where
 * [RealRouteCheck] currently is. */
public data class VerifyViewState(val inputLabel: String, val check: RouteCheckState?)
