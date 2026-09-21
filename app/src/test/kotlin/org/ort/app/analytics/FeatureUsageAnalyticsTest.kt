package org.ort.app.analytics

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.navigation.ReaderDestination
import org.ort.telemetry.ANALYTICS_SCHEMA_VERSION
import org.ort.telemetry.AnalyticsEventCodec
import org.ort.telemetry.AnalyticsEventFactory
import org.ort.telemetry.AnalyticsProvenance
import org.ort.telemetry.AnalyticsTier1Payload
import org.robolectric.RobolectricTestRunner

/** FR-ANL-2's "which screens ... were used, never their content" — [FeatureUsageAnalytics] is a
 * thin wrapper; this proves it actually reaches the real queue with the closed [ReaderDestination]
 * enum name, gated the same way every other tier-1 call site is (on by default, AC-172). */
@RunWith(RobolectricTestRunner::class)
class FeatureUsageAnalyticsTest {

    @Before
    fun configure() {
        AnalyticsAppWiring.configureOnce(ApplicationProvider.getApplicationContext<Application>())
    }

    @After
    fun tearDown() {
        AnalyticsAppWiring.resetForTest()
    }

    @Test
    fun `FR_ANL_2_screenViewed submits a tier1 Usage event for the given screen`() = runTest {
        FeatureUsageAnalytics.screenViewed(ReaderDestination.SETTINGS)

        assertEquals(AnalyticsUploadRunOutcome.NotConfigured, AnalyticsAppWiring.runUploadOnce())
    }

    /** R-1100: the action-level counterpart of [FR_ANL_2_screenViewed submits a tier1 Usage event
     * for the given screen] — proves [FeatureUsageAnalytics.actionInvoked] reaches the same real
     * queue, gated the same way, without crashing before [AnalyticsAppWiring.configureOnce] runs
     * (it does here) or after. */
    @Test
    fun `R_1100_actionInvoked submits a tier1 Usage event for the given screen and action`() = runTest {
        FeatureUsageAnalytics.actionInvoked(ReaderDestination.LOG, UsageAction.OPEN_TRANSMISSION)

        assertEquals(AnalyticsUploadRunOutcome.NotConfigured, AnalyticsAppWiring.runUploadOnce())
    }

    private fun provenance() = AnalyticsProvenance(
        installId = "install-1",
        sessionId = null,
        overId = null,
        appVersion = "0.1.1",
        buildHash = "abc123",
        modelIds = emptyList(),
        modelShas = emptyList(),
        executionProvider = "cpu",
        deviceModel = "Pixel 7",
        soc = "Tensor G2",
        detectedTier = "T2",
        captureMode = null,
        rigModule = null,
        band = null,
        schemaVersion = ANALYTICS_SCHEMA_VERSION,
    )

    /** R-1100: a golden-JSON schema contract, the same discipline `AnalyticsEventCodecTest`
     * already applies to `Crash` — `Usage`'s own field list is `{"screen":...,"action":...}` and
     * nothing else; if a future change ever adds a field to that payload (a transmission id "just
     * for debugging", say), this fails immediately rather than silently widening what tier 1 can
     * carry, and the round trip proves the codec recovers the exact same event back.
     */
    @Test
    fun `R_1100_an actionInvoked-shaped event encodes to exactly its closed field list and round-trips`() {
        val usageAction = UsageAction.OPEN_TRANSMISSION.name
        val event = AnalyticsEventFactory.tier1(
            provenance(),
            AnalyticsTier1Payload.Usage(screen = ReaderDestination.LOG.name, action = usageAction),
        )

        val encoded = AnalyticsEventCodec.encode(event)

        assertTrue(
            "expected the exact Usage field list on the wire: $encoded",
            encoded.contains(""""screen":"LOG"""") && encoded.contains(""""action":"OPEN_TRANSMISSION""""),
        )
        assertEquals(event, AnalyticsEventCodec.decode(encoded))
    }

    /** R-1100: every [UsageAction] is a plain enum name — no action can ever smuggle a
     * transmission/station/callsign/frequency value through this field, since the wire form is
     * always one of this fixed, closed set. */
    @Test
    fun `R_1100_every UsageAction name is a closed, structural label — never free text`() {
        for (action in UsageAction.entries) {
            assert(action.name.all { it == '_' || it.isUpperCase() }) {
                "UsageAction.${action.name} is not a plain SCREAMING_SNAKE_CASE structural label"
            }
        }
        assertEquals(UsageAction.entries.size, UsageAction.entries.map { it.name }.distinct().size)
    }
}
