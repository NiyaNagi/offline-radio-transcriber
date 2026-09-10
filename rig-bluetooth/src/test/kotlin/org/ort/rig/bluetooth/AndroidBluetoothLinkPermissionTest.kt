package org.ort.rig.bluetooth

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * FR-PLT-2's Bluetooth equivalent (E2-C02): `BLUETOOTH_CONNECT` absent is a structural failure
 * (an empty list, a thrown [BluetoothLinkException]) at the one seam ([AndroidBluetoothLink])
 * that actually calls the platform APIs the permission gates — never an uncaught
 * `SecurityException`. Pinned to API 31, the level `BLUETOOTH_CONNECT` was introduced at; a real
 * Bluetooth stack is still hardware rows H2/H3, so nothing here calls `connect()` all the way
 * through to a socket.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
public class AndroidBluetoothLinkPermissionTest {

    private fun context(): Application = ApplicationProvider.getApplicationContext()

    @Test
    @Requirement("FR-PLT-2", "FR-RIG-14")
    public fun `FR_PLT_2 pairedDevices returns empty without BLUETOOTH_CONNECT, never throws`() {
        val context = context()
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val link = AndroidBluetoothLink(context)

        assertEquals(emptyList<PairedBluetoothDevice>(), link.pairedDevices())
    }

    @Test
    @Requirement("FR-PLT-2", "FR-RIG-14")
    public fun `FR_PLT_2 connect without BLUETOOTH_CONNECT is a structural failure, never a SecurityException`() {
        val context = context()
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val link = AndroidBluetoothLink(context)

        assertThrows(BluetoothLinkException::class.java) { link.connect("AA:BB:CC:DD:EE:FF") }
    }

    @Test
    @Requirement("FR-PLT-2")
    public fun `FR_PLT_2 hasConnectPermission reflects the platform permission state`() {
        val context = context()

        Shadows.shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertFalse(AndroidBluetoothLink(context).hasConnectPermission())

        Shadows.shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertTrue(AndroidBluetoothLink(context).hasConnectPermission())
    }
}
