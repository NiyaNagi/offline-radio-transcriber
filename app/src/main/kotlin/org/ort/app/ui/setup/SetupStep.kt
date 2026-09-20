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
 *
 * P22 (D43, NFR-6c, FR-AST-10..12) adds two more: [JURISDICTION_NOTICE] (right after [WELCOME] —
 * NFR-6c's jurisdiction/legality notice, shown exactly once on first run, AC-166) and [MODELS]
 * (right before [READY] — FR-AST-10's download step for whatever the detected tier requires that
 * this build did not bundle).
 *
 * P28 (D42, FR-ANL-10, AC-180) adds [ANALYTICS_CONSENT], right after [MODELS] and before [READY]
 * — explains tier 1 and offers tiers 2/3 as an explicit, unchecked choice, shown exactly once (the
 * identical shown-once shape [JURISDICTION_NOTICE] already established), and — unlike [MODELS] —
 * never a hard gate: [SetupStateMachine.stepFor] advances past it the instant it has been seen,
 * whatever the two toggles are left at (FR-ANL-10's own "declining leaves every other function
 * fully working").
 *
 * **R-1087 (register): [MODELS] and [ANALYTICS_CONSENT] used to share [READY]'s own indicator
 * segment** rather than each getting one of their own — the earlier doc comment here called that
 * deliberate ("renumbering every other stage's artboard is outside this unit's file ownership"),
 * but the consequence was that `setup-models/S11a-models` and
 * `setup-analytics-consent/S11b-analytics-consent` both rendered an identical "8 of 8", telling
 * the operator twice that they were on the last step. Fixed by giving both their own position (see
 * [indicatorIndex]'s own doc comment) and renumbering every affected artboard in the same change —
 * the ownership boundary that excuse relied on no longer applies once the register names the
 * defect this caused.
 */
public enum class SetupStep {
    WELCOME,
    JURISDICTION_NOTICE,
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
    MODELS,
    ANALYTICS_CONSENT,
    READY,
}

/** Total steps the header's step indicator counts against (`Setup-Mode.dc.html`: "1 of 10",
 * `Setup-Done.dc.html`: "10 of 10") — one segment per numbered stage (design-intent.md §2).
 * [SetupStep.WELCOME] and every step sharing a stage's segment with another (the mic sub-screens,
 * the radio sub-screens) do not each get their own segment.
 *
 * **R-1087: this is a fixed total, not a per-run count.** The step list [SetupStateMachine.stepFor]
 * actually walks on a given run is conditional — [SetupStep.BLUETOOTH_PERMISSION] only appears in
 * Bluetooth capture mode, the whole [SetupStep.RIG_TRANSPORT]/[SetupStep.RIG_BLUETOOTH] branch only
 * with a real rig chosen, [SetupStep.MODELS] only when something this build did not bundle needs
 * fetching — so "the steps this run will show" is not known until several stages downstream of
 * where the operator currently is, and a denominator that changed mid-run as those conditions
 * resolved (8 becoming 6 becoming 9) would be its own kind of dishonest indicator, worse than a
 * denominator that is merely larger than today's run needs. [SETUP_TOTAL_STEPS] instead counts
 * every stage the full state machine can ever reach — stable for the whole flow, at the cost of a
 * bar that does not always fill edge-to-edge for operators who skip conditional stages (the same
 * trade-off already implicit in the original eight-stage design, which never reserved a segment
 * count per capture mode either). */
public const val SETUP_TOTAL_STEPS: Int = 10

/** The step-indicator segment a given [SetupStep] lights up — `Setup-Welcome.dc.html` has no
 * indicator at all (returns `null`); every other board names its "n of 10" explicitly
 * (design-intent.md §2's stage table: Mode 1, Mic/Mic-denied/BT-permission 2, Notify 3,
 * Input/Verify/Mismatch 4, Level 5, Overnight 6, Radio/Transport/USB/Bluetooth/Verified 7,
 * Models 8, Analytics consent 9, Ready 10).
 *
 * P22: [JURISDICTION_NOTICE] shares [WELCOME]'s `null` (it is a pre-flow legal notice, not a
 * numbered onboarding stage — that part of the original design stands).
 *
 * **R-1087: [MODELS] and [ANALYTICS_CONSENT] each now get their own position** (8 and 9), no
 * longer sharing [READY]'s. Both are real, distinct decisions an operator can be asked to make —
 * collapsing either into "the same step as Ready" is exactly the false "you are on the last step"
 * signal the register row named. See [SETUP_TOTAL_STEPS]'s own doc comment for why the total
 * counts every reachable stage rather than only the ones a given run walks. */
public fun SetupStep.indicatorIndex(): Int? = when (this) {
    SetupStep.WELCOME, SetupStep.JURISDICTION_NOTICE -> null
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
    SetupStep.MODELS -> 8
    SetupStep.ANALYTICS_CONSENT -> 9
    SetupStep.READY -> 10
}

/** Whether this step's indicator segment should render halted (`halt/text`, guide §6.10).
 *
 * **R-1091 (register):** `Setup-Mic-Denied.dc.html` drew [SetupStep.MICROPHONE_DENIED]'s segment
 * halted from the start; this function did not agree, and nobody had decided which was wrong.
 * Judged here: a permanently denied microphone is a real halt, not a lesser warning — capture
 * cannot start at all, [MicrophoneDeniedScreen] already renders [SetupHaltBanner] (the same
 * halting-tone banner [RouteMismatchScreen] uses) for exactly that reason, and the screen offers
 * no `Continue`, only "Open app settings" and "Check again" — [RouteMismatchScreen]'s own shape.
 * The drawing and the screen's own content were right; this function was the outlier. Fixed by
 * adding [SetupStep.MICROPHONE_DENIED] here, which changes one visible thing:
 * [SegmentBars] now paints its segment `halt/text` and the step indicator's merged content
 * description reads "halted at step 2" on that screen, matching `Setup-Route-Mismatch.dc.html`'s
 * own halted segment 4. Nothing else reads [isHalted] today.
 */
public fun SetupStep.isHalted(): Boolean = this == SetupStep.ROUTE_MISMATCH || this == SetupStep.MICROPHONE_DENIED
