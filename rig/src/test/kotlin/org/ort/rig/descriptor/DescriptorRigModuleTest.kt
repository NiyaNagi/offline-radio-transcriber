package org.ort.rig.descriptor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.rig.RigBand
import org.ort.rig.RigHealth
import org.ort.rig.RigHealthIssue
import org.ort.rig.RigStateConfidence
import org.ort.rig.RigTransport
import org.ort.rig.RigTransportKind
import org.ort.rig.TransportState
import org.ort.rig.fakes.FakeRigTransport
import org.ort.testing.TestClock
import java.util.concurrent.Executors

private const val TEST_READ_TIMEOUT_MS = 60L

/** A [RigTransport] that never opens -- [readLine] always returns `null` synchronously, exactly
 * as `UsbSerialTransport`/`BluetoothSppTransport` do while `Connecting`/`Lost` (their real
 * `open()` launches an async connect and returns immediately, before the link is actually up).
 * `:rig` cannot depend on `:rig-usb`/`:rig-bluetooth` to reuse those classes directly, so this
 * reproduces the one behaviour that matters for this test. */
private class NeverOpenTransport : RigTransport {
    override val state: Flow<TransportState> = MutableStateFlow(TransportState.Connecting)
    override fun open() = Unit
    override fun close() = Unit
    override fun write(line: String) = Unit
    override suspend fun readLine(timeoutMs: Long): String? = null
}

private fun pollingFrequencyDescriptor(): RigDescriptor = RigDescriptor(
    schemaVersion = 1,
    id = "test-rig",
    displayName = "Test Rig",
    transports = listOf(TransportSpec(kind = "usb_serial", capabilities = listOf("FREQUENCY"))),
    poll = PollSpec(
        // Long enough that a second poll cycle never fires inside one test's real-time window.
        intervalMs = 60_000,
        commands = listOf(CommandSpec(send = "FQ", expect = "^FQ(\\d{10})$", map = mapOf("frequencyHz" to "$1"))),
    ),
)

private fun pushOnlyDescriptor(): RigDescriptor = RigDescriptor(
    schemaVersion = 1,
    id = "push-rig",
    displayName = "Push Rig",
    transports = listOf(TransportSpec(kind = "usb_serial", capabilities = listOf("SQUELCH_STATE", "SUB_BAND"))),
    unsolicited = UnsolicitedSpec(
        enable = "AI 1",
        patterns = listOf(PatternSpec(expect = "^BY (\\d),(\\d)$", map = mapOf("band" to "$1", "squelchOpen" to "$2"))),
    ),
)

/**
 * FR-RIG-4/FR-RIG-6/FR-RIG-7 against [FakeRigTransport]: hang, garbage, drop and push, plus the
 * mid-transmission correlation FR-RIG-6 requires.
 */
class DescriptorRigModuleTest {

    @Test
    fun `FR_RIG_1_health_hang a hung transport is rescued by the module's own watchdog`() = runBlocking {
        val transport = FakeRigTransport()
        // Set BEFORE connect so the very first read is guaranteed to be the hung one -- no race.
        transport.hangOnNextRead()
        val module = DescriptorRigModule(
            pollingFrequencyDescriptor(),
            { _, _, _ -> transport },
            readTimeoutMs = TEST_READ_TIMEOUT_MS,
        )
        try {
            module.connect(RigTransportKind.USB_SERIAL, emptyMap())

            val health = withTimeoutOrNull(2_000) { module.health().first() }

            assertNotNull(health, "a hung read must still surface a health event via the module's own watchdog")
            assertTrue(health is RigHealth.Degraded && health.issue == RigHealthIssue.TIMEOUT)
        } finally {
            module.disconnect()
        }
    }

    @Test
    fun `FR_RIG_11_garbage a garbage reply emits no state and health says so`() = runBlocking {
        val transport = FakeRigTransport()
        transport.scriptGarbage("FQ", "NOPE THIS IS NOT A REPLY")
        val module = DescriptorRigModule(
            pollingFrequencyDescriptor(),
            { _, _, _ -> transport },
            readTimeoutMs = TEST_READ_TIMEOUT_MS,
        )
        try {
            module.connect(RigTransportKind.USB_SERIAL, emptyMap())

            val health = withTimeoutOrNull(2_000) { module.health().first() }
            assertTrue(health is RigHealth.Degraded && health.issue == RigHealthIssue.UNPARSEABLE_RESPONSE)

            val state = withTimeoutOrNull(200) { module.observe().first() }
            assertNull(state, "garbage must never be turned into a RigState")
        } finally {
            module.disconnect()
        }
    }

