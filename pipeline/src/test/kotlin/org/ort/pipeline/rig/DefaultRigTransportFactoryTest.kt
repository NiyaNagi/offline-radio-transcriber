package org.ort.pipeline.rig

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.rig.RigTransportKind
import org.ort.rig.bluetooth.BluetoothSppTransport
import org.ort.rig.descriptor.TransportSpec
import org.ort.rig.usb.UsbSerialTransport
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * FR-RIG-13/14, the production [RigTransportFactory]: real transports for the two kinds WPB built,
 * loud failure for every param this package cannot invent a safe default for (constitution I).
 */
@RunWith(RobolectricTestRunner::class)
public class DefaultRigTransportFactoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun usbParams(overrides: Map<String, String> = emptyMap()): Map<String, String> = mapOf(
        DefaultRigTransportFactory.ParamKeys.USB_VENDOR_ID to "1155",
        DefaultRigTransportFactory.ParamKeys.USB_PRODUCT_ID to "22336",
        DefaultRigTransportFactory.ParamKeys.LINE_TERMINATOR to "\n",
    ) + overrides

    private fun bluetoothParams(overrides: Map<String, String> = emptyMap()): Map<String, String> = mapOf(
        DefaultRigTransportFactory.ParamKeys.BLUETOOTH_ADDRESS to "AA:BB:CC:DD:EE:FF",
        DefaultRigTransportFactory.ParamKeys.LINE_TERMINATOR to "\n",
    ) + overrides

    @Test
    @Requirement("FR-RIG-3")
    public fun `USB_SERIAL with valid params builds a real UsbSerialTransport`() {
        val factory = DefaultRigTransportFactory(context)
        val transport = factory.create(RigTransportKind.USB_SERIAL, null, usbParams())
        assertTrue(transport is UsbSerialTransport)
    }

    @Test
    @Requirement("FR-RIG-14")
    public fun `BLUETOOTH_SPP with valid params builds a real BluetoothSppTransport`() {
        val factory = DefaultRigTransportFactory(context)
        val transport = factory.create(RigTransportKind.BLUETOOTH_SPP, null, bluetoothParams())
        assertTrue(transport is BluetoothSppTransport)
    }

    @Test
    public fun `USB_SERIAL missing vendorId fails loudly rather than guessing`() {
        val factory = DefaultRigTransportFactory(context)
        assertThrows(IllegalStateException::class.java) {
            factory.create(
                RigTransportKind.USB_SERIAL,
                null,
                usbParams().minus(DefaultRigTransportFactory.ParamKeys.USB_VENDOR_ID),
            )
        }
    }

    @Test
    public fun `USB_SERIAL missing lineTerminator fails loudly rather than guessing`() {
        val factory = DefaultRigTransportFactory(context)
        assertThrows(IllegalStateException::class.java) {
            factory.create(
                RigTransportKind.USB_SERIAL,
                null,
                usbParams().minus(DefaultRigTransportFactory.ParamKeys.LINE_TERMINATOR),
            )
        }
    }

    @Test
    public fun `BLUETOOTH_SPP missing address fails loudly rather than guessing`() {
        val factory = DefaultRigTransportFactory(context)
        assertThrows(IllegalStateException::class.java) {
            factory.create(
                RigTransportKind.BLUETOOTH_SPP,
                null,
                bluetoothParams().minus(DefaultRigTransportFactory.ParamKeys.BLUETOOTH_ADDRESS),
            )
        }
    }

    @Test
    public fun `an unregistered transport kind throws UnsupportedRigTransportException, never a silent no-op`() {
        val factory = DefaultRigTransportFactory(context)
        assertThrows(UnsupportedRigTransportException::class.java) {
            factory.create(RigTransportKind.NONE, null, emptyMap())
        }
    }

    @Test
    public fun `dispose releases every USB link this factory created without throwing`() {
        val factory = DefaultRigTransportFactory(context)
        factory.create(RigTransportKind.USB_SERIAL, null, usbParams())
        val overrideKey = DefaultRigTransportFactory.ParamKeys.USB_VENDOR_ID
        factory.create(RigTransportKind.USB_SERIAL, null, usbParams(mapOf(overrideKey to "1")))
        factory.dispose()
    }

    // WPC3 (FR-RIG-3): the descriptor's own TransportSpec is read first; CaptureConfiguration's
    // rigParams is the fallback for exactly what the spec does not declare.

    @Test
    @Requirement("FR-RIG-3")
    public fun `USB_SERIAL with a spec declaring vid, pid and terminator ignores empty params entirely`() {
        val factory = DefaultRigTransportFactory(context)
        val spec = TransportSpec(kind = "usb_serial", usbVendorId = 0x0451, usbProductId = 0x8613, lineTerminator = ";")
        val transport = factory.create(RigTransportKind.USB_SERIAL, spec, emptyMap())
        assertTrue(transport is UsbSerialTransport)
    }

    @Test
    @Requirement("FR-RIG-3")
    public fun `USB_SERIAL with a spec declaring nothing still falls back to params, unchanged`() {
        val factory = DefaultRigTransportFactory(context)
        val spec = TransportSpec(kind = "usb_serial")
        val transport = factory.create(RigTransportKind.USB_SERIAL, spec, usbParams())
        assertTrue(transport is UsbSerialTransport)
    }

    @Test
    @Requirement("FR-RIG-3")
    public fun `USB_SERIAL with a spec declaring only vid still fails loudly on the missing pid`() {
        val factory = DefaultRigTransportFactory(context)
        val spec = TransportSpec(kind = "usb_serial", usbVendorId = 0x0451, lineTerminator = ";")
        assertThrows(IllegalStateException::class.java) {
            factory.create(RigTransportKind.USB_SERIAL, spec, emptyMap())
        }
    }

    @Test
    @Requirement("FR-RIG-3")
    public fun `BLUETOOTH_SPP always reads the address from params even when a spec is present`() {
        val factory = DefaultRigTransportFactory(context)
        val spec = TransportSpec(kind = "bluetooth_spp", lineTerminator = ";")
        val transport = factory.create(RigTransportKind.BLUETOOTH_SPP, spec, bluetoothParams())
        assertTrue(transport is BluetoothSppTransport)
    }
}
