package org.ort.rig.descriptor

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.rig.RigCapability
import org.ort.rig.RigTransportKind
import org.ort.rig.fakes.FakeRigTransport

/** The bundled generic ASCII CAT descriptor (FR-RIG-14's second row: frequency and mode only,
 * both transports). Illustrates the level-1 declarative path for a rig whose family is known
 * but whose exact descriptor is not (FR-RIG-18). */
class GenericAsciiCatDescriptorTest {

    @Test
    fun `capabilities are FREQUENCY and MODE on both transports`() {
        val module = DescriptorRigModule(BundledDescriptors.genericAsciiCat(), { _, _, _ -> FakeRigTransport() })

        val expected = setOf(RigCapability.FREQUENCY, RigCapability.MODE)
        assertEquals(expected, module.capabilities(RigTransportKind.USB_SERIAL))
        assertEquals(expected, module.capabilities(RigTransportKind.BLUETOOTH_SPP))
    }

    @Test
    fun `FA and MD poll replies are parsed, mode translated through the lookup table`() = runBlocking {
        val transport = FakeRigTransport()
        transport.scriptReply("FA;", "FA00014250000;")
        transport.scriptReply("MD;", "MD2;")
        val module = DescriptorRigModule(
            BundledDescriptors.genericAsciiCat(),
            { _, _, _ -> transport },
            readTimeoutMs = 60,
        )
        try {
            module.connect(RigTransportKind.USB_SERIAL, emptyMap())

            val withMode = awaitStateWhere(module) { it.mode != null }

            assertEquals("USB", withMode.mode)
            assertEquals(14_250_000L, withMode.frequencyHz)
        } finally {
            module.disconnect()
        }
    }
}