    @Test
    fun `FR_RIG_7_drop a dropped transport marks the last state stale and reports TRANSPORT_LOST`() = runBlocking {
        val transport = FakeRigTransport()
        transport.scriptReply("FQ", "FQ0014250000")
        val module = DescriptorRigModule(
            pollingFrequencyDescriptor(),
            { _, _, _ -> transport },
            readTimeoutMs = TEST_READ_TIMEOUT_MS,
        )
        try {
            module.connect(RigTransportKind.USB_SERIAL, emptyMap())

            val fresh = withTimeoutOrNull(2_000) { module.observe().first() }
            assertNotNull(fresh)
            assertEquals(RigStateConfidence.FRESH, fresh!!.sourceConfidence)
            assertEquals(14_250_000L, fresh.frequencyHz)

            transport.dropMidStream("cable pulled")

            val health = withTimeoutOrNull(2_000) { module.health().first() }
            assertTrue(health is RigHealth.Degraded && health.issue == RigHealthIssue.TRANSPORT_LOST)

            val stale = withTimeoutOrNull(2_000) { module.observe().first() }
            assertNotNull(stale)
            assertEquals(RigStateConfidence.STALE, stale!!.sourceConfidence)
            assertEquals(
                14_250_000L,
                stale.frequencyHz,
                "the value itself is carried forward, only the confidence changes",
            )
        } finally {
            module.disconnect()
        }
    }

    @Test
    fun `FR_RIG_1_push a push line updates state with no poll ever sent`() = runBlocking {
        val transport = FakeRigTransport()
        val module = DescriptorRigModule(
            pushOnlyDescriptor(),
            { _, _, _ -> transport },
            readTimeoutMs = TEST_READ_TIMEOUT_MS,
        )
        try {
            module.connect(RigTransportKind.USB_SERIAL, emptyMap())

            transport.pushUnsolicited("BY 0,1")
            val state = withTimeoutOrNull(2_000) { module.observe().first() }

            assertNotNull(state)
            assertEquals(RigBand.A, state!!.band)
            assertEquals(true, state.squelchOpen)
            assertEquals(
                listOf("AI 1"),
                transport.commandsSent,
                "only the unsolicited enable was ever written -- no poll command",
            )
        } finally {
            module.disconnect()
        }
    }

    @Test
    fun `FR_RIG_6 a mid-transmission rig change is flagged, an unchanged one is not`() = runBlocking {
        val transport = FakeRigTransport()
        val clock = TestClock(startMonotonicNanos = 1_000_000_000L)
        val module = DescriptorRigModule(
            pushOnlyDescriptor(),
            { _, _, _ -> transport },
            clock = clock,
            readTimeoutMs = TEST_READ_TIMEOUT_MS,
        )
        try {
            module.connect(RigTransportKind.USB_SERIAL, emptyMap())

            // t=1_000_000_000: squelch opens on band A.
            transport.pushUnsolicited("BY 0,1")
            val first = withTimeoutOrNull(2_000) { module.observe().first() }
            assertNotNull(first)
            val startNanos = first!!.timestampNanos

            // A transmission that starts and ends entirely within this one reading: unchanged.
            val unchanged = module.stateForTransmission(RigBand.A, startNanos, startNanos + 10)
            assertNotNull(unchanged)
            assertEquals(false, unchanged!!.changedDuringTransmission)

            // Advance the clock and push a second change -- this one lands mid-transmission.
            clock.advanceNanos(500)
            transport.pushUnsolicited("BY 0,0")
            val second = withTimeoutOrNull(2_000) {
                var s = module.observe().first()
                // first() alone could re-observe the replayed `first` value if we raced the emit;
                // poll until the squelch value actually flips.
                var attempts = 0
                while (s.squelchOpen != false && attempts < 50) {
                    kotlinx.coroutines.delay(20)
                    s = module.observe().first()
                    attempts++
                }
                s
            }
            assertNotNull(second)
            assertEquals(false, second!!.squelchOpen)

            val changed = module.stateForTransmission(RigBand.A, startNanos, second.timestampNanos + 10)
            assertNotNull(changed)
            assertEquals(true, changed!!.changedDuringTransmission)
        } finally {
            module.disconnect()
        }
    }

