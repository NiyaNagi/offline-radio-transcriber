package org.ort.pipeline.rig

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.core.capture.CaptureMode
import org.ort.pipeline.capture.RigStatus
import org.ort.rig.RigTransportKind
import org.ort.rig.bluetooth.BluetoothSppTransport
import org.ort.rig.bluetooth.SppSupport
import org.ort.rig.bluetooth.fakes.FakeBluetoothLink
import org.ort.rig.usb.UsbDeviceHandle
import org.ort.rig.usb.UsbSerialLineConfig
import org.ort.rig.usb.UsbSerialParity
import org.ort.rig.usb.UsbSerialTransport
import org.ort.rig.usb.fakes.FakeUsbSerialPort
import org.ort.testing.Requirement

/**
 * The coordinator's follow-up ask (WPB merged): [RigSupervisor] reaches `Connected` and degrades
 * to `Stale` on a drop through the **real** transport classes — `UsbSerialTransport` and
 * `BluetoothSppTransport` (WPB) — each over its own behavioural fake link
 * ([FakeUsbSerialPort]/[FakeBluetoothLink]), not `:rig`'s bare [org.ort.rig.fakes.FakeRigTransport]
 * (which has no self-healing state machine to exercise this against — see [RigSupervisor]'s class
 * kdoc). Neither transport's own reconnect loop is driven by `RigSupervisor` (that is each
 * transport's own responsibility); recovery itself is already proven by `UsbSerialTransportTest`/
 * `BluetoothSppTransportTest` in their own modules, so it is not re-proven here.
 */
class RigSupervisorRealTransportTest {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var supervisor: RigSupervisor? = null

    @AfterEach
    fun teardown() {
        supervisor?.disconnect()
        scope.coroutineContext[Job]?.cancel()
        RigStatus.reset()
    }

    /** Waits for a `Connected` reading that has actually picked up the frequency poll reply —
     * `FA;`/`MD;` are two separate poll commands/replies, so the very first `Connected` transition
     * can legitimately be the mode-only reading with `frequencyHz` still `null`, carried forward
     * once the frequency reply lands a moment later. */
    private suspend fun awaitConnected(timeoutMs: Long = 2_000): RigStatus.State.Connected {
        withTimeout(timeoutMs) {
            while (true) {
                val state = RigStatus.state
                if (state is RigStatus.State.Connected && state.bands.firstOrNull()?.frequencyHz != null) {
                    return@withTimeout
                }
                delay(10)
            }
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
    @Requirement("FR-RIG-3", "FR-RIG-7")
    fun `USB_SERIAL reaches Connected through the real UsbSerialTransport, degrades to Stale on a detach`() =
        runBlocking {
            val port = FakeUsbSerialPort()
            val device = UsbDeviceHandle(vendorId = 0x0483, productId = 0x5740, deviceName = "test-cdc-acm")
            port.attach(device)
            port.grantPermission(device)
            val lineConfig = UsbSerialLineConfig(
                baudRate = 9_600,
                dataBits = 8,
                stopBits = 1,
                parity = UsbSerialParity.NONE,
                lineTerminator = '\n',
            )
            // generic-ascii-cat's own poll commands (rig/src/main/resources/descriptors/generic-ascii-cat.json).
            port.scriptReply("FA;", "FA00014230000;", lineConfig = lineConfig)
            port.scriptReply("MD;", "MD4;", lineConfig = lineConfig)

            val factory = RigTransportFactory { _, _, _ ->
                UsbSerialTransport(device.vendorId, device.productId, lineConfig, port)
            }
            val rigSupervisor = RigSupervisor(factory, scope)
            supervisor = rigSupervisor
            rigSupervisor.connect(
                CaptureConfiguration(
                    mode = CaptureMode.USB_RADIO,
                    selectedInputId = "usb-1",
                    rigId = "generic-ascii-cat",
                    rigTransportKind = RigTransportKind.USB_SERIAL,
                ),
            )

            val connected = awaitConnected()
            assertEquals(RigTransportKind.USB_SERIAL, connected.transportKind)
            assertEquals("generic-ascii-cat", connected.descriptorId)
            assertEquals(14_230_000L, connected.bands.first().frequencyHz)

            port.detach(device)

            val stale = awaitStale()
            assertEquals(RigTransportKind.USB_SERIAL, stale.lastKnown.transportKind)
        }

    @Test
    @Requirement("FR-RIG-14", "FR-RIG-15")
    fun `BLUETOOTH_SPP reaches Connected through the real BluetoothSppTransport, degrades to Stale on a drop`() =
        runBlocking {
            val link = FakeBluetoothLink()
            val address = "AA:BB:CC:DD:EE:FF"
            link.pair(address, "Test Radio", SppSupport.YES)
            val terminator = '\n'
            link.scriptReply("FA;", "FA00014230000;", terminator = terminator)
            link.scriptReply("MD;", "MD4;", terminator = terminator)

            val factory = RigTransportFactory { _, _, _ -> BluetoothSppTransport(address, link, terminator) }
            val rigSupervisor = RigSupervisor(factory, scope)
            supervisor = rigSupervisor
            rigSupervisor.connect(
                CaptureConfiguration(
                    mode = CaptureMode.BLUETOOTH_RADIO,
                    selectedInputId = null,
                    rigId = "generic-ascii-cat",
                    rigTransportKind = RigTransportKind.BLUETOOTH_SPP,
                ),
            )

            val connected = awaitConnected()
            assertEquals(RigTransportKind.BLUETOOTH_SPP, connected.transportKind)
            assertEquals("generic-ascii-cat", connected.descriptorId)
            assertEquals(14_230_000L, connected.bands.first().frequencyHz)

            link.drop()

            val stale = awaitStale()
            assertEquals(RigTransportKind.BLUETOOTH_SPP, stale.lastKnown.transportKind)
        }
}
