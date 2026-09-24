package org.ort.app.ui.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.app.permissions.PermissionsState
import org.ort.core.capture.CaptureMode
import org.ort.core.capture.RigTransportKind

/**
 * The pure decision half of the guided setup sequence — which [SetupStep] to show given the real
 * permission state and what has been configured so far ([SetupSnapshot]), with no `Context`/`Activity`
 * at all.
 *
 * **P39 (D58) rebuilt the ladder this file tests.** Four screens, one conditional, and two structural
 * rules that R-1161 was a symptom of: the terminal state is a function of "no gates remain" rather
 * than the `setupComplete` latch, and there is no "nothing to show" answer for setup to hand back on.
 */
class SetupStateMachineTest {

    private fun permissions(recordAudio: Boolean, notifications: Boolean, bluetoothConnect: Boolean = true) =
        PermissionsState(
            recordAudioGranted = recordAudio,
            notificationsGranted = notifications,
            isIgnoringBatteryOptimizationsDiagnosticOnly = false,
            bluetoothConnectGranted = bluetoothConnect,
        )

    @Suppress("LongParameterList")
    private fun snapshot(
        welcomeSeen: Boolean = true,
        captureMode: CaptureMode? = CaptureMode.USB_RADIO,
        bluetoothPermissionDeclined: Boolean = false,
        selectedInputId: String? = "usb-1",
        inputVerified: Boolean = true,
        levelInBand: Boolean = true,
        levelAcknowledged: Boolean = false,
        radioChoice: RadioChoice? = RadioChoice.NONE,
        rigTransport: RigTransportKind? = null,
        rigBluetoothVerified: Boolean = false,
        // P39: `false` is the shipped answer — no rig module has a CAT implementation — so a test that
        // is not specifically about the rig branch never has to know the branch exists.
        rigModuleAvailable: Boolean = false,
        requiredModelsInstalled: Boolean = true,
        setupComplete: Boolean = false,
    ) = SetupSnapshot(
        welcomeSeen = welcomeSeen,
        captureMode = captureMode,
        bluetoothPermissionDeclined = bluetoothPermissionDeclined,
        selectedInputId = selectedInputId,
        inputVerified = inputVerified,
        levelInBand = levelInBand,
        levelAcknowledged = levelAcknowledged,
        radioChoice = radioChoice,
        rigTransport = rigTransport,
        rigBluetoothVerified = rigBluetoothVerified,
        rigModuleAvailable = rigModuleAvailable,
        requiredModelsInstalled = requiredModelsInstalled,
        setupComplete = setupComplete,
    )

    private fun stepFor(
        permissions: PermissionsState = permissions(recordAudio = true, notifications = true),
        micPermanentlyDenied: Boolean = false,
        snapshot: SetupSnapshot = snapshot(),
    ) = SetupStateMachine.stepFor(permissions, micPermanentlyDenied, snapshot)

    // --- AC-198: four screens, five with a download -----------------------------------------------

    /**
     * **AC-198**, at the state machine. Walked as a real first run would be — every answer supplied in
     * the order the flow asks for it — and the sequence of *distinct* steps is counted. Four, with
     * every model already present; the system permission dialog is not a screen and is not counted.
     *
     * Counted rather than asserted step by step on purpose: the criterion is about how many screens
     * the operator sees, and a test that asserted each transition individually would still pass if a
     * fifth screen were inserted between two of them.
     */
    @Test
    fun `AC_198 a first run with every model present reaches Ready in four screens`() {
        val walked = walkFirstRun(requiredModelsInstalled = true)
        assertEquals(
            listOf(SetupStep.WELCOME, SetupStep.MODE, SetupStep.LISTEN, SetupStep.READY),
            walked,
            "a first run must not exceed four screens (AC-198)",
        )
    }

    @Test
    fun `AC_198 a first run that must download a model reaches Ready in five screens`() {
        val walked = walkFirstRun(requiredModelsInstalled = false)
        assertEquals(
            listOf(SetupStep.WELCOME, SetupStep.MODE, SetupStep.LISTEN, SetupStep.MODELS, SetupStep.READY),
            walked,
            "a run that owes a download must not exceed five screens (AC-198)",
        )
    }

