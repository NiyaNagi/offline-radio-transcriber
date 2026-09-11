package org.ort.app.ui

import android.view.Window
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.setup.SetupActivity
import org.ort.app.ui.setup.SetupStep
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.core.SystemClock
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.digest.ForegroundActivityTracker
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

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

    // WPF (checklist row E2-F08, F23): a process-wide holder this class's own new cases below set
    // — reset here, not just relied on to be clean, the same discipline every other test in this
    // suite that touches a process-wide signal already follows (`FailureHostTest.resetHolders`,
    // `ReaderActivityDestinationSmokeTest`'s per-case `finally` blocks).
    @After
    fun resetInputStatus() {
        InputStatus.reset()
    }

    // --- E2-I03 (spec/e2e-capture-modes-plan.md, "owed by WPE") ------------------------------------

    @Test
    fun `E2_I03 the rule's own resumed activity has already marked ForegroundActivityTracker active`() {
        // `createAndroidComposeRule` resumes the activity before this test body ever runs — the
        // same moment `ReaderActivity.onResume` marks the tracker — so this asserts the effect of a
        // resume the test never has to drive itself. Compared against "now", not a "before" this
        // test captured earlier: the rule's own resume already happened before this method started,
        // so a timestamp taken here would already postdate it.
        val recentWindowMillis = 60_000L
        val now = SystemClock.wallMillis()

        val lastActive = ForegroundActivityTracker.lastActiveAtMillis
        assertTrue(
            "expected ForegroundActivityTracker.lastActiveAtMillis ($lastActive) to be within the last " +
                "$recentWindowMillis ms of now ($now)",
            now - lastActive in 0..recentWindowMillis,
        )
    }

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

    // -----------------------------------------------------------------------------------------
    // WPF (checklist row E2-F08): F23's own two recovery actions carry a real `FailureHostActions`
    // lambda in production, not the type's own no-op default — driven end to end through this real,
    // launched `ReaderActivity` (the composeTestRule's own default intent, no `EXTRA_SESSION_ID`, so
    // `resolveSessionId` resolves `null` and starts no session-gated polling loop — the same
    // real-Activity-with-no-session shape `E2_I03`/`R_001`/`R_007` above already use) rather than by
    // constructing `FailureHostActions` directly the way `FailureHostTest`'s own equivalent case
    // does for `FailureHost` in isolation. `InputStatus` is set from inside the test body, after the
    // rule's own `before()` has already launched and resumed the Activity, so this never risks the
    // non-null-session-id polling-loop hazard `resolveSessionId`'s own doc comment records.
    // -----------------------------------------------------------------------------------------

    @Test
    @Requirement("FR-CAP-5")
    fun `FR_CAP_5 F23 Retry now opens Setup's real Input step, not a no-op`() {
        val btDevice = AudioDeviceDescriptor("bt-1", AudioDeviceKind.BLUETOOTH, "Handheld BT")
        InputStatus.opened(btDevice, 16_000, "none", true, true, 0L)
        InputStatus.lost(sinceMillis = 0L)

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Retry now", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Retry now", substring = true).performClick()

        val started = shadowOf(composeTestRule.activity).nextStartedActivity
        assertNotNull("expected onRetryInput to start an Activity, not silently do nothing", started)
        assertEquals(SetupActivity::class.java.name, started!!.component?.className)
        assertEquals(SetupStep.INPUT.name, started.getStringExtra(SetupActivity.EXTRA_STEP))
    }

    @Test
    @Requirement("FR-CAP-5")
    fun `FR_CAP_5 F23 Switch to a wired input opens Setup's real Input step, not a no-op`() {
        val btDevice = AudioDeviceDescriptor("bt-1", AudioDeviceKind.BLUETOOTH, "Handheld BT")
        InputStatus.opened(btDevice, 16_000, "none", true, true, 0L)
        InputStatus.lost(sinceMillis = 0L)

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Switch to a wired input", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Switch to a wired input", substring = true).performClick()

        val started = shadowOf(composeTestRule.activity).nextStartedActivity
        assertNotNull("expected onSwitchToWiredInput to start an Activity, not silently do nothing", started)
        assertEquals(SetupActivity::class.java.name, started!!.component?.className)
        assertEquals(SetupStep.INPUT.name, started.getStringExtra(SetupActivity.EXTRA_STEP))
    }
}
