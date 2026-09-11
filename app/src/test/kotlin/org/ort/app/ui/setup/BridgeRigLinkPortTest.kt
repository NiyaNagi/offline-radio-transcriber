package org.ort.app.ui.setup

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.pipeline.rig.FakeRigLinkBridge
import org.ort.pipeline.rig.PairedRigDevice
import org.ort.pipeline.rig.PairedRigDevicesResult
import org.ort.pipeline.rig.RigLinkProbeState
import org.ort.pipeline.rig.RigLinkSppSupport
import org.ort.rig.RigCapability
import org.ort.rig.RigTransportKind

/**
 * The real [RigLinkPort] over WPC3's [org.ort.pipeline.rig.RigLinkBridge] (`:pipeline`, merged
 * `8e40041`) — driven here against [FakeRigLinkBridge] rather than real hardware (constitution
 * II). [org.ort.pipeline.rig.DefaultRigLinkBridge] itself has its own real-transport test suite in
 * `:pipeline` (`RigLinkBridgeTest`); this class proves only the translation
 * [BridgeRigLinkPort] does on top of it.
 */
class BridgeRigLinkPortTest {

    @Test
    fun `the happy path opens, identifies and verifies, mapped 1-1 from the probe states`() = runTest {
        val bridge = FakeRigLinkBridge()
        bridge.scriptProbe(
            "kenwood-thd75a",
            RigTransportKind.BLUETOOTH_SPP,
            flowOf(
                RigLinkProbeState.Opening,
                RigLinkProbeState.Open,
                RigLinkProbeState.Identified("kenwood-thd75a"),
                RigLinkProbeState.Verified(setOf(RigCapability.FREQUENCY, RigCapability.SQUELCH_STATE)),
            ),
        )
        val port = BridgeRigLinkPort(bridge)

        val states = port.connect("AA:BB:CC:DD:EE:FF", "kenwood-thd75a").toList()

        assertEquals(
            listOf(
                RigLinkState.Opening,
                RigLinkState.Open,
                RigLinkState.Identified("kenwood-thd75a"),
                RigLinkState.Verified(listOf("frequency", "squelch")),
            ),
            states,
        )
    }

    @Test
    fun `NoPermission maps straight through, never a SecurityException surfacing as a crash`() = runTest {
        val bridge = FakeRigLinkBridge()
        bridge.scriptProbe("kenwood-thd75a", RigTransportKind.BLUETOOTH_SPP, flowOf(RigLinkProbeState.NoPermission))
        val port = BridgeRigLinkPort(bridge)

        val states = port.connect("AA:BB:CC:DD:EE:FF", "kenwood-thd75a").toList()

        assertEquals(listOf(RigLinkState.NoPermission), states)
    }

    @Test
    fun `a Lost after Identified maps through, FR-RIG-15`() = runTest {
        val bridge = FakeRigLinkBridge()
        bridge.scriptProbe(
            "kenwood-thd75a",
            RigTransportKind.BLUETOOTH_SPP,
            flowOf(
                RigLinkProbeState.Opening,
                RigLinkProbeState.Open,
                RigLinkProbeState.Identified("kenwood-thd75a"),
                RigLinkProbeState.Lost("connection dropped"),
            ),
        )
        val port = BridgeRigLinkPort(bridge)

        val states = port.connect("AA:BB:CC:DD:EE:FF", "kenwood-thd75a").toList()

        assertEquals(RigLinkState.Lost("connection dropped"), states.last())
        assertTrue(states.none { it is RigLinkState.Verified }, "must never fabricate a verified link")
    }

    @Test
    fun `an unknown rig id maps its Failed reason straight through`() = runTest {
        val bridge = FakeRigLinkBridge()
        bridge.scriptProbe(
            "not-a-real-rig",
            RigTransportKind.BLUETOOTH_SPP,
            flowOf(RigLinkProbeState.Opening, RigLinkProbeState.Failed("no descriptor named 'not-a-real-rig'")),
        )
        val port = BridgeRigLinkPort(bridge)

        val states = port.connect("AA:BB:CC:DD:EE:FF", "not-a-real-rig").toList()

        assertEquals(RigLinkState.Failed("no descriptor named 'not-a-real-rig'"), states.last())
    }

    @Test
    fun `connect always probes Bluetooth SPP with the address in rigParams`() = runTest {
        var capturedTransportKind: RigTransportKind? = null
        var capturedParams: Map<String, String>? = null
        val bridge = object : org.ort.pipeline.rig.RigLinkBridge {
            override fun pairedDevices() = PairedRigDevicesResult(emptyList(), permissionGranted = true)
            override fun probe(rigId: String, transportKind: RigTransportKind, params: Map<String, String>) =
                flowOf<RigLinkProbeState>(RigLinkProbeState.Opening).also {
                    capturedTransportKind = transportKind
                    capturedParams = params
                }
        }
        val port = BridgeRigLinkPort(bridge)

        port.connect("AA:BB:CC:DD:EE:FF", "kenwood-thd75a").toList()

        assertEquals(RigTransportKind.BLUETOOTH_SPP, capturedTransportKind)
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            capturedParams?.get(org.ort.pipeline.rig.DefaultRigTransportFactory.ParamKeys.BLUETOOTH_ADDRESS),
        )
    }

    @Test
    fun `pairedDevices maps SPP-capable, headset-only and unknown devices, and the permission flag`() {
        val bridge = FakeRigLinkBridge()
        bridge.setPairedDevices(
            PairedRigDevicesResult(
                devices = listOf(
                    PairedRigDevice("TH-D75A", "AA:BB", RigLinkSppSupport.YES),
                    PairedRigDevice("Handheld BT", "CC:DD", RigLinkSppSupport.NO),
                    PairedRigDevice(null, "EE:FF", RigLinkSppSupport.UNKNOWN),
                ),
                permissionGranted = true,
            ),
        )
        val port = BridgeRigLinkPort(bridge)

        val result = port.pairedDevices()

        assertTrue(result.permissionGranted)
        assertEquals(
            listOf(
                PairedDevice("TH-D75A", "AA:BB", sppCapable = true),
                PairedDevice("Handheld BT", "CC:DD", sppCapable = false),
                PairedDevice("EE:FF", "EE:FF", sppCapable = null),
            ),
            result.devices,
        )
    }

    @Test
    fun `pairedDevices reports permissionGranted false straight through, never inferred from an empty list`() {
        val bridge = FakeRigLinkBridge()
        bridge.setPairedDevices(PairedRigDevicesResult(emptyList(), permissionGranted = false))
        val port = BridgeRigLinkPort(bridge)

        val result = port.pairedDevices()

        assertFalse(result.permissionGranted)
        assertEquals(emptyList<PairedDevice>(), result.devices)
    }
}
