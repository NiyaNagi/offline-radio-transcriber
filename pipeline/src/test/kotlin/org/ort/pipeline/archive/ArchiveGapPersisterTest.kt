package org.ort.pipeline.archive

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.capture.android.archive.ArchiveHole
import org.ort.data.OrtDatabase
import org.ort.pipeline.PipelineTestFixtures
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import org.robolectric.RobolectricTestRunner

/**
 * WPARC (FR-RUN-12, constitution IV "record that the archive has a hole, the way a gap is a
 * record"). Closes on a real [OrtDatabase]/[org.ort.data.dao.ArchiveGapDao], never a stand-in.
 */
@RunWith(RobolectricTestRunner::class)
class ArchiveGapPersisterTest {

    @Test
    @Requirement("FR-RUN-12")
    fun `a verification-failure hole is persisted with its sample range and reason, never filtered`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))
        val clock = TestClock(startWallMillis = 5_000L)
        val persister = ArchiveGapPersister(db.archiveGapDao(), clock)

        persister.persist(
            "S1",
            ArchiveHole(startSample = 480_000L, sampleCount = 16_000, reason = "verification_failed"),
        )

        val gaps = db.archiveGapDao().listBySession("S1")
        assertEquals(1, gaps.size)
        assertEquals(480_000L, gaps[0].startSample)
        assertEquals(16_000L, gaps[0].sampleCount)
        assertEquals("verification_failed", gaps[0].reason)
        assertEquals(5_000L, gaps[0].recordedAtMillis)
    }

    @Test
    @Requirement("FR-RUN-12")
    fun `an unexpected write failure is persisted by its exception class name, never free text`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))
        val persister = ArchiveGapPersister(db.archiveGapDao(), TestClock())

        persister.persistFailure("S1", startSample = 0L, sampleCount = 480_000, reason = "IOException")

        val gaps = db.archiveGapDao().listBySession("S1")
        assertEquals("IOException", gaps.single().reason)
    }

    @Test
    @Requirement("FR-RUN-12")
    fun `multiple holes on the same session are all retained, in sample order`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session(id = "S1"))
        val persister = ArchiveGapPersister(db.archiveGapDao(), TestClock())

        persister.persist("S1", ArchiveHole(480_000L, 16_000, "verification_failed"))
        persister.persist("S1", ArchiveHole(0L, 16_000, "verification_failed"))

        val gaps = db.archiveGapDao().listBySession("S1")
        assertEquals(listOf(0L, 480_000L), gaps.map { it.startSample })
    }
}
