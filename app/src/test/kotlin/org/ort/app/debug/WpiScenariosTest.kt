package org.ort.app.debug

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.ort.app.ui.setup.SetupStateMachine
import org.ort.app.ui.setup.SetupStep
import org.ort.app.ui.setup.SharedPreferencesSetupStore
import org.ort.core.AttributionState
import org.ort.core.capture.AudioRouteKind
import org.ort.core.capture.BluetoothAudioProfile
import org.ort.core.capture.CaptureMode
import org.ort.core.capture.RigTransportKind
import org.ort.data.OrtDatabase
import org.ort.data.entity.CaptureGapCause
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
    @Requirement("F-023", "FR-CAP-5")
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
        assertEquals(CaptureGapCause.INPUT_LOST, gap.cause)
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
