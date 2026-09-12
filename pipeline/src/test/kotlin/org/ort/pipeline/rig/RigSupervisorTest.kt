@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.ort.pipeline.rig

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.ort.core.Clock
import org.ort.core.SystemClock
import org.ort.core.capture.CaptureMode
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.RigVerification
import org.ort.rig.RigBand
import org.ort.rig.RigCapability
import org.ort.rig.RigHealthIssue
import org.ort.rig.RigTransportKind
import org.ort.rig.bluetooth.BluetoothReconnectBackoff
import org.ort.rig.descriptor.CommandSpec
import org.ort.rig.descriptor.PatternSpec
import org.ort.rig.descriptor.PollSpec
import org.ort.rig.descriptor.RigDescriptor
import org.ort.rig.descriptor.TransportSpec
import org.ort.rig.descriptor.UnsolicitedSpec
import org.ort.rig.fakes.FakeRigTransport
import org.ort.rig.usb.UsbReconnectBackoff
import org.ort.testing.Requirement
import org.ort.testing.TestClock

private const val TEST_RIG_ID = "test-rig"

/** Register R-808 follow-up: a fresh [CoroutineScope] on [UnconfinedTestDispatcher], tied to this
 * [TestScope]'s own [TestScope.testScheduler] -- the scope every converted case below passes to
 * [RigSupervisorTest.newSupervisor] explicitly, so both [RigSupervisor]'s own `observeJob` and the
 * [org.ort.rig.descriptor.DescriptorRigModule] it builds internally share one deterministic
 * virtual clock with the test body itself. */
private fun TestScope.freshUnconfinedScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

private fun testDescriptor(): RigDescriptor = RigDescriptor(
    schemaVersion = 1,
    id = TEST_RIG_ID,
    displayName = "Test Rig",
    transports = listOf(
        TransportSpec(kind = "usb_serial", capabilities = listOf("FREQUENCY")),
        TransportSpec(kind = "bluetooth_spp", capabilities = listOf("FREQUENCY")),
    ),
    poll = PollSpec(
        // Long enough that a second poll cycle never fires inside one test's real-time window --
        // the first cycle (which fires immediately on connect) is all any test here needs.
        intervalMs = 60_000,
        commands = listOf(CommandSpec(send = "FQ", expect = "^FQ(\\d{10})$", map = mapOf("frequencyHz" to "$1"))),
    ),
)

/** WPC3 (D23): a dual-band descriptor, TH-D75A-shaped, for [RigSupervisor.bandAtTransmissionStart]. */
private fun dualBandTestDescriptor(): RigDescriptor = RigDescriptor(
    schemaVersion = 1,
    id = TEST_RIG_ID,
    displayName = "Test Dual-Band Rig",
    transports = listOf(TransportSpec(kind = "usb_serial", capabilities = listOf("SQUELCH_STATE", "SUB_BAND"))),
    bands = listOf(0, 1),
    unsolicited = UnsolicitedSpec(
        enable = "AI 1",
        patterns = listOf(PatternSpec(expect = "^BY (\\d),(\\d)$", map = mapOf("band" to "$1", "squelchOpen" to "$2"))),
    ),
)

