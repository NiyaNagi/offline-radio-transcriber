package org.ort.app.ui.setup

/**
 * One screen of the guided setup sequence (build-plan P8, ui-conformance-plan WP9, register
 * R-080..R-084, `Flow-Setup.dc.html`). [VERIFY] and [ROUTE_MISMATCH] are reached only as a live
 * result of [INPUT]'s "Verify this input" action — [SetupStateMachine] never resumes directly into
 * either of them on a fresh launch (guide's "no stage advances unverified" — a stale in-flight
 * check is not something to resume into, the operator re-triggers it from [INPUT]).
 */
public enum class SetupStep {
    WELCOME,
    MICROPHONE,
    MICROPHONE_DENIED,
    NOTIFICATIONS,
    INPUT,
    VERIFY,
    ROUTE_MISMATCH,
    LEVEL,
    OVERNIGHT,
    RADIO,
    RADIO_USB,
    RADIO_VERIFIED,
    READY,
}

/** Total steps the header's step indicator counts against (`Flow-Setup.dc.html`: "7 of 7" on
 * S12) — one segment per numbered stage. [SetupStep.WELCOME] and the two radio sub-screens
 * ([SetupStep.RADIO_USB]/[SetupStep.RADIO_VERIFIED]) share stage 6's segment with [SetupStep.RADIO]. */
public const val SETUP_TOTAL_STEPS: Int = 7

/** The step-indicator segment a given [SetupStep] lights up — `Setup-Welcome.dc.html` has no
 * indicator at all (returns `null`); every other board names its "n of 7" explicitly. */
public fun SetupStep.indicatorIndex(): Int? = when (this) {
    SetupStep.WELCOME -> null
    SetupStep.MICROPHONE, SetupStep.MICROPHONE_DENIED -> 1
    SetupStep.NOTIFICATIONS -> 2
    SetupStep.INPUT, SetupStep.VERIFY, SetupStep.ROUTE_MISMATCH -> 3
    SetupStep.LEVEL -> 4
    SetupStep.OVERNIGHT -> 5
    SetupStep.RADIO, SetupStep.RADIO_USB, SetupStep.RADIO_VERIFIED -> 6
    SetupStep.READY -> 7
}

/** Whether this step's indicator segment should render halted (`halt/text`) — only the route
 * mismatch board halts (guide §6.10, `Setup-Route-Mismatch.dc.html`). */
public fun SetupStep.isHalted(): Boolean = this == SetupStep.ROUTE_MISMATCH
