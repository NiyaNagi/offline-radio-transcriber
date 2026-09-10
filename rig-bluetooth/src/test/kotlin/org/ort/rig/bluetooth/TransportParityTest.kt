@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.ort.rig.bluetooth

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.rig.RigState
import org.ort.rig.RigStateConfidence
import org.ort.rig.RigTransport
import org.ort.rig.TransportState
import org.ort.rig.bluetooth.fakes.FakeBluetoothLink
import org.ort.rig.usb.UsbDeviceHandle
import org.ort.rig.usb.UsbSerialLineConfig
import org.ort.rig.usb.UsbSerialParity
import org.ort.rig.usb.UsbSerialTransport
import org.ort.rig.usb.fakes.FakeUsbSerialPort

/**
 * AC-133 / E2-B04's transport half: "the TH-D75A module yields the same capabilities over
 * Bluetooth SPP as over USB serial, and a Bluetooth rig disconnection degrades to a stale
 * frequency without stopping capture, exactly as FR-RIG-7 treats a USB disconnection." `:rig`'s
 * declarative descriptor engine (`DescriptorRigModule`, functional spec §9.2) had not landed on
 * this branch at merge time, so this exercises the `RigTransport` contract directly with a
 * minimal inline parser standing in for the descriptor — the same command, the same reply,
 * through [UsbSerialTransport] over [FakeUsbSerialPort] and [BluetoothSppTransport] over
 * [FakeBluetoothLink]. `:rig-bluetooth` may only depend on `:core` and `:rig` in its main source
 * set (ModuleGraph); the `:rig-usb` reference here is a `testImplementation`-only dependency
 * (see this module's `build.gradle.kts`), which the dependency-rules check deliberately excludes.
 */
class TransportParityTest {

    private val terminator = ';'
    private val frequencyCommand = "FQ 0"
    private val frequencyPattern = Regex("^FQ 0,(\\d{10})$")

    private suspend fun pollFrequency(transport: RigTransport): RigState {
        transport.write(frequencyCommand)
        val line = transport.readLine(1_000) ?: error("no reply from transport")
        val match = frequencyPattern.matchEntire(line) ?: error("unparseable reply: $line")
        return RigState(
            timestampNanos = 0L,
            frequencyHz = match.groupValues[1].toLong(),
            sourceConfidence = RigStateConfidence.FRESH,
        )
    }

    @Test
    fun `AC_133 usb and bluetooth transports yield identical RigState for the same scripted reply`() = runTest {
        val expectedReply = "FQ 0,0014250000"
        val usbConfig = UsbSerialLineConfig(9600, 8, 1, UsbSerialParity.NONE, terminator)

        val usbDevice = UsbDeviceHandle(vendorId = 0x0123, productId = 0x4567, deviceName = "th-d75a")
        val usbPort = FakeUsbSerialPort()
        usbPort.attach(usbDevice)
        usbPort.grantPermission(usbDevice)
        usbPort.scriptReply(frequencyCommand, expectedReply, lineConfig = usbConfig)
        val usbTransport = UsbSerialTransport(
            usbDevice.vendorId,
            usbDevice.productId,
            usbConfig,
            usbPort,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        usbTransport.open()
        runCurrent()
        val usbState = pollFrequency(usbTransport)
        usbTransport.close()

        val btLink = FakeBluetoothLink()
        btLink.scriptReply(frequencyCommand, expectedReply, terminator = terminator)
        val btTransport = BluetoothSppTransport(
            "AA:BB:CC:DD:EE:FF",
            btLink,
            terminator,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        btTransport.open()
        runCurrent()
        val btState = pollFrequency(btTransport)
        btTransport.close()

        assertEquals(usbState, btState)
        assertEquals(14_250_000L, usbState.frequencyHz)
    }

    @Test
    fun `FR_RIG_15 a Bluetooth drop and a USB detach both degrade to Lost then recover, identically`() = runTest {
        val usbConfig = UsbSerialLineConfig(9600, 8, 1, UsbSerialParity.NONE, terminator)
        val usbDevice = UsbDeviceHandle(vendorId = 1, productId = 1, deviceName = "dev")
        val usbPort = FakeUsbSerialPort()
        usbPort.attach(usbDevice)
        usbPort.grantPermission(usbDevice)
        val usbTransport = UsbSerialTransport(
            usbDevice.vendorId,
            usbDevice.productId,
            usbConfig,
            usbPort,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val btLink = FakeBluetoothLink()
        val btTransport = BluetoothSppTransport(
            "AA:BB:CC:DD:EE:FF",
            btLink,
            terminator,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        usbTransport.open()
        btTransport.open()
        runCurrent()
        assertEquals(TransportState.Open, usbTransport.state.first())
        assertEquals(TransportState.Open, btTransport.state.first())

        usbPort.detach(usbDevice)
        btLink.drop()
        runCurrent()

        assertTrue(usbTransport.state.first() is TransportState.Lost)
        assertTrue(btTransport.state.first() is TransportState.Lost)

        usbPort.attach(usbDevice)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(TransportState.Open, usbTransport.state.first())
        assertEquals(TransportState.Open, btTransport.state.first())

        usbTransport.close()
        btTransport.close()
    }
}
