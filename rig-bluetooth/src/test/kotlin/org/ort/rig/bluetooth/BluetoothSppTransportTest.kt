@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.ort.rig.bluetooth

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.rig.TransportState
import org.ort.rig.bluetooth.fakes.FakeBluetoothLink

/**
 * The Bluetooth SPP state machine (E2-C02/E2-C03, FR-RIG-14, FR-RIG-15) against
 * [FakeBluetoothLink] — no Android dependency, plain unit tests.
 */
class BluetoothSppTransportTest {

    private val address = "AA:BB:CC:DD:EE:FF"
    private val terminator = ';'

    private fun TestScope.transportOver(link: FakeBluetoothLink) =
        BluetoothSppTransport(address, link, terminator, dispatcher = StandardTestDispatcher(testScheduler))

    @Test
    fun `FR_RIG_14 connects over RFCOMM and reads a scripted line`() = runTest {
        val link = FakeBluetoothLink()
        val transport = transportOver(link)

        transport.open()
        runCurrent()
        assertEquals(TransportState.Open, transport.state.first())

        link.scriptReply("FQ 0", "FQ 0,0014250000", terminator = terminator)
        transport.write("FQ 0")
        val line = transport.readLine(1_000)

        assertEquals("FQ 0,0014250000", line)
        transport.close()
    }

    @Test
    fun `FR_RIG_15 a drop degrades exactly like a USB detach and reconnects with backoff`() = runTest {
        val link = FakeBluetoothLink()
        val transport = transportOver(link)

        transport.open()
        runCurrent()
        assertEquals(TransportState.Open, transport.state.first())

        link.drop("link-layer disconnect")
        runCurrent()
        assertEquals(TransportState.Lost(BluetoothSppTransport.Reason.DROPPED), transport.state.first())

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(TransportState.Open, transport.state.first())

        transport.close()
    }

    @Test
    fun `FR_PLT_2 missing BLUETOOTH_CONNECT is a transport state, never a SecurityException`() = runTest {
        val link = FakeBluetoothLink()
        link.denyConnectPermission()
        val transport = transportOver(link)

        transport.open()
        runCurrent()
        assertEquals(TransportState.Lost(BluetoothSppTransport.Reason.NO_PERMISSION), transport.state.first())

        link.grantConnectPermission()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(TransportState.Open, transport.state.first())

        transport.close()
    }

    @Test
    fun `F16 socket gone during read never throws into the caller`() = runTest {
        val link = FakeBluetoothLink()
        val transport = transportOver(link)

        transport.open()
        runCurrent()
        assertEquals(TransportState.Open, transport.state.first())

        link.dropDuringRead()
        val line = transport.readLine(1_000)

        assertNull(line, "a socket-gone read must resolve to null, never throw")
        assertEquals(TransportState.Lost(BluetoothSppTransport.Reason.READ_FAILED), transport.state.first())

        transport.close()
    }

    @Test
    fun `FR_RIG_14 write before connect throws rather than silently doing nothing`() = runTest {
        val link = FakeBluetoothLink()
        val transport = transportOver(link)

        assertThrows(IllegalStateException::class.java) { transport.write("FQ 0") }
    }

    @Test
    fun `FR_RIG_14 pairedDevices reports SPP support honestly, including unknown`() {
        val link = FakeBluetoothLink()
        link.pair("11:11:11:11:11:11", "TH-D75A", SppSupport.YES)
        link.pair("22:22:22:22:22:22", "Unrelated headset", SppSupport.NO)
        link.pair("33:33:33:33:33:33", "Stack reports no UUIDs", SppSupport.UNKNOWN)

        val devices = BluetoothSppTransport.pairedDevices(link)

        assertEquals(3, devices.size)
        assertTrue(devices.any { it.advertisesSpp == SppSupport.YES && it.address == "11:11:11:11:11:11" })
        assertTrue(devices.any { it.advertisesSpp == SppSupport.UNKNOWN })
    }
}