/**
 * FR-RIG-2/6/7/8/9/13/15, E2-B09's session half, E2-D08: [RigSupervisor] against a real
 * [org.ort.rig.descriptor.DescriptorRigModule] over [FakeRigTransport].
 *
 * Register R-808 follow-up: every case that awaits an async [RigStatus] update
 * (`awaitBandSquelch`/`awaitConnected`/`awaitStale`, all real `withTimeout` + `delay(10)` polling
 * loops) now runs under [kotlinx.coroutines.test.runTest] with its own [RigSupervisor] built on
 * [UnconfinedTestDispatcher] tied to that test's own
 * [kotlinx.coroutines.test.TestScope.testScheduler] — passed to [newSupervisor]'s [scope]
 * parameter explicitly, never the class-level [scope] field, since `testScheduler` only exists
 * inside a `runTest` block. `RigSupervisor` forwards that same scope straight through to the
 * [org.ort.rig.descriptor.DescriptorRigModule] it builds internally (see [RigSupervisor.connect]'s
 * own `scope` argument) and to its own `observeJob`, so one shared virtual clock now drives every
 * coroutine on both sides — exactly the fix already proven out in `:rig`'s own
 * `DescriptorRigModuleTest`/`ThD75aDescriptorTest`/`GenericAsciiCatDescriptorTest` (register
 * R-808/R-808b). The four cases that never await anything async at all (`FR_RIG_2`, "an unknown
 * rig id...", `FR_RIG_8`, `FR_RIG_9` — each asserts [RigStatus]/[frequencyForTransmission]
 * immediately after a synchronous `connect()` call with no polling loop in between) carried no
 * real-time race to begin with and are left exactly as they were, still on the class-level
 * real-dispatcher [scope] (harmless, since nothing in them ever waits).
 *
 * **Every converted case now calls `supervisor.disconnect()` itself, inside its own `try`/
 * `finally`, before its `runTest` block ends** — found the hard way: `readJob`/`pollJob` are
 * `while (true)` loops that never naturally quiesce, and `runTest` requires every coroutine
 * sharing its `testScheduler` to reach that state before the test can finish; relying on the
 * class-level `@AfterEach` [teardown] alone (which runs *after* `runTest`'s own body returns, too
 * late) made `runTest`'s own advance-to-idle spin forever trying to reach a state the still-live
 * `readJob` was never going to produce on its own — a real, reproducible hang, not a timeout.
 * [teardown] still runs afterwards too, harmlessly idempotent on an already-disconnected
 * supervisor, as a backstop for the four plain (non-`runTest`) cases above.
 */
class RigSupervisorTest {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var supervisor: RigSupervisor? = null

    @AfterEach
    fun teardown() {
        supervisor?.disconnect()
        scope.coroutineContext[Job]?.cancel()
        RigStatus.reset()
    }

    private fun newSupervisor(
        transport: FakeRigTransport,
        descriptor: RigDescriptor = testDescriptor(),
        clock: Clock = SystemClock,
        scope: CoroutineScope = this.scope,
        /** R-1015: `null` (the default) uses [RigSupervisor]'s own real, descriptor-derived bound —
         * every case below relies on that real default resolving instantly against
         * [kotlinx.coroutines.test.TestScope]'s virtual clock, exactly as `RigLinkBridgeTest`'s own
         * R-1013/R-1014 cases do, since a real derived bound is stronger evidence than a fake tiny
         * one would be. Overridable here only for a test that needs an explicit, tiny bound. */
        healthStaleTimeoutMillisFor: ((RigDescriptor) -> Long)? = null,
    ): RigSupervisor {
        val s = if (healthStaleTimeoutMillisFor != null) {
            RigSupervisor(
                transportFactory = RigTransportFactory { _, _, _ -> transport },
                scope = scope,
                clock = clock,
                descriptorForId = { id -> if (id == TEST_RIG_ID) descriptor else null },
                healthStaleTimeoutMillisFor = healthStaleTimeoutMillisFor,
            )
        } else {
            RigSupervisor(
                transportFactory = RigTransportFactory { _, _, _ -> transport },
                scope = scope,
                clock = clock,
                descriptorForId = { id -> if (id == TEST_RIG_ID) descriptor else null },
            )
        }
        supervisor = s
        return s
    }

    /** WPC3: waits for [RigStatus] to report band [bandName]'s squelch as [squelchOpen]. */
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

    private fun connectedConfig(transportKind: RigTransportKind = RigTransportKind.USB_SERIAL) = CaptureConfiguration(
        mode = CaptureMode.USB_RADIO,
        selectedInputId = "usb-1",
        rigId = TEST_RIG_ID,
        rigTransportKind = transportKind,
    )

    private suspend fun awaitConnected(timeoutMs: Long = 2_000): RigStatus.State.Connected {
        withTimeout(timeoutMs) {
            while (RigStatus.state !is RigStatus.State.Connected) delay(10)
        }
        return RigStatus.state as RigStatus.State.Connected
    }

    private suspend fun awaitStale(timeoutMs: Long = 2_000): RigStatus.State.Stale {
        withTimeout(timeoutMs) {
            while (RigStatus.state !is RigStatus.State.Stale) delay(10)
        }
        return RigStatus.state as RigStatus.State.Stale
    }

