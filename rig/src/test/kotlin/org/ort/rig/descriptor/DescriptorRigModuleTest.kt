package org.ort.rig.descriptor

import kotlinx.coroutines.flow.first
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
import org.ort.rig.RigTransportKind
import org.ort.rig.fakes.FakeRigTransport
import org.ort.testing.TestClock

private const val TEST_READ_TIMEOUT_MS = 60L

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
            { _, _ -> transport },
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
            { _, _ -> transport },
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
            { _, _ -> transport },
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
            { _, _ -> transport },
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
            { _, _ -> transport },
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
}
