package org.ort.app.ui.failures

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.HeartbeatTrailEntry
import org.ort.pipeline.capture.HeartbeatTrailStore
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * **R-1161, R-1162, D58, AC-189 (as amended) and AC-199** — the battery-exemption ask's new home.
 *
 * The step this replaces refused to let capture start until overnight survival was proven, and the
 * only thing that can prove it is a capture it would not start. Every test here exists to keep one
 * of the three properties that fix depends on:
 *
 * 1. the trigger is a **real observed heartbeat gap**, never `isIgnoringBatteryOptimizations()`
 *    (constitution IV, NFR-8: that API lies on the reference device);
 * 2. the prompt **never gates anything** (AC-199) — it is a dismissable banner and nothing else;
 * 3. it **does not nag**: an answered gap stays answered, a newer gap is a new question.
 */
@RunWith(RobolectricTestRunner::class)
class KeepCaptureRunningTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    // R-1184: the one declaration, in the module that owns the trail this trigger reads.
    private val beatMillis = HeartbeatTrailStore.HEARTBEAT_INTERVAL_MILLIS

    private fun beat(sessionId: String, wallMillis: Long) = HeartbeatTrailEntry(
        sessionId = sessionId,
        wallMillis = wallMillis,
        monotonicNanos = wallMillis * 1_000_000L,
        samplePosition = wallMillis * 16L,
        framesObservedSinceLastBeat = true,
    )

    /** A clean run: [count] beats, exactly one cadence apart. */
    private fun cleanTrail(count: Int, startAt: Long = 1_000_000L, sessionId: String = "S1") =
        (0 until count).map { beat(sessionId, startAt + it * beatMillis) }

    private fun signals(
        missedHeartbeat: MissedHeartbeatEvidence? = null,
        dismissedThrough: Long? = null,
        thermal: ThermalStatus.State = ThermalStatus.State.Nominal(0, null),
    ) = FailureSignals(
        captureState = CaptureState.State.Capturing,
        inputStatus = InputStatus.State.None,
        levelStatus = LevelStatus.State.NotMeasured,
        thermalStatus = thermal,
        rigStatus = RigStatus.State.Absent,
        storageForecast = StorageForecast.State.NotYetMeasured(0L, 0L),
        shedLevel = 0,
        shedBacklog = 0,
        newestGap = null,
        nowMillis = 2_000_000L,
        debugOverride = null,
        missedHeartbeat = missedHeartbeat,
        keepCaptureRunningDismissedThroughWallMillis = dismissedThrough,
    )

    private fun evidence(resumedAt: Long, gapMillis: Long = 3 * 60 * 60_000L) = MissedHeartbeatEvidence(
        gapMillis = gapMillis,
        stoppedAtWallMillis = resumedAt - gapMillis,
        resumedAtWallMillis = resumedAt,
        crossedProcessRestart = true,
    )

    // -------------------------------------------------------------------------------------------
    // The trigger: a real gap in the app's own trail, and nothing else.
    // -------------------------------------------------------------------------------------------

    @Test
    @Requirement("AC-189", "NFR-8", "constitution IV")
    fun `AC_189 a clean heartbeat trail never raises the prompt`() {
        assertNull("beats on cadence are not a gap", newestMissedHeartbeat(cleanTrail(count = 20)))
        // An empty trail is the state of a device that has never captured at all — which is exactly
        // the state the deadlocked setup step used to ask in, and must produce nothing here.
        assertNull(newestMissedHeartbeat(emptyList()))
        assertNull("one beat cannot describe an interval", newestMissedHeartbeat(cleanTrail(count = 1)))
    }

    @Test
    @Requirement("AC-189", "NFR-8")
    fun `AC_189 a beat that is merely late is a busy phone, not a missed heartbeat`() {
        val trail = listOf(
            beat("S1", 1_000_000L),
            // Inside the tolerance ProveItAnalyzer itself allows.
            beat("S1", 1_000_000L + beatMillis + HEARTBEAT_GAP_TOLERANCE_MILLIS),
        )
        assertNull(newestMissedHeartbeat(trail))
    }

    @Test
    @Requirement("AC-189", "FR-SVC-5b")
    fun `AC_189 a real gap is found with when it started, how long it was, and whether the process restarted`() {
        val stoppedAt = 1_000_000L
        val resumedAt = stoppedAt + 3 * 60 * 60_000L
        val trail = listOf(beat("S1", stoppedAt - beatMillis), beat("S1", stoppedAt), beat("S2", resumedAt))

        val found = requireNotNull(newestMissedHeartbeat(trail))
        assertEquals(stoppedAt, found.stoppedAtWallMillis)
        assertEquals(resumedAt, found.resumedAtWallMillis)
        assertEquals(resumedAt - stoppedAt, found.gapMillis)
        assertTrue("the beats belong to different sessions — the process was restarted", found.crossedProcessRestart)
    }

    @Test
    @Requirement("AC-189")
    fun `AC_189 the newest gap wins when a trail holds more than one`() {
        val trail = listOf(
            beat("S1", 1_000_000L),
            beat("S2", 1_000_000L + 600_000L),
            beat("S2", 1_000_000L + 600_000L + beatMillis),
            beat("S3", 1_000_000L + 600_000L + beatMillis + 900_000L),
        )
        val found = requireNotNull(newestMissedHeartbeat(trail))
        assertEquals(1_000_000L + 600_000L + beatMillis + 900_000L, found.resumedAtWallMillis)
        assertEquals(900_000L, found.gapMillis)
    }

    /**
     * **R-1184: there is one cadence now, and this is what proves this trigger uses it.**
     *
     * The old version of this test asserted `30_000L` against a constant this file's own production
     * source declared for itself, because `RealCaptureService`'s was `private` — a copy, and one
     * whose drift would be invisible: widen the real cadence, leave the copy, and the prompt quietly
     * stops firing, which looks exactly like a phone that never misses a beat. The constant now
     * lives once on [HeartbeatTrailStore], read by the service's ticker and by
     * [newestMissedHeartbeat] alike.
     *
     * Asserted **behaviourally** rather than as `assertEquals(SHARED, SHARED)`, which would pass
     * whatever either side did: a beat exactly one shared cadence plus the tolerance late is not a
     * gap, and one millisecond beyond it is. Point a future `newestMissedHeartbeat` at any number
     * other than the shared one and one of the two halves fails.
     */
    @Test
    @Requirement("AC-189", "NFR-8", "R-1184", "constitution IV")
    fun `R_1184 this trigger measures against the one cadence RealCaptureService actually beats on`() {
        val cadence = HeartbeatTrailStore.HEARTBEAT_INTERVAL_MILLIS
        val onTime = listOf(beat("S1", 0L), beat("S1", cadence + HEARTBEAT_GAP_TOLERANCE_MILLIS))
        val late = listOf(beat("S1", 0L), beat("S1", cadence + HEARTBEAT_GAP_TOLERANCE_MILLIS + 1))

        assertNull("a beat inside the shared cadence plus its tolerance is a busy phone", newestMissedHeartbeat(onTime))
        assertEquals(
            "one millisecond past it is a real gap, measured against the shared cadence",
            cadence + HEARTBEAT_GAP_TOLERANCE_MILLIS + 1,
            requireNotNull(newestMissedHeartbeat(late)).gapMillis,
        )
    }

    /** NFR-8's own 8-hour overnight gate, as an identity rather than a comment two numbers have to
     * keep agreeing with by hand: the trail's bound *is* eight hours of beats at the cadence above.
     * Kept here, beside the trigger that depends on both, as well as in `:pipeline`'s own suite. */
    @Test
    @Requirement("NFR-8", "R-1184")
    fun `R_1184 the trail's bound is exactly the overnight gate's eight hours at that cadence`() {
        assertEquals(
            8 * 60 * 60_000L,
            HeartbeatTrailStore.DEFAULT_MAX_ENTRIES * HeartbeatTrailStore.HEARTBEAT_INTERVAL_MILLIS,
        )
    }

    // -------------------------------------------------------------------------------------------
    // The mapper: raised on evidence, suppressed by an answer, and never a takeover.
    // -------------------------------------------------------------------------------------------

    @Test
    @Requirement("AC-189", "D58")
    fun `AC_189 the prompt is raised by observed evidence and is a dismissable banner, never a takeover`() {
        val mapped = FailureMapper.map(signals(missedHeartbeat = evidence(resumedAt = 1_500_000L)))

        assertTrue("expected the keep-running prompt, got $mapped", mapped is FailurePresentation.KeepCaptureRunning)
        val state = (mapped as FailurePresentation.KeepCaptureRunning).state
        assertEquals("the dismiss key is the gap's own identity", 1_500_000L, state.dismissKey)
        assertTrue("it names when the app stopped", state.stoppedAtLabel.isNotBlank())
        assertTrue("it names how much was lost", state.gapDurationLabel.isNotBlank())
    }

    @Test
    @Requirement("AC-199", "FR-RUN-1", "constitution IV")
    fun `AC_199 the prompt is never one of the takeovers, so no destination and no capture can be withheld`() {
        // AC-199's structural half, asserted rather than argued: `FailureHost` routes a presentation
        // to a full-screen takeover only if it is in TAKEOVER_PRESENTATIONS, and this one is not — so
        // there is no code path on which it can stand between the operator and the app. The
        // complementary behavioural half lives in `FailureHostTest`.
        val mapped = FailureMapper.map(signals(missedHeartbeat = evidence(resumedAt = 1_500_000L)))
        val takeovers = listOf(
            FailurePresentation.Route::class,
            FailurePresentation.StorageHalt::class,
            FailurePresentation.Usb::class,
            FailurePresentation.Reconcile::class,
            FailurePresentation.Migration::class,
            FailurePresentation.AssetSwap::class,
            FailurePresentation.Calibration::class,
            FailurePresentation.Clock::class,
            FailurePresentation.Interrupted::class,
        )
        assertTrue("a post-capture prompt must never be a takeover", mapped::class !in takeovers)
    }

    @Test
    @Requirement("AC-189", "R-1161")
    fun `AC_189 an answered gap stays answered — the prompt does not nag`() {
        val answered = evidence(resumedAt = 1_500_000L)
        val mapped = FailureMapper.map(
            signals(missedHeartbeat = answered, dismissedThrough = answered.resumedAtWallMillis),
        )
        assertTrue("a dismissed gap must not come back: $mapped", mapped !is FailurePresentation.KeepCaptureRunning)
    }

    @Test
    @Requirement("AC-189")
    fun `AC_189 a strictly newer gap is a new question and does come back`() {
        val mapped = FailureMapper.map(
            signals(missedHeartbeat = evidence(resumedAt = 1_500_001L), dismissedThrough = 1_500_000L),
        )
        assertTrue(
            "AC-189's own 'reappears on every relevant subsequent launch' is new evidence, not a repeat",
            mapped is FailurePresentation.KeepCaptureRunning,
        )
    }

    @Test
    @Requirement("AC-189", "R-1161")
    fun `AC_189 the prompt outranks a thermal warning but never a real OS-stopped gap record`() {
        // Above thermal: a phone that ends the app outranks one that is merely hot.
        val withThermal = FailureMapper.map(
            signals(
                missedHeartbeat = evidence(resumedAt = 1_500_000L),
                thermal = ThermalStatus.State.Hot(osThermalStatus = 4, realTimeFactor = 1.4, sinceMillis = 1_000L),
            ),
        )
        assertTrue(
            "expected the keep-running prompt to win over a thermal banner, got $withThermal",
            withThermal is FailurePresentation.KeepCaptureRunning,
        )

        // Below F5: a real, closed `OS_STOPPED` gap row is the *same kill* reported with stronger,
        // session-level evidence. Two banners about one event would be the defect.
        val withKilledRecord = FailureMapper.map(
            signals(missedHeartbeat = evidence(resumedAt = 1_990_000L)).copy(
                newestGap = CaptureGapEntity(
                    id = "gap1",
                    sessionId = "s1",
                    startedAt = 1_800_000L,
                    endedAt = 1_990_000L,
                    cause = CaptureGapCause.OS_STOPPED,
                    recoveredAutomatically = true,
                ),
            ),
        )
        assertTrue(
            "F5 already reports this kill with a real gap row; got $withKilledRecord",
            withKilledRecord is FailurePresentation.Killed,
        )
    }

    // -------------------------------------------------------------------------------------------
    // The dismissal, persisted.
    // -------------------------------------------------------------------------------------------

    @Test
    @Requirement("AC-189", "R-1161")
    fun `AC_189 a dismissal survives the process, so the prompt does not return on the next launch`() {
        val name = "keep-running-${System.nanoTime()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }

        assertNull(SharedPreferencesKeepCaptureRunningDismissStore(prefs).dismissedThroughWallMillis())
        SharedPreferencesKeepCaptureRunningDismissStore(prefs).dismiss(1_500_000L)

        // A second store over the same file is exactly what the next launch constructs.
        assertEquals(1_500_000L, SharedPreferencesKeepCaptureRunningDismissStore(prefs).dismissedThroughWallMillis())
    }

    @Test
    @Requirement("AC-189", "constitution I")
    fun `AC_189 the dismiss watermark never moves backwards and cannot un-answer a newer gap`() {
        val name = "keep-running-back-${System.nanoTime()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val store = SharedPreferencesKeepCaptureRunningDismissStore(prefs)

        store.dismiss(2_000_000L)
        store.dismiss(1_000_000L)

        assertEquals(2_000_000L, store.dismissedThroughWallMillis())
    }

    @Test
    @Requirement("AC-189", "constitution II")
    fun `AC_189 the in-memory dismiss store behaves the same way as the real one`() {
        val fake = InMemoryKeepCaptureRunningDismissStore()
        assertNull(fake.dismissedThroughWallMillis())
        fake.dismiss(2_000_000L)
        fake.dismiss(1_000_000L)
        assertEquals(2_000_000L, fake.dismissedThroughWallMillis())
    }

    // -------------------------------------------------------------------------------------------
    // The real reader, over a real trail file.
    // -------------------------------------------------------------------------------------------

    @Test
    @Requirement("AC-189", "FR-SVC-5b")
    fun `AC_189 the real reader finds a gap in a real trail file and nothing in a clean one`() {
        val file = java.io.File(context.filesDir, "keep-running-${System.nanoTime()}.log")
        val store = org.ort.pipeline.capture.FileHeartbeatTrailStore(file)
        cleanTrail(count = 4).forEach(store::append)
        assertNull(FileMissedHeartbeatReader(store).newest())

        // The trail file is what survives the process death this detects — the row after the gap is
        // written by a different process, under a different session id.
        store.append(beat("S2", 1_000_000L + 3 * beatMillis + 4 * 60 * 60_000L))
        val found = requireNotNull(FileMissedHeartbeatReader(store).newest())
        assertEquals(4 * 60 * 60_000L, found.gapMillis)
        assertTrue(found.crossedProcessRestart)
    }

    @Test
    @Requirement("AC-189", "constitution IV")
    fun `AC_189 a device that has never captured reads no evidence rather than failing`() {
        // No trail file has ever been written — the state of a fresh install, which the old setup
        // step tried to gate on. It must be silent here, not an error and not a prompt.
        val file = java.io.File(context.filesDir, "keep-running-absent-${System.nanoTime()}.log")
        assertTrue("precondition: the trail file does not exist", !file.exists())
        assertNull(FileMissedHeartbeatReader(org.ort.pipeline.capture.FileHeartbeatTrailStore(file)).newest())
    }
}
