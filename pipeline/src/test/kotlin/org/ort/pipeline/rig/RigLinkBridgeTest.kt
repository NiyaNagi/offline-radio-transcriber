@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.ort.pipeline.rig

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.pipeline.capture.CaptureState
import org.ort.rig.RigCapability
import org.ort.rig.RigTransportKind
import org.ort.rig.bluetooth.BluetoothSppTransport
import org.ort.rig.bluetooth.fakes.FakeBluetoothLink
import org.ort.rig.descriptor.CommandSpec
import org.ort.rig.descriptor.PatternSpec
import org.ort.rig.descriptor.PollSpec
import org.ort.rig.descriptor.RigDescriptor
import org.ort.rig.descriptor.TransportSpec
import org.ort.rig.descriptor.UnsolicitedSpec
import org.ort.rig.fakes.FakeRigTransport
import org.ort.rig.usb.UsbDeviceHandle
import org.ort.rig.usb.UsbSerialLineConfig
import org.ort.rig.usb.UsbSerialParity
import org.ort.rig.usb.UsbSerialTransport
import org.ort.rig.usb.fakes.FakeUsbSerialPort
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors

private const val TEST_RIG_ID = "test-rig"

/** Unsolicited BY, matching both [RigCapability.SQUELCH_STATE] and [RigCapability.SUB_BAND] from
 * one line — the fastest path from Identified to Verified for a [FakeRigTransport]-backed test. */
private fun pushDescriptor(kind: String): RigDescriptor = RigDescriptor(
    schemaVersion = 1,
    id = TEST_RIG_ID,
    displayName = "Test Rig",
    transports = listOf(TransportSpec(kind = kind, capabilities = listOf("SQUELCH_STATE", "SUB_BAND"))),
    unsolicited = UnsolicitedSpec(
        enable = "AI 1",
        patterns = listOf(PatternSpec(expect = "^BY (\\d),(\\d)$", map = mapOf("band" to "$1", "squelchOpen" to "$2"))),
    ),
)

/** A poll-based descriptor over the two real transports (WPB), one command/one capability, so
 * `FakeUsbSerialPort`/`FakeBluetoothLink` (which model a byte-level wire, unlike `FakeRigTransport`'s
 * `pushUnsolicited`) can drive it through a scripted reply. */
private fun pollDescriptor(kind: String): RigDescriptor = RigDescriptor(
    schemaVersion = 1,
    id = TEST_RIG_ID,
    displayName = "Test Rig",
    transports = listOf(TransportSpec(kind = kind, capabilities = listOf("FREQUENCY"))),
    poll = PollSpec(
        intervalMs = 60_000,
        commands = listOf(CommandSpec(send = "FQ", expect = "^FQ(\\d{10})$", map = mapOf("frequencyHz" to "$1"))),
    ),
)

/**
 * WPC3: [RigLinkBridge.probe] over a real [org.ort.rig.descriptor.DescriptorRigModule] — the
 * open → identify → verify sequence S10b's checklist names (E2-E10), plus the two ways it can be
 * cut short: a permission refusal and a mid-sequence drop.
 */