    /**
     * Walks the flow the way an operator does: ask [SetupStateMachine.stepFor] what to show, answer
     * exactly that step, ask again. Returns the distinct steps seen, in order. A gate that is never
     * satisfied would loop, so the walk is bounded and the bound failing is itself the finding.
     */
    private fun walkFirstRun(requiredModelsInstalled: Boolean): List<SetupStep> {
        var welcomeSeen = false
        var micGranted = false
        var mode: CaptureMode? = null
        var selectedInput: String? = null
        var verified = false
        var levelInBand = false
        var modelsInstalled = requiredModelsInstalled
        val seen = mutableListOf<SetupStep>()
        repeat(WALK_BOUND) {
            val step = SetupStateMachine.stepFor(
                permissions(recordAudio = micGranted, notifications = false),
                micPermanentlyDenied = false,
                snapshot(
                    welcomeSeen = welcomeSeen,
                    captureMode = mode,
                    selectedInputId = selectedInput,
                    inputVerified = verified,
                    levelInBand = levelInBand,
                    requiredModelsInstalled = modelsInstalled,
                ),
            )
            if (seen.lastOrNull() != step) seen += step
            when (step) {
                SetupStep.WELCOME -> welcomeSeen = true
                // One tap: the mode is chosen and the system dialog it fires is answered.
                SetupStep.MODE -> {
                    mode = CaptureMode.USB_RADIO
                    micGranted = true
                }
                SetupStep.LISTEN -> {
                    selectedInput = "usb-1"
                    verified = true
                    levelInBand = true
                }
                SetupStep.MODELS -> modelsInstalled = true
                SetupStep.READY -> return seen
                else -> error("a first run reached $step, which is not part of the ladder at all")
            }
        }
        error("the flow never reached READY within $WALK_BOUND steps — seen: $seen")
    }

    // --- AC-199: nothing gates capture on evidence only capture can produce -----------------------

    /**
     * **AC-199**, at the state machine's half of it. Every capture-produced signal reports unproven —
     * overnight survival above all, the one that deadlocked R-1161 — and the flow still terminates on
     * [SetupStep.READY], the screen with the `Start capture` press. `SetupActivityTest`/`MainActivityTest`
     * carry the other half: that the route from launch actually starts capture.
     *
     * The snapshot below cannot even express the old gate, which is the real fix: `overnightStepSeen`
     * is not a field of [SetupSnapshot] any more, so no future change can reintroduce the block by
     * adding one line to `stepFor`.
     */
    @Test
    fun `AC_199 with every capture-produced signal unproven the flow still terminates on Ready`() {
        assertEquals(SetupStep.READY, stepFor(snapshot = snapshot(setupComplete = false)))
    }

    /** AC-199, stated as the property rather than the instance: no field of the snapshot the state
     * machine reads can only be true after a capture. Enforced structurally — the type carries no such
     * field — and asserted here so deleting the field is not silently undone. */
    @Test
    fun `AC_199 the snapshot the state machine reads carries no post-capture evidence at all`() {
        // `declaredFields`, not `KClass.members`: kotlin-reflect is not on this module's test
        // classpath, and a test that throws `KotlinReflectionNotSupportedError` proves nothing.
        val fields = SetupSnapshot::class.java.declaredFields.map { it.name }.toSet()
        listOf("overnightStepSeen", "overnightSurvivalProven").forEach { forbidden ->
            assertFalse(
                forbidden in fields,
                "$forbidden is evidence only a completed capture can produce — it must never gate one",
            )
        }
    }

    // --- AC-201's state-machine half: the route gate has no acknowledged escape --------------------

    /**
     * **Constitution IV, and the line AC-201 closes with: "an unverified audio route still never
     * proceeds — the block is communicated, not removed."** The level has an acknowledged escape; the
     * route does not, and this asserts the asymmetry directly rather than trusting it.
     */
    @Test
    fun `AC_201 an unverified route never proceeds, whatever else has been acknowledged`() {
        val step = stepFor(
            snapshot = snapshot(inputVerified = false, levelInBand = true, levelAcknowledged = true),
        )
        assertEquals(SetupStep.LISTEN, step, "constitution IV: an unverified route never proceeds")
    }

