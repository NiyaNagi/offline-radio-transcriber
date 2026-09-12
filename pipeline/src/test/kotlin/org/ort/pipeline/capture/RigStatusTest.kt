package org.ort.pipeline.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ort.rig.RigCapability
import org.ort.rig.RigHealthIssue
import org.ort.rig.RigTransportKind
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

    @Test
    @Requirement("FR-RIG-1")
    fun `a caller that predates WPC2 and omits transport and descriptor still compiles and reads null for both`() {
        RigStatus.connected("TH-D75A", emptyList())
        val state = RigStatus.state
        assertTrue(state is RigStatus.State.Connected)
        state as RigStatus.State.Connected
        assertNull(state.transportKind)
        assertNull(state.descriptorId)
    }

    @Test
    @Requirement("FR-RIG-14")
    fun `WPC2_connected carries the transport kind and descriptor id so the UI never re-derives them`() {
        RigStatus.connected(
            descriptor = "Kenwood TH-D75A",
            bands = listOf(RigStatus.BandState("A", 145_230_000L, "FM", squelchOpen = true)),
            transportKind = RigTransportKind.BLUETOOTH_SPP,
            descriptorId = "kenwood-thd75a",
        )
        val state = RigStatus.state
        assertTrue(state is RigStatus.State.Connected)
        state as RigStatus.State.Connected
        assertEquals(RigTransportKind.BLUETOOTH_SPP, state.transportKind)
        assertEquals("kenwood-thd75a", state.descriptorId)
    }

    @Test
    @Requirement("FR-RIG-7")
    fun `WPC2_stale carries the transport kind and descriptor id forward from lastKnown`() {
        val lastKnown = RigStatus.State.Connected(
            descriptor = "Kenwood TH-D75A",
            bands = listOf(RigStatus.BandState("A", 145_230_000L, "FM", squelchOpen = true)),
            transportKind = RigTransportKind.USB_SERIAL,
            descriptorId = "kenwood-thd75a",
        )
        RigStatus.stale(lastKnown, sinceMillis = 999L)
        val state = RigStatus.state
        assertTrue(state is RigStatus.State.Stale)
        state as RigStatus.State.Stale
        assertEquals(RigTransportKind.USB_SERIAL, state.lastKnown.transportKind)
        assertEquals("kenwood-thd75a", state.lastKnown.descriptorId)
    }

    // R-1019 (WPRIG2): RigStatus.State.Connected must be able to carry a partial-verification
    // fact -- see this class's own report for why S12 could not render it before this.

    @Test
    @Requirement("FR-RIG-1")
    fun `a caller that predates R-1019 and omits verification reads Unknown, never a false Full`() {
        RigStatus.connected("TH-D75A", emptyList())
        val state = RigStatus.state
        assertTrue(state is RigStatus.State.Connected)
        assertEquals(RigVerification.Unknown, (state as RigStatus.State.Connected).verification)
    }

    @Test
    @Requirement("FR-RIG-1")
    fun `R_1019 connected carries a Full verification when the caller states every capability was seen`() {
        RigStatus.connected("TH-D75A", emptyList(), verification = RigVerification.Full)
        val state = RigStatus.state as RigStatus.State.Connected
        assertEquals(RigVerification.Full, state.verification)
    }

    @Test
    @Requirement("FR-RIG-1")
    fun `R_1019 connected carries a Partial verification naming exactly what was never observed`() {
        val missing = setOf(RigCapability.SIGNAL_STRENGTH, RigCapability.MEMORY_CHANNEL)
        RigStatus.connected("TH-D75A", emptyList(), verification = RigVerification.Partial(missing))
        val state = RigStatus.state as RigStatus.State.Connected
        assertEquals(RigVerification.Partial(missing), state.verification)
    }

    @Test
    @Requirement("FR-RIG-7")
    fun `R_1019 stale carries the partial verification forward from lastKnown, unchanged`() {
        val lastKnown = RigStatus.State.Connected(
            descriptor = "TH-D75A",
            bands = emptyList(),
            verification = RigVerification.Partial(setOf(RigCapability.SIGNAL_STRENGTH)),
        )
        RigStatus.stale(lastKnown, sinceMillis = 1L)
        val state = RigStatus.state as RigStatus.State.Stale
        assertEquals(RigVerification.Partial(setOf(RigCapability.SIGNAL_STRENGTH)), state.lastKnown.verification)
    }

    // R-1015 (WPRIG2): RigStatus.State.Stale must distinguish a health-timeout silence from a
    // transport-reported loss -- reusing RigHealthIssue, the vocabulary DescriptorRigModule's own
    // health() already emits, rather than inventing a parallel one.

    @Test
    @Requirement("FR-RIG-15")
    fun `a caller that predates R-1015 and omits issue reads null, never a guessed reason`() {
        val lastKnown = RigStatus.State.Connected("TH-D75A", emptyList())
        RigStatus.stale(lastKnown, sinceMillis = 1L)
        val state = RigStatus.state as RigStatus.State.Stale
        assertNull(state.issue)
    }

    @Test
    @Requirement("FR-RIG-15")
    fun `R_1015 stale carries RigHealthIssue TIMEOUT when the caller states the rig went silent, not lost`() {
        val lastKnown = RigStatus.State.Connected("TH-D75A", emptyList())
        RigStatus.stale(lastKnown, sinceMillis = 1L, issue = RigHealthIssue.TIMEOUT)
        val state = RigStatus.state as RigStatus.State.Stale
        assertEquals(RigHealthIssue.TIMEOUT, state.issue)
    }

    @Test
    @Requirement("FR-RIG-15")
    fun `R_1015 stale carries RigHealthIssue TRANSPORT_LOST distinctly from TIMEOUT`() {
        val lastKnown = RigStatus.State.Connected("TH-D75A", emptyList())
        RigStatus.stale(lastKnown, sinceMillis = 1L, issue = RigHealthIssue.TRANSPORT_LOST)
        val state = RigStatus.state as RigStatus.State.Stale
        assertEquals(RigHealthIssue.TRANSPORT_LOST, state.issue)
    }
}
