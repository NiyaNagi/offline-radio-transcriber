package org.ort.app.ui.data

import android.content.Context
import org.ort.data.OrtDatabase

/**
 * R-010 (ui-conformance-plan WP3): the drawer's Stations/Frequencies counts — real, global
 * figures `ReaderPolling.drawerBadges` (WP4's file, outside this package's ownership row) does not
 * supply. `Menu.dc.html` shows both beside every other row's mono count, unconditional on any
 * running session — the same real DAO reads `ReaderPolling.listStationSummaries`/
 * `listFrequencySummaries` already use for the full Stations/Frequencies screens, just the
 * `.size` a drawer badge needs rather than the fully mapped list.
 *
 * The live-bar fallback this object used to carry ([liveBar], reading `CaptureState`/
 * `ThermalStatus`/`StorageForecast` directly) is gone — `ui/data/LiveBarPolling.kt` (WP4's real
 * read path) landed on this branch, and `OrtNavHost` now calls `LiveBarPolling.current` directly.
 * See `CHANGELOG.md`'s WP3 addendum for the reconciliation.
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
}