    @Test
    fun `AC_201 a level out of band and not acknowledged holds the operator on Listen`() {
        assertEquals(
            SetupStep.LISTEN,
            stepFor(snapshot = snapshot(levelInBand = false, levelAcknowledged = false)),
        )
    }

    /** R-1170's second half: tapping `Continue` out of band records unresolved-but-acknowledged and
     * the flow proceeds — the amber row on Ready is what carries it from there. */
    @Test
    fun `AC_201 a level out of band but acknowledged proceeds to Ready`() {
        assertEquals(
            SetupStep.READY,
            stepFor(snapshot = snapshot(levelInBand = false, levelAcknowledged = true)),
        )
    }

    // --- The two structural rules (D58) ------------------------------------------------------------

    /**
     * **D58's first structural rule.** `READY` used to sit behind `!setupComplete`, so the flow's own
     * last screen became unreachable the instant that flag was set and re-entry had nowhere to land
     * (R-1161). The terminal state is now a function of "no gates remain" and nothing else.
     */
    @Test
    fun `R_1161 Ready is still the answer once setupComplete is true and every gate has cleared`() {
        assertEquals(SetupStep.READY, stepFor(snapshot = snapshot(setupComplete = true)))
    }

    /**
     * **D58's second structural rule.** `stepFor` is non-null by type, so [SetupActivity] has no
     * "nothing left to do" branch to hand control back from — and two components that each defer to
     * the other are a cycle by construction. Asserted over every reachable combination rather than one
     * case, because the property is "there is no input for which this returns nothing".
     */
    @Test
    fun `R_1161 stepFor always names a destination, for every combination of gates`() {
        everyCombinationOfFiveFlags().forEach { flags ->
            val step = SetupStateMachine.stepFor(
                permissions(recordAudio = flags.microphoneGranted, notifications = false),
                micPermanentlyDenied = false,
                snapshot(
                    welcomeSeen = flags.welcomeSeen,
                    inputVerified = flags.inputVerified,
                    requiredModelsInstalled = flags.modelsInstalled,
                    setupComplete = flags.setupComplete,
                ),
            )
            assertTrue(step in SetupStep.entries, "every combination must name a step")
        }
    }

    /** One point in the space the test above sweeps — named rather than positional, so a reader can
     * tell which gate is which without counting commas. */
    private data class GateFlags(
        val welcomeSeen: Boolean,
        val microphoneGranted: Boolean,
        val inputVerified: Boolean,
        val modelsInstalled: Boolean,
        val setupComplete: Boolean,
    )

    /** The 32 combinations of those five booleans, enumerated by bit rather than by five nested
     * loops — the nesting is the only thing that made the loop form worth avoiding. */
    private fun everyCombinationOfFiveFlags(): List<GateFlags> = (0 until FIVE_FLAG_COMBINATIONS).map { bits ->
        fun bit(index: Int) = bits shr index and 1 == 1
        GateFlags(bit(0), bit(1), bit(2), bit(3), bit(4))
    }

    // --- The ladder, gate by gate -----------------------------------------------------------------

    @Test
    fun `R_080 a never-begun setup shows Welcome before any permission is checked`() {
        val step = stepFor(
            permissions = permissions(recordAudio = false, notifications = false),
            snapshot = snapshot(welcomeSeen = false),
        )
        assertEquals(SetupStep.WELCOME, step)
    }

    /** AC-166/AC-180 fold onto Welcome, so `welcomeSeen` is the single acknowledgement gate and the
     * next thing asked is the mode. */
    @Test
    fun `AC_166 Welcome once acknowledged proceeds straight to Mode, with no notice screen between`() {
        assertEquals(SetupStep.MODE, stepFor(snapshot = snapshot(captureMode = null)))
    }

    /**
     * **AC-204**: there is no explainer step. An ungranted microphone resumes on the mode surface —
     * the one that carries the rationale and fires the dialog — rather than a screen of its own.
     */
    @Test
    fun `AC_204 an ungranted microphone resumes on Mode, never on an explainer step`() {
        val step = stepFor(
            permissions = permissions(recordAudio = false, notifications = false),
            snapshot = snapshot(captureMode = CaptureMode.LOCAL_MICROPHONE),
        )
        assertEquals(SetupStep.MODE, step)
    }