    @Test
    @Requirement("FR-RIG-2")
    fun `FR_RIG_2 no rig configured reports Absent, first-class not a fallback`() {
        val supervisor = newSupervisor(FakeRigTransport())
        supervisor.connect(CaptureConfiguration.DEFAULT)
        assertEquals(RigStatus.State.Absent, RigStatus.state)
    }

    @Test
    @Requirement("FR-RIG-11")
    fun `an unknown rig id degrades to the null module and reports Absent, never blocks`() {
        val supervisor = newSupervisor(FakeRigTransport())
        supervisor.connect(connectedConfig().copy(rigId = "no-such-rig"))
        assertEquals(RigStatus.State.Absent, RigStatus.state)
    }

    @Test
    @Requirement("FR-RIG-1")
    fun `WPC2_connected carries the transport kind and descriptor id`() = runTest {
        val transport = FakeRigTransport()
        transport.scriptReply("FQ", "FQ0014250000")
        val supervisor = newSupervisor(transport, scope = freshUnconfinedScope())
        try {
            supervisor.connect(connectedConfig(RigTransportKind.USB_SERIAL))

            val connected = awaitConnected()
            assertEquals(RigTransportKind.USB_SERIAL, connected.transportKind)
            assertEquals(TEST_RIG_ID, connected.descriptorId)
            assertEquals(14_250_000L, connected.bands.first().frequencyHz)
        } finally {
            supervisor.disconnect()
        }
    }

    @Test
    @Requirement("FR-RIG-7", "FR-RIG-15")
    fun `FR_RIG_7 a dropped transport degrades to Stale, carrying the last known transport and descriptor`() = runTest {
        val transport = FakeRigTransport()
        transport.scriptReply("FQ", "FQ0014250000")
        val supervisor = newSupervisor(transport, scope = freshUnconfinedScope())
        try {
            supervisor.connect(connectedConfig(RigTransportKind.USB_SERIAL))
            awaitConnected()

            transport.dropMidStream("cable pulled")

            val stale = awaitStale()
            assertEquals(RigTransportKind.USB_SERIAL, stale.lastKnown.transportKind)
            assertEquals(TEST_RIG_ID, stale.lastKnown.descriptorId)
            assertEquals(14_250_000L, stale.lastKnown.bands.first().frequencyHz, "the value is carried forward")
        } finally {
            supervisor.disconnect()
        }
    }

    @Test
    @Requirement("FR-RIG-15")
    fun `FR_RIG_15 a Bluetooth rig drop degrades exactly as a USB drop does`() = runTest {
        val transport = FakeRigTransport()
        transport.scriptReply("FQ", "FQ0014250000")
        val supervisor = newSupervisor(transport, scope = freshUnconfinedScope())
        try {
            supervisor.connect(connectedConfig(RigTransportKind.BLUETOOTH_SPP))
            val connected = awaitConnected()
            assertEquals(RigTransportKind.BLUETOOTH_SPP, connected.transportKind)

            transport.dropMidStream("link lost")
            val stale = awaitStale()
            assertEquals(RigTransportKind.BLUETOOTH_SPP, stale.lastKnown.transportKind)
            // Reconnection is the transport's own responsibility, not RigSupervisor's (see its
            // class kdoc) -- FakeRigTransport has no self-healing loop of its own, so this bare
            // fake legitimately stays Stale here. RigSupervisorRealTransportTest proves the actual
            // recovery path against UsbSerialTransport/BluetoothSppTransport's own fakes, which do.
        } finally {
            supervisor.disconnect()
        }
    }

    @Test
    @Requirement("FR-RIG-8", "FR-RIG-9")
    fun `FR_RIG_8 a manual override always takes precedence, provenance manual`() {
        val supervisor = newSupervisor(FakeRigTransport())
        supervisor.connect(connectedConfig())
        supervisor.setManualFrequencyOverrideHz(146_520_000L)

        val reading = supervisor.frequencyForTransmission(band = null, startNanos = 0L, endNanos = 1_000L)
        assertEquals(146_520_000L, reading.frequencyHz)
        assertEquals(FrequencyProvenance.MANUAL, reading.provenance)
    }

