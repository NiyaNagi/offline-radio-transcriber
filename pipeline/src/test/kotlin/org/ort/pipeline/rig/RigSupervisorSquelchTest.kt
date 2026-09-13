@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.ort.pipeline.rig

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.capture.CaptureMode
import org.ort.pipeline.capture.RigStatus
import org.ort.rig.RigTransportKind
import org.ort.rig.descriptor.CommandSpec
import org.ort.rig.descriptor.PatternSpec
import org.ort.rig.descriptor.PollSpec
import org.ort.rig.descriptor.RigDescriptor
import org.ort.rig.descriptor.TransportSpec
import org.ort.rig.descriptor.UnsolicitedSpec
import org.ort.rig.fakes.FakeRigTransport
import org.ort.testing.Requirement

private const val TEST_RIG_ID = "test-squelch-rig"

/** Mirrors RigSupervisorTest's own `awaitBandSquelch` -- the proven way to know a pushed line has
 * actually been processed under a shared testScheduler, since [RigSupervisor.onRigState] updates
 * [RigStatus] and emits a squelch-union transition in the very same synchronous call: once
 * [RigStatus] reflects the expected band state, any transition that call was going to emit has
 * already been emitted. */
private suspend fun awaitBandSquelch(bandName: String, squelchOpen: Boolean, timeoutMs: Long = 2_000) {
    withTimeout(timeoutMs) {
        while (true) {
            val state = RigStatus.state
            if (state is RigStatus.State.Connected &&
                state.bands.any { it.band == bandName && it.squelchOpen == squelchOpen }
            ) {
                return@withTimeout
            }
            delay(10)
        }
    }
}

/**
 * WPSQUELCH (FR-SEG-5, D23, FR-RUN-17): [RigSupervisor.observeSquelchUnion] and
 * [RigSupervisor.squelchFusionEligible] — the squelch-state exposure half of R-1062. These are
 * the discriminating tests for that exposure: reverting [RigSupervisor]'s
 * `emitSquelchUnionIfChanged`/`squelchFusionEligible` makes every "eligible" case here fail (no
 * event ever emitted, or eligibility wrongly reported).
 */
class RigSupervisorSquelchTest {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var supervisor: RigSupervisor? = null

    @AfterEach
    fun teardown() {
        supervisor?.disconnect()
        scope.coroutineContext[Job]?.cancel()
        RigStatus.reset()
    }

    private fun freshUnconfinedScope(scheduler: TestCoroutineScheduler): CoroutineScope =
        CoroutineScope(Job() + UnconfinedTestDispatcher(scheduler))

    private fun connectedConfig() = CaptureConfiguration(
        mode = CaptureMode.USB_RADIO,
        selectedInputId = "usb-1",
        rigId = TEST_RIG_ID,
        rigTransportKind = RigTransportKind.USB_SERIAL,
    )

    /** Push-capable, dual-band, SQUELCH_STATE-declared -- FR-SEG-5's eligible case. */
    private fun pushSquelchDescriptor(): RigDescriptor = RigDescriptor(
        schemaVersion = 1,
        id = TEST_RIG_ID,
        displayName = "Push Squelch Rig",
        transports = listOf(TransportSpec(kind = "usb_serial", capabilities = listOf("SQUELCH_STATE", "SUB_BAND"))),
        bands = listOf(0, 1),
        unsolicited = UnsolicitedSpec(
            enable = "AI 1",
            patterns = listOf(
                PatternSpec(expect = "^BY (\\d),(\\d)$", map = mapOf("band" to "$1", "squelchOpen" to "$2")),
            ),
        ),
    )

    /** No `unsolicited` section, and a poll cadence far slower than FR-RUN-17's 250ms bound. */
    private fun slowPollSquelchDescriptor(): RigDescriptor = RigDescriptor(
        schemaVersion = 1,
        id = TEST_RIG_ID,
        displayName = "Slow Poll Squelch Rig",
        transports = listOf(TransportSpec(kind = "usb_serial", capabilities = listOf("SQUELCH_STATE"))),
        poll = PollSpec(
            intervalMs = 2000,
            commands = listOf(CommandSpec(send = "BY", expect = "^BY(\\d)$", map = mapOf("squelchOpen" to "$1"))),
        ),
    )

    /** Declares no SQUELCH_STATE capability at all, even though it happens to be push-capable. */
    private fun noSquelchCapabilityDescriptor(): RigDescriptor = RigDescriptor(
        schemaVersion = 1,
        id = TEST_RIG_ID,
        displayName = "No Squelch Rig",
        transports = listOf(TransportSpec(kind = "usb_serial", capabilities = listOf("FREQUENCY"))),
        unsolicited = UnsolicitedSpec(
            enable = "AI 1",
            patterns = listOf(PatternSpec(expect = "^FQ(\\d{10})$", map = mapOf("frequencyHz" to "$1"))),
        ),
    )

