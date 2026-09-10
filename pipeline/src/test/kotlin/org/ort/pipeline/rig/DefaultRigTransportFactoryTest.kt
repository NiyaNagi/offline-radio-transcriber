package org.ort.pipeline.rig

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.rig.RigTransportKind
import org.ort.rig.bluetooth.BluetoothSppTransport
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
        val transport = factory.create(RigTransportKind.USB_SERIAL, usbParams())
        assertTrue(transport is UsbSerialTransport)
    }

    @Test
    @Requirement("FR-RIG-14")
    public fun `BLUETOOTH_SPP with valid params builds a real BluetoothSppTransport`() {
        val factory = DefaultRigTransportFactory(context)
        val transport = factory.create(RigTransportKind.BLUETOOTH_SPP, bluetoothParams())
        assertTrue(transport is BluetoothSppTransport)
    }

    @Test
    public fun `USB_SERIAL missing vendorId fails loudly rather than guessing`() {
        val factory = DefaultRigTransportFactory(context)
        assertThrows(IllegalStateException::class.java) {
            factory.create(
                RigTransportKind.USB_SERIAL,
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
                bluetoothParams().minus(DefaultRigTransportFactory.ParamKeys.BLUETOOTH_ADDRESS),
            )
        }
    }

    @Test
    public fun `an unregistered transport kind throws UnsupportedRigTransportException, never a silent no-op`() {
        val factory = DefaultRigTransportFactory(context)
        assertThrows(UnsupportedRigTransportException::class.java) {
            factory.create(RigTransportKind.NONE, emptyMap())
        }
    }

    @Test
    public fun `dispose releases every USB link this factory created without throwing`() {
        val factory = DefaultRigTransportFactory(context)
        factory.create(RigTransportKind.USB_SERIAL, usbParams())
        val overrideKey = DefaultRigTransportFactory.ParamKeys.USB_VENDOR_ID
        factory.create(RigTransportKind.USB_SERIAL, usbParams(mapOf(overrideKey to "1")))
        factory.dispose()
    }
}
