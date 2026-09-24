package org.ort.app.ui.setup

/**
 * One screen of the guided setup sequence.
 *
 * **P39 (D58, AC-198..204) rebuilt this ladder.** A brand-new operator used to walk twelve screens
 * and could reach twenty; the sequence now has four — [WELCOME], [MODE], [LISTEN], [READY] — plus
 * [MODELS] when something the detected tier needs is genuinely absent. The test applied to every
 * departing step was D58's: *will the app behave wrongly, or be unable to start, in the next
 * minute, without this answer?*
 *
 * **What left the ladder, and where each went** — moved, never deleted (constitution III):
 * - `JURISDICTION_NOTICE` and `ANALYTICS_CONSENT` fold into [WELCOME] as acknowledged lines whose
 *   full content is one tap away (AC-166 and AC-180 both require the content to be shown and
 *   reachable, neither requires a step of its own — see each criterion's own D58 amendment note).
 * - `MICROPHONE` — the explainer screen — is gone outright. The system dialog is fired straight
 *   from the [MODE] tap, with its rationale on that surface (AC-204); a dedicated explainer in
 *   front of a system dialog is request fatigue, and the mode surface is where the ask is earned.
 *   [MICROPHONE_DENIED] stays exactly as it was: a permanently denied microphone is a real halt.
 * - `NOTIFICATIONS` is asked at first capture start ([SetupActivity.onStartCapture]), in context,
 *   where the persistent notification is about to appear.
 * - `OVERNIGHT` is no longer a pre-capture gate at all — AC-189 as amended, and the structural half
 *   of R-1161. Overnight survival is evidence only a completed capture can produce, so a gate on it
 *   is unsatisfiable by construction and deadlocked every first-run operator. The step and
 *   [OvernightScreen] remain in the code, reached by [SetupActivity.EXTRA_STEP] and by [READY]'s own
 *   row, so the *Keep capture running* prompt owed to the first missed heartbeat has somewhere to
 *   land; [SetupStateMachine.stepFor] never returns it.
 * - `INPUT`, `VERIFY` and `LEVEL` merge into [LISTEN] — one screen carrying the route list, the
 *   verify checklist expanding in place, and the meter with its gain beneath. This is the only
 *   screen that must be full-screen, because it is the only one whose failure is *silent*
 *   (constitution IV). [ROUTE_MISMATCH] remains reachable from it and remains the one deliberate
 *   hard halt in the product.
 * - The `RADIO_USB` frequency prompt leaves onboarding entirely (AC-202, R-1167): the field and
 *   everything downstream of it stay, and the value is edited in the log's own header instead.
 *
 * [RADIO], [RIG_TRANSPORT] and [RIG_BLUETOOTH] stay in the code but are entered only when the
 * chosen mode implies a rig **and** a rig module with a real CAT implementation exists
 * ([SetupSnapshot.rigModuleAvailable]). None does today, so on current builds that branch does not
 * appear at all, which is the honest rendering of the state the code is in.
 *
 * [ROUTE_MISMATCH], [RADIO_USB] and [RADIO_VERIFIED] are, as before, transient live results of an
 * action taken on an earlier step — [SetupStateMachine.stepFor] never resumes directly into them.
 *
 * **Declaration order is load-bearing**: [SetupActivity.tryOpenAtRequestedStep] refuses an
 * [SetupActivity.EXTRA_STEP] request whose ordinal is past the natural resume point, so a debug or
 * Settings entry can only ever offer a *reconfiguration* entry into an already-valid sequence,
 * never a way around a verification the guide requires. The off-ladder steps therefore sit before
 * [READY], so a request for one is honoured once every real gate has cleared.
 */
public enum class SetupStep {
    WELCOME,
    MODE,
    MICROPHONE_DENIED,
    BLUETOOTH_PERMISSION,
    LISTEN,
    ROUTE_MISMATCH,
    RADIO,
    RIG_TRANSPORT,
    RADIO_USB,
    RIG_BLUETOOTH,
    RADIO_VERIFIED,
    MODELS,
    OVERNIGHT,
    READY,
}

/**
 * The step indicator's denominator, and **it is finally a true one** (D58's 2026-09-23 resolution,
 * retiring R-1087's fixed-total compromise).
 *
 * R-1087 had to use a fixed total larger than any real run because the old ladder's length was not
 * knowable until several stages downstream of wherever the operator was standing — a denominator
 * that changed from 8 to 6 to 9 mid-run would have been its own kind of dishonest. The rebuilt
 * ladder has exactly one conditional stage, [SetupStep.MODELS], and whether it will be walked is
 * known at launch from the installed-asset state alone. So the total is **3 when every required
 * model is present and 4 when one must be downloaded**, and it never moves under the operator.
 */
public fun setupTotalSteps(requiredModelsInstalled: Boolean): Int =
    if (requiredModelsInstalled) SETUP_STEPS_WITHOUT_DOWNLOAD else SETUP_STEPS_WITH_DOWNLOAD

/** Mode, Listen, Ready. */
public const val SETUP_STEPS_WITHOUT_DOWNLOAD: Int = 3

/** Mode, Listen, Models, Ready. */
public const val SETUP_STEPS_WITH_DOWNLOAD: Int = 4

/**
 * The step-indicator segment a given [SetupStep] lights up, against [totalSteps]
 * ([setupTotalSteps]).
 *
 * [SetupStep.WELCOME] has no indicator — the sequence has not begun. Neither do the off-ladder
 * steps: the rig branch is not a numbered stage of the first run (it is entered only from a rig
 * that exists, which none does today), and [SetupStep.OVERNIGHT] is no longer part of the sequence
 * at all. A step with no segment renders no counter either ([SetupScaffold]'s own header row), which
 * is the honest rendering of "you are not on a numbered stage" — never a fabricated position.
 */
public fun SetupStep.indicatorIndex(totalSteps: Int = SETUP_STEPS_WITHOUT_DOWNLOAD): Int? = when (this) {
    SetupStep.WELCOME -> null
    SetupStep.MODE, SetupStep.MICROPHONE_DENIED, SetupStep.BLUETOOTH_PERMISSION -> 1
    SetupStep.LISTEN, SetupStep.ROUTE_MISMATCH -> 2
    SetupStep.MODELS -> 3
    SetupStep.READY -> totalSteps
    SetupStep.RADIO,
    SetupStep.RIG_TRANSPORT,
    SetupStep.RADIO_USB,
    SetupStep.RIG_BLUETOOTH,
    SetupStep.RADIO_VERIFIED,
    SetupStep.OVERNIGHT,
    -> null
}

/** Whether this step's indicator segment should render halted (`halt/text`, guide §6.10).
 *
 * **R-1091 (register):** a permanently denied microphone is a real halt, not a lesser warning —
 * capture cannot start at all, [MicrophoneDeniedScreen] renders [SetupHaltBanner] for exactly that
 * reason, and the screen offers no `Continue`, only "Open app settings" and "Check again".
 * [SetupStep.ROUTE_MISMATCH] is the other, and is the one deliberate hard halt in the product
 * (constitution IV) — P39 merged the screen it is reached *from* into [SetupStep.LISTEN] and
 * changed nothing about the halt itself.
 */
public fun SetupStep.isHalted(): Boolean = this == SetupStep.ROUTE_MISMATCH || this == SetupStep.MICROPHONE_DENIED
