package org.ort.data.dao

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.data.OrtDatabase
import org.ort.data.TestFixtures
import org.ort.data.entity.ShedEventEntity
import org.ort.data.entity.ShedTrigger
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * F-021 — persistence for [org.ort.pipeline.shed.ShedController.ShedEvent], which today lives
 * only in that controller's in-memory list. FR-RUN-3 requires each shed step to be surfaced;
 * FR-RUN-5 requires shed level to be observable. This test proves the `:data` half only — no
 * `:pipeline` persist call is wired here.
 */
@RunWith(RobolectricTestRunner::class)
public class ShedEventDaoTest {

    private lateinit var db: OrtDatabase

    @Before
    public fun openDatabase() {
        db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
    }

    @Test
    @Requirement("FR-RUN-3")
    public fun insert_then_listBySession_returns_shed_events_in_wall_clock_order(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))

        db.shedEventDao().insert(
            ShedEventEntity(
                id = "SE2",
                sessionId = "S1",
                levelBefore = 1,
                levelAfter = 2,
                trigger = ShedTrigger.BACKLOG,
                reason = "backlog 60 >= threshold",
                atWallMillis = 2_000L,
                atMonotonicNanos = 2_000_000_000L,
                samplePosition = 44_100L,
            ),
        )
        db.shedEventDao().insert(
            ShedEventEntity(
                id = "SE1",
                sessionId = "S1",
                levelBefore = 0,
                levelAfter = 1,
                trigger = ShedTrigger.BACKLOG,
                reason = "backlog 20 >= threshold",
                atWallMillis = 1_000L,
                atMonotonicNanos = 1_000_000_000L,
                samplePosition = null,
            ),
        )

        val rows = db.shedEventDao().listBySession("S1")

        assertEquals(listOf("SE1", "SE2"), rows.map { it.id }) // ordered by atWallMillis, not insert order
        assertEquals(0, rows[0].levelBefore)
        assertEquals(1, rows[0].levelAfter)
        assertNull(rows[0].samplePosition)
        assertEquals(44_100L, rows[1].samplePosition)
    }

    @Test
    @Requirement("FR-RUN-5")
    public fun latestForSession_returns_the_most_recent_shed_level_for_that_session_only(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        db.sessionDao().insert(TestFixtures.session("S2"))

        db.shedEventDao().insert(
            ShedEventEntity(
                id = "SE1",
                sessionId = "S1",
                levelBefore = 0,
                levelAfter = 1,
                trigger = ShedTrigger.BACKLOG,
                reason = "backlog 20 >= threshold",
                atWallMillis = 1_000L,
                atMonotonicNanos = 1_000_000_000L,
                samplePosition = null,
            ),
        )
        db.shedEventDao().insert(
            ShedEventEntity(
                id = "SE2",
                sessionId = "S1",
                levelBefore = 1,
                levelAfter = 4,
                trigger = ShedTrigger.BATTERY,
                reason = "battery 10% < 15% and not charging",
                atWallMillis = 5_000L,
                atMonotonicNanos = 5_000_000_000L,
                samplePosition = 88_200L,
            ),
        )
        db.shedEventDao().insert(
            ShedEventEntity(
                id = "SE3",
                sessionId = "S2",
                levelBefore = 0,
                levelAfter = 5,
                trigger = ShedTrigger.STORAGE,
                reason = "storage exhausted",
                atWallMillis = 9_000L,
                atMonotonicNanos = 9_000_000_000L,
                samplePosition = null,
            ),
        )

        val latest = db.shedEventDao().latestForSession("S1")

        assertEquals("SE2", latest!!.id)
        assertEquals(4, latest.levelAfter)
        assertEquals(ShedTrigger.BATTERY, latest.trigger)
    }
}
