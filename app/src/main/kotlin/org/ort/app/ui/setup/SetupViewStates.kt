package org.ort.app.ui.setup

/** S04's whole view-state (`Setup-Input.dc.html`) — the enumerated routes and which one, if any,
 * is currently selected. Built by [InputRouteEnumerator]; [InputScreen] is a pure function of it. */
public data class InputViewState(val routes: List<InputRouteOption>, val selectedId: String?)

/** S05's whole view-state (`Setup-Verify.dc.html`) — the input being checked and where
 * [RealRouteCheck] currently is. */
public data class VerifyViewState(val inputLabel: String, val check: RouteCheckState?)