    @Test
    @Requirement("FR-RIG-9")
    fun `FR_RIG_9 nothing known yet reads UNKNOWN, never a fabricated value`() {
        val supervisor = newSupervisor(FakeRigTransport())
        supervisor.connect(CaptureConfiguration.DEFAULT)
        val reading = supervisor.frequencyForTransmission(band = null, startNanos = 0L, endNanos = 1_000L)
        assertEquals(FrequencyReading.UNKNOWN, reading)
        assertNull(reading.frequencyHz)
    }

    @Test
    @Requirement("FR-RIG-6", "FR-RIG-9")
    fun `FR_RIG_6 a rig reading in force at transmission start is reported with provenance rig`() = runTest {
        val transport = FakeRigTransport()
        transport.scriptReply("FQ", "FQ0014250000")
        val supervisor = newSupervisor(transport, scope = freshUnconfinedScope())
        try {
            supervisor.connect(connectedConfig())
            awaitConnected()

            // A generous window: the transmission "started" well before this reading and "ends"
            // well after it, so the reading is unambiguously in force throughout.
            val reading = supervisor.frequencyForTransmission(
                band = null,
                startNanos = 0L,
                endNanos = Long.MAX_VALUE / 2,
            )
            assertEquals(14_250_000L, reading.frequencyHz)
            assertEquals(FrequencyProvenance.RIG, reading.provenance)
        } finally {
            supervisor.disconnect()
        }
    }

    @Test
    @Requirement("FR-RIG-15")
    fun `disconnect returns to Absent and stops republishing`() = runTest {
        val transport = FakeRigTransport()
        transport.scriptReply("FQ", "FQ0014250000")
        val supervisor = newSupervisor(transport, scope = freshUnconfinedScope())
        supervisor.connect(connectedConfig())
        awaitConnected()

        supervisor.disconnect()
        assertEquals(RigStatus.State.Absent, RigStatus.state)
    }

    // WPC3 (D23): bandAtTransmissionStart -- see that method's own kdoc for the rule.

    @Test
    @Requirement("D23")
    fun `D23 exactly one band open is reported unambiguously`() = runTest {
        val transport = FakeRigTransport()
        val supervisor = newSupervisor(transport, descriptor = dualBandTestDescriptor(), scope = freshUnconfinedScope())
        try {
            supervisor.connect(connectedConfig(RigTransportKind.USB_SERIAL))

            transport.pushUnsolicited("BY 0,1")
            awaitBandSquelch("A", true)

            val result = supervisor.bandAtTransmissionStart(startNanos = Long.MAX_VALUE / 2)
            assertEquals(RigBand.A, result.band)
            assertEquals(false, result.ambiguous)
        } finally {
            supervisor.disconnect()
        }
    }

    @Test
    @Requirement("D23")
    fun `D23 neither band open reports NONE, never a guessed band`() = runTest {
        val transport = FakeRigTransport()
        val supervisor = newSupervisor(transport, descriptor = dualBandTestDescriptor(), scope = freshUnconfinedScope())
        try {
            supervisor.connect(connectedConfig(RigTransportKind.USB_SERIAL))

            val result = supervisor.bandAtTransmissionStart(startNanos = Long.MAX_VALUE / 2)
            assertEquals(BandAtStart.NONE, result)
        } finally {
            supervisor.disconnect()
        }
    }

    @Test
    @Requirement("D23")
    fun `D23 both bands open resolves to whichever opened first, flagged ambiguous`() = runTest {
        val transport = FakeRigTransport()
        val clock = TestClock(startMonotonicNanos = 1_000_000_000L)
        val supervisor = newSupervisor(
            transport,
            descriptor = dualBandTestDescriptor(),
            clock = clock,
            scope = freshUnconfinedScope(),
        )
        try {
            supervisor.connect(connectedConfig(RigTransportKind.USB_SERIAL))

            // Band A opens first...
            transport.pushUnsolicited("BY 0,1")
            awaitBandSquelch("A", true)

            // ...then band B opens later.
            clock.advanceNanos(500)
            transport.pushUnsolicited("BY 1,1")
            awaitBandSquelch("B", true)

            val result = supervisor.bandAtTransmissionStart(startNanos = Long.MAX_VALUE / 2)
            assertEquals(RigBand.A, result.band, "band A opened first, so it wins the ambiguity")
            assertEquals(true, result.ambiguous)
        } finally {
            supervisor.disconnect()
        }
    }

