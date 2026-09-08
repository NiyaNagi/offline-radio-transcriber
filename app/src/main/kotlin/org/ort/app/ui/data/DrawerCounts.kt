package org.ort.app.ui.data

import android.content.Context
import org.ort.app.ui.components.LiveBarTone
import org.ort.app.ui.components.LiveBarViewState
import org.ort.data.OrtDatabase
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus

/**
 * R-010 (ui-conformance-plan WP3): the drawer's Stations/Frequencies counts — real, global
 * figures `ReaderPolling.drawerBadges` (WP4's file, outside this package's ownership row) does not
 * supply. `Menu.dc.html` shows both beside every other row's mono count, unconditional on any
 * running session — the same real DAO reads `ReaderPolling.listStationSummaries`/
 * `listFrequencySummaries` already use for the full Stations/Frequencies screens, just the
 * `.size` a drawer badge needs rather than the fully mapped list.
 */
public data class DrawerCountsViewState(public val stationCount: Int, public val frequencyCount: Int) {
    public companion object {
        public val ZERO: DrawerCountsViewState = DrawerCountsViewState(stationCount = 0, frequencyCount = 0)
    }
}

public object DrawerCounts {

    public suspend fun current(context: Context): DrawerCountsViewState {
        val db = OrtDatabase.create(context.applicationContext)
        return DrawerCountsViewState(
            stationCount = db.activityDao().listStations().size,
            frequencyCount = db.activityDao().listDistinctFrequencies().size,
        )
    }

    /**
     * R-022's fallback read path: this prompt's brief names `ui/data/LiveBarPolling.kt` (WP4,
     * built concurrently) as the real one, wired instead the moment it exists on this branch — it
     * does not today (confirmed by search before writing this). Until then, this reads the same
     * process-wide holders [org.ort.app.ui.data.ReaderPolling.currentStatus] already reads
     * ([CaptureState], [ThermalStatus]) plus [StorageForecast], honestly: this package has no real
     * audio level or Pass A partial-text signal to read, so [LiveBarViewState.level] stays empty
     * and [LiveBarViewState.partialText] stays `null` here rather than either being invented.
     * `null` overall means "no live bar" — the caller pins nothing.
     */
    public fun liveBar(sessionId: String?): LiveBarViewState? {
        if (sessionId == null || CaptureState.sessionId != sessionId) return null
        return when (CaptureState.state) {
            CaptureState.State.Idle -> null
            CaptureState.State.Capturing -> nominalOrDegraded()
            is CaptureState.State.Interrupted -> LiveBarViewState(
                level = emptyList(),
                partialText = null,
                label = "Interrupted",
                tone = LiveBarTone.DEGRADED,
            )
            is CaptureState.State.Failed -> LiveBarViewState(
                level = emptyList(),
                partialText = null,
                label = "Halted",
                tone = LiveBarTone.HALTED,
            )
        }
    }

    /** Capture is genuinely running; thermal/storage may still make it a degraded (never red) bar. */
    private fun nominalOrDegraded(): LiveBarViewState {
        val thermal = ThermalStatus.state
        val storage = StorageForecast.state
        val label = when {
            thermal is ThermalStatus.State.Hot -> "Thermal"
            thermal is ThermalStatus.State.Warm -> "Warm"
            storage is StorageForecast.State.OneNightLeft -> "Storage low"
            storage is StorageForecast.State.ThreeNightsLeft -> "Storage low"
            else -> "Live"
        }
        return LiveBarViewState(
            level = emptyList(),
            partialText = null,
            label = label,
            tone = if (label == "Live") LiveBarTone.NOMINAL else LiveBarTone.DEGRADED,
        )
    }
}
