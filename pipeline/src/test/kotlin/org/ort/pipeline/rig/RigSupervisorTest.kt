package org.ort.pipeline.rig

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.ort.core.Clock
import org.ort.core.SystemClock
import org.ort.core.capture.CaptureMode
import org.ort.pipeline.capture.RigStatus
import org.ort.rig.RigBand
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
    ): RigSupervisor {
        val s = RigSupervisor(
            transportFactory = RigTransportFactory { _, _, _ -> transport },
            scope = scope,
            clock = clock,
            descriptorForId = { id -> if (id == TEST_RIG_ID) descriptor else null },
        )
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
    fun `WPC2_connected carries the transport kind and descriptor id`() = runBlocking {
        val transport = FakeRigTransport()
        transport.scriptReply("FQ", "FQ0014250000")
        val supervisor = newSupervisor(transport)
        supervisor.connect(connectedConfig(RigTransportKind.USB_SERIAL))

        val connected = awaitConnected()
        assertEquals(RigTransportKind.USB_SERIAL, connected.transportKind)
        assertEquals(TEST_RIG_ID, connected.descriptorId)
        assertEquals(14_250_000L, connected.bands.first().frequencyHz)
    }

    @Test
    @Requirement("FR-RIG-7", "FR-RIG-15")
    fun `FR_RIG_7 a dropped transport degrades to Stale, carrying the last known transport and descriptor`() =
        runBlocking {
            val transport = FakeRigTransport()
            transport.scriptReply("FQ", "FQ0014250000")
            val supervisor = newSupervisor(transport)
            supervisor.connect(connectedConfig(RigTransportKind.USB_SERIAL))
            awaitConnected()

            transport.dropMidStream("cable pulled")

            val stale = awaitStale()
            assertEquals(RigTransportKind.USB_SERIAL, stale.lastKnown.transportKind)
            assertEquals(TEST_RIG_ID, stale.lastKnown.descriptorId)
            assertEquals(14_250_000L, stale.lastKnown.bands.first().frequencyHz, "the value is carried forward")
        }

    @Test
    @Requirement("FR-RIG-15")
    fun `FR_RIG_15 a Bluetooth rig drop degrades exactly as a USB drop does`() = runBlocking {
        val transport = FakeRigTransport()
        transport.scriptReply("FQ", "FQ0014250000")
        val supervisor = newSupervisor(transport)
        supervisor.connect(connectedConfig(RigTransportKind.BLUETOOTH_SPP))
        val connected = awaitConnected()
        assertEquals(RigTransportKind.BLUETOOTH_SPP, connected.transportKind)

        transport.dropMidStream("link lost")
        val stale = awaitStale()
        assertEquals(RigTransportKind.BLUETOOTH_SPP, stale.lastKnown.transportKind)
        // Reconnection is the transport's own responsibility, not RigSupervisor's (see its class
        // kdoc) -- FakeRigTransport has no self-healing loop of its own, so this bare fake
        // legitimately stays Stale here. RigSupervisorRealTransportTest proves the actual
        // recovery path against UsbSerialTransport/BluetoothSppTransport's own fakes, which do.
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
    fun `FR_RIG_6 a rig reading in force at transmission start is reported with provenance rig`() = runBlocking {
        val transport = FakeRigTransport()
        transport.scriptReply("FQ", "FQ0014250000")
        val supervisor = newSupervisor(transport)
        supervisor.connect(connectedConfig())
        awaitConnected()

        // A generous window: the transmission "started" well before this reading and "ends" well
        // after it, so the reading is unambiguously in force throughout.
        val reading = supervisor.frequencyForTransmission(band = null, startNanos = 0L, endNanos = Long.MAX_VALUE / 2)
        assertEquals(14_250_000L, reading.frequencyHz)
        assertEquals(FrequencyProvenance.RIG, reading.provenance)
    }

    @Test
    @Requirement("FR-RIG-15")
    fun `disconnect returns to Absent and stops republishing`() = runBlocking {
        val transport = FakeRigTransport()
        transport.scriptReply("FQ", "FQ0014250000")
        val supervisor = newSupervisor(transport)
        supervisor.connect(connectedConfig())
        awaitConnected()

        supervisor.disconnect()
        assertEquals(RigStatus.State.Absent, RigStatus.state)
    }

    // WPC3 (D23): bandAtTransmissionStart -- see that method's own kdoc for the rule.

    @Test
    @Requirement("D23")
    fun `D23 exactly one band open is reported unambiguously`() = runBlocking {
        val transport = FakeRigTransport()
        val supervisor = newSupervisor(transport, descriptor = dualBandTestDescriptor())
        supervisor.connect(connectedConfig(RigTransportKind.USB_SERIAL))

        transport.pushUnsolicited("BY 0,1")
        awaitBandSquelch("A", true)

        val result = supervisor.bandAtTransmissionStart(startNanos = Long.MAX_VALUE / 2)
        assertEquals(RigBand.A, result.band)
        assertEquals(false, result.ambiguous)
    }

    @Test
    @Requirement("D23")
    fun `D23 neither band open reports NONE, never a guessed band`() = runBlocking {
        val transport = FakeRigTransport()
        val supervisor = newSupervisor(transport, descriptor = dualBandTestDescriptor())
        supervisor.connect(connectedConfig(RigTransportKind.USB_SERIAL))

        val result = supervisor.bandAtTransmissionStart(startNanos = Long.MAX_VALUE / 2)
        assertEquals(BandAtStart.NONE, result)
    }

    @Test
    @Requirement("D23")
    fun `D23 both bands open resolves to whichever opened first, flagged ambiguous`() = runBlocking {
        val transport = FakeRigTransport()
        val clock = TestClock(startMonotonicNanos = 1_000_000_000L)
        val supervisor = newSupervisor(transport, descriptor = dualBandTestDescriptor(), clock = clock)
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
    }

    @Test
    @Requirement("D23")
    fun `D23 a single-band rig never reports a band, preserving pre-existing behaviour`() = runBlocking {
        val transport = FakeRigTransport()
        transport.scriptReply("FQ", "FQ0014250000")
        val supervisor = newSupervisor(transport)
        supervisor.connect(connectedConfig())
        awaitConnected()

        val result = supervisor.bandAtTransmissionStart(startNanos = Long.MAX_VALUE / 2)
        assertEquals(BandAtStart.NONE, result)
    }

    // F9 (WPC3): RigStatus.State.Stale's ladder-position fields.

    @Test
    @Requirement("F9")
    fun `F9 a fresh drop reports the first attempt with the real ladder's own first-step delay`() = runBlocking {
        val transport = FakeRigTransport()
        transport.scriptReply("FQ", "FQ0014250000")
        val supervisor = newSupervisor(transport, descriptor = testDescriptor())
        supervisor.connect(connectedConfig(RigTransportKind.USB_SERIAL))
        awaitConnected()

        transport.dropMidStream("cable pulled")

        val stale = awaitStale()
        assertEquals(1, stale.attempt)
        assertEquals(5, stale.ofTotal)
        assertEquals(UsbReconnectBackoff.delayMillisFor(1), stale.nextRetryInMillis)
    }

    @Test
    @Requirement("F9")
    fun `F9 the position advances as real wall time elapses, from the same ladder, never a second timer`() =
        runBlocking {
            val transport = FakeRigTransport()
            transport.scriptReply("FQ", "FQ0014250000")
            val clock = TestClock(startMonotonicNanos = 1_000_000_000L)
            val supervisor = newSupervisor(transport, descriptor = testDescriptor(), clock = clock)
            supervisor.connect(connectedConfig(RigTransportKind.USB_SERIAL))
            awaitConnected()

            transport.dropMidStream("cable pulled")
            val first = awaitStale()
            assertEquals(1, first.attempt)

            // Exactly UsbReconnectBackoff's own first step (1s) -- a second, distinct Lost
            // observation (a different reason string forces a new StateFlow emission) reflects
            // real elapsed time against the same real ladder, never an independently-invented one.
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
        }

    @Test
    @Requirement("F9")
    fun `F9 the Bluetooth ladder is used for a Bluetooth transport, not the USB one`() = runBlocking {
        val transport = FakeRigTransport()
        transport.scriptReply("FQ", "FQ0014250000")
        val supervisor = newSupervisor(transport, descriptor = testDescriptor())
        supervisor.connect(connectedConfig(RigTransportKind.BLUETOOTH_SPP))
        awaitConnected()

        transport.dropMidStream("link lost")

        val stale = awaitStale()
        assertEquals(BluetoothReconnectBackoff.delayMillisFor(1), stale.nextRetryInMillis)
    }
}