    @Test
    fun `D23 squelchOpenedAtNanos reports null when the band has never been open`() = runBlocking {
        val transport = FakeRigTransport()
        val module = DescriptorRigModule(
            pushOnlyDescriptor(),
            { _, _, _ -> transport },
            readTimeoutMs = TEST_READ_TIMEOUT_MS,
        )
        try {
            module.connect(RigTransportKind.USB_SERIAL, emptyMap())
            transport.pushUnsolicited("BY 0,0")
            awaitStateWhere(module) { it.band == RigBand.A && it.squelchOpen == false }

            assertNull(module.squelchOpenedAtNanos(RigBand.A, Long.MAX_VALUE / 2))
            assertNull(module.squelchOpenedAtNanos(RigBand.B, Long.MAX_VALUE / 2))
        } finally {
            module.disconnect()
        }
    }

    @Test
    fun `D23 squelchOpenedAtNanos reports the timestamp of the earliest contiguous open reading`() = runBlocking {
        val transport = FakeRigTransport()
        val clock = TestClock(startMonotonicNanos = 1_000_000_000L)
        val module = DescriptorRigModule(
            pushOnlyDescriptor(),
            { _, _, _ -> transport },
            clock = clock,
            readTimeoutMs = TEST_READ_TIMEOUT_MS,
        )
        try {
            module.connect(RigTransportKind.USB_SERIAL, emptyMap())

            transport.pushUnsolicited("BY 0,1")
            val opened = awaitStateWhere(module) { it.band == RigBand.A && it.squelchOpen == true }
            val openedAtNanos = opened.timestampNanos

            // A later, still-open reading for the same band must not move "opened since" forward --
            // the contiguous run started at the FIRST open reading, not the most recent one.
            clock.advanceNanos(500)
            transport.pushUnsolicited("BY 0,1")
            awaitStateWhere(module) { it.band == RigBand.A && it.timestampNanos > openedAtNanos }

            assertEquals(openedAtNanos, module.squelchOpenedAtNanos(RigBand.A, Long.MAX_VALUE / 2))
        } finally {
            module.disconnect()
        }
    }

    @Test
    fun `WPC3 connect passes this transport's matching TransportSpec to the factory`() = runBlocking {
        val transport = FakeRigTransport()
        var seenSpec: TransportSpec? = null
        val descriptor = BundledDescriptors.kenwoodThD75a()
        val module = DescriptorRigModule(
            descriptor,
            { kind, spec, _ ->
                if (kind == RigTransportKind.USB_SERIAL) seenSpec = spec
                transport
            },
        )
        try {
            module.connect(RigTransportKind.USB_SERIAL, emptyMap())
            assertEquals(
                descriptor.transports.single { it.kind == "usb_serial" },
                seenSpec,
                "the factory must see the descriptor's own USB TransportSpec, not just the kind",
            )
        } finally {
            module.disconnect()
        }
    }

    @Test
    fun `WPC3 the read loop yields even when the transport never opens, so it never starves its scope`() = runBlocking {
        // A single, dedicated thread: if readJob busy-spins (no genuine suspension point when
        // readLine() returns null synchronously, as a not-yet-Open real transport does), NOTHING
        // else on this one-thread scope -- including the plain probe coroutine below -- ever gets
        // to run at all. This is exactly what WPC3's RigLinkBridgeTest reproduced against the real
        // UsbSerialTransport/BluetoothSppTransport: a deterministic, permanent timeout.
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val singleThreadScope = CoroutineScope(SupervisorJob() + dispatcher)
            val module = DescriptorRigModule(
                pollingFrequencyDescriptor(),
                { _, _, _ -> NeverOpenTransport() },
                scope = singleThreadScope,
                readTimeoutMs = 10,
            )
            try {
                module.connect(RigTransportKind.USB_SERIAL, emptyMap())

                val probeRan = withTimeoutOrNull(5_000) {
                    singleThreadScope.launch {}.join()
                    true
                }

                assertEquals(
                    true,
                    probeRan,
                    "a plain coroutine on the same single-thread scope must still get to run -- " +
                        "readJob must not be an uncancellable, unyielding busy-spin",
                )
            } finally {
                module.disconnect()
            }
        } finally {
            dispatcher.close()
        }
    }
}
