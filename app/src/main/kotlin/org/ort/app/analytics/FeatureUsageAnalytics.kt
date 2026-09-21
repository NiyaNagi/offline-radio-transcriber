package org.ort.app.analytics

import org.ort.app.ui.navigation.ReaderDestination
import org.ort.telemetry.AnalyticsEventFactory
import org.ort.telemetry.AnalyticsTier1Payload

/**
 * FR-ANL-2's "usage and feature events — which screens and actions were used, never their
 * content". [screenViewed] is the call site [org.ort.app.ui.navigation.OrtNavHost] needs for
 * "which screens": a [ReaderDestination]'s own closed enum name, never a transmission, station or
 * callsign the screen happens to be showing when it is reached.
 *
 * **R-1100: [actionInvoked] closes the "actions" half.** The app has no central dispatch for
 * arbitrary UI actions — every screen wires its own `on*` callbacks directly — so this does not
 * attempt to cover every button in the tree; that really would mean a submit call at dozens of
 * independent, cross-package call sites, exactly the "much larger and riskier surface" this file
 * used to describe. What the app *does* have, and always has, is one real chokepoint for
 * navigation-shaped actions: [org.ort.app.ui.navigation.NavHostCallbacks], the single struct
 * every "open a drill-in / switch destination / filter the Log" action already funnels through on
 * its way from a tap to a state change. [org.ort.app.ui.navigation.instrumentedNavHostCallbacks]
 * wraps that one struct once, so every action in [UsageAction] is reported at its one real origin
 * rather than at each of the dozen-plus screens that happen to trigger it.
 *
 * [action] is always one of [UsageAction]'s closed set, never a screen's own free-text label —
 * the same closed-field discipline [screen] already has as a [ReaderDestination] enum name, so
 * this can never carry a transmission id, station id, callsign or frequency even though several
 * of the actions themselves take one as an argument (see [UsageAction]'s own doc comment).
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

    /** R-1100: which feature was used, on which screen — never the transmission/station/callsign
     * argument the real call site carries alongside it (see [UsageAction]'s own doc comment). */
    public fun actionInvoked(destination: ReaderDestination, action: UsageAction) {
        AnalyticsAppWiring.submitSafely {
            AnalyticsEventFactory.tier1(
                AnalyticsAppWiring.baseProvenance(),
                AnalyticsTier1Payload.Usage(screen = destination.name, action = action.name),
            )
        }
    }

    private const val ACTION_VIEW = "VIEW"
}

/**
 * R-1100: the closed set of navigation-shaped feature actions
 * [org.ort.app.ui.navigation.instrumentedNavHostCallbacks] reports, one per
 * [org.ort.app.ui.navigation.NavHostCallbacks] field — named for what the operator did
 * ("opened a station"), never for the argument that real call carries (a station id, a
 * transmission id, a time window), which is exactly the tier-1/tier-2 line this codebase already
 * draws everywhere else: an action name is structural, a transmission/station/callsign is
 * content, and only the former may ever reach [FeatureUsageAnalytics.actionInvoked].
 */
public enum class UsageAction {
    OPEN_DRAWER,
    OPEN_SEARCH,
    CLOSE_DRILL_INS,
    OPEN_CAPTURE,
    OPEN_LOG,
    OPEN_TRANSMISSION,
    OPEN_STATION,
    OPEN_FREQUENCY,
    OPEN_THREAD,
    OPEN_STATIONS,
    OPEN_MODELS,
    OPEN_SETTINGS_STORAGE,
    OPEN_LEVEL_METER,
    OPEN_OVERS,
    OPEN_REVIEW_SESSION,
    OPEN_ACTIVATION_THREAD,
    OPEN_STATION_OVERS,
    OPEN_ATTRIBUTED_STATION,
    OPEN_HOUR,
    VIEW_AFFECTED_OVERS,
    OPEN_CHANGED_OVERS,
    OPEN_RECORDING_SESSION,
    OPEN_RECORDING_SESSION_LOG,
}
