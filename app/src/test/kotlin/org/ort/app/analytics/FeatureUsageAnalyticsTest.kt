package org.ort.app.analytics

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.navigation.ReaderDestination
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
}
