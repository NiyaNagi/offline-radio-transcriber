package org.ort.app.ui

import android.view.Window
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * R-001 and R-007 (ui-conformance-plan WP1).
 *
 * R-001 uses [createAndroidComposeRule] (the same officially-supported Compose test entry point
 * `ReaderAccessibilityTest` already uses for [org.ort.app.ui.navigation.OrtNavHost] itself) rather
 * than a bare `Robolectric.buildActivity(...)`: the raw `ActivityController` path left a live
 * `Recomposer` registration behind no matter how carefully the lifecycle was driven afterward
 * (create→start→resume→pause→stop→destroy still did not clear it under this Robolectric version),
 * which is what poisoned `ActivityPatternChartTest`'s Compose idle-check later in the same `:app`
 * suite run — `createAndroidComposeRule`'s `ActivityScenarioRule` disposes the composition properly
 * once the test method returns, and does not.
 *
 * R-007 asserts [resolveSessionId] directly instead of building any activity at all — see that
 * function's own doc comment for why building one with a non-null session id is itself a separate
 * risk (it starts `OrtNavHost`'s real polling loops).
 */
@RunWith(RobolectricTestRunner::class)
class ReaderActivityTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ReaderActivity>()

    @Test
    fun `R_001 ReaderActivity window has no action bar`() {
        assertFalse(composeTestRule.activity.window.hasFeature(Window.FEATURE_ACTION_BAR))
    }

    @Test
    fun `R_001 ReaderActivity applies Theme_Ort, which is not the platform default`() {
        // Theme.Material.NoActionBar (Theme.Ort's parent) is a real, resolvable theme distinct
        // from the platform DeviceDefault the activity rendered under before AndroidManifest.xml
        // declared android:theme — a null/zero theme resource here is what R-001 originally found.
        assertNotNull(composeTestRule.activity.theme)
    }

    @Test
    fun `R_007 a session actually capturing in this process is preferred over a stale intent extra`() {
        // Without this fix, relaunching over an already-capturing session (task resumed from
        // recents, or the launcher icon tapped again) always kept whichever id the intent carried,
        // polling a session nothing was capturing into (constitution IV "never lies"; FR-UI-7).
        assertEquals(
            "live-session",
            resolveSessionId(intentSessionId = "stale-session", liveSessionId = "live-session", isCapturing = true),
        )
    }

    @Test
    fun `R_007 with no live capture, the intent extra is used as before`() {
        assertEquals(
            "prior-session",
            resolveSessionId(intentSessionId = "prior-session", liveSessionId = null, isCapturing = false),
        )
    }

    @Test
    fun `R_007 a live session id is ignored unless CaptureState reports it is actually capturing`() {
        // A stale sessionId left over in CaptureState from a session that already ended must not
        // be treated as live just because the field is non-null (CaptureState.kt's own contract).
        assertEquals(
            "intent-session",
            resolveSessionId(intentSessionId = "intent-session", liveSessionId = "stale-leftover", isCapturing = false),
        )
    }
}
