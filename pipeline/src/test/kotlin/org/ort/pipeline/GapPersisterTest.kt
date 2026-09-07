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
}
