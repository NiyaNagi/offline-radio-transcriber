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
import org.ort.app.ui.data.ModelCatalog
import org.ort.app.ui.data.ModelId
import org.ort.app.ui.data.ModelsController
import org.ort.app.ui.failures.DebugFailureOverride
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
        assertTrue(CaptureState.isCapturing)
    }

    /**
     * `ModelsController.currentState`'s own [ModelRowViewState.status] verifies the marker it finds
     * against [ModelCatalog]'s *hardcoded, generated* production checksum (`GeneratedBundledAssetManifest`),
     * not this fixture's own synthetic placeholder bytes — so it can never read `INSTALLED` for a
     * scenario-installed file, by construction, regardless of whether [BundledAssetInstaller] itself
     * genuinely installed it. What *is* real and checkable here is the filesystem effect
     * [BundledAssetInstaller.installAll] itself produces: a real destination file exists at
     * [ModelCatalog]'s own real, production destination path for every entry that installed, and is
     * genuinely absent for one that failed its digest check (AC-137's own "never activated").
     */
    @Test
    @Requirement("FR-AST-3", "FR-AST-3b", "AC-137")
    fun `AC_137_assets-bundled installs every real ModelCatalog entry`() = runTest {
        Scenarios.load(context, "assets-bundled")

        ModelCatalog.entries.forEach { entry ->
            val destination = entry.destination(context.filesDir)
            assertTrue("${entry.id} must have a real installed file on disk", destination.isFile)
            assertTrue("${entry.id} must not be an empty file", destination.length() > 0)
        }
    }

    @Test
    @Requirement("FR-AST-3b", "AC-137")
    fun `AC_137_asset-corrupt fails exactly the corrupt entry and installs every other one`() = runTest {
        Scenarios.load(context, "asset-corrupt")

        val corruptDestination = ModelCatalog.entry(ModelId.ASR_ENCODER).destination(context.filesDir)
        assertFalse("a corrupt copy must never be activated (AC-137)", corruptDestination.isFile)
        ModelCatalog.entries.filter { it.id != ModelId.ASR_ENCODER }.forEach { entry ->
            val destination = entry.destination(context.filesDir)
            assertTrue("${entry.id} must still install for real", destination.isFile)
        }
    }

    @Test
    @Requirement("FR-AST-3a", "AC-138")
    fun `AC_138_tier0-llm-stored installs the LLM but reports it tier-ineligible below T3`() = runTest {
        Scenarios.load(context, "tier0-llm-stored")

        val destination = ModelCatalog.entry(ModelId.LLM_GEMMA3_1B).destination(context.filesDir)
        assertTrue("the LLM asset must genuinely be on disk (stored)", destination.isFile)

        val llmRow = ModelsController.currentState(context).rows.associateBy { it.id }.getValue(ModelId.LLM_GEMMA3_1B)
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
