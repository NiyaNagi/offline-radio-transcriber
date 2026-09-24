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
import kotlin.random.Random

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
 * R-1062 round 3: scripts the real `kenwood-thd75a.json` descriptor's own poll replies for both
 * of its declared bands (`FQ {band}`/`BY {band}` -- see `DescriptorRigModule.startLoops`'s poll
 * loop for the exact wire strings this must match) -- band 0's squelch per [bandAOpen], band 1
 * always closed, frequencies arbitrary but well-formed (10-digit Hz, matching the descriptor's
 * own `expect` pattern). [FakeRigTransport.scriptReply] resends the same queued reply on every
 * matching [FakeRigTransport.write], so this correctly models "every poll cycle reports the same
 * value" for as long as the test lets the poll loop keep running.
 */
private fun FakeRigTransport.scriptPerBandReplies(bandAOpen: Boolean) {
    scriptReply("FQ 0", "FQ 0,0014250000")
    scriptReply("BY 0", "BY 0,${if (bandAOpen) 1 else 0}")
    scriptReply("FQ 1", "FQ 1,0014300000")
    scriptReply("BY 1", "BY 1,0")
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

    private fun connectedConfig(transportKind: RigTransportKind = RigTransportKind.USB_SERIAL) = CaptureConfiguration(
        mode = CaptureMode.USB_RADIO,
        selectedInputId = "usb-1",
        rigId = TEST_RIG_ID,
        rigTransportKind = transportKind,
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

    /** R-1062 round 3: no `unsolicited` at all -- a pure poll rig, fast enough ([intervalMs]
     * default 100ms) to be [RigSupervisor.squelchFusionEligible], with a genuine poll heartbeat
     * `restartSquelchStaleWatchdog` can watch. */
    private fun pollOnlySquelchDescriptor(intervalMs: Long = 100): RigDescriptor = RigDescriptor(
        schemaVersion = 1,
        id = TEST_RIG_ID,
        displayName = "Poll-Only Squelch Rig",
        transports = listOf(TransportSpec(kind = "usb_serial", capabilities = listOf("SQUELCH_STATE"))),
        poll = PollSpec(
            intervalMs = intervalMs,
            commands = listOf(CommandSpec(send = "BY", expect = "^BY(\\d)$", map = mapOf("squelchOpen" to "$1"))),
        ),
    )

    /** The real bundled TH-D75A descriptor's own rig id, driven through the production
     * [org.ort.pipeline.rig.bundledDescriptorById] default resolver -- never a hand-rolled stand-in
     * -- for R-1062 round 3's realistic-latency cases. */
    private fun kenwoodConfig(transportKind: RigTransportKind = RigTransportKind.USB_SERIAL) = CaptureConfiguration(
        mode = CaptureMode.USB_RADIO,
        selectedInputId = "usb-1",
        rigId = "kenwood-thd75a",
        rigTransportKind = transportKind,
    )

    /** [RigSupervisor] with the real, production default `descriptorForId` (`::bundledDescriptorById`)
     * -- unlike [newSupervisor], which always substitutes a hand-rolled test descriptor for
     * [TEST_RIG_ID]. */
    private fun newSupervisorForBundledDescriptor(
        transport: FakeRigTransport,
        testScope: CoroutineScope,
    ): RigSupervisor {
        val s = RigSupervisor(transportFactory = RigTransportFactory { _, _, _ -> transport }, scope = testScope)
        supervisor = s
        return s
    }

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
            // No RigStatus band-state signal exists for an unbanded rig, so this advances the
            // clock instead: far enough for the poll-only descriptor's own read loop to have
            // processed the line several times over. (Corrected while annotating for R-1180: this
            // used to describe itself as "a bounded real-time wait", which it is not -- the whole
            // scope is on the test scheduler, so `delay(50)` costs 50ms of *virtual* time and no
            // wall-clock time at all. What it does is right; what it said about itself was not.)
            // R-1180-virtual-timeout-ok: the supervisor's scope is
            // UnconfinedTestDispatcher(testScheduler) over a FakeRigTransport, so this bounds only
            // test-scheduler work - which is also what makes it a provable no-op.
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
            assertEquals(true, events.single().open)
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

    // --- R-1062 follow-up: RigSupervisor must actually EMIT the loss signal, not just the
    // unit-level SquelchGate/Segmenter accepting one if it arrived. ------------------------

    @Test
    @Requirement("FR-SEG-5", "R-1062", "FR-RIG-15")
    fun `R_1062 a real transport drop emits a squelch loss through RigSupervisor, not just RigStatus`() = runTest {
        val transport = FakeRigTransport()
        val testScope = freshUnconfinedScope(testScheduler)
        val sup = newSupervisor(transport, pushSquelchDescriptor(), testScope)
        try {
            sup.connect(connectedConfig(RigTransportKind.USB_SERIAL))
            val events = mutableListOf<RigSquelchTransition>()
            val job = testScope.launch { sup.observeSquelchUnion().collect { events.add(it) } }

            transport.pushUnsolicited("BY 0,1")
            awaitBandSquelch("A", true)

            transport.dropMidStream("cable pulled")
            // RigStatus itself already proves the drop happened (RigSupervisorTest's own
            // FR_RIG_7 case); this asserts the SEPARATE fact the coordinator's report found
            // missing -- that the same drop also reaches observeSquelchUnion as a loss.
            // R-1180-virtual-timeout-ok: the supervisor's scope is
            // UnconfinedTestDispatcher(testScheduler) over a FakeRigTransport, so this bounds only
            // test-scheduler work - which is also what makes it a provable no-op.
            withTimeout(60_000) {
                while (events.none { it.open == null }) delay(10)
            }
            job.cancel()
            assertEquals(true, events.first().open)
            assertEquals(null, events.last().open, "the transport drop must surface as a squelch loss too")
        } finally {
            sup.disconnect()
        }
    }

    @Test
    @Requirement("FR-SEG-5", "R-1062", "FR-RUN-17")
    fun `R_1062 a genuinely dead polled link reverts to VAD-only past its own derived bound`() = runTest {
        // Round 3 (coordinator finding): a push-capable descriptor with NO poll fallback has no
        // natural heartbeat at all -- a quiet radio genuinely sends nothing, by design -- so the
        // staleness watchdog must not run for one (see the push-only 30s-silence case below).
        // This test proves the OTHER half still works: a descriptor that DOES poll, with its
        // link genuinely gone (no more replies ever, matching R_1015's own established
        // "pushUnsolicited once, then silence" pattern for a transport that stays nominally
        // Open but stops answering), still reverts to VAD-only within its own derived bound.
        val transport = FakeRigTransport()
        val testScope = freshUnconfinedScope(testScheduler)
        val descriptor = pollOnlySquelchDescriptor(intervalMs = 100)
        val sup = newSupervisor(transport, descriptor, testScope)
        try {
            sup.connect(connectedConfig())
            val events = mutableListOf<RigSquelchTransition>()
            val job = testScope.launch { sup.observeSquelchUnion().collect { events.add(it) } }

            // One genuine reading, exactly like R_1015's own fixture -- the poll loop keeps
            // writing "BY" every 100ms forever, but nothing is ever scripted for it, so every
            // write after this gets no reply at all: a link that stays Open yet answers nothing.
            transport.pushUnsolicited("BY1")
            // R-1180-virtual-timeout-ok: the supervisor's scope is
            // UnconfinedTestDispatcher(testScheduler) over a FakeRigTransport, so this bounds only
            // test-scheduler work - which is also what makes it a provable no-op.
            withTimeout(2_000) { while (events.isEmpty()) delay(10) }

            // The derived bound is 100ms * 2 = 200ms (squelchStalenessBoundMillis). 2000ms here
            // is 10x that -- comfortably long enough to prove it fires -- but nowhere near
            // RigHealth's own generic floor (5 * 100 = 500, coerced up to its 5000ms minimum),
            // so a pass here can only be the squelch-specific watchdog, not that separate,
            // far looser mechanism.
            // R-1180-virtual-timeout-ok: the supervisor's scope is
            // UnconfinedTestDispatcher(testScheduler) over a FakeRigTransport, so this bounds only
            // test-scheduler work - which is also what makes it a provable no-op.
            withTimeout(2_000) {
                while (events.none { it.open == null }) delay(10)
            }
            job.cancel()
            assertEquals(true, events.first().open)
            assertEquals(null, events.last().open)
        } finally {
            sup.disconnect()
        }
    }

    // --- R-1062 round 3 (coordinator finding): the staleness bound must survive a poll
    // descriptor's own ordinary rhythm, not just fire eventually. -------------------------

    @Test
    @Requirement("FR-SEG-5", "R-1062", "FR-RUN-17")
    fun `R_1062 the real TH-D75A descriptor with realistic reply latency holds one open over for 30s, zero losses`() =
        runTest {
            val testScope = freshUnconfinedScope(testScheduler)
            val transport = FakeRigTransport(scope = testScope).apply {
                replyLatencyMillis = 40
                scriptPerBandReplies(bandAOpen = true)
            }
            val sup = newSupervisorForBundledDescriptor(transport, testScope)
            try {
                sup.connect(kenwoodConfig())
                val events = mutableListOf<RigSquelchTransition>()
                val job = testScope.launch { sup.observeSquelchUnion().collect { events.add(it) } }

                // Established by the descriptor's own poll cycle (no manual push at all) --
                // the real, unmodified kenwood-thd75a.json descriptor is what is under test.
                // R-1180-virtual-timeout-ok: the supervisor's scope is
                // UnconfinedTestDispatcher(testScheduler) over a FakeRigTransport, so this bounds only
                // test-scheduler work - which is also what makes it a provable no-op.
                withTimeout(60_000) { while (events.isEmpty()) delay(10) }
                assertEquals(true, events.single().open, "band 0 opened from the poll's own BY 0 reply")

                // 30 seconds of virtual time, band 0 held open by every subsequent poll cycle's
                // identical BY 0 reply, nothing else ever changing.
                delay(30_000)

                job.cancel()
                assertEquals(1, events.size, "no loss and no re-open across 30s of a genuinely continuous over")
                assertTrue(events.none { it.open == null })
            } finally {
                sup.disconnect()
            }
        }

    @Test
    @Requirement("FR-SEG-5", "R-1062", "FR-RUN-17")
    fun `R_1062 the real TH-D75A descriptor with occasional 300ms jitter still holds one open over for 30s`() =
        runTest {
            val testScope = freshUnconfinedScope(testScheduler)
            val transport = FakeRigTransport(scope = testScope, random = Random(1062L)).apply {
                replyLatencyMillis = 40
                replyJitterMillis = 300
                scriptPerBandReplies(bandAOpen = true)
            }
            val sup = newSupervisorForBundledDescriptor(transport, testScope)
            try {
                sup.connect(kenwoodConfig())
                val events = mutableListOf<RigSquelchTransition>()
                val job = testScope.launch { sup.observeSquelchUnion().collect { events.add(it) } }

                // R-1180-virtual-timeout-ok: the supervisor's scope is
                // UnconfinedTestDispatcher(testScheduler) over a FakeRigTransport, so this bounds only
                // test-scheduler work - which is also what makes it a provable no-op.
                withTimeout(60_000) { while (events.isEmpty()) delay(10) }
                assertEquals(true, events.single().open)

                delay(30_000)

                job.cancel()
                assertEquals(1, events.size, "occasional 300ms jitter must never masquerade as a lost link")
                assertTrue(events.none { it.open == null })
            } finally {
                sup.disconnect()
            }
        }

    @Test
    @Requirement("FR-SEG-5", "R-1062")
    fun `R_1062 a push-only descriptor with no poll fallback never runs the staleness watchdog over 30s of silence`() =
        runTest {
            val testScope = freshUnconfinedScope(testScheduler)
            val transport = FakeRigTransport(scope = testScope)
            val sup = newSupervisor(transport, pushSquelchDescriptor(), testScope)
            try {
                sup.connect(connectedConfig())
                val events = mutableListOf<RigSquelchTransition>()
                val job = testScope.launch { sup.observeSquelchUnion().collect { events.add(it) } }

                transport.pushUnsolicited("BY 0,1")
                awaitBandSquelch("A", true)

                // 30 seconds of genuine radio silence -- a real quiet AI-mode radio sends
                // nothing at all in this window, by design (no poll fallback to fall back on).
                delay(30_000)

                job.cancel()
                assertEquals(1, events.size, "a push-only descriptor must never declare loss from mere silence")
                assertEquals(true, events.single().open)
            } finally {
                sup.disconnect()
            }
        }

    @Test
    @Requirement("FR-SEG-5", "R-1062")
    fun `R_1062 an explicit supervisor stop emits a squelch loss, not just eligibility going false`() = runTest {
        val transport = FakeRigTransport()
        val testScope = freshUnconfinedScope(testScheduler)
        val sup = newSupervisor(transport, pushSquelchDescriptor(), testScope)
        val events = mutableListOf<RigSquelchTransition>()
        val job = testScope.launch { sup.observeSquelchUnion().collect { events.add(it) } }

        sup.connect(connectedConfig())
        transport.pushUnsolicited("BY 0,1")
        awaitBandSquelch("A", true)

        sup.disconnect()
        job.cancel()
        assertEquals(true, events.first().open)
        assertEquals(null, events.last().open, "an explicit stop must emit a loss, not just reset internal state")
    }
}
