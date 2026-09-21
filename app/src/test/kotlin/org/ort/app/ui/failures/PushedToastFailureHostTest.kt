package org.ort.app.ui.failures

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.pipeline.capture.CaptureState
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * Register R-1008 ("app-wide", `Feedback.dc.html`): [FailureHost] actually wires
 * [PushedToastChannel] to its own toast slot — the Compose-level half of the proof;
 * [PushedToastChannelTest] proves the channel's own "not lost, not shown twice" guarantees
 * without Compose at all.
 */
@RunWith(RobolectricTestRunner::class)
class PushedToastFailureHostTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        CaptureState.idle(clearSession = true)
        PushedToastChannel.resetForTest()
    }

    @After
    fun tearDown() {
        CaptureState.idle(clearSession = true)
        PushedToastChannel.resetForTest()
    }

    @Test
    @Requirement("R-1008")
    fun `R_1008 a toast pushed after the host is already composed appears in its own toast slot`() {
        composeTestRule.setContent {
            OrtTheme {
                FailureHost(sessionId = null) {
                    Text("underlying destination", modifier = Modifier.fillMaxSize())
                }
            }
        }
        composeTestRule.waitForIdle()

        PushedToastChannel.push(RecoveryToast("propagated-1", "6 overs updated"))

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("failure-toast-propagated-1").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("failure-toast-propagated-1").assertIsDisplayed()
        composeTestRule.onNodeWithText("6 overs updated").assertIsDisplayed()
    }

    /**
     * Register R-1008: this is the row's own scenario, reproduced as directly as a single
     * [FailureHost] composition can — an action's result is pushed *before* the host that will
     * show it has composed at all (standing in for "the operator has already navigated on and a
     * new destination, with a fresh `content`, is what's mounted by the time the result arrives"),
     * and it still shows, once the host exists. [PushedToastChannel]'s own kdoc explains why a
     * `Channel` rather than a `StateFlow`/plain `var` is what makes that true: a push before any
     * collector is running is buffered, not dropped.
     */
    @Test
    @Requirement("R-1008")
    fun `R_1008 a toast pushed before the host has composed at all is not lost, only delayed`() {
        PushedToastChannel.push(RecoveryToast("early", "Corrected to K7LWH"))

        composeTestRule.setContent {
            OrtTheme {
                FailureHost(sessionId = null) {
                    Text("underlying destination", modifier = Modifier.fillMaxSize())
                }
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("failure-toast-early").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Register R-1008: proves the undo contract end to end — [RecoveryToast.onUndo] here is built
     * from `subjectCapturedAtPushTime`, a value already fixed before the push, never from a
     * variable this test mutates afterward — the shape [PushedToastChannel]'s own kdoc says a real
     * caller must follow for Undo to survive a destination change safely. Clicking the toast's own
     * `Undo` (wired through unchanged from the existing [org.ort.app.ui.components.Toast]) must
     * invoke it.
     */
    @Test
    @Requirement("R-1008")
    fun `R_1008 clicking a pushed toast's Undo invokes the closure captured at push time, not live state`() {
        val subjectCapturedAtPushTime = "tx-42"
        var undoneFor: String? = null

        composeTestRule.setContent {
            OrtTheme {
                FailureHost(sessionId = null) {
                    Text("underlying destination", modifier = Modifier.fillMaxSize())
                }
            }
        }
        composeTestRule.waitForIdle()

        PushedToastChannel.push(
            RecoveryToast(
                id = "correction-1",
                message = "Corrected to K7LWH",
                onUndo = { undoneFor = subjectCapturedAtPushTime },
            ),
        )

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("failure-toast-correction-1").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Undo", substring = true).performClick()

        assertEquals("tx-42", undoneFor)
    }

    @Test
    @Requirement("R-1008")
    fun `R_1008 a polled recovery toast still shows with no Undo action, unchanged by this row`() {
        composeTestRule.setContent {
            OrtTheme {
                FailureHost(sessionId = null) {
                    Text("underlying destination", modifier = Modifier.fillMaxSize())
                }
            }
        }
        composeTestRule.waitForIdle()

        // Same path `RecoveryAnnouncer.diff` itself would produce — `onUndo` left at its default.
        PushedToastChannel.push(RecoveryToast("tier", "Back to tier 3"))

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("failure-toast-tier").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "expected no Undo action on a toast whose onUndo is null",
            composeTestRule.onAllNodesWithText("Undo", substring = true).fetchSemanticsNodes().isEmpty(),
        )
    }
}
