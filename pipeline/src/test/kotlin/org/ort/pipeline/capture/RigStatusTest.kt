package org.ort.pipeline.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

/** register R-104: the rig module (FR-RIG) is unbuilt (R-084), so [RigStatus.Absent] is the only real state today. */
class RigStatusTest {

    @BeforeEach
    fun reset() {
        RigStatus.reset()
    }

    @Test
    @Requirement("F-009", "R-104")
    fun `before anything is configured, the rig is reported Absent`() {
        assertEquals(RigStatus.State.Absent, RigStatus.state)
    }

    @Test
    @Requirement("F-009", "R-104")
    fun `F9_connected_carries_the_descriptor_and_per_band_facts`() {
        RigStatus.connected(
            "TH-D75A",
            listOf(
                RigStatus.BandState("A", 145_230_000L, "FM", squelchOpen = true),
                RigStatus.BandState("B", 146_960_000L, "FM", squelchOpen = false),
            ),
        )
        val state = RigStatus.state
        assertTrue(state is RigStatus.State.Connected)
        state as RigStatus.State.Connected
        assertEquals("TH-D75A", state.descriptor)
        assertEquals(2, state.bands.size)
        assertTrue(state.bands[0].squelchOpen)
        assertTrue(!state.bands[1].squelchOpen)
    }

    @Test
    @Requirement("F-009", "R-104")
    fun `F9_stale_carries_the_last_known_connection_and_since_when_it_stopped_reporting`() {
        val lastKnown = RigStatus.State.Connected(
            "TH-D75A",
            listOf(RigStatus.BandState("A", 145_230_000L, "FM", squelchOpen = true)),
        )
        RigStatus.stale(lastKnown, sinceMillis = 12_345L)

        val state = RigStatus.state
        assertTrue(state is RigStatus.State.Stale)
        state as RigStatus.State.Stale
        assertEquals(lastKnown, state.lastKnown)
        assertEquals(12_345L, state.sinceMillis)
    }

    @Test
    @Requirement("F-009", "R-104")
    fun `reset returns to Absent`() {
        RigStatus.connected("TH-D75A", emptyList())
        RigStatus.reset()
        assertEquals(RigStatus.State.Absent, RigStatus.state)
    }
}
