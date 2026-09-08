package org.ort.app.ui.navigation

import org.ort.pipeline.capture.RigStatus

/**
 * The drawer's session header (`Menu.dc.html`: "Repeater watch" / "TH-D75A · both bands") — R-010
 * (ui-conformance-plan WP3).
 *
 * [title] falls back to `"Tonight"` because no prompt has ever given
 * [org.ort.data.entity.SessionEntity] a label field — confirmed by reading its full column set
 * (`id`, `startedAt`, `endedAt`, `profileId`, `deviceTier`, `appVersion`, `terminationReason`,
 * `sourceId`, `schemaVersion`, `gapCount`, `shedEvents`; nothing else). [from]'s `sessionLabel`
 * parameter is real and honoured the moment such a field exists — until then every caller passes
 * `null` and this always reads `"Tonight"`, rather than this type fabricating a label from
 * whatever else happens to be on hand.
 *
 * [rigLabel] reads the real, process-wide [RigStatus] (F9, register R-104): `"no radio"` for
 * [RigStatus.State.Absent] — [RealCaptureService][org.ort.pipeline.capture.RealCaptureService]'s
 * only real production value today, since the rig module (FR-RIG) is unbuilt (register R-084) —
 * and the real descriptor plus band count for [RigStatus.State.Connected]/[RigStatus.State.Stale]
 * (the latter's *last-known* facts — staleness itself is `Fail-Rig.dc.html`'s own screen's job,
 * not this one-line summary's).
 */
public data class DrawerSessionHeaderViewState(public val title: String, public val rigLabel: String) {
    public companion object {
        public fun from(sessionLabel: String?, rigState: RigStatus.State): DrawerSessionHeaderViewState =
            DrawerSessionHeaderViewState(
                title = sessionLabel?.trim()?.takeIf { it.isNotEmpty() } ?: "Tonight",
                rigLabel = rigLabelFor(rigState),
            )

        private fun rigLabelFor(rigState: RigStatus.State): String = when (rigState) {
            RigStatus.State.Absent -> "no radio"
            is RigStatus.State.Connected -> "${rigState.descriptor} · ${bandsLabel(rigState.bands)}"
            is RigStatus.State.Stale -> "${rigState.lastKnown.descriptor} · ${bandsLabel(rigState.lastKnown.bands)}"
        }

        private fun bandsLabel(bands: List<RigStatus.BandState>): String = when (bands.size) {
            0 -> "no bands"
            1 -> "band ${bands[0].band}"
            2 -> "both bands"
            else -> "${bands.size} bands"
        }
    }
}