    @Test
    fun `R_085 microphone not granted and permanently denied shows MicrophoneDenied`() {
        val step = stepFor(
            permissions = permissions(recordAudio = false, notifications = false),
            micPermanentlyDenied = true,
        )
        assertEquals(SetupStep.MICROPHONE_DENIED, step)
    }

    @Test
    fun `permanent denial is only consulted while the microphone is actually not granted`() {
        val step = stepFor(micPermanentlyDenied = true, snapshot = snapshot(selectedInputId = null))
        assertEquals(SetupStep.LISTEN, step)
    }

    /** P39: notifications never appear in the ladder again — they are asked at first capture start. */
    @Test
    fun `AC_198 an ungranted notification permission adds no step to the flow`() {
        val step = stepFor(permissions = permissions(recordAudio = true, notifications = false))
        assertEquals(SetupStep.READY, step)
    }

    /**
     * **AC-202 (R-1167)**: first-run setup never asks for the manual frequency.
     *
     * The flow used to be internally incoherent about it: `RADIO_USB` demanded a parseable number with
     * a disabled button, while the tap immediately beside it advanced with the value still null and
     * capture ran perfectly well. Both prompts are out of onboarding now; the field and everything
     * downstream of it stay, and the value is edited in the log's own header instead.
     *
     * Asserted over the whole walk rather than at one gate, because the criterion is "never asks" — a
     * test of a single snapshot would pass while a branch three gates later still asked.
     */
    @Test
    fun `AC_202 no first run ever reaches the manual-frequency prompt, in any mode`() {
        CaptureMode.entries.forEach { mode ->
            listOf(true, false).forEach { modelsInstalled ->
                val walked = walkFirstRunFor(mode, modelsInstalled)
                assertFalse(
                    SetupStep.RADIO_USB in walked,
                    "a first run in $mode must never be asked for the frequency (AC-202) — walked $walked",
                )
            }
        }
    }

    private fun walkFirstRunFor(mode: CaptureMode, modelsInstalled: Boolean): List<SetupStep> {
        var welcomeSeen = false
        var micGranted = false
        var chosenMode: CaptureMode? = null
        var selectedInput: String? = null
        var verified = false
        var levelInBand = false
        var models = modelsInstalled
        val seen = mutableListOf<SetupStep>()
        repeat(WALK_BOUND) {
            val step = SetupStateMachine.stepFor(
                permissions(recordAudio = micGranted, notifications = false),
                micPermanentlyDenied = false,
                snapshot(
                    welcomeSeen = welcomeSeen,
                    captureMode = chosenMode,
                    selectedInputId = selectedInput,
                    inputVerified = verified,
                    levelInBand = levelInBand,
                    requiredModelsInstalled = models,
                ),
            )
            if (seen.lastOrNull() != step) seen += step
            when (step) {
                SetupStep.WELCOME -> welcomeSeen = true
                SetupStep.MODE -> {
                    chosenMode = mode
                    micGranted = true
                }
                SetupStep.LISTEN -> {
                    selectedInput = "route-1"
                    verified = true
                    levelInBand = true
                }
                SetupStep.MODELS -> models = true
                SetupStep.READY -> return seen
                else -> return seen
            }
        }
        return seen
    }

    // --- D33: SetupStep.BLUETOOTH_PERMISSION -------------------------------------------------------

    @Test
    fun `D33 Bluetooth mode with BLUETOOTH_CONNECT ungranted and not declined shows BluetoothPermission`() {
        val step = stepFor(
            permissions = permissions(recordAudio = true, notifications = false, bluetoothConnect = false),
            snapshot = snapshot(captureMode = CaptureMode.BLUETOOTH_RADIO),
        )
        assertEquals(SetupStep.BLUETOOTH_PERMISSION, step)
    }

    @Test
    fun `D33 Bluetooth mode with BLUETOOTH_CONNECT granted skips BluetoothPermission`() {
        val step = stepFor(snapshot = snapshot(captureMode = CaptureMode.BLUETOOTH_RADIO))
        assertEquals(SetupStep.READY, step)
    }

    @Test
    fun `D33 declining Bluetooth permission never returns to BluetoothPermission again`() {
        val step = stepFor(
            permissions = permissions(recordAudio = true, notifications = false, bluetoothConnect = false),
            snapshot = snapshot(captureMode = CaptureMode.USB_RADIO, bluetoothPermissionDeclined = true),
        )
        assertEquals(SetupStep.READY, step)
    }

