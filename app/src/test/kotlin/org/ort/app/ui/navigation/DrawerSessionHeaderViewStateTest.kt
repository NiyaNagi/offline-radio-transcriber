package org.ort.app.ui.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.pipeline.capture.RigStatus
import org.ort.testing.Requirement

/** R-010 (ui-conformance-plan WP3): the drawer's session/rig header, `Menu.dc.html`'s own figures. */
class DrawerSessionHeaderViewStateTest {

    @Test
    @Requirement("R-010")
    fun `no session label falls back to Tonight`() {
        val state = DrawerSessionHeaderViewState.from(sessionLabel = null, rigState = RigStatus.State.Absent)

        assertEquals("Tonight", state.title)
    }

    @Test
    @Requirement("R-010")
    fun `a blank session label also falls back to Tonight, never an empty title`() {
        val state = DrawerSessionHeaderViewState.from(sessionLabel = "   ", rigState = RigStatus.State.Absent)

        assertEquals("Tonight", state.title)
    }

    @Test
    @Requirement("R-010")
    fun `a real session label is used verbatim`() {
        val state = DrawerSessionHeaderViewState.from(
            sessionLabel = "Repeater watch",
            rigState = RigStatus.State.Absent,
        )

        assertEquals("Repeater watch", state.title)
    }

    @Test
    @Requirement("R-010")
    fun `an absent rig reads no radio, never a fabricated device name`() {
        val state = DrawerSessionHeaderViewState.from(sessionLabel = null, rigState = RigStatus.State.Absent)

        assertEquals("no radio", state.rigLabel)
    }

    @Test
    @Requirement("R-010")
    fun `a connected rig with two bands reads both bands`() {
        val state = DrawerSessionHeaderViewState.from(
            sessionLabel = null,
            rigState = RigStatus.State.Connected(
                descriptor = "TH-D75A",
                bands = listOf(
                    RigStatus.BandState("A", 145_230_000L, "FM", squelchOpen = true),
                    RigStatus.BandState("B", 146_960_000L, "FM", squelchOpen = false),
                ),
            ),
        )

        assertEquals("TH-D75A · both bands", state.rigLabel)
    }

    @Test
    @Requirement("R-010")
    fun `a connected rig with one band names that band, not both bands`() {
        val state = DrawerSessionHeaderViewState.from(
            sessionLabel = null,
            rigState = RigStatus.State.Connected(
                descriptor = "TH-D75A",
                bands = listOf(RigStatus.BandState("A", 145_230_000L, "FM", squelchOpen = true)),
            ),
        )

        assertEquals("TH-D75A · band A", state.rigLabel)
    }

    @Test
    @Requirement("R-010")
    fun `a stale rig reads its last-known descriptor and bands, not that it is stale`() {
        val lastKnown = RigStatus.State.Connected(
            descriptor = "TH-D75A",
            bands = listOf(
                RigStatus.BandState("A", 145_230_000L, "FM", squelchOpen = true),
                RigStatus.BandState("B", 146_960_000L, "FM", squelchOpen = false),
            ),
        )
        val state = DrawerSessionHeaderViewState.from(
            sessionLabel = null,
            rigState = RigStatus.State.Stale(lastKnown, sinceMillis = 0L),
        )

        assertEquals("TH-D75A · both bands", state.rigLabel)
    }
}
