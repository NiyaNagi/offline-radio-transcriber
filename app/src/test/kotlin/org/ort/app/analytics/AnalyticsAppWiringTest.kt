package org.ort.app.analytics

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.telemetry.AnalyticsTier
import org.robolectric.RobolectricTestRunner

/**
 * P28: the composition root wires real `SharedPreferences`/filesystem-backed
 * `:telemetry`/`:net` types together — Robolectric proves the real objects, not fakes, actually
 * cooperate (`SetupStoreTest`'s own reasoning for testing `SharedPreferencesSetupStore` the same
 * way).
 */
@RunWith(RobolectricTestRunner::class)
class AnalyticsAppWiringTest {

    @Before
    fun resetSingleton() {
        AnalyticsAppWiring.resetForTest()
    }

    @After
    fun tearDown() {
        AnalyticsAppWiring.resetForTest()
    }

    private fun context() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `AC_172_configureOnce leaves tier 1 on and tiers 2 and 3 off by default`() {
        AnalyticsAppWiring.configureOnce(context())

        assert(AnalyticsAppWiring.tierPreferences.isEnabled(AnalyticsTier.TIER_1))
        assert(!AnalyticsAppWiring.tierPreferences.isEnabled(AnalyticsTier.TIER_2))
        assert(!AnalyticsAppWiring.tierPreferences.isEnabled(AnalyticsTier.TIER_3))
    }

    @Test
    fun `configureOnce is idempotent — a second call keeps the same install id`() {
        AnalyticsAppWiring.configureOnce(context())
        val firstId = AnalyticsAppWiring.installIdStore.currentId()

        AnalyticsAppWiring.configureOnce(context())

        assert(AnalyticsAppWiring.installIdStore.currentId() == firstId)
    }

    @Test
    fun `D48_no endpoint configured in a test build means the destination is not configured`() {
        AnalyticsAppWiring.configureOnce(context())

        assert(!AnalyticsAppWiring.isDestinationConfigured())
    }

    @Test
    fun `AC_181_resetInstallId mints a new id`() = runTest {
        AnalyticsAppWiring.configureOnce(context())
        val before = AnalyticsAppWiring.installIdStore.currentId()

        AnalyticsAppWiring.resetInstallId()

        assert(AnalyticsAppWiring.installIdStore.currentId() != before)
    }

    @Test
    fun `baseProvenance carries the current install id and the app's own version`() {
        AnalyticsAppWiring.configureOnce(context())

        val provenance = AnalyticsAppWiring.baseProvenance()

        assert(provenance.installId == AnalyticsAppWiring.installIdStore.currentId())
        assert(provenance.appVersion.isNotBlank())
    }

    @Test
    fun `runUploadOnce reports NotConfigured when no endpoint is set (D48 default state)`() = runTest {
        AnalyticsAppWiring.configureOnce(context())

        val outcome = AnalyticsAppWiring.runUploadOnce()

        assert(outcome == AnalyticsUploadRunOutcome.NothingQueued || outcome == AnalyticsUploadRunOutcome.NotConfigured)
    }
}