    @Test
    fun `D33 USB and local-microphone modes never show BluetoothPermission regardless of the OS grant`() {
        listOf(CaptureMode.USB_RADIO, CaptureMode.LOCAL_MICROPHONE).forEach { mode ->
            val step = stepFor(
                permissions = permissions(recordAudio = true, notifications = false, bluetoothConnect = false),
                snapshot = snapshot(captureMode = mode),
            )
            assertEquals(SetupStep.READY, step, "mode $mode")
        }
    }

    // --- Listen ------------------------------------------------------------------------------------

    @Test
    fun `R_081 no input chosen shows Listen`() {
        assertEquals(SetupStep.LISTEN, stepFor(snapshot = snapshot(selectedInputId = null)))
    }

    @Test
    fun `R_081 an input chosen but not yet verified stays on Listen`() {
        assertEquals(
            SetupStep.LISTEN,
            stepFor(snapshot = snapshot(selectedInputId = "usb-1", inputVerified = false)),
        )
    }

    // --- P39: the rig branch only when a rig module actually exists --------------------------------

    /**
     * **D58**: the branch is entered only when a rig module with a real CAT implementation exists
     * *and* the mode implies a rig. None does today, so on current builds it does not appear at all —
     * which is the honest rendering of the state the code is in, not a feature being hidden.
     */
    @Test
    fun `D58 no rig module exists, so the rig branch never appears however the rig fields are left`() {
        val step = stepFor(snapshot = snapshot(radioChoice = null, rigModuleAvailable = false))
        assertEquals(SetupStep.READY, step, "a question about a radio nothing can command must not be asked")
    }

    @Test
    fun `D58 with a rig module present a USB-radio operator is asked which rig`() {
        val step = stepFor(
            snapshot = snapshot(
                captureMode = CaptureMode.USB_RADIO,
                radioChoice = null,
                rigModuleAvailable = true,
            ),
        )
        assertEquals(SetupStep.RADIO, step)
    }

    /** A local-microphone operator has no rig to control, whatever modules are installed. */
    @Test
    fun `D58 a local-microphone mode never enters the rig branch, even with a rig module present`() {
        val step = stepFor(
            snapshot = snapshot(
                captureMode = CaptureMode.LOCAL_MICROPHONE,
                radioChoice = null,
                rigModuleAvailable = true,
            ),
        )
        assertEquals(SetupStep.READY, step)
    }

    @Test
    fun `D33 a real rig chosen with no transport yet shows RigTransport`() {
        val step = stepFor(
            snapshot = snapshot(
                radioChoice = RadioChoice.TH_D75A,
                rigTransport = null,
                rigModuleAvailable = true,
            ),
        )
        assertEquals(SetupStep.RIG_TRANSPORT, step)
    }

    @Test
    fun `D33 choosing no radio never routes through RigTransport at all`() {
        val step = stepFor(
            snapshot = snapshot(radioChoice = RadioChoice.NONE, rigTransport = null, rigModuleAvailable = true),
        )
        assertEquals(SetupStep.READY, step)
    }

    @Test
    fun `D33 USB transport chosen for a real rig proceeds straight through to Ready`() {
        val step = stepFor(
            snapshot = snapshot(
                radioChoice = RadioChoice.TH_D75A,
                rigTransport = RigTransportKind.USB_SERIAL,
                rigModuleAvailable = true,
            ),
        )
        assertEquals(SetupStep.READY, step)
    }

    @Test
    fun `D33 Bluetooth transport chosen but not yet verified shows RigBluetooth`() {
        val step = stepFor(
            snapshot = snapshot(
                radioChoice = RadioChoice.TH_D75A,
                rigTransport = RigTransportKind.BLUETOOTH_SPP,
                rigBluetoothVerified = false,
                rigModuleAvailable = true,
            ),
        )
        assertEquals(SetupStep.RIG_BLUETOOTH, step)
    }

    @Test
    fun `D33 Bluetooth transport verified proceeds straight through to Ready`() {
        val step = stepFor(
            snapshot = snapshot(
                radioChoice = RadioChoice.TH_D75A,
                rigTransport = RigTransportKind.BLUETOOTH_SPP,
                rigBluetoothVerified = true,
                rigModuleAvailable = true,
            ),
        )
        assertEquals(SetupStep.READY, step)
    }