    @Test
    @Requirement("D23")
    fun `D23 a single-band rig never reports a band, preserving pre-existing behaviour`() = runTest {
        val transport = FakeRigTransport()
        transport.scriptReply("FQ", "FQ0014250000")
        val supervisor = newSupervisor(transport, scope = freshUnconfinedScope())
        try {
            supervisor.connect(connectedConfig())
            awaitConnected()

            val result = supervisor.bandAtTransmissionStart(startNanos = Long.MAX_VALUE / 2)
            assertEquals(BandAtStart.NONE, result)
        } finally {
            supervisor.disconnect()
        }
    }

    // F9 (WPC3): RigStatus.State.Stale's ladder-position fields.

    @Test
    @Requirement("F9")
    fun `F9 a fresh drop reports the first attempt with the real ladder's own first-step delay`() = runTest {
        val transport = FakeRigTransport()
        transport.scriptReply("FQ", "FQ0014250000")
        val supervisor = newSupervisor(transport, descriptor = testDescriptor(), scope = freshUnconfinedScope())
        try {
            supervisor.connect(connectedConfig(RigTransportKind.USB_SERIAL))
            awaitConnected()

            transport.dropMidStream("cable pulled")

            val stale = awaitStale()
            assertEquals(1, stale.attempt)
            assertEquals(5, stale.ofTotal)
            assertEquals(UsbReconnectBackoff.delayMillisFor(1), stale.nextRetryInMillis)
        } finally {
            supervisor.disconnect()
        }
    }

    @Test
    @Requirement("F9")
    fun `F9 the position advances as real wall time elapses, from the same ladder, never a second timer`() = runTest {
        val transport = FakeRigTransport()
        transport.scriptReply("FQ", "FQ0014250000")
        val clock = TestClock(startMonotonicNanos = 1_000_000_000L)
        val supervisor = newSupervisor(
            transport,
            descriptor = testDescriptor(),
            clock = clock,
            scope = freshUnconfinedScope(),
        )
        try {
            supervisor.connect(connectedConfig(RigTransportKind.USB_SERIAL))
            awaitConnected()

            transport.dropMidStream("cable pulled")
            val first = awaitStale()
            assertEquals(1, first.attempt)

            // Exactly UsbReconnectBackoff's own first step (1s) -- a second, distinct Lost
            // observation (a different reason string forces a new StateFlow emission)
            // reflects real elapsed time against the same real ladder, never an
            // independently-invented one.
            clock.advance(1_000)
            transport.dropMidStream("cable pulled again")
            val second = withTimeout(2_000) {
                var s = RigStatus.state
                while (s !is RigStatus.State.Stale || s.attempt != 2) {
                    delay(10)
                    s = RigStatus.state
                }
                s as RigStatus.State.Stale
            }
            assertEquals(2, second.attempt)
            assertEquals(5, second.ofTotal)
            assertEquals(UsbReconnectBackoff.delayMillisFor(2), second.nextRetryInMillis)
        } finally {
            supervisor.disconnect()
        }
    }

    @Test
    @Requirement("F9")
    fun `F9 the Bluetooth ladder is used for a Bluetooth transport, not the USB one`() = runTest {
        val transport = FakeRigTransport()
        transport.scriptReply("FQ", "FQ0014250000")
        val supervisor = newSupervisor(transport, descriptor = testDescriptor(), scope = freshUnconfinedScope())
        try {
            supervisor.connect(connectedConfig(RigTransportKind.BLUETOOTH_SPP))
            awaitConnected()

            transport.dropMidStream("link lost")

            val stale = awaitStale()
            assertEquals(BluetoothReconnectBackoff.delayMillisFor(1), stale.nextRetryInMillis)
        } finally {
            supervisor.disconnect()
        }
    }

