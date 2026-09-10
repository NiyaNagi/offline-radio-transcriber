@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.ort.rig.usb

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.ort.rig.TransportState
import org.ort.rig.usb.fakes.FakeUsbSerialPort

/**
 * The USB serial state machine (E2-C01, FR-RIG-3, FR-PLT-2, F16, FR-RIG-7) against
 * [FakeUsbSerialPort] — no Android dependency, so this runs as a plain unit test rather than
 * needing Robolectric (constitution II: capability probed through the fake seam, not exception
 * forensics on a real UsbManager).
 */
class UsbSerialTransportTest {

    private val lineConfig = UsbSerialLineConfig(
        baudRate = 9600,
        dataBits = 8,
        stopBits = 1,
        parity = UsbSerialParity.NONE,
        lineTerminator = ';',
    )

    @Test
    fun `FR_RIG_3 open waits for permission then opens, reads a scripted line`() = runTest {
        val device = UsbDeviceHandle(vendorId = 4, productId = 5, deviceName = "th-d75a")
        val port = FakeUsbSerialPort()
        port.attach(device)
        val transport = UsbSerialTransport(4, 5, lineConfig, port, dispatcher = StandardTestDispatcher(testScheduler))

        transport.open()
        runCurrent()
        assertEquals(TransportState.Connecting, transport.state.first())
        assertEquals(UsbSerialTransport.UsbPermissionState.UNKNOWN, transport.permissionState.first())

        port.grantPermission(device)
        runCurrent()
        assertEquals(TransportState.Open, transport.state.first())
        assertEquals(UsbSerialTransport.UsbPermissionState.GRANTED, transport.permissionState.first())

        port.scriptReply("FQ 0", "FQ 0,0014250000", lineConfig = lineConfig)
        transport.write("FQ 0")
        val line = transport.readLine(1_000)

        assertEquals("FQ 0,0014250000", line)
        transport.close()
    }

    @Test
    fun `FR_RIG_7 a detach is reported Lost and the transport reconnects with backoff`() = runTest {
        val device = UsbDeviceHandle(vendorId = 6, productId = 9, deviceName = "th-d75a")
        val port = FakeUsbSerialPort()
        port.attach(device)
        port.grantPermission(device)
        val transport = UsbSerialTransport(6, 9, lineConfig, port, dispatcher = StandardTestDispatcher(testScheduler))

        transport.open()
        runCurrent()
        assertEquals(TransportState.Open, transport.state.first())

        port.detach(device)
        runCurrent()
        assertEquals(TransportState.Lost(UsbSerialTransport.Reason.DETACHED), transport.state.first())

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(TransportState.Lost(UsbSerialTransport.Reason.DEVICE_NOT_FOUND), transport.state.first())

        port.attach(device)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(TransportState.Open, transport.state.first())

        transport.close()
    }

    @Test
    fun `FR_PLT_2 permission denied twice reports permission-denied both times, never crashes`() = runTest {
        val device = UsbDeviceHandle(vendorId = 1, productId = 2, deviceName = "dev")
        val port = FakeUsbSerialPort()
        port.attach(device)
        val transport = UsbSerialTransport(1, 2, lineConfig, port, dispatcher = StandardTestDispatcher(testScheduler))

        transport.open()
        runCurrent()

        port.denyPermission(device)
        runCurrent()
        assertEquals(TransportState.Lost(UsbSerialTransport.Reason.PERMISSION_DENIED), transport.state.first())
        assertEquals(UsbSerialTransport.UsbPermissionState.DENIED, transport.permissionState.first())

        advanceTimeBy(1_000)
        runCurrent()
        port.denyPermission(device)
        runCurrent()
        assertEquals(TransportState.Lost(UsbSerialTransport.Reason.PERMISSION_DENIED), transport.state.first())

        transport.close()
    }

    @Test
    fun `F16 permission lost after a re-attach is distinguished from a first-time denial`() = runTest {
        val device = UsbDeviceHandle(vendorId = 3, productId = 3, deviceName = "dev")
        val port = FakeUsbSerialPort()
        port.attach(device)
        port.grantPermission(device)
        val transport = UsbSerialTransport(3, 3, lineConfig, port, dispatcher = StandardTestDispatcher(testScheduler))

        transport.open()
        runCurrent()
        assertEquals(TransportState.Open, transport.state.first())

        // Re-attach without permission surviving (FR-PLT-2: permission is per-attachment, not
        // persistent by default).
        port.detach(device)
        runCurrent()
        port.attach(device)
        port.denyPermission(device)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(TransportState.Lost(UsbSerialTransport.Reason.PERMISSION_LOST), transport.state.first())
        assertEquals(UsbSerialTransport.UsbPermissionState.LOST, transport.permissionState.first())

        transport.close()
    }

    @Test
    fun `F16 device gone during read never throws into the caller`() = runTest {
        val device = UsbDeviceHandle(vendorId = 7, productId = 8, deviceName = "dev")
        val port = FakeUsbSerialPort()
        port.attach(device)
        port.grantPermission(device)
        val transport = UsbSerialTransport(7, 8, lineConfig, port, dispatcher = StandardTestDispatcher(testScheduler))

        transport.open()
        runCurrent()
        assertEquals(TransportState.Open, transport.state.first())

        port.dropDuringRead()
        val line = transport.readLine(1_000)

        assertNull(line, "a device-gone read must resolve to null, never throw")
        assertEquals(TransportState.Lost(UsbSerialTransport.Reason.DEVICE_GONE_DURING_READ), transport.state.first())

        transport.close()
    }

    @Test
    fun `FR_RIG_3 write before open throws rather than silently doing nothing`() = runTest {
        val port = FakeUsbSerialPort()
        val transport = UsbSerialTransport(1, 1, lineConfig, port, dispatcher = StandardTestDispatcher(testScheduler))

        assertThrows(IllegalStateException::class.java) { transport.write("FQ 0") }
    }
}