    /**
     * AC-130 (FR-CAP-9, FR-RIG-13): Bluetooth *rig control* with **wired** audio — the one combination
     * no mode names by default — is still fully reachable. A preset is not an enforcement, and
     * [SetupStateMachine] never refuses the combination.
     */
    @Test
    fun `AC_130_bluetooth_control_with_wired_audio_is_reachable`() {
        val step = stepFor(
            snapshot = snapshot(
                captureMode = CaptureMode.BLUETOOTH_RADIO,
                selectedInputId = "usb-1",
                inputVerified = true,
                radioChoice = RadioChoice.TH_D75A,
                rigTransport = RigTransportKind.BLUETOOTH_SPP,
                rigBluetoothVerified = true,
                rigModuleAvailable = true,
            ),
        )
        assertEquals(SetupStep.READY, step)
    }

    // --- P22: SetupStep.MODELS (FR-AST-10..12, AC-184, AC-188) -------------------------------------

    @Test
    fun `AC_184 a required model still missing shows Models rather than Ready`() {
        assertEquals(SetupStep.MODELS, stepFor(snapshot = snapshot(requiredModelsInstalled = false)))
    }

    @Test
    fun `AC_188 Models is checked even once level is already in band -- both gates are required together`() {
        val step = stepFor(snapshot = snapshot(levelInBand = true, requiredModelsInstalled = false))
        assertEquals(SetupStep.MODELS, step, "level alone must never be enough to reach Ready")
    }

    @Test
    fun `AC_188 level not yet in band is still checked before Models, regardless of the model state`() {
        val step = stepFor(snapshot = snapshot(levelInBand = false, requiredModelsInstalled = false))
        assertEquals(SetupStep.LISTEN, step, "the earlier gate must still resolve first")
    }

    @Test
    fun `AC_184 every required model installed proceeds past Models to Ready`() {
        val step = stepFor(snapshot = snapshot(requiredModelsInstalled = true))
        assertEquals(SetupStep.READY, step, "a full-variant install with nothing to download must never gain a step")
    }

    @Test
    fun `a revoked microphone permission after setup completed still routes back to Mode`() {
        val step = stepFor(
            permissions = permissions(recordAudio = false, notifications = true),
            snapshot = snapshot(setupComplete = true),
        )
        assertEquals(SetupStep.MODE, step)
    }

    // --- isComplete: MainActivity's fast-path check -------------------------------------------------

    @Test
    fun `R_080 isComplete is true once setupComplete is set and the microphone is granted`() {
        assertTrue(SetupStateMachine.isComplete(permissions(true, true), snapshot(setupComplete = true)))
    }

    @Test
    fun `isComplete is false while setupComplete has not been reached`() {
        assertFalse(SetupStateMachine.isComplete(permissions(true, true), snapshot(setupComplete = false)))
    }

    @Test
    fun `isComplete is false when the microphone was revoked after setup finished`() {
        assertFalse(SetupStateMachine.isComplete(permissions(false, true), snapshot(setupComplete = true)))
    }

    /**
     * **AC-199, at the router's own check.** `isComplete` used to require `notificationsGranted`, which
     * `MainActivity` only ever satisfied through the skip flag the deleted `NOTIFICATIONS` step wrote.
     * Leaving that clause would have rebuilt R-1161's cycle exactly: `Start capture` sets the latch,
     * the router finds capture "not permitted" over a notification permission, sends the operator back
     * to Setup, Setup finds every gate clear and shows `Ready` again, forever.
     */
    @Test
    fun `AC_199 an ungranted notification permission never makes a completed setup incomplete`() {
        assertTrue(
            SetupStateMachine.isComplete(
                permissions(recordAudio = true, notifications = false),
                snapshot(setupComplete = true),
            ),
            "notifications never gate capture, so they must never divert the router either",
        )
    }

    private companion object {
        /** Generous enough for any real ladder, small enough that a cycle fails fast rather than
         * hanging the suite — a walk that needs more than this has a gate it can never satisfy. */
        const val WALK_BOUND = 12

        const val FIVE_FLAG_COMBINATIONS = 1 shl 5
    }
}
