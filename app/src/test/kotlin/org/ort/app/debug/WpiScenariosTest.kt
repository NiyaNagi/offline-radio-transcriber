package org.ort.app.debug

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.permissions.PermissionsState
import org.ort.app.ui.data.ModelId
import org.ort.app.ui.data.ModelRowStatus
import org.ort.app.ui.data.ModelsController
import org.ort.app.ui.failures.DebugFailureOverride
import org.ort.app.ui.setup.DebugRigLinkPortOverride
import org.ort.app.ui.setup.InMemoryRigLinkPort
import org.ort.app.ui.setup.RadioChoice
import org.ort.app.ui.setup.RigLinkState
import org.ort.app.ui.setup.SetupStateMachine
import org.ort.app.ui.setup.SetupStep
import org.ort.app.ui.setup.SharedPreferencesSetupStore
import org.ort.capture.android.AudioDeviceKind
import org.ort.capture.android.heartbeat.FileHeartbeatStore
import org.ort.core.AttributionState
import org.ort.core.SystemClock
import org.ort.core.capture.AudioRouteKind
import org.ort.core.capture.BluetoothAudioProfile
import org.ort.core.capture.CaptureMode
import org.ort.core.capture.RigTransportKind
import org.ort.data.OrtDatabase
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.SessionEntity
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.pipeline.capture.VadAvailability
import org.ort.pipeline.digest.RoomProseSummaryStore
import org.ort.pipeline.digest.SharedPreferencesProseDigestSettingsStore
import org.ort.pipeline.rig.DefaultRigTransportFactory
import org.ort.pipeline.rig.SharedPreferencesCaptureConfigurationStore
import org.ort.rig.descriptor.BundledDescriptors
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.ort.rig.RigTransportKind as RigModuleTransportKind

/**
 * spec/e2e-capture-modes-plan.md WPI (E2-J01..J03): proves each of the seventeen P19/WPI scenarios
 * seeds the real store/holder/DB state its own doc comment in [Scenarios] claims — split out from
 * [ScenariosTest] (the same detekt `LargeClass` split that file's own header already documents
 * having done once, for the same reason: a self-contained cluster, not entangled with the rest of
 * what that file covers).
 */
