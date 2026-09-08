package org.ort.pipeline

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.capture.android.GapRecord
import org.ort.data.OrtDatabase
import org.ort.data.entity.CaptureGapCause
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GapPersisterTest {

    @Test
    @Requirement("AC-48", "AC-49", "FR-RUN-12")
    fun `AC_48 a capture-android GapRecord is persisted as a CaptureGapEntity with the same bounds`() = runTest {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session())
        val persister = GapPersister(db.captureGapDao(), TestClock())

        val gap = GapRecord(
            startMonotonicNanos = 0,
            endMonotonicNanos = 5_000_000_000,
            startWallMillis = 1_000,
            endWallMillis = 6_000,
            cause = "focus loss",
        )
        persister.persist("SESSION01", gap)

        val stored = db.captureGapDao().listBySession("SESSION01").single()
        assertEquals(1_000L, stored.startedAt)
        assertEquals(6_000L, stored.endedAt)
        assertEquals(CaptureGapCause.INTERRUPTION, stored.cause)
        assertEquals(true, stored.recoveredAutomatically)
    }

    @Test
    @Requirement("AC-48", "AC-3", "FR-RUN-12")
    fun `AC_48 F-010's dropped-span cause is mapped to DEVICE_LOST, not UNKNOWN`() = runTest {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session())
        val persister = GapPersister(db.captureGapDao(), TestClock())

        // The exact string capture-android's DroppedSpanCause.encode() produces (its PREFIX and
        // parenthetical are internal, so this is duplicated verbatim rather than referenced).
        val gap = GapRecord(
            startMonotonicNanos = 0,
            endMonotonicNanos = 30_000_000,
            startWallMillis = 0,
            endWallMillis = 30,
            cause = "dropped samples: 480 samples over 30ms (stalled consumer or read shortfall)",
        )
        persister.persist("SESSION01", gap)

        val stored = db.captureGapDao().listBySession("SESSION01").single()
        assertEquals(CaptureGapCause.DEVICE_LOST, stored.cause)
    }

    @Test
    @Requirement("F-015", "FR-RUN-11", "R-106")
    fun `F15 a cause naming a call is mapped to CALL`() = runTest {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session())
        val persister = GapPersister(db.captureGapDao(), TestClock())

        val gap = GapRecord(
            startMonotonicNanos = 0,
            endMonotonicNanos = 38_000_000_000,
            startWallMillis = 0,
            endWallMillis = 38_000,
            cause = "incoming call took the microphone",
        )
        persister.persist("SESSION01", gap)

        assertEquals(CaptureGapCause.CALL, db.captureGapDao().listBySession("SESSION01").single().cause)
    }

    @Test
    @Requirement("R-106")
    fun `a bare read error is mapped to INPUT_LOST, the successor to DEVICE_LOST`() = runTest {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session())
        val persister = GapPersister(db.captureGapDao(), TestClock())

        val gap = GapRecord(
            startMonotonicNanos = 0,
            endMonotonicNanos = 1_000_000,
            startWallMillis = 0,
            endWallMillis = 1,
            cause = "read error",
        )
        persister.persist("SESSION01", gap)

        assertEquals(CaptureGapCause.INPUT_LOST, db.captureGapDao().listBySession("SESSION01").single().cause)
    }

    @Test
    @Requirement("F-005", "R-106")
    fun `F5 a cause naming an OS stop is mapped to OS_STOPPED`() = runTest {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session())
        val persister = GapPersister(db.captureGapDao(), TestClock())

        val gap = GapRecord(
            startMonotonicNanos = 0,
            endMonotonicNanos = 1_000_000,
            startWallMillis = 0,
            endWallMillis = 1,
            cause = "os stopped: unclean end detected on launch",
        )
        persister.persist("SESSION01", gap)

        assertEquals(CaptureGapCause.OS_STOPPED, db.captureGapDao().listBySession("SESSION01").single().cause)
    }

    @Test
    @Requirement("R-106")
    fun `a route-mismatch-shaped cause is mapped to ROUTE_LOST -- reserved, unreachable today`() = runTest {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session())
        val persister = GapPersister(db.captureGapDao(), TestClock())

        val gap = GapRecord(
            startMonotonicNanos = 0,
            endMonotonicNanos = 1_000_000,
            startWallMillis = 0,
            endWallMillis = 1,
            cause = "route mismatch: expected usb-1, got builtin-mic",
        )
        persister.persist("SESSION01", gap)

        assertEquals(CaptureGapCause.ROUTE_LOST, db.captureGapDao().listBySession("SESSION01").single().cause)
    }
}
