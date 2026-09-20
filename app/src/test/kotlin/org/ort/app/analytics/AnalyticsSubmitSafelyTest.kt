package org.ort.app.analytics

import org.junit.Test
import org.junit.runner.RunWith
import org.ort.telemetry.AnalyticsEventFactory
import org.ort.telemetry.AnalyticsTier1Payload
import org.robolectric.RobolectricTestRunner

/**
 * A dedicated, single-test class — Robolectric gives every test class its own sandboxed
 * classloader, so [AnalyticsAppWiring] here is genuinely untouched by any other test's
 * `configureOnce()` call. This is the one place that proves the case
 * [AnalyticsAppWiringTest]'s own shared-state test methods cannot reliably prove: a call site
 * reached before [AnalyticsAppWiring.configureOnce] has ever run in this process at all —
 * exactly what every one of this change's new instrumentation call sites (`CorrectionAnalytics`,
 * `SetupFunnelAnalytics`, `FeatureUsageAnalytics`, `QualityStatsReporter`) risks the moment a
 * test exercises them without also wiring analytics first, which most of this app's existing
 * tests do not and should not have to.
 */
@RunWith(RobolectricTestRunner::class)
class AnalyticsSubmitSafelyTest {

    @Test
    fun `FR_ANL_13_submitSafely never throws before configureOnce has ever run`() {
        AnalyticsAppWiring.submitSafely {
            AnalyticsEventFactory.tier1(AnalyticsAppWiring.baseProvenance(), AnalyticsTier1Payload.Usage("NOW", "VIEW"))
        }
        // Reaching this line at all is the assertion: neither reading AnalyticsAppWiring.baseProvenance()
        // (which reads the unset installIdStore) nor AnalyticsAppWiring.submit (the unset controller)
        // is allowed to crash the caller.
    }
}
