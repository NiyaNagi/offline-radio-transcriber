package org.ort.app.ui.setup

/**
 * One screen of the guided setup sequence (build-plan P8/P19, `spec/e2e-capture-modes-plan.md`
 * WPD, `Flow-Setup.dc.html`/`Flow-Mode.dc.html`). [VERIFY] and [ROUTE_MISMATCH] are reached only
 * as a live result of [INPUT]'s "Verify this input" action ([SetupStateMachine] never resumes
 * directly into either of them on a fresh launch — a stale in-flight check is not something to
 * resume into, the operator re-triggers it from [INPUT]). [RADIO_USB] and [RADIO_VERIFIED] are
 * the same kind of transient, live-only destination (reached from [RADIO]/[RIG_TRANSPORT] via
 * `navigateForward`, never returned by [SetupStateMachine.stepFor] — unchanged since before P19).
 *
 * D33 (P19/WPD) adds four steps: [MODE] (before [MICROPHONE] — S00, "how is the radio
 * connected?"), [BLUETOOTH_PERMISSION] (after [MICROPHONE], Bluetooth mode only — S02c, the
 * `BLUETOOTH_CONNECT` rationale), [RIG_TRANSPORT] (after [RADIO], when a real rig — not the null
 * module — was chosen — S09b, "how is the rig linked?"), and [RIG_BLUETOOTH] (after
 * [RIG_TRANSPORT] when the chosen transport is Bluetooth SPP — S10b, the paired-device list and
 * open/identify/verify checklist). Unlike [RADIO_USB]/[RADIO_VERIFIED], [RIG_TRANSPORT] and
 * [RIG_BLUETOOTH] ARE returned by [SetupStateMachine.stepFor] — FR-RIG-13 treats the rig
 * transport as a genuinely separate, resumable decision from which rig was chosen, not a live-only
 * detail.
 */
public enum class SetupStep {
    WELCOME,
    MODE,
    MICROPHONE,
    MICROPHONE_DENIED,
    BLUETOOTH_PERMISSION,
    NOTIFICATIONS,
    INPUT,
    VERIFY,
    ROUTE_MISMATCH,
    LEVEL,
    OVERNIGHT,
    RADIO,
    RIG_TRANSPORT,
    RADIO_USB,
    RIG_BLUETOOTH,
    RADIO_VERIFIED,
    READY,
}

/** Total steps the header's step indicator counts against (`Setup-Mode.dc.html`: "1 of 8",
 * `Setup-Done.dc.html`: "8 of 8") — one segment per numbered stage (design-intent.md §2, D33:
 * "eight stages since 2026-09-10"). [SetupStep.WELCOME] and every step sharing a stage's segment
 * with another (the mic sub-screens, the radio sub-screens) do not each get their own segment. */
public const val SETUP_TOTAL_STEPS: Int = 8

/** The step-indicator segment a given [SetupStep] lights up — `Setup-Welcome.dc.html` has no
 * indicator at all (returns `null`); every other board names its "n of 8" explicitly
 * (design-intent.md §2's stage table: Mode 1, Mic/Mic-denied/BT-permission 2, Notify 3,
 * Input/Verify/Mismatch 4, Level 5, Overnight 6, Radio/Transport/USB/Bluetooth/Verified 7,
 * Ready 8). */
public fun SetupStep.indicatorIndex(): Int? = when (this) {
    SetupStep.WELCOME -> null
    SetupStep.MODE -> 1
    SetupStep.MICROPHONE, SetupStep.MICROPHONE_DENIED, SetupStep.BLUETOOTH_PERMISSION -> 2
    SetupStep.NOTIFICATIONS -> 3
    SetupStep.INPUT, SetupStep.VERIFY, SetupStep.ROUTE_MISMATCH -> 4
    SetupStep.LEVEL -> 5
    SetupStep.OVERNIGHT -> 6
    SetupStep.RADIO,
    SetupStep.RIG_TRANSPORT,
    SetupStep.RADIO_USB,
    SetupStep.RIG_BLUETOOTH,
    SetupStep.RADIO_VERIFIED,
    -> 7
    SetupStep.READY -> 8
}

/** Whether this step's indicator segment should render halted (`halt/text`) — only the route
 * mismatch board halts (guide §6.10, `Setup-Route-Mismatch.dc.html`). */
public fun SetupStep.isHalted(): Boolean = this == SetupStep.ROUTE_MISMATCH
