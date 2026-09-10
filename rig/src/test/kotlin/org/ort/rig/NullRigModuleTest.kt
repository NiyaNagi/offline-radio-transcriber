package org.ort.rig

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** FR-RIG-2: manual frequency entry, first-class, not a fallback. */
class NullRigModuleTest {

    @Test
    fun `FR_RIG_2 capabilities are empty and connect always succeeds`() {
        val module = NullRigModule()

        assertEquals(emptySet<RigCapability>(), module.capabilities(RigTransportKind.NONE))
        assertTrue(module.connect(RigTransportKind.NONE, emptyMap()).isSuccess)
    }

    @Test
    fun `FR_RIG_11 a descriptor error is carried, inspectable rather than silent`() {
        val module = NullRigModule(descriptorError = "unknown transport kind 'carrier_pigeon'")

        assertTrue(module.descriptorError!!.contains("carrier_pigeon"))
    }

    @Test
    fun `FR_RIG_8_9 manual frequency entry is observable with provenance fresh`() = runBlocking {
        val module = NullRigModule()

        module.setManualFrequencyHz(frequencyHz = 146_520_000L, timestampNanos = 42L)
        val state = module.observe().first()

        assertEquals(146_520_000L, state.frequencyHz)
        assertEquals(RigStateConfidence.FRESH, state.sourceConfidence)
    }

    @Test
    fun `before any manual entry the state is stale with no frequency`() = runBlocking {
        val module = NullRigModule()

        val state = module.observe().first()

        assertNull(state.frequencyHz)
        assertEquals(RigStateConfidence.STALE, state.sourceConfidence)
    }
}