@RunWith(RobolectricTestRunner::class)
public class RigLinkBridgeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    public fun setUp() {
        CaptureState.idle(clearSession = true)
    }

    @After
    public fun tearDown() {
        CaptureState.idle(clearSession = true)
    }

    @Test
    @Requirement("FR-RIG-13")
    public fun `capture running refuses the probe with Failed, before anything is opened`() = runTest {
        CaptureState.capturing("SESSION-LIVE")
        val bridge = DefaultRigLinkBridge(
            context = context,
            transportFactory = RigTransportFactory { _, _, _ -> FakeRigTransport() },
            catalogue = { id -> if (id == TEST_RIG_ID) pushDescriptor("usb_serial") else null },
            moduleScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
        )

        val states = withTimeout(2_000) {
            val collected = mutableListOf<RigLinkProbeState>()
            bridge.probe(TEST_RIG_ID, RigTransportKind.USB_SERIAL, emptyMap()).collect { collected += it }
            collected
        }

        assertEquals(listOf(RigLinkProbeState.Opening), states.filterIsInstance<RigLinkProbeState.Opening>())
        val failed = states.filterIsInstance<RigLinkProbeState.Failed>().single()
        assertTrue(failed.reason.contains("capture", ignoreCase = true))
        assertTrue("must never open a transport while capture runs", states.none { it is RigLinkProbeState.Open })
    }

    @Test
    @Requirement("FR-RIG-3")
    public fun `happy path over a fake RigTransport reaches Verified with the declared capabilities`() = runTest {
        val transport = FakeRigTransport()
        val bridge = DefaultRigLinkBridge(
            context = context,
            transportFactory = RigTransportFactory { _, _, _ -> transport },
            catalogue = { id -> if (id == TEST_RIG_ID) pushDescriptor("usb_serial") else null },
            moduleScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
        )

        val states = withTimeout(2_000) {
            val collected = mutableListOf<RigLinkProbeState>()
            bridge.probe(TEST_RIG_ID, RigTransportKind.USB_SERIAL, emptyMap()).collect { state ->
                collected += state
                if (state is RigLinkProbeState.Opening) transport.pushUnsolicited("BY 0,1")
                if (state is RigLinkProbeState.Verified || state is RigLinkProbeState.Failed) return@collect
            }
            collected
        }

        assertEquals(RigLinkProbeState.Opening, states[0])
        assertEquals(RigLinkProbeState.Open, states[1])
        assertTrue(states.any { it is RigLinkProbeState.Identified && it.rigId == TEST_RIG_ID })
        val verified = states.filterIsInstance<RigLinkProbeState.Verified>().single()
        assertEquals(setOf(RigCapability.SQUELCH_STATE, RigCapability.SUB_BAND), verified.capabilities)
    }

    @Test
    @Requirement("FR-RIG-3")
    public fun `an unknown rig id fails, naming both the requested rig id and the transport`() = runTest {
        val bridge = DefaultRigLinkBridge(
            context = context,
            transportFactory = RigTransportFactory { _, _, _ -> FakeRigTransport() },
            catalogue = { null },
            moduleScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
        )

        val failed = withTimeout(2_000) {
            bridge.probe("no-such-rig", RigTransportKind.USB_SERIAL, emptyMap())
                .first { it is RigLinkProbeState.Failed } as RigLinkProbeState.Failed
        }

        assertTrue("must name the requested rig id: ${failed.reason}", failed.reason.contains("no-such-rig"))
        assertTrue("must name the requested transport: ${failed.reason}", failed.reason.contains("USB_SERIAL"))
    }

    // Register R-808 follow-up: deliberately NOT converted to runTest/UnconfinedTestDispatcher,
    // unlike the three cases above. This case and the USB detach case below each hold a real
    // transport (BluetoothSppTransport/UsbSerialTransport) whose own internal reconnect loop runs
    // genuinely concurrently with this test on a real OS thread -- the whole point of each test is
    // that real-thread property (a busy-spin-starvation risk here; a genuine connect-vs-detach race
    // below), which an unconfined test dispatcher's eager, single-threaded interleaving would
    // either mask or trivially reorder, proving nothing. Both stay on runBlocking with real
    // dispatchers, exactly as `:rig`'s own busy-spin guard test does for the same reason.
    @Test
    @Requirement("FR-CAP-5", "F23")
    public fun `no Bluetooth permission reports NoPermission, never Identified`() = runBlocking {
        val link = FakeBluetoothLink()
        link.denyConnectPermission()
        // A dedicated OS thread for the transport's own connect-retry loop, isolated from
        // Dispatchers.Default -- but deliberately NOT shared with DescriptorRigModule's own
        // moduleScope (left on the production default): DescriptorRigModule's readJob busy-polls
        // readLine() with no genuine suspension whenever the transport is not Open (a pre-existing
        // :rig characteristic, not introduced here), which starves a single-threaded dispatcher
        // outright if both loops share it -- confirmed by reproducing a 100%-deterministic timeout
        // that way. Dispatchers.Default's multiple worker threads are what make that survivable in
        // production and in every other test in this suite; the wide timeout below is the honest
        // cost of depending on that pool's own scheduling under a busy build machine.
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val transport = BluetoothSppTransport("AA:BB:CC:DD:EE:FF", link, '\n', dispatcher = dispatcher)
            val bridge = DefaultRigLinkBridge(
                context = context,
                transportFactory = RigTransportFactory { _, _, _ -> transport },
                catalogue = { id -> if (id == TEST_RIG_ID) pollDescriptor("bluetooth_spp") else null },
            )

            val states = withTimeout(10_000) {
                val collected = mutableListOf<RigLinkProbeState>()
                bridge.probe(TEST_RIG_ID, RigTransportKind.BLUETOOTH_SPP, emptyMap()).collect { state ->
                    collected += state
                    if (state is RigLinkProbeState.NoPermission || state is RigLinkProbeState.Failed) return@collect
                }
                collected
            }

            assertTrue(states.contains(RigLinkProbeState.NoPermission))
            assertTrue("no permission must never reach Identified", states.none { it is RigLinkProbeState.Identified })
        } finally {
            dispatcher.close()
        }
    }

    @Test
    @Requirement("FR-RIG-7", "FR-RIG-15")
    public fun `a USB detach before any reply lands reports Lost, never Identified`() = runBlocking {
        val port = FakeUsbSerialPort()
        val device = UsbDeviceHandle(vendorId = 0x0483, productId = 0x5740, deviceName = "test-cdc-acm")
        port.attach(device)
        port.grantPermission(device)
        // Deliberately no scriptReply -- the rig never gets a chance to identify before it drops.
        val lineConfig = UsbSerialLineConfig(
            baudRate = 9_600,
            dataBits = 8,
            stopBits = 1,
            parity = UsbSerialParity.NONE,
            lineTerminator = '\n',
        )
        // A dedicated OS thread, never shared with Dispatchers.Default -- see the matching comment
        // on the Bluetooth permission test above for why.
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val transport = UsbSerialTransport(
                device.vendorId,
                device.productId,
                lineConfig,
                port,
                dispatcher = dispatcher,
            )
            val bridge = DefaultRigLinkBridge(
                context = context,
                transportFactory = RigTransportFactory { _, _, _ -> transport },
                catalogue = { id -> if (id == TEST_RIG_ID) pollDescriptor("usb_serial") else null },
            )

            // The real UsbSerialTransport connects on its own background coroutine, genuinely
            // concurrently with this test -- detaching the moment this bridge's own `Open` state is
            // observed would race that background connect attempt (observed: it can still be
            // mid-connectOnce(), so the device would already read as "not found" rather than
            // detached). Watching the transport's own state directly for the real Open transition
            // is the deterministic signal; this test holds the transport instance precisely so it can.
            // moduleScope stays on the production default (Dispatchers.Default) -- see the matching
            // comment on the Bluetooth permission test above for why it must not share this test's
            // own single-thread transport dispatcher.
            val states = withTimeout(10_000) {
                coroutineScope {
                    launch {
                        transport.state.first { it is org.ort.rig.TransportState.Open }
                        port.detach(device)
                    }
                    val collected = mutableListOf<RigLinkProbeState>()
                    bridge.probe(TEST_RIG_ID, RigTransportKind.USB_SERIAL, emptyMap()).collect { collected += it }
                    collected
                }
            }

            val lost = states.filterIsInstance<RigLinkProbeState.Lost>().singleOrNull()
            assertTrue("expected a Lost state, got: $states", lost != null)
            assertEquals(UsbSerialTransport.Reason.DETACHED, lost!!.reason)
            assertTrue(
                "a drop before any reply must never Identify",
                states.none { it is RigLinkProbeState.Identified },
            )
        } finally {
            dispatcher.close()
        }
    }
}
