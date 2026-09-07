package org.ort.app.status

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StatusActivityTest {

    @Test
    fun `render shows state, elapsed, count and gaps, hides the banner when there is none`() {
        val activity = Robolectric.buildActivity(StatusActivity::class.java).create().get()
        activity.render(
            StatusViewState(
                stateLabel = "Capturing",
                elapsedLabel = "00:05:00",
                transmissionCount = 3,
                gapCount = 1,
                shedLevel = 0,
                shedLevelLabel = "Nominal",
                livenessLabel = "Alive (heartbeat current)",
                uncleanEndBanner = null,
            ),
        )

        assertEquals(android.view.View.GONE, activity.bannerView.visibility)
        assertEquals("Capturing", activity.stateView.text.toString())
        assertEquals("3 transmissions", activity.countView.text.toString())
        assertEquals("1 gaps", activity.gapView.text.toString())
    }

    @Test
    fun `render shows the unclean-end banner when one is present`() {
        val activity = Robolectric.buildActivity(StatusActivity::class.java).create().get()
        activity.render(
            StatusViewState(
                stateLabel = "Idle",
                elapsedLabel = "00:00:00",
                transmissionCount = 0,
                gapCount = 0,
                shedLevel = 0,
                shedLevelLabel = "Nominal",
                livenessLabel = "Not responding (heartbeat stale)",
                uncleanEndBanner = "The previous session ended unexpectedly.",
            ),
        )

        assertEquals(android.view.View.VISIBLE, activity.bannerView.visibility)
        assertEquals("The previous session ended unexpectedly.", activity.bannerView.text.toString())
    }
}