    private fun newSupervisor(
        transport: FakeRigTransport,
        descriptor: RigDescriptor,
        testScope: CoroutineScope,
    ): RigSupervisor {
        val s = RigSupervisor(
            transportFactory = RigTransportFactory { _, _, _ -> transport },
            scope = testScope,
            descriptorForId = { id -> if (id == TEST_RIG_ID) descriptor else null },
        )
        supervisor = s
        return s
    }

    @Test
    @Requirement("FR-SEG-5")
    fun `FR_SEG_5 an eligible push descriptor is reported eligible`() = runTest {
        val transport = FakeRigTransport()
        val sup = newSupervisor(transport, pushSquelchDescriptor(), freshUnconfinedScope(testScheduler))
        try {
            sup.connect(connectedConfig())
            assertTrue(sup.squelchFusionEligible())
        } finally {
            sup.disconnect()
        }
    }

    @Test
    @Requirement("FR-SEG-5", "FR-RUN-17")
    fun `FR_SEG_5 a poll-only descriptor slower than the 250ms bound is not eligible, emits nothing`() = runTest {
        val transport = FakeRigTransport()
        val sup = newSupervisor(transport, slowPollSquelchDescriptor(), freshUnconfinedScope(testScheduler))
        try {
            sup.connect(connectedConfig())
            assertFalse(sup.squelchFusionEligible())

            val emitted = mutableListOf<RigSquelchTransition>()
            val job = launch { sup.observeSquelchUnion().toList(emitted) }
            transport.pushUnsolicited("BY1") // even a genuine squelch-open line changes nothing
            // No RigStatus band-state signal exists for an unbanded rig -- a bounded real-time
            // wait is the honest substitute here, long enough for the poll-only descriptor's own
            // read loop to have processed the line several times over.
            withTimeout(2_000) { delay(50) }
            job.cancel()
            assertTrue(emitted.isEmpty(), "an ineligible connection must never emit a squelch transition")
        } finally {
            sup.disconnect()
        }
    }

    @Test
    @Requirement("FR-SEG-5")
    fun `FR_SEG_5 no SQUELCH_STATE capability declared is never eligible`() = runTest {
        val transport = FakeRigTransport()
        val sup = newSupervisor(transport, noSquelchCapabilityDescriptor(), freshUnconfinedScope(testScheduler))
        try {
            sup.connect(connectedConfig())
            transport.pushUnsolicited("FQ0014250000")
            assertFalse(sup.squelchFusionEligible())
        } finally {
            sup.disconnect()
        }
    }

    @Test
    @Requirement("FR-SEG-5", "D23")
    fun `D23 the union opens on the first band and does not re-emit while the second also opens`() = runTest {
        val transport = FakeRigTransport()
        val testScope = freshUnconfinedScope(testScheduler)
        val sup = newSupervisor(transport, pushSquelchDescriptor(), testScope)
        try {
            sup.connect(connectedConfig())
            val events = mutableListOf<RigSquelchTransition>()
            val job = testScope.launch { sup.observeSquelchUnion().collect { events.add(it) } }

            transport.pushUnsolicited("BY 0,1") // band A opens -- union: closed -> open
            awaitBandSquelch("A", true)
            transport.pushUnsolicited("BY 1,1") // band B also opens -- union unchanged (still open)
            awaitBandSquelch("B", true)

            job.cancel()
            assertEquals(1, events.size, "the union must not re-emit while it is already open")
            assertTrue(events.single().open)
        } finally {
            sup.disconnect()
        }
    }

    @Test
    @Requirement("FR-SEG-5", "D23")
    fun `D23 the union only closes once every band has closed`() = runTest {
        val transport = FakeRigTransport()
        val testScope = freshUnconfinedScope(testScheduler)
        val sup = newSupervisor(transport, pushSquelchDescriptor(), testScope)
        try {
            sup.connect(connectedConfig())
            val events = mutableListOf<RigSquelchTransition>()
            val job = testScope.launch { sup.observeSquelchUnion().collect { events.add(it) } }

            transport.pushUnsolicited("BY 0,1") // A opens
            awaitBandSquelch("A", true)
            transport.pushUnsolicited("BY 1,1") // B opens too
            awaitBandSquelch("B", true)
            transport.pushUnsolicited("BY 0,0") // A closes -- B still open, union stays open
            awaitBandSquelch("A", false)
            transport.pushUnsolicited("BY 1,0") // B closes too -- NOW the union closes
            awaitBandSquelch("B", false)

            job.cancel()
            assertEquals(listOf(true, false), events.map { it.open })
        } finally {
            sup.disconnect()
        }
    }

    @Test
    @Requirement("FR-SEG-5")
    fun `FR_SEG_5 disconnecting resets eligibility and the union to unknown`() = runTest {
        val transport = FakeRigTransport()
        val sup = newSupervisor(transport, pushSquelchDescriptor(), freshUnconfinedScope(testScheduler))
        sup.connect(connectedConfig())
        assertTrue(sup.squelchFusionEligible())

        sup.disconnect()
        assertFalse(sup.squelchFusionEligible(), "no connection at all must never be eligible")
    }
}
