package org.ort.app.analytics

import org.ort.app.ui.navigation.ReaderDestination
import org.ort.telemetry.AnalyticsEventFactory
import org.ort.telemetry.AnalyticsTier1Payload

/**
 * FR-ANL-2's "usage and feature events — which screens and actions were used, never their
 * content". [screenViewed] is the one call site [org.ort.app.ui.navigation.OrtNavHost] needs for
 * "which screens": a [ReaderDestination]'s own closed enum name, never a transmission, station or
 * callsign the screen happens to be showing when it is reached.
 *
 * **Left open**: per-action usage (which button, not just which screen). This app has no central
 * action-dispatch chokepoint today (confirmed by search before writing this) — every screen wires
 * its own `on*` callbacks directly — so covering "actions" as well as "screens" would mean adding
 * a submit call to dozens of independent call sites across many files this change does not
 * otherwise touch, a much larger and riskier surface than this unit's scope. Reported as left open
 * rather than half-wired into a handful of arbitrarily chosen actions.
 */
public object FeatureUsageAnalytics {

    public fun screenViewed(destination: ReaderDestination) {
        AnalyticsAppWiring.submitSafely {
            AnalyticsEventFactory.tier1(
                AnalyticsAppWiring.baseProvenance(),
                AnalyticsTier1Payload.Usage(screen = destination.name, action = ACTION_VIEW),
            )
        }
    }

    private const val ACTION_VIEW = "VIEW"
}
