package org.ort.pipeline.digest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.llm.FakeLlmEngine
import org.ort.llm.LlmState

class ProseDigestSettingsTest {

    @Test
    fun `FR_DIG_3b_disabling_releases_the_engine_and_moves_it_to_Unloaded`() {
        val engine = FakeLlmEngine()
        engine.loadBehavior = FakeLlmEngine.LoadBehavior.SUCCEED
        // Simulate an already-resident engine the way a real Ready state would look.
        engine.residentBytesOnReady = 500_000_000L
        val settings = ProseDigestSettings(InMemoryProseDigestSettingsStore(initiallyEnabled = true))

        settings.setEnabled(false, engine)

        assertEquals(LlmState.Unloaded, engine.state.value)
        assertEquals(1, engine.releaseCallCount)
        assertEquals(false, settings.enabled.value)
    }

    @Test
    fun `enabling again does not itself load the engine`() {
        val engine = FakeLlmEngine()
        val settings = ProseDigestSettings(InMemoryProseDigestSettingsStore(initiallyEnabled = false))

        settings.setEnabled(true, engine)

        assertEquals(true, settings.enabled.value)
        assertEquals(0, engine.loadCallCount)
        assertEquals(LlmState.Unloaded, engine.state.value)
    }

    @Test
    fun `enabled starts at the constructor value`() {
        assertTrue(ProseDigestSettings(InMemoryProseDigestSettingsStore(initiallyEnabled = true)).enabled.value)
        assertEquals(
            false,
            ProseDigestSettings(InMemoryProseDigestSettingsStore(initiallyEnabled = false)).enabled.value,
        )
    }

    @Test
    fun `disabling persists through the store, so a later ProseDigestSettings sees it`() {
        val engine = FakeLlmEngine()
        val store = InMemoryProseDigestSettingsStore(initiallyEnabled = true)

        ProseDigestSettings(store).setEnabled(false, engine)

        assertEquals(false, ProseDigestSettings(store).enabled.value)
    }

    @Test
    fun `default no-arg construction is enabled, matching D36 and FR-DIG-3b`() {
        assertTrue(ProseDigestSettings().enabled.value)
    }
}
