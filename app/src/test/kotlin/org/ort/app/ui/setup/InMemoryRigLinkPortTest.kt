package org.ort.app.ui.setup

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WPD's `:app`-local seam onto a Bluetooth rig link (S10b, `RigLinkPort.kt`'s own doc comment has
 * the full account of why the real `:rig-bluetooth` adapter cannot yet live here). Every failure
 * mode FR-RIG-15 cares about is proven against the fake alone — constitution II.
 */
class InMemoryRigLinkPortTest {

    @Test
    fun `a device with no script opens, identifies and verifies in order`() = runTest {
        val port = InMemoryRigLinkPort(devices = listOf(PairedDevice("TH-D75A", "AA:BB", sppCapable = true)))

        val states = withTimeout(1_000) { port.connect("AA:BB", "kenwood-thd75a").toList() }

        assertEquals(
            listOf(
                RigLinkState.Opening,
                RigLinkState.Open,
                RigLinkState.Identified("kenwood-thd75a"),
                RigLinkState.Verified(InMemoryRigLinkPort.DEFAULT_VERIFIED_COMMANDS),
            ),
            states,
        )
    }

    @Test
    fun `verifiesWith overrides the default verified command list`() = runTest {
        val port = InMemoryRigLinkPort()
        port.verifiesWith(listOf("FQ", "BY"))

        val states = withTimeout(1_000) { port.connect("AA:BB", "kenwood-thd75a").toList() }

        assertEquals(RigLinkState.Verified(listOf("FQ", "BY")), states.last())
    }

    @Test
    fun `a hung device emits Opening and then never completes on its own`() = runTest {
        val port = InMemoryRigLinkPort()
        port.hang("AA:BB")

        val firstState = withTimeout(1_000) { port.connect("AA:BB", "kenwood-thd75a").take(1).toList() }

        assertEquals(listOf(RigLinkState.Opening), firstState)
    }

    @Test
    fun `a device that fails to open reports Failed with the scripted reason, never a crash`() = runTest {
        val port = InMemoryRigLinkPort()
        port.failToOpen("AA:BB", reason = "connection refused")

        val states = withTimeout(1_000) { port.connect("AA:BB", "kenwood-thd75a").toList() }

        assertEquals(RigLinkState.Failed("connection refused"), states.last())
        assertTrue(states.none { it is RigLinkState.Verified }, "must never fabricate a verified link")
    }

    @Test
    fun `a device that drops after opening reports Lost, not a blank screen, FR-RIG-15`() = runTest {
        val port = InMemoryRigLinkPort()
        port.dropAfterOpen("AA:BB", reason = "connection dropped")

        val states = withTimeout(1_000) { port.connect("AA:BB", "kenwood-thd75a").toList() }

        assertEquals(listOf(RigLinkState.Opening, RigLinkState.Open, RigLinkState.Lost("connection dropped")), states)
    }

    @Test
    fun `a device with no permission reports NoPermission, never a SecurityException`() = runTest {
        val port = InMemoryRigLinkPort()
        port.noPermission("AA:BB")

        val states = withTimeout(1_000) { port.connect("AA:BB", "kenwood-thd75a").toList() }

        assertEquals(listOf(RigLinkState.NoPermission), states)
    }

    @Test
    fun `pairedDevices returns exactly what was supplied, including headset-only and unknown-capability rows`() {
        val devices = listOf(
            PairedDevice("TH-D75A", "AA:BB", sppCapable = true),
            PairedDevice("Handheld BT", "CC:DD", sppCapable = false),
            PairedDevice("Mystery device", "EE:FF", sppCapable = null),
        )
        val port = InMemoryRigLinkPort(devices)

        val result = port.pairedDevices()
        assertEquals(devices, result.devices)
        assertTrue(result.permissionGranted)
    }

    @Test
    fun `denyPermission reports an empty list with permissionGranted false, never conflated with nothing paired`() {
        val devices = listOf(PairedDevice("TH-D75A", "AA:BB", sppCapable = true))
        val port = InMemoryRigLinkPort(devices)

        port.denyPermission()
        val result = port.pairedDevices()

        assertEquals(emptyList<PairedDevice>(), result.devices)
        assertFalse(result.permissionGranted)
    }
}
