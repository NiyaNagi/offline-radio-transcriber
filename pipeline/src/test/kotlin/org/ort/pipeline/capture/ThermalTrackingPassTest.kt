package org.ort.pipeline.capture

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.PassId
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.PassRunOutcome
import org.ort.data.entity.WorkQueueItemEntity
import org.ort.data.entity.WorkQueueState
import org.ort.pipeline.Pass
import org.ort.pipeline.PipelineTestFixtures
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import org.robolectric.RobolectricTestRunner

/**
 * F7 (register R-104): [ThermalTrackingPass] is the seam that measures Pass B's real real-time
 * factor — see its own kdoc for why it lives here rather than inside `CaptureProcessingLoop` or
 * `PassDrainRunner`. Tested directly against a fake [Pass], the same "extracted seam" approach
 * [RealCaptureServiceGapTest]/[RealCaptureServiceShedTest] already use for pieces
 * [RealCaptureService] wires together (F-011's `ServiceController` harness exists now, but a real
 * capture+decode round trip is not needed to prove this decorator's own timing arithmetic).
 */
@RunWith(RobolectricTestRunner::class)
class ThermalTrackingPassTest {

    // Both @Before and @After, deliberately: ThermalStatus is a process-wide singleton shared by
    // every test class in this Gradle test worker's JVM (ThermalStatusTest/ScenariosTest included)
    // -- @Before alone would still start clean, but @After also leaves the shared holder honest for
    // whatever test happens to run next in the same JVM, not just this class's own tests.
    @Before
    @After
    fun resetHolder() {
        ThermalStatus.reset()
    }

    private fun item(transmissionId: String) = WorkQueueItemEntity(
        transmissionId = transmissionId,
        pass = PassId.B_OFFLINE,
        state = WorkQueueState.LEASED,
        priority = 0,
        enqueuedAt = 0L,
    )

    @Test
    @Requirement("F-007", "R-104")
    fun `F7_a_real_pass_run_records_wall_time_over_the_transmissions_real_duration`() = runTest {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session())
        val tx = PipelineTestFixtures.transmission(id = "TX01").copy(durationMs = 1_000L)
        db.transmissionDao().insert(tx)

        val clock = TestClock()
        // The delegate's own run() advances the clock by exactly 300ms of "wall time" before
        // returning -- TestClock lets this be exact rather than a flaky real Thread.sleep.
        val delegate = Pass {
            clock.advance(300)
            PassRunOutcome.Finished(TransmissionState.COMPLETE)
        }
        val tracked = ThermalTrackingPass(delegate, db, clock)

        assertNull("nothing measured yet", (ThermalStatus.state as ThermalStatus.State.Nominal).realTimeFactor)

        tracked.run(item("TX01"))

        ThermalStatus.sample(ThermalStatus.THERMAL_STATUS_NONE)
        val rtf = ThermalStatus.state.realTimeFactor
        assertNotNull(rtf)
        // 300ms wall / 1000ms audio == 0.3.
        assertEquals(0.3, rtf!!, 1e-9)
    }

    @Test
    @Requirement("F-007", "R-104")
    fun `a transmission with no findable duration contributes no sample, rather than a fabricated one`() = runTest {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session())
        // No transmission row inserted at all -- getById(...) returns null.
        val clock = TestClock()
        val delegate = Pass {
            clock.advance(50)
            PassRunOutcome.Finished(TransmissionState.COMPLETE)
        }
        val tracked = ThermalTrackingPass(delegate, db, clock)

        tracked.run(item("DOES-NOT-EXIST"))

        ThermalStatus.sample(ThermalStatus.THERMAL_STATUS_NONE)
        assertNull((ThermalStatus.state as ThermalStatus.State.Nominal).realTimeFactor)
    }

    @Test
    @Requirement("F-007", "R-104")
    fun `the delegate's outcome passes through unchanged`() = runTest {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session())
        db.transmissionDao().insert(PipelineTestFixtures.transmission(id = "TX02").copy(durationMs = 500L))
        val clock = TestClock()
        val delegate = Pass { PassRunOutcome.Errored("boom") }
        val tracked = ThermalTrackingPass(delegate, db, clock)

        val outcome = tracked.run(item("TX02"))

        assertEquals(PassRunOutcome.Errored("boom"), outcome)
    }
}
