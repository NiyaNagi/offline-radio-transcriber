package org.ort.rig.fakes

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.ort.rig.RigTransportException
import org.ort.rig.TransportState

/**
 * One test per failure mode [FakeRigTransport] can be told to exhibit (constitution II: "a fake
 * that cannot be told to fail, hang or return a hallucination is a stub").
 */
class FakeRigTransportTest {

    @Test
    fun `failToOpen makes open throw and reports Lost`() {
        val transport = FakeRigTransport()
        transport.failToOpen()

        assertThrows(RigTransportException::class.java) { transport.open() }

        runTest {
            val state = transport.state.first()
            assertEquals(TransportState.Lost("failed to open"), state)
        }
    }

    @Test
    fun `hangOnNextRead never returns without an external timeout`() = runTest {
        val transport = FakeRigTransport()
        transport.open()
        transport.hangOnNextRead()

        val result = withTimeoutOrNull(50) { transport.readLine(5_000) }

        assertNull(result, "a hung read must not resolve on its own")
    }

    @Test
    fun `dropMidStream reports Lost and further reads return null`() = runTest {
        val transport = FakeRigTransport()
        transport.open()

        transport.dropMidStream("cable pulled")

        val state = transport.state.first()
        assertEquals(TransportState.Lost("cable pulled"), state)
        assertNull(transport.readLine(100))
    }

    @Test
    fun `scripted reply is delivered when its exact command is written`() = runTest {
        val transport = FakeRigTransport()
        transport.scriptReply("FQ 0", "FQ 0,0014250000")
        transport.open()

        transport.write("FQ 0")
        val line = transport.readLine(1_000)

        assertEquals("FQ 0,0014250000", line)
        assertEquals(listOf("FQ 0"), transport.commandsSent)
    }

    @Test
    fun `garbage reply is delivered like any other line`() = runTest {
        val transport = FakeRigTransport()
        transport.scriptGarbage("FQ 0")
        transport.open()

        transport.write("FQ 0")
        val line = transport.readLine(1_000)

        assertEquals("@@GARBAGE@@", line)
    }

    @Test
    fun `pushUnsolicited arrives with no matching write`() = runTest {
        val transport = FakeRigTransport()
        transport.open()

        transport.pushUnsolicited("BY 0,1")
        val line = transport.readLine(1_000)

        assertEquals("BY 0,1", line)
        assertEquals(emptyList<String>(), transport.commandsSent)
    }
}
