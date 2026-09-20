package org.ort.app.analytics

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** FR-ANL-2/FR-ANL-10/FR-AST-11: [SetupFunnelAnalytics] is a thin wrapper over the tier-1
 * `SetupFunnel` payload; this proves each of its four call shapes actually reaches the real
 * queue (on by default, AC-172), gated the identical way every other tier-1 call site is. */
@RunWith(RobolectricTestRunner::class)
class SetupFunnelAnalyticsTest {

    @Before
    fun configure() {
        AnalyticsAppWiring.configureOnce(ApplicationProvider.getApplicationContext<Application>())
    }

    @After
    fun tearDown() {
        AnalyticsAppWiring.resetForTest()
    }

    @Test
    fun `FR_ANL_2_reached submits an event`() = runTest {
        SetupFunnelAnalytics.reached("WELCOME")
        assertEquals(AnalyticsUploadRunOutcome.NotConfigured, AnalyticsAppWiring.runUploadOnce())
    }

    @Test
    fun `FR_ANL_2_completed submits an event`() = runTest {
        SetupFunnelAnalytics.completed("READY")
        assertEquals(AnalyticsUploadRunOutcome.NotConfigured, AnalyticsAppWiring.runUploadOnce())
    }

    @Test
    fun `FR_ANL_2_skipped submits an event`() = runTest {
        SetupFunnelAnalytics.skipped("NOTIFICATIONS")
        assertEquals(AnalyticsUploadRunOutcome.NotConfigured, AnalyticsAppWiring.runUploadOnce())
    }

    @Test
    fun `FR_AST_11_modelDownloadOutcome submits an event for each outcome`() = runTest {
        SetupFunnelAnalytics.modelDownloadOutcome(succeeded = true)
        assertEquals(AnalyticsUploadRunOutcome.NotConfigured, AnalyticsAppWiring.runUploadOnce())

        SetupFunnelAnalytics.modelDownloadOutcome(succeeded = false, retrying = true)
        assertEquals(AnalyticsUploadRunOutcome.NotConfigured, AnalyticsAppWiring.runUploadOnce())

        SetupFunnelAnalytics.modelDownloadOutcome(succeeded = false)
        assertEquals(AnalyticsUploadRunOutcome.NotConfigured, AnalyticsAppWiring.runUploadOnce())
    }
}
