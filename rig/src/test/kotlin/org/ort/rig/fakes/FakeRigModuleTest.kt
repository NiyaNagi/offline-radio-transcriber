package org.ort.rig.fakes

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.rig.RigHealth
import org.ort.rig.RigState
import org.ort.rig.RigStateConfidence
import org.ort.rig.RigTransportKind

class FakeRigModuleTest {

    @Test
    fun `connect succeeds by default and can be scripted to fail`() {
        val ok = FakeRigModule()
        assertTrue(ok.connect(RigTransportKind.NONE, emptyMap()).isSuccess)
        assertTrue(ok.connected)

        val failing = FakeRigModule()
        failing.scriptConnectFailure("no such device")
        val result = failing.connect(RigTransportKind.NONE, emptyMap())

        assertTrue(result.isFailure)
        assertFalse(failing.connected)
        assertEquals("no such device", result.exceptionOrNull()?.message)
    }

    @Test
    fun `emitted state and health reach observers`() = runTest {
        val module = FakeRigModule()
        val state = RigState(timestampNanos = 1L, sourceConfidence = RigStateConfidence.FRESH)

        module.observe().test {
            module.emit(state)
            assertEquals(state, awaitItem())
        }
        module.health().test {
            module.emitHealth(RigHealth.Healthy(1L))
            assertEquals(RigHealth.Healthy(1L), awaitItem())
        }
    }
}
