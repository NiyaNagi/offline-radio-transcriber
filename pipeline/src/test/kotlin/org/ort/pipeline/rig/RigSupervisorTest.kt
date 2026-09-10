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
import org.ort.core.capture.CaptureMode
import org.ort.pipeline.capture.RigStatus
import org.ort.rig.RigTransportKind
import org.ort.rig.descriptor.CommandSpec
import org.ort.rig.descriptor.PollSpec
import org.ort.rig.descriptor.RigDescriptor
import org.ort.rig.descriptor.TransportSpec
import org.ort.rig.fakes.FakeRigTransport
import org.ort.testing.Requirement

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

    private fun newSupervisor(transport: FakeRigTransport): RigSupervisor {
        val s = RigSupervisor(
            transportFactory = RigTransportFactory { _, _ -> transport },
            scope = scope,
            descriptorForId = { id -> if (id == TEST_RIG_ID) testDescriptor() else null },
        )
        supervisor = s
        return s
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
    fun `FR_RIG_15 a Bluetooth rig drop degrades and reconnects exactly as a USB drop does`() = runBlocking {
        val transport = FakeRigTransport()
        transport.scriptReply("FQ", "FQ0014250000")
        val supervisor = newSupervisor(transport)
        supervisor.connect(connectedConfig(RigTransportKind.BLUETOOTH_SPP))
        val connected = awaitConnected()
        assertEquals(RigTransportKind.BLUETOOTH_SPP, connected.transportKind)

        transport.dropMidStream("link lost")
        val stale = awaitStale()
        assertEquals(RigTransportKind.BLUETOOTH_SPP, stale.lastKnown.transportKind)

        // FakeRigTransport.open() succeeds again once dropMidStream's flag is cleared by a fresh
        // open() -- exactly what a re-plugged/re-paired real link does. The reconnect loop's first
        // attempt (BackoffLadder's 1s) reaches it well inside this timeout.
        val reconnected = awaitConnected(timeoutMs = 5_000)
        assertEquals(RigTransportKind.BLUETOOTH_SPP, reconnected.transportKind, "recovery announced by Connected again")
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
}