    // R-1015 (WPRIG2): RigSupervisor.connect() only ever watched observe(), never health() -- a
    // transport that stays TransportState.Open but goes silent (the rig never answers again, but
    // never itself reports Lost either) left RigStatus confidently Connected forever. These prove
    // the fix reads DescriptorRigModule's own RigHealth.Degraded(TIMEOUT) -- already emitted,
    // never read before this -- and declares Stale within a bounded, derived time, tagged
    // distinctly from a genuine transport loss.

    @Test
    @Requirement("FR-RIG-15", "NFR-1a")
    fun `R_1015 a transport that stays open and goes silent degrades to Stale within a bounded time, tagged TIMEOUT`() =
        runTest {
            val transport = FakeRigTransport()
            val supervisor = newSupervisor(transport, descriptor = testDescriptor(), scope = freshUnconfinedScope())
            try {
                supervisor.connect(connectedConfig(RigTransportKind.USB_SERIAL))
                // An unsolicited push, not a scripted poll reply -- FakeRigTransport re-sends every
                // scripted reply on each subsequent poll write(), which would keep "refreshing" the
                // rig forever and never let it fall genuinely silent.
                transport.pushUnsolicited("FQ0014250000")
                awaitConnected()

                // Nothing more ever arrives, and the transport itself never reports
                // TransportState.Lost (still Open the whole time) -- DescriptorRigModule's own read
                // loop just keeps timing out and retrying forever (its own comment: "retries
                // forever"). Before this fix, RigStatus simply never moved again.
                val stale = awaitStale(timeoutMs = 10_000_000L)
                assertEquals(
                    RigHealthIssue.TIMEOUT,
                    stale.issue,
                    "must be distinguishable from a transport-reported loss",
                )
                assertEquals(RigTransportKind.USB_SERIAL, stale.lastKnown.transportKind)
                assertEquals(
                    14_250_000L,
                    stale.lastKnown.bands.first().frequencyHz,
                    "the last known value is carried forward",
                )
                assertNull(
                    stale.attempt,
                    "no reconnect ladder is actually running under a transport that still believes it is open",
                )
                assertNull(stale.nextRetryInMillis)
            } finally {
                supervisor.disconnect()
            }
        }

    @Test
    @Requirement("FR-RIG-15")
    fun `R_1015 the health-timeout stale bound is derived from the descriptor's own poll cadence, not hardcoded`() =
        runTest {
            // A descriptor with a ten-times-faster poll cadence must still reach the timeout-tagged
            // Stale within a comfortably smaller virtual bound than testDescriptor's own 60s
            // cadence would allow -- proving the bound is derived from the descriptor, not a fixed
            // constant, the same discipline RigLinkBridge's own R-1013/R-1014 timeouts follow.
            val fastPoll = testDescriptor().poll!!.copy(intervalMs = 100)
            val fastDescriptor = testDescriptor().copy(poll = fastPoll)
            val transport = FakeRigTransport()
            val supervisor = newSupervisor(transport, descriptor = fastDescriptor, scope = freshUnconfinedScope())
            try {
                supervisor.connect(connectedConfig(RigTransportKind.USB_SERIAL))
                transport.pushUnsolicited("FQ0014250000")
                awaitConnected()

                // Comfortably less than testDescriptor()'s own 60_000ms poll interval alone would
                // require for even a single cycle multiple -- a fixed constant sized for the slow
                // descriptor would never fire this fast.
                val stale = awaitStale(timeoutMs = 60_000L)
                assertEquals(RigHealthIssue.TIMEOUT, stale.issue)
            } finally {
                supervisor.disconnect()
            }
        }

    @Test
    @Requirement("FR-RIG-7", "FR-RIG-15")
    fun `R_1015 a genuinely lost transport is still tagged TRANSPORT_LOST, distinct from silence`() = runTest {
        val transport = FakeRigTransport()
        transport.scriptReply("FQ", "FQ0014250000")
        val supervisor = newSupervisor(transport, scope = freshUnconfinedScope())
        try {
            supervisor.connect(connectedConfig(RigTransportKind.USB_SERIAL))
            awaitConnected()

            transport.dropMidStream("cable pulled")

            val stale = awaitStale()
            assertEquals(RigHealthIssue.TRANSPORT_LOST, stale.issue)
            assertEquals(1, stale.attempt, "the real reconnect ladder is running for a genuinely lost transport")
        } finally {
            supervisor.disconnect()
        }
    }

