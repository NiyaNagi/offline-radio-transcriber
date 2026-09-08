package org.ort.pipeline.capture

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.data.OrtDatabase
import org.ort.pipeline.PipelineTestFixtures
import org.ort.pipeline.shed.FakeShedSignals
import org.ort.pipeline.shed.ShedController
import org.ort.pipeline.shed.ShedEventPersister
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import org.robolectric.RobolectricTestRunner

/**
 * audit F-007. No `ServiceController` harness exists yet to start [RealCaptureService] itself
 * (F-011, still open) so, following the same approach F-005/F-028 used, this exercises the
 * extracted seams directly: [ShedEventRelay] (the join between a real [ShedController] tick and
 * [ShedEventPersister]) and [storageFloorBreached] (the honest, loud stop check).
 */
@RunWith(RobolectricTestRunner::class)
class RealCaptureServiceShedTest {

    @Test
    @Requirement("FR-RUN-3", "FR-RUN-5", "AC-46")
    fun `FR_RUN_3 a tick that raises the shed level persists a shed_event row with level before and after`() = runTest {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session())
        val signals = FakeShedSignals()
        val clock = TestClock()
        val controller = ShedController(signals, clock)
        val persister = ShedEventPersister(db.shedEventDao(), clock)
        val relay = ShedEventRelay(controller, "SESSION01", persister) { 12_345L }

        signals.backlog = 25
        controller.sample()
        relay.drain()

        val rows = db.shedEventDao().listBySession("SESSION01")
        assertEquals(1, rows.size)
        assertEquals(0, rows[0].levelBefore)
        assertEquals(1, rows[0].levelAfter)
        assertEquals(12_345L, rows[0].samplePosition)
    }

    @Test
    @Requirement("FR-RUN-3", "FR-RUN-5")
    fun `FR_RUN_3 two successive transitions each persist their own levelBefore, not both 0`() = runTest {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session())
        val signals = FakeShedSignals()
        val clock = TestClock()
        val controller = ShedController(signals, clock)
        val persister = ShedEventPersister(db.shedEventDao(), clock)
        val relay = ShedEventRelay(controller, "SESSION01", persister) { 0L }

        signals.backlog = 25
        controller.sample()
        relay.drain()

        clock.advance(70_000)
        signals.backlog = 60
        controller.sample()
        relay.drain()

        val rows = db.shedEventDao().listBySession("SESSION01")
        assertEquals(2, rows.size)
        assertEquals(0, rows[0].levelBefore)
        assertEquals(1, rows[0].levelAfter)
        assertEquals(
            "the second event's levelBefore must be the level entered by the first, not 0",
            1,
            rows[1].levelBefore,
        )
        assertEquals(2, rows[1].levelAfter)
    }

    @Test
    @Requirement("FR-STO-4", "FR-RUN-6")
    fun `FR_STO_4 free storage below the floor is reported as breached`() {
        assertTrue(storageFloorBreached(freeBytes = 10L, floorBytes = 100L))
    }

    @Test
    @Requirement("FR-STO-4")
    fun `FR_STO_4 free storage at or above the floor is not breached`() {
        assertFalse(storageFloorBreached(freeBytes = 100L, floorBytes = 100L))
        assertFalse(storageFloorBreached(freeBytes = 200L, floorBytes = 100L))
    }
}
