@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.ort.rig.descriptor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.rig.RigBand
import org.ort.rig.RigState
import org.ort.rig.RigTransportKind
import org.ort.rig.fakes.FakeRigTransport

internal suspend fun awaitStateWhere(
    module: DescriptorRigModule,
    timeoutMs: Long = 2_000,
    predicate: (RigState) -> Boolean,
): RigState {
    val result = withTimeoutOrNull(timeoutMs) {
        var state = module.observe().first()
        while (!predicate(state)) {
            delay(20)
            state = module.observe().first()
        }
        state
    }
    return requireNotNull(result) { "no matching RigState observed within ${timeoutMs}ms" }
}

/**
 * The bundled TH-D75A descriptor (FR-RIG-3, §9.3) against [FakeRigTransport] playing the
 * reference's own example replies (`docs/reference/th-d75a-cat.md`). Closes E2-B0x rows for the
 * TH-D75A: band-scoped state (D23), identical capabilities over both transports (AC-133), and
 * squelch attributed to the band that actually opened.
 */
class ThD75aDescriptorTest {

    @Test
    fun `AC_133_parity capabilities are identical over USB serial and Bluetooth SPP`() {
        val module = DescriptorRigModule(BundledDescriptors.kenwoodThD75a(), { _, _, _ -> FakeRigTransport() })

        val usb = module.capabilities(RigTransportKind.USB_SERIAL)
        val bluetooth = module.capabilities(RigTransportKind.BLUETOOTH_SPP)

        assertTrue(usb.isNotEmpty())
        assertEquals(usb, bluetooth)
        assertEquals(setOf(RigTransportKind.USB_SERIAL, RigTransportKind.BLUETOOTH_SPP), module.transports)
    }

    @Test
    fun `both bands report their own frequency from AI push lines`() = runTest {
        val transport = FakeRigTransport()
        val module = DescriptorRigModule(
            BundledDescriptors.kenwoodThD75a(),
            { _, _, _ -> transport },
            readTimeoutMs = 60,
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
        )
        try {
            module.connect(RigTransportKind.USB_SERIAL, emptyMap())

            transport.pushUnsolicited("FQ 0,0014250000")
            val bandA = awaitStateWhere(module) { it.band == RigBand.A && it.frequencyHz != null }
            assertEquals(14_250_000L, bandA.frequencyHz)

            transport.pushUnsolicited("FQ 1,0014460000")
            val bandB = awaitStateWhere(module) { it.band == RigBand.B && it.frequencyHz != null }
            assertEquals(14_460_000L, bandB.frequencyHz)

            // The descriptor's poll block is a resync fallback (docs/reference/th-d75a-cat.md)
            // and fires its first cycle immediately alongside AI -- the readings above came from
            // the push lines themselves regardless, since they carry the values asserted above
            // and arrived before any poll reply could plausibly be scripted (none was).
            assertTrue(transport.commandsSent.contains("AI 1"))
        } finally {
            module.disconnect()
        }
    }

    @Test
    fun `D23 a BY change on band B attributes squelch to B, not A`() = runTest {
        val transport = FakeRigTransport()
        val module = DescriptorRigModule(
            BundledDescriptors.kenwoodThD75a(),
            { _, _, _ -> transport },
            readTimeoutMs = 60,
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
        )
        try {
            module.connect(RigTransportKind.USB_SERIAL, emptyMap())

            transport.pushUnsolicited("BY 1,1")
            val bandB = awaitStateWhere(module) { it.band == RigBand.B && it.squelchOpen == true }

            assertEquals(RigBand.B, bandB.band)
            assertEquals(true, bandB.squelchOpen)
        } finally {
            module.disconnect()
        }
    }
}
