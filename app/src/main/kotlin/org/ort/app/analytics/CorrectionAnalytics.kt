package org.ort.app.analytics

import org.ort.telemetry.AnalyticsEventFactory
import org.ort.telemetry.AnalyticsTier2Payload

/**
 * FR-ANL-3's tier-2 `(ASR hypothesis, user correction)` pair — opt-in, off by default
 * ([org.ort.telemetry.AnalyticsController.submit]'s own doc comment: dropped before ever reaching
 * the queue while tier 2 is off, never queued-then-filtered).
 *
 * The only correction this codebase can make today is a callsign/attribution correction
 * ([org.ort.app.ui.data.CorrectionPolling.applyCorrection]) — there is no free-text transcript-edit
 * flow yet for a caller to report a literal ASR-transcript-vs-corrected-transcript pair from. So
 * [corrected] reports the previous and new *callsigns* as the hypothesis/correction pair, the
 * realisation of FR-ANL-3's pair this build can actually produce — the same two fields
 * [AnalyticsTier2Payload.Correction]'s own field-vocabulary test (`AC_173`) already fixes as its
 * exact shape.
 */
public object CorrectionAnalytics {

    public fun corrected(previousStationId: String?, newStationId: String) {
        AnalyticsAppWiring.submitSafely {
            AnalyticsEventFactory.tier2(
                AnalyticsAppWiring.baseProvenance(),
                AnalyticsTier2Payload.Correction(
                    asrHypothesis = previousStationId ?: UNKNOWN_HYPOTHESIS,
                    userCorrection = newStationId,
                    callsign = newStationId,
                ),
            )
        }
    }

    private const val UNKNOWN_HYPOTHESIS = "UNKNOWN"
}
