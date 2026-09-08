package org.ort.app.debug

import androidx.test.core.app.ApplicationProvider
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
import org.ort.app.ui.failures.DebugFailureOverride
import org.ort.app.ui.failures.FailurePresentation
import org.ort.app.ui.setup.SetupStateMachine
import org.ort.app.ui.setup.SetupStep
import org.ort.app.ui.setup.SharedPreferencesSetupStore
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.TranscriptPass
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.pipeline.capture.VadAvailability
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * spec/ui-conformance-plan.md WP0, register R-110: proves each scenario inserts what it claims
 * through `:data`'s real (file-backed, not in-memory) [OrtDatabase] — the same instance
 * [org.ort.app.ui.data.ReaderPolling] opens — that every transmission row carries a full, valid
 * attribution (constitution I), that the tag-and-clear between scenario loads actually clears, and
 * that no scenario references anything outside its own fixture. House style follows
 * `app/src/test/kotlin/org/ort/app/ui/data/ReaderPollingTest.kt`.
 */
@RunWith(RobolectricTestRunner::class)
class ScenariosTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: OrtDatabase

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    @After
    fun resetProcessWideAvailability() {
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
        // setup-verified (register R-227) is the one scenario that writes outside :data and the
        // process-wide capture facets -- clear it too, or it would leak into every later test in
        // this same Robolectric process the same way a stray SharedPreferences write always would.
        context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    @Requirement("R-110")
    fun `R_110 every declared scenario name loads without throwing`() = runTest {
        Scenarios.NAMES.forEach { name ->
            val result = Scenarios.load(context, name)
            assertTrue("'$name' reported a negative session count", result.sessionCount >= 0)
        }
    }

    @Test
    @Requirement("R-110")
    fun `R_110 an unknown scenario name is refused, not silently ignored`() = runTest {
        var threw = false
        try {
            Scenarios.load(context, "does-not-exist")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    @Requirement("R-110")
    fun `R_110 empty seeds no sessions at all`() = runTest {
        val result = Scenarios.load(context, "empty")

        assertEquals(0, result.sessionCount)
        assertEquals(0, result.transmissionCount)
        assertNull(result.primarySessionId)
        assertTrue(db.sessionDao().listAll().isEmpty())
    }

    @Test
    @Requirement("R-110")
    fun `R_110 first-session seeds one session with no transmissions and marks capture running`() = runTest {
        val result = Scenarios.load(context, "first-session")

        assertEquals(1, result.sessionCount)
        assertEquals(0, result.transmissionCount)
        assertTrue(CaptureState.isCapturing)
        assertEquals(result.primarySessionId, CaptureState.sessionId)
    }

    @Test
    @Requirement("R-110")
    fun `R_110 loading a new scenario clears everything the previous scenario wrote`() = runTest {
        val first = Scenarios.load(context, "corrected")
        val firstSessionId = requireNotNull(first.primarySessionId)
        assertTrue(db.transmissionDao().listBySession(firstSessionId).isNotEmpty())

        Scenarios.load(context, "no-audio")

        assertNull(db.sessionDao().getById(firstSessionId))
        assertTrue(db.transmissionDao().listBySession(firstSessionId).isEmpty())
    }

    @Test
    @Requirement("R-110")
    fun `R_110 overnight exercises every Rows dc html attribution variant`() = runTest {
        val result = Scenarios.load(context, "overnight")
        val sessionId = requireNotNull(result.primarySessionId)
        val rows = db.transmissionDao().listBySession(sessionId)

        assertTrue("expected roughly 40 overs, got ${rows.size}", rows.size in 35..50)
        assertTrue(rows.any { it.attributionState == AttributionState.CONFIRMED })
        assertTrue(
            rows.any { it.attributionState == AttributionState.INFERRED && it.attributionSourceTransmissionId != null },
        )
        assertTrue(rows.any { it.attributionState == AttributionState.AMBIGUOUS })
        assertTrue(rows.any { it.attributionState == AttributionState.UNKNOWN })
        assertTrue("expected a corrected row", rows.any { it.corrected })
        assertTrue(
            "expected a rejected row with its reason retained",
            rows.any { it.processingState == TransmissionState.REJECTED && it.rejectionReason == "squelch tail" },
        )
        assertTrue("expected a shared threadId across a QSO", rows.any { it.threadId != null })
        assertTrue("expected mixed signal strengths", rows.mapNotNull { it.signalStrength }.toSet().size > 3)

        val gaps = db.captureGapDao().listBySession(sessionId)
        assertTrue(gaps.any { (it.endedAt!! - it.startedAt) == 38_000L })

        val newStation = db.catalogDao().getStation("WA7HJR")
        assertNotNull("expected a first-heard station row", newStation)
        assertEquals(newStation!!.firstHeardAt, newStation.lastHeardAt)

        val thread = rows.mapNotNull { it.threadId }.first()
        val threadEntity = db.catalogDao().getThread(thread)
        assertNotNull(threadEntity)
        assertEquals(4, threadEntity!!.transmissionCount)
        assertEquals(2, threadEntity.participantStationIds?.size)
    }

    @Test
    @Requirement("R-110")
    fun `R_110 overnight carries at least one revised transmission with an older superseded version`() = runTest {
        val result = Scenarios.load(context, "overnight")
        val sessionId = requireNotNull(result.primarySessionId)
        val revised = db.transmissionDao().listBySession(sessionId)
            .first { db.transcriptDao().getAllVersions(it.id).size > 1 }

        val versions = db.transcriptDao().getAllVersions(revised.id)
        assertEquals(2, versions.size)
        assertEquals(1, versions.count { it.isCurrent })
        assertTrue(versions.any { it.pass == TranscriptPass.A && !it.isCurrent })
    }

    @Test
    @Requirement("R-110")
    fun `R_110 FR_LEX_31 overnight carries both a cold-start and a negative prior for Detail-Why`() = runTest {
        val result = Scenarios.load(context, "overnight")
        val sessionId = requireNotNull(result.primarySessionId)
        val inferred = db.transmissionDao().listBySession(sessionId)
            .first { it.attributionState == AttributionState.INFERRED && it.attributionSourceTransmissionId != null }
        val candidates = db.catalogDao().candidatesFor(inferred.id)
        val breakdown = candidates.single().priorBreakdown!!

        assertTrue("expected a cold-start (exactly zero) prior", breakdown.values.any { it == 0.0 })
        assertTrue("expected a negative (argued against) prior", breakdown.values.any { it < 0.0 })
    }

    @Test
    @Requirement("R-110")
    fun `R_110 every overnight transmission's attribution shape matches Attribution's own invariants`() = runTest {
        val result = Scenarios.load(context, "overnight")
        val sessionId = requireNotNull(result.primarySessionId)

        db.transmissionDao().listBySession(sessionId).forEach { row ->
            when (row.attributionState) {
                AttributionState.CONFIRMED, AttributionState.INFERRED ->
                    if (!row.corrected) {
                        assertNotNull("${row.id}: ${row.attributionState} needs a station", row.stationId)
                    }
                AttributionState.AMBIGUOUS, AttributionState.UNKNOWN -> Unit
            }
        }
    }

    @Test
    @Requirement("R-110")
    fun `R_110 gap-call adds a second capture gap on top of overnight's fixtures`() = runTest {
        val overnight = Scenarios.load(context, "overnight")
        val overnightGaps = db.captureGapDao().listBySession(requireNotNull(overnight.primarySessionId)).size

        val gapCall = Scenarios.load(context, "gap-call")
        val gaps = db.captureGapDao().listBySession(requireNotNull(gapCall.primarySessionId))

        assertEquals(overnightGaps + 1, gaps.size)
    }

    @Test
    @Requirement("F-015", "R-106")
    fun `F15_gap-call's second gap carries cause CALL`() = runTest {
        val gapCall = Scenarios.load(context, "gap-call")
        val gaps = db.captureGapDao().listBySession(requireNotNull(gapCall.primarySessionId))

        assertTrue("expected a CALL-caused gap", gaps.any { it.cause == CaptureGapCause.CALL })
    }

    @Test
    @Requirement("R-110")
    fun `R_110 unclean-end writes a heartbeat that reads as an unclean end, never as capturing`() = runTest {
        Scenarios.load(context, "unclean-end")

        val heartbeat = File(context.filesDir, "heartbeat.txt")
        assertTrue(heartbeat.isFile)
        val lines = heartbeat.readLines()
        assertEquals("false", lines[4])
        assertFalse(CaptureState.isCapturing)
    }

    @Test
    @Requirement("R-110")
    fun `R_110 pass-a-partial has a current Pass A transcript and no Pass B row yet`() = runTest {
        val result = Scenarios.load(context, "pass-a-partial")
        val tx = db.transmissionDao().listBySession(requireNotNull(result.primarySessionId)).single()

        assertEquals(TransmissionState.PROCESSING, tx.processingState)
        val versions = db.transcriptDao().getAllVersions(tx.id)
        assertEquals(1, versions.size)
        assertEquals(TranscriptPass.A, versions.single().pass)
        assertTrue(versions.single().isCurrent)
    }

    @Test
    @Requirement("R-110")
    fun `R_110 corrected carries the CORRECTED lock shape and its audit row`() = runTest {
        val result = Scenarios.load(context, "corrected")
        val tx = db.transmissionDao().listBySession(requireNotNull(result.primarySessionId)).single()

        assertTrue(tx.corrected)
        assertEquals(AttributionState.INFERRED, tx.attributionState)
        assertNull(tx.attributionConfidence)
        assertNull(tx.attributionSourceTransmissionId)
        assertEquals(1, db.catalogDao().correctionsFor(tx.id).size)
    }

    @Test
    @Requirement("R-110")
    fun `R_110 no-audio transmission has no retained audio file`() = runTest {
        val result = Scenarios.load(context, "no-audio")
        val tx = db.transmissionDao().listBySession(requireNotNull(result.primarySessionId)).single()

        assertFalse(File(context.filesDir, tx.audioPath()).isFile)
    }

    @Test
    @Requirement("R-110")
    fun `R_110 revisions carries one current and one superseded transcript version`() = runTest {
        val result = Scenarios.load(context, "revisions")
        val tx = db.transmissionDao().listBySession(requireNotNull(result.primarySessionId)).single()

        val versions = db.transcriptDao().getAllVersions(tx.id)
        assertEquals(2, versions.size)
        assertEquals(1, versions.count { it.isCurrent })
        assertTrue(File(context.filesDir, tx.audioPath()).isFile)
    }

    @Test
    @Requirement("R-110")
    fun `R_110 field-tier1 stamps the session's deviceTier`() = runTest {
        val result = Scenarios.load(context, "field-tier1")
        val session = db.sessionDao().getById(requireNotNull(result.primarySessionId))

        assertEquals("T1", session?.deviceTier)
    }

    @Test
    @Requirement("R-110")
    fun `R_110 search-corpus seeds fourteen park-activation transcripts across three nights`() = runTest {
        val result = Scenarios.load(context, "search-corpus")

        assertEquals(3, result.sessionCount)
        assertEquals(14, result.transmissionCount)
        val allTransmissions = db.sessionDao().listAll()
            .filter { it.id.startsWith("${ScenarioFixtures.SESSION_PREFIX}search-corpus") }
        assertEquals(3, allTransmissions.size)
    }

    @Test
    @Requirement("R-110")
    fun `R_110 stations-14-nights spans fourteen sessions with a real, unlistened whole day`() = runTest {
        val result = Scenarios.load(context, "stations-14-nights")

        assertEquals(14, result.sessionCount)
        assertTrue(result.transmissionCount > 0)
        val stationRows = ScenarioFixtures.CALLSIGNS.mapNotNull { db.catalogDao().getStation(it) }
        assertTrue("expected several stations recorded", stationRows.size >= 6)
    }

    @Test
    @Requirement("R-110")
    fun `R_110 backlog sets the real shed status the reader already displays`() = runTest {
        Scenarios.load(context, "backlog")

        assertEquals(3, ShedStatus.currentLevel)
        assertEquals(112, ShedStatus.backlog)
        assertTrue(CaptureState.isCapturing)
    }

    @Test
    @Requirement("R-110")
    fun `R_110 model-missing sets AsrAvailability unavailable and leaves overs untranscribed`() = runTest {
        val result = Scenarios.load(context, "model-missing")

        assertTrue(AsrAvailability.state is AsrAvailability.State.Unavailable)
        val tx = db.transmissionDao().listBySession(requireNotNull(result.primarySessionId)).single()
        assertNull(db.transcriptDao().getCurrent(tx.id))
    }

    @Test
    @Requirement("FR-STO-3", "R-105")
    fun `FR_STO_3_storage-warn reports ThreeNightsLeft with capture still genuinely running`() = runTest {
        Scenarios.load(context, "storage-warn")

        assertTrue("storage-warn must not reuse F6's exhaustion failure", CaptureState.isCapturing)
        assertTrue(StorageForecast.state is StorageForecast.State.ThreeNightsLeft)
    }

    @Test
    @Requirement("F-005", "R-106")
    fun `F5_os-stopped seeds the unclean-end heartbeat and an OS_STOPPED gap on the previous session`() = runTest {
        val result = Scenarios.load(context, "os-stopped")
        val sessionId = requireNotNull(result.primarySessionId)

        val heartbeat = File(context.filesDir, "heartbeat.txt")
        assertTrue(heartbeat.isFile)
        assertEquals("false", heartbeat.readLines()[4])

        val gaps = db.captureGapDao().listBySession(sessionId)
        assertEquals(1, gaps.size)
        assertEquals(CaptureGapCause.OS_STOPPED, gaps.single().cause)
    }

    @Test
    @Requirement("F-007", "R-104")
    fun `F7_thermal sets ThermalStatus Warm with a measured RTF of 0_9`() = runTest {
        Scenarios.load(context, "thermal")

        val state = ThermalStatus.state
        assertTrue(state is ThermalStatus.State.Warm)
        assertEquals(0.9, state.realTimeFactor)
        assertTrue(CaptureState.isCapturing)
    }

    @Test
    @Requirement("R-177")
    fun `R_177_thermal sets a real transition moment, not now`() = runTest {
        Scenarios.load(context, "thermal")

        val state = ThermalStatus.state as ThermalStatus.State.Warm
        assertTrue(
            "sinceMillis must be a real 'minutes ago' moment, not the current instant",
            state.sinceMillis <= System.currentTimeMillis() - 11 * 60_000L,
        )
    }

    @Test
    @Requirement("F-009", "R-104")
    fun `F9_rig-lost sets RigStatus Stale since thirty minutes ago, last known 145_230`() = runTest {
        Scenarios.load(context, "rig-lost")

        val state = RigStatus.state
        assertTrue(state is RigStatus.State.Stale)
        state as RigStatus.State.Stale
        assertEquals(145_230_000L, state.lastKnown.bands.first().frequencyHz)
        assertTrue(isAtLeastThirtyMinutesAgo(state.sinceMillis))
    }

    private fun isAtLeastThirtyMinutesAgo(sinceMillis: Long): Boolean =
        sinceMillis <= System.currentTimeMillis() - 29 * 60_000L

    @Test
    @Requirement("R-112")
    fun `R_112_level-low sets LevelStatus Measured at minus 38 dBFS with no clip`() = runTest {
        Scenarios.load(context, "level-low")

        val state = LevelStatus.state
        assertTrue(state is LevelStatus.State.Measured)
        state as LevelStatus.State.Measured
        assertEquals(-38f, state.peakDbfs)
        assertEquals(-60f, state.noiseFloorDbfs)
        assertFalse(state.clipped)
        assertTrue(CaptureState.isCapturing)
    }

    @Test
    @Requirement("R-112")
    fun `R_112_level-clip sets LevelStatus Measured clipped at 0 dBFS with twelve clips`() = runTest {
        Scenarios.load(context, "level-clip")

        val state = LevelStatus.state
        assertTrue(state is LevelStatus.State.Measured)
        state as LevelStatus.State.Measured
        assertEquals(0f, state.peakDbfs)
        assertTrue(state.clipped)
        assertEquals(12, state.clipCountLastSecond)
    }

    @Test
    @Requirement("R-113")
    fun `R_113_input-verified sets InputStatus Opened at 48kHz with a verified matching route`() = runTest {
        Scenarios.load(context, "input-verified")

        val state = InputStatus.state
        assertTrue(state is InputStatus.State.Opened)
        state as InputStatus.State.Opened
        assertEquals(48_000, state.nativeRateHz)
        assertTrue(state.routeVerified)
        assertTrue(state.routedDeviceMatches)
        assertTrue(state.resamplerId.isNotBlank())
    }

    @Test
    @Requirement("R-113", "F-001")
    fun `R_113_input-mismatch sets InputStatus Mismatch and does not claim capture is running`() = runTest {
        Scenarios.load(context, "input-mismatch")

        val state = InputStatus.state
        assertTrue(state is InputStatus.State.Mismatch)
        state as InputStatus.State.Mismatch
        assertEquals("USB Audio Device", state.expected.label)
        assertEquals("Built-in microphone", state.actual?.label)
        assertFalse("a mismatch must not claim capture is still running", CaptureState.isCapturing)
    }

    /**
     * R-227 (validator pass 2): before this, S07/S12 had no sanctioned path on an emulator with a
     * silent mic -- this proves the scenario actually lands `SetupStateMachine.stepFor` at
     * `SetupStep.READY` (S12) given fully-granted permissions, the same real decision function
     * `SetupActivity` itself calls, not merely that some preferences got written.
     */
    @Test
    @Requirement("R-227")
    fun `R_227_setup-verified seeds SetupStore so stepFor resumes at READY`() = runTest {
        Scenarios.load(context, "setup-verified")

        val store = SharedPreferencesSetupStore(
            context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, android.content.Context.MODE_PRIVATE),
        )
        assertTrue(store.inputVerified)
        assertTrue(store.levelInBand)
        assertTrue(store.overnightStepSeen)
        assertFalse("must land the operator on READY, not skip straight past it", store.setupComplete)

        val fullyGranted = PermissionsState(
            recordAudioGranted = true,
            notificationsGranted = true,
            isIgnoringBatteryOptimizationsDiagnosticOnly = false,
        )
        val step = SetupStateMachine.stepFor(fullyGranted, micPermanentlyDenied = false, snapshot = store.snapshot())
        assertEquals(SetupStep.READY, step)
    }

    /** The one thing this scenario cannot seed -- see the scenario's own doc comment and
     * `results/ui-audit/README.md`'s "Reaching S07/S12" section. Without a granted `RECORD_AUDIO`,
     * `stepFor` must still resume at `MICROPHONE`, never silently past it. */
    @Test
    @Requirement("R-227")
    fun `R_227_setup-verified does not and cannot grant the two OS permissions itself`() = runTest {
        Scenarios.load(context, "setup-verified")

        val store = SharedPreferencesSetupStore(
            context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, android.content.Context.MODE_PRIVATE),
        )
        val micNotGranted = PermissionsState(
            recordAudioGranted = false,
            notificationsGranted = true,
            isIgnoringBatteryOptimizationsDiagnosticOnly = false,
        )
        val step = SetupStateMachine.stepFor(micNotGranted, micPermanentlyDenied = false, snapshot = store.snapshot())
        assertEquals(SetupStep.MICROPHONE, step)
    }

    // -----------------------------------------------------------------------------------------
    // WP11b (register R-100): the seven ids with no runtime signal today, driven through
    // DebugFailureOverride — each scenario's only real job is to set the exact FailurePresentation
    // its board needs, so these tests assert exactly that rather than re-testing the composables
    // (FailureScreensTest already does that).
    // -----------------------------------------------------------------------------------------

    @Test
    @Requirement("F-014", "R-100")
    fun `F14_clock-dst sets the Clock debug override`() = runTest {
        Scenarios.load(context, "clock-dst")
        assertTrue(DebugFailureOverride.current is FailurePresentation.Clock)
    }

    @Test
    @Requirement("R-147")
    fun `R_147 F14_clock-dst carries the facts and Around the change log the full screen needs`() = runTest {
        Scenarios.load(context, "clock-dst")
        val presentation = DebugFailureOverride.current
        assertTrue(presentation is FailurePresentation.Clock)
        presentation as FailurePresentation.Clock
        assertTrue(presentation.state.nightLabel.isNotEmpty())
        assertTrue(presentation.state.windowLabel.isNotEmpty())
        assertTrue(presentation.state.logRows.isNotEmpty())
    }

    @Test
    @Requirement("F-016", "R-100")
    fun `F16_usb-permission sets the Usb debug override`() = runTest {
        Scenarios.load(context, "usb-permission")
        assertTrue(DebugFailureOverride.current is FailurePresentation.Usb)
    }

    @Test
    @Requirement("F-017", "R-100")
    fun `F17_interrupted-pass sets the Interrupted debug override`() = runTest {
        Scenarios.load(context, "interrupted-pass")
        assertTrue(DebugFailureOverride.current is FailurePresentation.Interrupted)
    }

    @Test
    @Requirement("R-147")
    fun `R_147 F17_interrupted-pass carries the backlog label and the 3 overs list the full screen needs`() = runTest {
        Scenarios.load(context, "interrupted-pass")
        val presentation = DebugFailureOverride.current
        assertTrue(presentation is FailurePresentation.Interrupted)
        presentation as FailurePresentation.Interrupted
        assertTrue(presentation.state.backlogLabel.isNotEmpty())
        assertEquals(3, presentation.state.overs.size)
    }

    @Test
    @Requirement("F-019", "R-100")
    fun `F19_reconcile sets the Reconcile debug override with both mismatch directions`() = runTest {
        Scenarios.load(context, "reconcile")
        val presentation = DebugFailureOverride.current
        assertTrue(presentation is FailurePresentation.Reconcile)
        presentation as FailurePresentation.Reconcile
        assertTrue(presentation.state.recordsNoFile.isNotEmpty())
        assertTrue(presentation.state.filesNoRecord.isNotEmpty())
    }

    @Test
    @Requirement("F-020", "R-100")
    fun `F20_migration-failed sets the Migration debug override with a failed step`() = runTest {
        Scenarios.load(context, "migration-failed")
        val presentation = DebugFailureOverride.current
        assertTrue(presentation is FailurePresentation.Migration)
        presentation as FailurePresentation.Migration
        assertTrue(presentation.state.steps.any { !it.ok })
    }

    @Test
    @Requirement("F-021", "R-100")
    fun `F21_asset-swap sets the AssetSwap debug override`() = runTest {
        Scenarios.load(context, "asset-swap")
        assertTrue(DebugFailureOverride.current is FailurePresentation.AssetSwap)
    }

    @Test
    @Requirement("R-148")
    fun `R_148 F21_asset-swap's options each carry a real sub-line, not a bare label`() = runTest {
        Scenarios.load(context, "asset-swap")
        val presentation = DebugFailureOverride.current
        assertTrue(presentation is FailurePresentation.AssetSwap)
        presentation as FailurePresentation.AssetSwap
        assertTrue(presentation.state.options.isNotEmpty())
        presentation.state.options.forEach { option -> assertTrue(option.subLine.isNotEmpty()) }
    }

    @Test
    @Requirement("F-022", "R-100")
    fun `F22_calibration sets the Calibration debug override`() = runTest {
        Scenarios.load(context, "calibration")
        assertTrue(DebugFailureOverride.current is FailurePresentation.Calibration)
    }

    @Test
    @Requirement("R-100")
    fun `R_100 loading a real-signal scenario clears a prior debug override`() = runTest {
        Scenarios.load(context, "clock-dst")
        assertNotNull(DebugFailureOverride.current)

        Scenarios.load(context, "thermal")

        assertNull("a later scenario must not leave the previous one's override behind", DebugFailureOverride.current)
    }
}