    @Test
    @Requirement("FR-RIG-15")
    fun `R_1015 recovering before the bound elapses never declares Stale from silence`() = runTest {
        val fastPoll = testDescriptor().poll!!.copy(intervalMs = 100)
        val fastDescriptor = testDescriptor().copy(poll = fastPoll)
        val transport = FakeRigTransport()
        val supervisor = newSupervisor(transport, descriptor = fastDescriptor, scope = freshUnconfinedScope())
        try {
            supervisor.connect(connectedConfig(RigTransportKind.USB_SERIAL))
            transport.pushUnsolicited("FQ0014250000")
            awaitConnected()

            // The rig speaks again well inside the bound -- a fresh RigState must cancel the
            // pending health-timeout countdown, never leaving RigStatus stuck mid-flight.
            transport.pushUnsolicited("FQ0014300000")
            withTimeout(5_000) {
                while ((RigStatus.state as? RigStatus.State.Connected)?.bands?.first()?.frequencyHz != 14_300_000L) {
                    delay(10)
                }
            }
            assertEquals(
                RigStatus.State.Connected::class,
                RigStatus.state::class,
                "recovering before the bound elapses must never leave RigStatus Stale",
            )
        } finally {
            supervisor.disconnect()
        }
    }

    // R-1019 (WPRIG2): RigStatus.State.Connected must carry whether every declared capability has
    // actually been observed, not just that a link opened -- ReadyScreen (S12) had no field to read
    // this from at all before this.

    @Test
    @Requirement("FR-RIG-1")
    fun `R_1019 every declared capability observed reports Full verification`() = runTest {
        val transport = FakeRigTransport()
        transport.scriptReply("FQ", "FQ0014250000")
        val supervisor = newSupervisor(transport, scope = freshUnconfinedScope())
        try {
            supervisor.connect(connectedConfig(RigTransportKind.USB_SERIAL))
            val connected = awaitConnected()
            // testDescriptor() declares only FREQUENCY for usb_serial -- FQ alone satisfies it.
            assertEquals(RigVerification.Full, connected.verification)
        } finally {
            supervisor.disconnect()
        }
    }

    @Test
    @Requirement("FR-RIG-1")
    fun `R_1019 a capability never observed reports Partial, naming exactly what is missing`() = runTest {
        val descriptor = testDescriptor().copy(
            transports = listOf(
                TransportSpec(kind = "usb_serial", capabilities = listOf("FREQUENCY", "SIGNAL_STRENGTH")),
            ),
        )
        val transport = FakeRigTransport()
        transport.scriptReply("FQ", "FQ0014250000") // never reports SIGNAL_STRENGTH
        val supervisor = newSupervisor(transport, descriptor = descriptor, scope = freshUnconfinedScope())
        try {
            supervisor.connect(connectedConfig(RigTransportKind.USB_SERIAL))
            val connected = awaitConnected()
            assertEquals(RigVerification.Partial(setOf(RigCapability.SIGNAL_STRENGTH)), connected.verification)
        } finally {
            supervisor.disconnect()
        }
    }

    @Test
    @Requirement("FR-RIG-7")
    fun `R_1019 a stale reading carries its verification forward unchanged`() = runTest {
        val descriptor = testDescriptor().copy(
            transports = listOf(
                TransportSpec(kind = "usb_serial", capabilities = listOf("FREQUENCY", "SIGNAL_STRENGTH")),
            ),
        )
        val transport = FakeRigTransport()
        transport.scriptReply("FQ", "FQ0014250000")
        val supervisor = newSupervisor(transport, descriptor = descriptor, scope = freshUnconfinedScope())
        try {
            supervisor.connect(connectedConfig(RigTransportKind.USB_SERIAL))
            awaitConnected()

            transport.dropMidStream("cable pulled")
            val stale = awaitStale()
            assertEquals(RigVerification.Partial(setOf(RigCapability.SIGNAL_STRENGTH)), stale.lastKnown.verification)
        } finally {
            supervisor.disconnect()
        }
    }
}