@RunWith(RobolectricTestRunner::class)
class WpiScenariosTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: OrtDatabase

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    @After
    fun resetProcessWideFacets() {
        AsrAvailability.reset()
        VadAvailability.reset()
        CaptureState.idle(clearSession = true)
        ShedStatus.reset()
        ThermalStatus.reset()
        RigStatus.reset()
        StorageForecast.reset()
        LevelStatus.reset()
        InputStatus.reset()
        DebugFailureOverride.clear()
        DebugRigLinkPortOverride.clear()
        DebugRigLinkPortOverride.isDebugBuild = { org.ort.app.BuildConfig.DEBUG }
        context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(
            SharedPreferencesCaptureConfigurationStore.PREFS_NAME,
            android.content.Context.MODE_PRIVATE,
        ).edit().clear().commit()
        context.getSharedPreferences(
            SharedPreferencesProseDigestSettingsStore.PREFS_NAME,
            android.content.Context.MODE_PRIVATE,
        ).edit().clear().commit()
    }

    private fun setupStore() = SharedPreferencesSetupStore(
        context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, android.content.Context.MODE_PRIVATE),
    )

    private fun captureConfigurationStore() = SharedPreferencesCaptureConfigurationStore(
        context.getSharedPreferences(
            SharedPreferencesCaptureConfigurationStore.PREFS_NAME,
            android.content.Context.MODE_PRIVATE,
        ),
    )

    private fun fullyGrantedBluetooth(bluetoothConnectGranted: Boolean = true) = PermissionsState(
        recordAudioGranted = true,
        notificationsGranted = true,
        isIgnoringBatteryOptimizationsDiagnosticOnly = false,
        bluetoothConnectGranted = bluetoothConnectGranted,
    )

    /**
     * R-804 (halt, coordinator spot-check): before this fix, `overnight/CF02` (and eight other
     * pre-existing USB-seeding scenarios — every scenario in [usbSeedingScenarioNames]) showed
     * "Local microphone" above a USB Audio Device input row, because CF02/CF11 read
     * `CaptureConfigurationStore.current()`, never `SetupStore` or `InputStatus` (which every one of
     * these scenarios already published correctly). Not limited to the seventeen P19/WPI scenarios
     * this file otherwise covers — `Scenarios.kt` is this package's file regardless of which wave
     * first added a given scenario, and CF02/CF11's own read path does not distinguish them either.
     */
    private val usbSeedingScenarioNames = listOf(
        "overnight",
        "overnight-live",
        "stations-14-nights",
        "setup-verified",
        "setup-level",
        "setup-radio",
        "level-low",
        "level-clip",
        "input-verified",
    )

    @Test
    @Requirement("R-804")
    fun `R_804_every pre-existing USB-seeding scenario writes CaptureConfigurationStore as USB_RADIO usb-1`() =
        runTest {
            usbSeedingScenarioNames.forEach { name ->
                Scenarios.load(context, name)
                val config = captureConfigurationStore().current()
                assertEquals("'$name' must configure USB_RADIO", CaptureMode.USB_RADIO, config.mode)
                assertEquals("'$name' must select the usb-1 input", "usb-1", config.selectedInputId)
            }
        }

    @Test
    @Requirement("D33", "FR-CAP-8")
    fun `D33_setup-mode seeds a fresh store so stepFor resumes at MODE`() = runTest {
        Scenarios.load(context, "setup-mode")

        val store = setupStore()
        assertTrue(store.welcomeSeen)
        assertNull(store.captureMode)
        val step = SetupStateMachine.stepFor(
            fullyGrantedBluetooth(),
            micPermanentlyDenied = false,
            snapshot = store.snapshot(),
        )
        assertEquals(SetupStep.MODE, step)
    }

    @Test
    @Requirement("D33", "FR-CAP-8")
    fun `D33_setup-bt-permission seeds Bluetooth mode, BLUETOOTH_CONNECT ungranted, stepFor resumes there`() = runTest {
        Scenarios.load(context, "setup-bt-permission")

        val store = setupStore()
        assertEquals(CaptureMode.BLUETOOTH_RADIO, store.captureMode)
        assertFalse(store.bluetoothPermissionDeclined)
        val step = SetupStateMachine.stepFor(
            fullyGrantedBluetooth(bluetoothConnectGranted = false),
            micPermanentlyDenied = false,
            snapshot = store.snapshot(),
        )
        assertEquals(SetupStep.BLUETOOTH_PERMISSION, step)
    }

    @Test
    @Requirement("D33", "FR-RIG-13")
    fun `D33_setup-rig-transport seeds a chosen rig with no transport, stepFor resumes at RIG_TRANSPORT`() = runTest {
        Scenarios.load(context, "setup-rig-transport")

        val store = setupStore()
        assertTrue(store.inputVerified)
        assertNotNull(store.radioChoice)
        assertNull(store.rigTransport)
        val step = SetupStateMachine.stepFor(
            fullyGrantedBluetooth(),
            micPermanentlyDenied = false,
            snapshot = store.snapshot(),
        )
        assertEquals(SetupStep.RIG_TRANSPORT, step)
    }

    /**
     * E2-J04 (checklist row, coordinator round): `setup-verified`/`setup-level`/`setup-radio` all
     * share [org.ort.app.debug.Scenarios]'s own `USB_RADIO`/`usb-1` base — no scenario ever reached
     * S07..S12 under `LOCAL_MICROPHONE`. `setup-verified-local-mic` is that missing base:
     * `stepFor` resumes at [SetupStep.READY] (a fully-verified, `setupComplete = false` snapshot,
     * same as `setup-verified` itself), with a real `radioChoice = NONE` — the honest "no rig chosen"
     * fact S12's own Mode row reads regardless of capture mode.
     */
    @Test
    @Requirement("FR-CAP-2b", "AC-128")
    fun `setup-verified-local-mic seeds a verified local-mic input, stepFor resumes at READY`() = runTest {
        Scenarios.load(context, "setup-verified-local-mic")

        val store = setupStore()
        assertEquals(CaptureMode.LOCAL_MICROPHONE, store.captureMode)
        assertTrue(store.inputVerified)
        assertEquals("mic-0", store.selectedInputId)
        assertEquals(RadioChoice.NONE, store.radioChoice)
        assertNull("local-mic mode has no rig transport to choose", store.rigTransport)
        val step = SetupStateMachine.stepFor(
            fullyGrantedBluetooth(),
            micPermanentlyDenied = false,
            snapshot = store.snapshot(),
        )
        assertEquals(SetupStep.READY, step)

        val config = captureConfigurationStore().current()
        assertEquals(
            "R-804's own class: CF02/CF11 read the store, not SetupStore",
            CaptureMode.LOCAL_MICROPHONE,
            config.mode,
        )
        assertEquals("mic-0", config.selectedInputId)
    }

    /**
     * E2-J04 (checklist row, coordinator round): `setup-rig-transport-preset` is, today, functionally
     * identical to [setupRigTransport] — see that scenario's own doc comment for the known, honest
     * gap this test documents rather than papers over: `SetupActivity.selectedRigTransportKind` (the
     * field driving S09b's own pre-selected radio marker) is seeded only on the forward-navigation
     * path, never on a cold `EXTRA_STEP` landing, so no scenario can make S09b open with a row
     * genuinely pre-selected without a `SetupActivity.kt` fix this round's coordinator message did
     * not pre-approve. This test only proves the scenario names load and resolve to the same real
     * `stepFor`/preset-eligible state `setup-rig-transport` already does — not that the visual
     * pre-selection gap is closed.
     */
    @Test
    @Requirement("FR-RIG-13")
    fun `setup-rig-transport-preset loads and resumes at RIG_TRANSPORT, same as setup-rig-transport`() = runTest {
        Scenarios.load(context, "setup-rig-transport-preset")

        val store = setupStore()
        assertNotNull(store.radioChoice)
        assertNull(store.rigTransport)
        val step = SetupStateMachine.stepFor(
            fullyGrantedBluetooth(),
            micPermanentlyDenied = false,
            snapshot = store.snapshot(),
        )
        assertEquals(SetupStep.RIG_TRANSPORT, step)
    }

    @Test
    @Requirement("D33", "D34", "FR-RIG-14")
    fun `D33_setup-rig-bluetooth seeds Bluetooth SPP unverified so stepFor resumes at RIG_BLUETOOTH`() = runTest {
        Scenarios.load(context, "setup-rig-bluetooth")

        val store = setupStore()
        assertEquals(RigTransportKind.BLUETOOTH_SPP, store.rigTransport)
        assertFalse(store.rigBluetoothVerified)
        val step = SetupStateMachine.stepFor(
            fullyGrantedBluetooth(),
            micPermanentlyDenied = false,
            snapshot = store.snapshot(),
        )
        assertEquals(SetupStep.RIG_BLUETOOTH, step)
    }

    /** WPD's seam (coordinator-assigned): the real paired-device list S10b now renders. */
    @Test
    @Requirement("D33", "D34", "FR-RIG-14")
    fun `D33_setup-rig-bluetooth installs a real DebugRigLinkPortOverride naming both paired devices`() = runTest {
        Scenarios.load(context, "setup-rig-bluetooth")

        val override = DebugRigLinkPortOverride.activeOverride
        assertNotNull("setup-rig-bluetooth must publish a real override", override)
        val devices = override!!.pairedDevices()
        assertTrue("BLUETOOTH_CONNECT is granted in this scenario", devices.permissionGranted)
        assertEquals(2, devices.devices.size)
        val sppCapable = devices.devices.single { it.name == "TH-D75A" }
        assertEquals(true, sppCapable.sppCapable)
        val headsetOnly = devices.devices.single { it.name == "Handheld BT" }
        assertEquals(false, headsetOnly.sppCapable)
    }

    /** The gate itself is `DebugRigLinkPortOverrideTest`'s own row to prove in general; this proves
     * the scenario's own published port participates in it correctly. */
    @Test
    @Requirement("D33", "D34")
    fun `D33_setup-rig-bluetooth own override is ignored the moment isDebugBuild reports false`() = runTest {
        Scenarios.load(context, "setup-rig-bluetooth")
        assertNotNull(DebugRigLinkPortOverride.current)

        DebugRigLinkPortOverride.isDebugBuild = { false }
        assertNull(
            "a release build must never consult this scenario's own override",
            DebugRigLinkPortOverride.activeOverride,
        )
    }

    private val rigBluetoothTestAddress = "AA:BB:CC:11:22:33"

    /**
     * WPD's `EXTRA_DEBUG_RIG_BLUETOOTH_ADDRESS` seam (this round): the four `setup-rig-bluetooth*`
     * scenarios below script this address differently, each for one checklist state the screenshot
     * tour cannot otherwise capture (no tap). `.take(n)` bounds the collect for the two scripts that
     * hang forever past their own last real emission ([InMemoryRigLinkPort.hang]/
     * [InMemoryRigLinkPort.hangAfterIdentify]) — cancelling the flow the same way a real
     * `LaunchedEffect` cancellation would, never letting this test itself hang.
     */
    @Test
    @Requirement("D33", "D34", "FR-RIG-14")
    fun `setup-rig-bluetooth-connecting scripts the address to hang at Opening`() = runTest {
        Scenarios.load(context, "setup-rig-bluetooth-connecting")

        val port = requireNotNull(DebugRigLinkPortOverride.activeOverride)
        val emissions = port.connect(rigBluetoothTestAddress, "kenwood-thd75a").take(1).toList()
        assertEquals(listOf(RigLinkState.Opening), emissions)
    }

    @Test
    @Requirement("D33", "D34", "FR-RIG-14")
    fun `setup-rig-bluetooth-identified scripts the address to hang at Identified, never Verified`() = runTest {
        Scenarios.load(context, "setup-rig-bluetooth-identified")

        val port = requireNotNull(DebugRigLinkPortOverride.activeOverride)
        val emissions = port.connect(rigBluetoothTestAddress, "kenwood-thd75a").take(3).toList()
        assertEquals(
            listOf(RigLinkState.Opening, RigLinkState.Open, RigLinkState.Identified("kenwood-thd75a")),
            emissions,
        )
    }

    @Test
    @Requirement("D33", "D34", "FR-RIG-14", "FR-RIG-15")
    fun `setup-rig-bluetooth-dropped scripts the address to report Lost after opening`() = runTest {
        Scenarios.load(context, "setup-rig-bluetooth-dropped")

        val port = requireNotNull(DebugRigLinkPortOverride.activeOverride)
        val emissions = port.connect(rigBluetoothTestAddress, "kenwood-thd75a").toList()
        assertTrue("expected a Lost state, got $emissions", emissions.last() is RigLinkState.Lost)
    }

    @Test
    @Requirement("D33", "D34", "FR-RIG-14")
    fun `setup-rig-bluetooth scripts the address through to Verified`() = runTest {
        Scenarios.load(context, "setup-rig-bluetooth")

        val port = requireNotNull(DebugRigLinkPortOverride.activeOverride)
        val emissions = port.connect(rigBluetoothTestAddress, "kenwood-thd75a").toList()
        assertEquals(
            RigLinkState.Verified(InMemoryRigLinkPort.DEFAULT_VERIFIED_COMMANDS),
            emissions.last(),
        )
    }

    @Test
    @Requirement("FR-CAP-3a", "FR-CAP-10")
    fun `AC_128_mode-local-mic writes v7 columns LOCAL_MICROPHONE BUILT_IN_MIC and a live session`() = runTest {
        val result = Scenarios.load(context, "mode-local-mic")
        val session = db.sessionDao().getById(requireNotNull(result.primarySessionId))

        assertEquals(CaptureMode.LOCAL_MICROPHONE.name, session?.captureMode)
        assertEquals(AudioRouteKind.BUILT_IN_MIC.name, session?.audioRouteKind)
        assertNull("local-mic mode has no rig", session?.rigTransport)
        assertTrue(CaptureState.isCapturing)
        assertTrue(InputStatus.state is InputStatus.State.Opened)
    }

    /** R-821: CF02/CF11 read `CaptureConfigurationStore.current()`, not the session row — a scenario
     * that seeds only the `:data` session leaves them reading the store's own honest `DEFAULT`
     * (LOCAL_MICROPHONE, no input selected), which happened to read right for mode but wrong for
     * "no input chosen" on a session that plainly has one. */
    @Test
    @Requirement("FR-CAP-3a", "R-821")
    fun `R_821_mode-local-mic seeds CaptureConfigurationStore current matching the session`() = runTest {
        Scenarios.load(context, "mode-local-mic")

        val config = captureConfigurationStore().current()
        assertEquals(CaptureMode.LOCAL_MICROPHONE, config.mode)
        assertEquals("mic-0", config.selectedInputId)
        assertNull(
            "no pending change should exist for a scenario that never wrote one",
            captureConfigurationStore().pendingConfiguration(),
        )
    }

    /** R-823: a transmission's own `stationId` is not itself a `station` catalog row — ST01 reads
     * the `station` table directly, so a scenario claiming a station was heard must also seed one. */
    @Test
    @Requirement("R-823")
    fun `R_823_mode-local-mic seeds a real station row for W7NPC, not just the transmission's stationId`() = runTest {
        Scenarios.load(context, "mode-local-mic")

        val station = db.catalogDao().getStation("W7NPC")
        assertNotNull("ST01 reads the station table directly, not a transmission's own stationId", station)
    }

    @Test
    @Requirement("FR-CAP-13", "AC-129")
    fun `AC_129_mode-usb writes v7 columns USB_RADIO and RigStatus names the USB-serial transport`() = runTest {
        val result = Scenarios.load(context, "mode-usb")
        val session = db.sessionDao().getById(requireNotNull(result.primarySessionId))

        assertEquals(CaptureMode.USB_RADIO.name, session?.captureMode)
        assertEquals(AudioRouteKind.USB.name, session?.audioRouteKind)
        assertEquals(RigTransportKind.USB_SERIAL.name, session?.rigTransport)
        val state = RigStatus.state
        assertTrue(state is RigStatus.State.Connected)
        state as RigStatus.State.Connected
        assertEquals(RigModuleTransportKind.USB_SERIAL, state.transportKind)
    }

    @Test
    @Requirement("FR-CAP-13", "R-821")
    fun `R_821_mode-usb seeds CaptureConfigurationStore current matching the session`() = runTest {
        Scenarios.load(context, "mode-usb")

        val config = captureConfigurationStore().current()
        assertEquals(CaptureMode.USB_RADIO, config.mode)
        assertEquals("usb-1", config.selectedInputId)
        assertEquals(RigModuleTransportKind.USB_SERIAL, config.rigTransportKind)
    }

    @Test
    @Requirement("D34", "FR-CAP-11", "FR-CAP-13")
    fun `D34_mode-bluetooth writes Bluetooth audio and Bluetooth SPP rig control at once`() = runTest {
        val result = Scenarios.load(context, "mode-bluetooth")
        val session = db.sessionDao().getById(requireNotNull(result.primarySessionId))

        assertEquals(CaptureMode.BLUETOOTH_RADIO.name, session?.captureMode)
        assertEquals(AudioRouteKind.BLUETOOTH_SCO.name, session?.audioRouteKind)
        assertEquals(BluetoothAudioProfile.HFP_MSBC.name, session?.bluetoothProfile)
        assertEquals(RigTransportKind.BLUETOOTH_SPP.name, session?.rigTransport)
        val rig = RigStatus.state
        assertTrue(rig is RigStatus.State.Connected)
        assertEquals(RigModuleTransportKind.BLUETOOTH_SPP, (rig as RigStatus.State.Connected).transportKind)
        val input = InputStatus.state
        assertTrue(input is InputStatus.State.Opened)
        assertEquals(BluetoothAudioProfile.HFP_MSBC, (input as InputStatus.State.Opened).descriptor.bluetoothProfile)
    }

    @Test
    @Requirement("D34", "FR-CAP-11", "R-821", "R-822")
    fun `R_821_mode-bluetooth seeds CaptureConfigurationStore current with the Bluetooth address in rigParams`() =
        runTest {
            Scenarios.load(context, "mode-bluetooth")

            val config = captureConfigurationStore().current()
            assertEquals(CaptureMode.BLUETOOTH_RADIO, config.mode)
            assertEquals(RigModuleTransportKind.BLUETOOTH_SPP, config.rigTransportKind)
            assertNotNull(
                "R-822: CF02/CF06's own Link address reads rigParams directly",
                config.rigParams[DefaultRigTransportFactory.ParamKeys.BLUETOOTH_ADDRESS],
            )
        }

    @Test
    @Requirement("FR-CAP-13")
    fun `E2_G04_bt-audio-session writes an ended session with two overs and the Bluetooth profile column`() = runTest {
        val result = Scenarios.load(context, "bt-audio-session")
        val session = db.sessionDao().getById(requireNotNull(result.primarySessionId))

        assertNotNull("must be an ended session", session?.endedAt)
        assertEquals(BluetoothAudioProfile.HFP_MSBC.name, session?.bluetoothProfile)
        assertEquals(2, result.transmissionCount)
        db.transmissionDao().listBySession(result.primarySessionId!!).forEach {
            assertEquals(AttributionState.CONFIRMED, it.attributionState)
        }
    }

    @Test
    @Requirement("F-023", "FR-CAP-5", "R-838")
    fun `F23_bt-audio-dropped sets InputStatus Lost with a Bluetooth lastKnown and an open gap`() = runTest {
        val result = Scenarios.load(context, "bt-audio-dropped")

        val input = InputStatus.state
        assertTrue(input is InputStatus.State.Lost)
        input as InputStatus.State.Lost
        assertEquals(BluetoothAudioProfile.HFP_MSBC, input.lastKnown.descriptor.bluetoothProfile)

        val rig = RigStatus.state
        assertTrue(
            "the rig's own control link stays connected — this is an audio-only drop",
            rig is RigStatus.State.Connected,
        )

        val gaps = db.captureGapDao().listBySession(requireNotNull(result.primarySessionId))
        val gap = gaps.single()
        assertEquals(
            "R-838: this schema has carried BLUETOOTH_AUDIO_LOST since v9/E2-A06 — no more INPUT_LOST stand-in",
            CaptureGapCause.BLUETOOTH_AUDIO_LOST,
            gap.cause,
        )
        assertNull("the gap must still be open", gap.endedAt)
    }

    /** R-832: F23's own ladder sentence ("retry N of M, next attempt in ...") needs real numbers,
     * not the honest-but-blank defaulted-null fields a bare [InputStatus.lost] call leaves it in. */
    @Test
    @Requirement("F-023", "R-832")
    fun `R_832_bt-audio-dropped publishes a real reconnect-ladder position`() = runTest {
        Scenarios.load(context, "bt-audio-dropped")

        val state = InputStatus.state as InputStatus.State.Lost
        assertEquals(3, state.attempt)
        assertEquals(8, state.ofTotal)
        assertEquals(20_000L, state.nextRetryInMillis)
    }

    @Test
    @Requirement("F-009")
    fun `CF06_rig-bt-connected sets RigStatus Connected over Bluetooth SPP, setup left at RADIO_VERIFIED`() = runTest {
        Scenarios.load(context, "rig-bt-connected")

        val state = RigStatus.state
        assertTrue(state is RigStatus.State.Connected)
        state as RigStatus.State.Connected
        assertEquals(RigModuleTransportKind.BLUETOOTH_SPP, state.transportKind)

        val store = setupStore()
        assertTrue(store.rigBluetoothVerified)
        assertFalse(store.setupComplete)
    }

    @Test
    @Requirement("F-009", "FR-RIG-15")
    fun `F9_rig-bt-lost sets RigStatus Stale whose lastKnown names Bluetooth SPP`() = runTest {
        Scenarios.load(context, "rig-bt-lost")

        val state = RigStatus.state
        assertTrue(state is RigStatus.State.Stale)
        state as RigStatus.State.Stale
        assertEquals(RigModuleTransportKind.BLUETOOTH_SPP, state.lastKnown.transportKind)
    }

    /** R-832: F9's own ladder sentence needs the same real numbers `bt-audio-dropped` seeds for F23. */
    @Test
    @Requirement("F-009", "R-832")
    fun `R_832_rig-bt-lost publishes a real reconnect-ladder position`() = runTest {
        Scenarios.load(context, "rig-bt-lost")

        val state = RigStatus.state as RigStatus.State.Stale
        assertEquals(3, state.attempt)
        assertEquals(8, state.ofTotal)
        assertEquals(20_000L, state.nextRetryInMillis)
    }

    /**
     * R-872 (register, halt): validator V10 found `rig-bt-lost`'s own live bar meter reading as
     * the same muted flat dots `bt-audio-dropped`'s genuine input-lost drop shows — not because
     * `LiveBarPolling` fails to distinguish them (`LiveBarPollingTest.R_836 a rig-only drop keeps
     * the real live meter...` already proves it does, given a real `LevelStatus.Measured` reading
     * to show), but because this scenario never seeds one at all, so the meter's own honest
     * "nothing measured yet" floor reads identically to a genuine drop on a real screen. This is
     * an audio-only-fine, rig-control-only drop (FR-RIG-15) — the session's own real
     * `WIRED_HEADSET` v7 columns already say the audio route is verified; the live process-wide
     * holders must agree, the same way every other "audio is fine" scenario seeds them.
     */
    @Test
    @Requirement("R-872")
    fun `R_872_rig-bt-lost seeds a real Measured level and an opened, not lost, InputStatus`() = runTest {
        Scenarios.load(context, "rig-bt-lost")

        val input = InputStatus.state
        assertTrue("expected InputStatus.Opened, got $input", input is InputStatus.State.Opened)
        val level = LevelStatus.state
        assertTrue("expected LevelStatus.Measured, got $level", level is LevelStatus.State.Measured)
    }

    @Test
    @Requirement("FR-CAP-12", "AC-131")
    fun `AC_131_mode-change-pending writes a pending configuration without disturbing current`() = runTest {
        Scenarios.load(context, "mode-change-pending")

        val configStore = SharedPreferencesCaptureConfigurationStore(
            context.getSharedPreferences(
                SharedPreferencesCaptureConfigurationStore.PREFS_NAME,
                android.content.Context.MODE_PRIVATE,
            ),
        )
        assertEquals(CaptureMode.USB_RADIO, configStore.current().mode)
        val pending = configStore.pendingConfiguration()
        assertNotNull("a live session must freeze the change as pending, not current", pending)
        assertEquals(CaptureMode.BLUETOOTH_RADIO, pending?.mode)
        assertNotNull(
            "the pending Bluetooth change carries its own address in rigParams (R-822)",
            pending?.rigParams?.get(DefaultRigTransportFactory.ParamKeys.BLUETOOTH_ADDRESS),
        )
        assertTrue(CaptureState.isCapturing)
    }

    /**
     * R-860/R-861 (halt, coordinator spot-check): before this fix, `mode-change-pending` wrote its
     * live USB session's own `:data` row and `CaptureConfigurationStore` entries but never opened the
     * process-wide [InputStatus]/[RigStatus] holders `mode-usb` itself always does — CF02/CF11 read
     * those holders directly, never the session row, so the *current* USB session rendered as if
     * nothing were open at all, only the pending Bluetooth change (this test's sibling above) showing
     * anything real.
     */
    @Test
    @Requirement("FR-CAP-12", "R-860", "R-861")
    fun `R_860_mode-change-pending opens the live InputStatus and RigStatus holders for its USB session`() = runTest {
        Scenarios.load(context, "mode-change-pending")

        val input = InputStatus.state
        assertTrue("the current USB session must show a real, open input", input is InputStatus.State.Opened)
        input as InputStatus.State.Opened
        assertEquals(AudioDeviceKind.USB_DEVICE, input.descriptor.kind)

        val rig = RigStatus.state
        assertTrue("the current USB session must show a real, connected rig", rig is RigStatus.State.Connected)
        assertEquals(RigModuleTransportKind.USB_SERIAL, (rig as RigStatus.State.Connected).transportKind)
    }

    /**
     * E2-A07 (schema v10): every real TH-D75A session scenario writes [SessionEntity.rigDescriptorId]
     * as the real catalogue id, [SessionEntity.audioRouteVerified] `true` (the coordinator's own
     * seeding instruction — this fixture data claims a session whose route the OS already confirmed,
     * never the honest-but-unknown `null` a session still opening its route would carry) and
     * [SessionEntity.audioNativeRateHz] `48_000` (the TH-D75A's own USB/wired-audio native rate,
     * named uniformly across every transport this list covers, matching the coordinator's own
     * instruction rather than each scenario's own [InputStatus.opened] native rate, which for
     * `mode-bluetooth`/`bt-audio-session`/`bt-audio-dropped` is the *audio-path's* 16 kHz SCO rate —
     * a distinct fact `SessionEntity` does not otherwise carry).
     */
    private val kenwoodV10ScenarioNames = listOf(
        "mode-usb",
        "mode-bluetooth",
        "bt-audio-session",
        "bt-audio-dropped",
        "rig-bt-connected",
        "rig-bt-lost",
        "mode-change-pending",
        "overnight",
        "overnight-live",
        "gap-call",
    )

    @Test
    @Requirement("AC-53", "FR-AST-5", "FR-AST-6", "FR-CAP-13")
    fun `E2_A07_every real TH-D75A session scenario writes the v10 columns`() = runTest {
        kenwoodV10ScenarioNames.forEach { name ->
            val result = Scenarios.load(context, name)
            val session = db.sessionDao().getById(requireNotNull(result.primarySessionId))
            assertEquals(
                "'$name' must name the real TH-D75A catalogue id",
                BundledDescriptors.kenwoodThD75a().id,
                session?.rigDescriptorId,
            )
            assertEquals("'$name' must claim a verified route", true, session?.audioRouteVerified)
            assertEquals("'$name' must name the TH-D75A's own 48 kHz native rate", 48_000, session?.audioNativeRateHz)
        }
    }

    /**
     * E2-A07 (schema v10): `mode-local-mic` has no rig at all (FR-CAP-2b) — its own
     * [SessionEntity.rigDescriptorId] stays honestly `null`, while [SessionEntity.audioRouteVerified]
     * is still `true` (the built-in mic's route is confirmed the same as any other) and
     * [SessionEntity.audioNativeRateHz] is the mic's own native rate, 48 kHz — the same value
     * [modeLocalMic]'s own `InputStatus.opened(nativeRateHz = 48_000, ...)` call already publishes.
     */
    @Test
    @Requirement("AC-53", "FR-AST-5", "FR-AST-6", "FR-CAP-2b")
    fun `E2_A07_mode-local-mic writes the v10 columns with a null rig descriptor`() = runTest {
        val result = Scenarios.load(context, "mode-local-mic")
        val session = db.sessionDao().getById(requireNotNull(result.primarySessionId))

        assertNull("local-mic mode has no rig", session?.rigDescriptorId)
        assertEquals(true, session?.audioRouteVerified)
        assertEquals(48_000, session?.audioNativeRateHz)
    }

    /**
     * R-914 (register, reviewer B2 on run 3): DG04's Mode/Input/Rig-link rows under `overnight` read
     * "not tracked per session in this build" — a false claim in a v10 build, where a session row's
     * own null v7 columns mean "not recorded for this session", never "not tracked in this build".
     * `overnight`/`overnight-live`/`gap-call` share [OvernightScenario.build]'s one session insert,
     * now seeding the v7 columns alongside the v10 ones already asserted above.
     */
    @Test
    @Requirement("FR-CAP-13", "R-914")
    fun `R_914_overnight writes the v7 columns alongside the v10 ones`() = runTest {
        listOf("overnight", "overnight-live", "gap-call").forEach { name ->
            val result = Scenarios.load(context, name)
            val session = db.sessionDao().getById(requireNotNull(result.primarySessionId))
            assertEquals("'$name' must record captureMode", CaptureMode.USB_RADIO.name, session?.captureMode)
            assertEquals("'$name' must record audioRouteKind", AudioRouteKind.USB.name, session?.audioRouteKind)
            assertEquals("'$name' must record audioRouteLabel", "USB Audio Device", session?.audioRouteLabel)
            assertEquals(
                "'$name' must record rigTransport",
                RigTransportKind.USB_SERIAL.name,
                session?.rigTransport,
            )
        }
    }

    /**
     * R-913 (WPI half, register): "every live scenario writes a heartbeat so a live session looks
     * live" — a *current* heartbeat, not merely a present one: `wallMillis` within a bounded window
     * of real now, proving [ScenarioFixtures.markCapturing] (every scenario below's own real caller)
     * writes a fresh record on *this* load, not one carried over from a prior scenario's own run in
     * the same process (`Scenarios.clearPriorScenarioData` deletes `heartbeat.txt` unconditionally
     * before every load — see that function's own kdoc — so a present-and-fresh heartbeat here is
     * proof this scenario's own builder wrote it, not evidence of a stale leftover).
     */
    /** Generous enough for a slow test machine's own wall-clock read to land inside it, still far
     * short of ever accepting a heartbeat left over from a genuinely earlier load. */
    private val heartbeatFreshnessBoundMillis = 60_000L

    private val liveHeartbeatScenarioNames = listOf(
        "mode-local-mic",
        "mode-usb",
        "mode-bluetooth",
        "mode-change-pending",
        "overnight-live",
        "rig-bt-connected",
        "rig-bt-lost",
        "bt-audio-dropped",
    )

    @Test
    @Requirement("R-913")
    fun `R_913_every live scenario writes a current heartbeat`() = runTest {
        liveHeartbeatScenarioNames.forEach { name ->
            Scenarios.load(context, name)
            val heartbeat = FileHeartbeatStore(java.io.File(context.filesDir, "heartbeat.txt")).last()
            assertNotNull("'$name' must write a heartbeat for its own live session", heartbeat)
            val ageMillis = SystemClock.wallMillis() - requireNotNull(heartbeat).wallMillis
            assertTrue(
                "'$name' heartbeat must be current (age ${ageMillis}ms), not stale or from session start",
                ageMillis in 0..heartbeatFreshnessBoundMillis,
            )
        }
    }

    /** The four non-gated catalog entries — every [ModelId] except the gated LLM, which this build's
     * own escape hatch (no `HF_TOKEN`) genuinely cannot bundle (see [Scenarios.installRealBundledAssets]'s
     * own kdoc). */
    private val nonGatedModelIds = ModelId.entries.filter { it != ModelId.LLM_GEMMA3_1B }

    /**
     * R-841/R-842/R-843 (halt): asserts [ModelsController.currentState] itself — the exact read path
     * `Settings-Assets`/S12's Models row use — not merely a file on disk, so this test would have
     * caught the original bug (a genuinely installed file whose marker still failed
     * `ModelsController`'s own checksum comparison against a different value).
     */
    @Test
    @Requirement("FR-AST-3", "FR-AST-3b", "AC-137", "R-841")
    fun `R_841_assets-bundled reports every non-gated entry INSTALLED through ModelsController`() = runTest {
        Scenarios.load(context, "assets-bundled")

        val rows = ModelsController.currentState(context).rows.associateBy { it.id }
        nonGatedModelIds.forEach { id ->
            assertEquals(
                "$id must read INSTALLED through the real ModelsController",
                ModelRowStatus.INSTALLED,
                rows.getValue(id).status,
            )
        }
        assertEquals(
            "this dev/escape-hatch build genuinely lacks the gated LLM — honest, not a defect",
            ModelRowStatus.NOT_INSTALLED,
            rows.getValue(ModelId.LLM_GEMMA3_1B).status,
        )
    }

    @Test
    @Requirement("FR-AST-3b", "AC-137", "R-841")
    fun `R_841_asset-corrupt reports ASR_ENCODER Failed, every other non-gated entry Installed`() = runTest {
        Scenarios.load(context, "asset-corrupt")

        val rows = ModelsController.currentState(context).rows.associateBy { it.id }
        assertEquals(
            "a genuinely corrupted copy must never verify as installed",
            ModelRowStatus.NOT_INSTALLED,
            rows.getValue(ModelId.ASR_ENCODER).status,
        )
        nonGatedModelIds.filter { it != ModelId.ASR_ENCODER }.forEach { id ->
            assertEquals("$id must still read INSTALLED", ModelRowStatus.INSTALLED, rows.getValue(id).status)
        }
    }

    @Test
    @Requirement("FR-AST-3a", "AC-138", "R-842")
    fun `R_842_tier0-llm-stored reports the LLM INSTALLED but tier-ineligible below T3, through ModelsController`() =
        runTest {
            Scenarios.load(context, "tier0-llm-stored")

            val llmRow = ModelsController.currentState(context).rows.associateBy {
                it.id
            }.getValue(ModelId.LLM_GEMMA3_1B)
            assertEquals("stored — AC-138's own distinction", ModelRowStatus.INSTALLED, llmRow.status)
            assertFalse("stored, never loaded — AC-138's own distinction", llmRow.tierEligible)
        }

    /**
     * R-865 (halt, coordinator spot-check): before this fix, every entry here — the four non-gated
     * ones included — went through [ScenarioFixtures.installModelFixture]'s own 64-byte placeholder,
     * so `ModelsController.currentState` reported them `INSTALLED` without the real installer ever
     * having genuinely copied or verified anything. `sizeBytes` (real disk size for a verified row —
     * [org.ort.app.ui.data.ModelRowViewState]'s own kdoc) is exactly 64 for that placeholder shape and
     * some real, much larger value for a genuinely installed asset — the discriminating fact this test
     * checks directly, the same way `R_841_assets-bundled...` already does for the real-installer
     * scenarios.
     */
    @Test
    @Requirement("FR-AST-3", "FR-AST-3b", "AC-137", "R-865")
    fun `R_865_tier0-llm-stored installs its four non-gated entries through the real installer`() = runTest {
        Scenarios.load(context, "tier0-llm-stored")

        val rows = ModelsController.currentState(context).rows.associateBy { it.id }
        nonGatedModelIds.forEach { id ->
            val row = rows.getValue(id)
            assertEquals(
                "$id must read INSTALLED through the real ModelsController",
                ModelRowStatus.INSTALLED,
                row.status,
            )
            assertNotEquals(
                "$id must be the real installed asset's own size, not installModelFixture's 64-byte placeholder",
                64L,
                row.sizeBytes,
            )
        }
    }

    @Test
    @Requirement("FR-DIG-3", "FR-DIG-6", "FR-DIG-11")
    fun `FR_DIG_11_llm-enabled-prose stores two real prose summaries with prose enabled`() = runTest {
        val result = Scenarios.load(context, "llm-enabled-prose")

        val settings = SharedPreferencesProseDigestSettingsStore(context)
        assertTrue(settings.isEnabled())

        val summaryStore = RoomProseSummaryStore(db)
        val sessionId = requireNotNull(result.primarySessionId)
        val summary = runBlocking { summaryStore.forThread("$sessionId-thread1") }
        assertNotNull("expected a real, stored prose summary for the QSO thread", summary)
        assertEquals(4, summary?.sourceTransmissionIds?.size)
        val other = runBlocking { summaryStore.forThread("$sessionId-thread-other") }
        assertNotNull("expected a real, stored prose summary for the second thread DG05 draws", other)
    }

    @Test
    @Requirement("FR-DIG-3b", "AC-140")
    fun `AC_140_llm-disabled leaves the same summaries stored while prose is disabled`() = runTest {
        val result = Scenarios.load(context, "llm-disabled")

        val settings = SharedPreferencesProseDigestSettingsStore(context)
        assertFalse("E2-G07's own discriminating fact: stored, but disabled", settings.isEnabled())

        val summaryStore = RoomProseSummaryStore(db)
        val sessionId = requireNotNull(result.primarySessionId)
        val summary = runBlocking { summaryStore.forThread("$sessionId-thread1") }
        assertNotNull("the summary is genuinely stored regardless of the toggle", summary)
    }
}
