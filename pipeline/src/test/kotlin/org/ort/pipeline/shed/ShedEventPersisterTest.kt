package org.ort.pipeline.shed

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.data.OrtDatabase
import org.ort.data.entity.ShedTrigger
import org.ort.pipeline.PipelineTestFixtures
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShedEventPersisterTest {

    @Test
    @Requirement("FR-RUN-3", "FR-RUN-5")
    fun `FR_RUN_3 a backlog transition is persisted with the right trigger and sample position`() = runTest {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session())
        val persister = ShedEventPersister(db.shedEventDao(), TestClock())

        val event = ShedController.ShedEvent(level = 1, reason = "backlog 25 >= threshold", atWallMillis = 5_000)
        persister.persist(sessionId = "SESSION01", levelBefore = 0, event = event, samplePosition = 48_000L)

        val stored = db.shedEventDao().listBySession("SESSION01").single()
        assertEquals(0, stored.levelBefore)
        assertEquals(1, stored.levelAfter)
        assertEquals(ShedTrigger.BACKLOG, stored.trigger)
        assertEquals("backlog 25 >= threshold", stored.reason)
        assertEquals(5_000L, stored.atWallMillis)
        assertEquals(48_000L, stored.samplePosition)
    }

    @Test
    @Requirement("FR-RUN-3")
    fun `FR_RUN_3 a battery-forced transition is classified BATTERY, not BACKLOG`() = runTest {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session())
        val persister = ShedEventPersister(db.shedEventDao(), TestClock())

        val event = ShedController.ShedEvent(
            level = 4,
            reason = "battery 10% < 15% and not charging",
            atWallMillis = 1_000,
        )
        persister.persist(sessionId = "SESSION01", levelBefore = 0, event = event, samplePosition = 0L)

        val stored = db.shedEventDao().listBySession("SESSION01").single()
        assertEquals(ShedTrigger.BATTERY, stored.trigger)
    }
}
