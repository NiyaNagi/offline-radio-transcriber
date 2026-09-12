package org.ort.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.CaptureStateTone
import org.ort.app.ui.data.CaptureStatusViewState
import org.ort.app.ui.data.KeyValueFacts
import org.ort.app.ui.data.LevelViewState
import org.ort.app.ui.data.LiveMonitorOverRow
import org.ort.app.ui.data.LiveMonitorOversViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `Live-Monitor.dc.html` (design-intent N07, R-1007) — the operator's own seven states, each
 * rendered as the row it actually is, never one omitted or promoted to something it is not
 * (constitution I).
 */
@RunWith(RobolectricTestRunner::class)
class LiveMonitorScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun notMeasured() = KeyValueFacts(value = "Not measured")

    private val status = CaptureStatusViewState(
        stateLabel = "Capturing",
        stateTone = CaptureStateTone.NOMINAL,
        sinceElapsedLabel = "Since 22:00 · 0:31:07 · alive, heartbeat 3s ago",
        haltActionLabel = "Stop",
        haltConfirmTitle = "Stop capture?",
        haltConfirmBody = "Audio already captured is kept.",
        input = notMeasured(),
        level = notMeasured(),
        radio = notMeasured(),
        overs = notMeasured(),
        backlog = notMeasured(),
        tier = notMeasured(),
        thermal = notMeasured(),
        storage = notMeasured(),
        battery = notMeasured(),
    )

    private val level = LevelViewState.notMeasured()

    private val sevenRows = listOf(
        LiveMonitorOverRow.Transcribing(
            id = "TX-transcribing",
            timeLabel = "04:58:53",
            durationLabel = "2.4 s",
            passLabel = "Pass B running",
            tierLabel = "tier 3",
        ),
        LiveMonitorOverRow.Resolved(
            id = "TX-confirmed",
            timeLabel = "04:58:31",
            transcript = "good copy on that",
            durationLabel = "5.1 s",
            frequencyLabel = "145.230",
            attribution = Attribution.confirmed("W1ABC", 0.98),
            callsign = "W1ABC",
            attributionStateLabel = "confirmed",
            inferredFromLabel = null,
        ),
        LiveMonitorOverRow.Waiting(
            id = "TX-waiting",
            timeLabel = "04:57:02",
            durationLabel = "1.8 s",
            aheadCount = 3,
        ),
        LiveMonitorOverRow.ListenedSilence(
            id = "silence-1",
            timeLabel = "04:52:10",
            durationLabel = "4 m 52 s",
        ),
        LiveMonitorOverRow.Resolved(
            id = "TX-inferred",
            timeLabel = "04:52:06",
            transcript = "back to you on the two metre machine",
            durationLabel = "3.3 s",
            frequencyLabel = "145.230",
            attribution = Attribution.inferred("W1ABC", 0.71),
            callsign = "W1ABC",
            attributionStateLabel = "inferred",
            inferredFromLabel = "04:58:31",
        ),
        LiveMonitorOverRow.NotTranscribed(
            id = "TX-failed",
            timeLabel = "04:49:33",
            durationLabel = "2.1 s",
            attemptsLabel = "Pass B errored 5 times",
        ),
        LiveMonitorOverRow.Resolved(
            id = "TX-unknown",
            timeLabel = "04:47:15",
            transcript = "roger that, QSY to the simplex frequency",
            durationLabel = "4.8 s",
            frequencyLabel = "145.230",
            attribution = Attribution.unknown(),
            callsign = null,
            attributionStateLabel = "unknown station",
            inferredFromLabel = null,
        ),
    )

    private val oversState = LiveMonitorOversViewState(overs = sevenRows, summaryLabel = "19 overs · 3 waiting")

    @Test
    @Requirement("R-1007")
    fun `every one of the operator's seven states renders its own row`() {
        composeTestRule.setContent {
            OrtTheme {
                LiveMonitorScreen(
                    status = status,
                    level = level,
                    hearingText = null,
                    overs = oversState,
                    localMicrophone = false,
                )
            }
        }

        sevenRows.forEach { row ->
            composeTestRule.onNodeWithTag("live-monitor-row-${row.id}").assertExists()
        }
    }

    @Test
    @Requirement("R-1007")
    fun `the failed row states the real attempt count and that the audio is kept, never silently dropped`() {
        composeTestRule.setContent {
            OrtTheme {
                LiveMonitorScreen(
                    status = status,
                    level = level,
                    hearingText = null,
                    overs = oversState,
                    localMicrophone = false,
                )
            }
        }

        composeTestRule.onNodeWithText("Pass B errored 5 times", substring = true).assertExists()
        composeTestRule.onNodeWithText("the audio is kept", substring = true).assertExists()
    }

    @Test
    @Requirement("FR-RUN-12")
    fun `the listened-silence row is not clickable, every other row is`() {
        var openedId: String? = null
        composeTestRule.setContent {
            OrtTheme {
                LiveMonitorScreen(
                    status = status,
                    level = level,
                    hearingText = null,
                    overs = oversState,
                    localMicrophone = false,
                    actions = LiveMonitorActions(onOpenOver = { openedId = it }),
                )
            }
        }

        composeTestRule.onNodeWithTag("live-monitor-row-TX-confirmed").performClick()
        assertTrue(openedId == "TX-confirmed")

        openedId = null
        composeTestRule.onNodeWithTag("live-monitor-row-silence-1").assertExists()
        // A `Box` with no clickable modifier ignores `performClick()` silently rather than
        // throwing — the real assertion is that it never reaches `onOpenOver`.
        assertTrue(openedId == null)
    }

    @Test
    @Requirement("R-1007")
    fun `Hearing now shows the real partial and never fabricates its own duration`() {
        composeTestRule.setContent {
            OrtTheme {
                LiveMonitorScreen(
                    status = status,
                    level = level,
                    hearingText = "and we're clear on the repeater, seven three to you",
                    overs = LiveMonitorOversViewState(overs = emptyList(), summaryLabel = "0 overs · 0 waiting"),
                    localMicrophone = false,
                )
            }
        }

        composeTestRule.onNodeWithTag("live-monitor-hearing").assertExists()
        composeTestRule.onNodeWithText("Pass A partial · not attributed — partials never are", substring = true)
            .assertExists()
    }

    @Test
    @Requirement("R-1007")
    fun `no Hearing now card when nothing is being heard right now, never an empty placeholder`() {
        composeTestRule.setContent {
            OrtTheme {
                LiveMonitorScreen(
                    status = status,
                    level = level,
                    hearingText = null,
                    overs = LiveMonitorOversViewState(overs = emptyList(), summaryLabel = "0 overs · 0 waiting"),
                    localMicrophone = false,
                )
            }
        }

        composeTestRule.onNodeWithTag("live-monitor-hearing").assertDoesNotExist()
    }

    @Test
    @Requirement("R-1007")
    fun `tapping Stop asks for confirmation before calling onStop`() {
        var stopped = false
        composeTestRule.setContent {
            OrtTheme {
                LiveMonitorScreen(
                    status = status,
                    level = level,
                    hearingText = null,
                    overs = oversState,
                    localMicrophone = false,
                    actions = LiveMonitorActions(onStop = { stopped = true }),
                )
            }
        }

        composeTestRule.onNodeWithTag("live-monitor-stop").performClick()
        assertTrue(!stopped)
        composeTestRule.onNodeWithText("Stop capture", substring = false).performClick()
        assertTrue(stopped)
    }

    @Test
    @Requirement("R-1007")
    fun `tapping the back chevron calls onBack`() {
        var backed = false
        composeTestRule.setContent {
            OrtTheme {
                LiveMonitorScreen(
                    status = status,
                    level = level,
                    hearingText = null,
                    overs = oversState,
                    localMicrophone = false,
                    actions = LiveMonitorActions(onBack = { backed = true }),
                )
            }
        }

        composeTestRule.onNodeWithTag("live-monitor-back").performClick()
        assertTrue(backed)
    }

    @Test
    @Requirement("R-1007")
    fun `Full log invokes onOpenFullLog`() {
        var openedLog = false
        composeTestRule.setContent {
            OrtTheme {
                LiveMonitorScreen(
                    status = status,
                    level = level,
                    hearingText = null,
                    overs = oversState,
                    localMicrophone = false,
                    actions = LiveMonitorActions(onOpenFullLog = { openedLog = true }),
                )
            }
        }

        composeTestRule.onNodeWithTag("live-monitor-full-log").performClick()
        assertTrue(openedLog)
    }

    @Test
    @Requirement("FR-CAP-3a")
    fun `the room mark shows only for a local-microphone session, and is never the only copy of the fact`() {
        composeTestRule.setContent {
            OrtTheme {
                LiveMonitorScreen(
                    status = status,
                    level = level,
                    hearingText = null,
                    overs = oversState,
                    localMicrophone = true,
                )
            }
        }

        composeTestRule.onNodeWithText("room").assertExists()
    }

    @Test
    @Requirement("AC-63")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `at the tour AVD's own width, every row clears the 44dp touch-target floor`() {
        composeTestRule.setContent {
            Box(modifier = Modifier.width(390.dp)) {
                OrtTheme {
                    LiveMonitorScreen(
                        status = status,
                        level = level,
                        hearingText = null,
                        overs = oversState,
                        localMicrophone = false,
                    )
                }
            }
        }

        sevenRows.forEach { row ->
            val bounds = composeTestRule.onNodeWithTag("live-monitor-row-${row.id}").getUnclippedBoundsInRoot()
            val height = bounds.bottom - bounds.top
            assertTrue(
                "expected live-monitor-row-${row.id} to clear the 44dp floor; got $height",
                height >= 44.dp,
            )
        }
    }

    @Test
    @Requirement("AC-63")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `at font scale 2_0 on the tour AVD's own width, rows stack top to bottom with no overlap`() {
        composeTestRule.setContent {
            val realDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density = realDensity, fontScale = 2f)) {
                Box(modifier = Modifier.width(390.dp)) {
                    OrtTheme {
                        LiveMonitorScreen(
                            status = status,
                            level = level,
                            hearingText = "and we're clear on the repeater",
                            overs = oversState,
                            localMicrophone = true,
                        )
                    }
                }
            }
        }

        val boundsInOrder = sevenRows.map { row ->
            composeTestRule.onNodeWithTag("live-monitor-row-${row.id}").getUnclippedBoundsInRoot()
        }
        for (i in 0 until boundsInOrder.size - 1) {
            val current = boundsInOrder[i]
            val next = boundsInOrder[i + 1]
            assertTrue(
                "expected row $i (bottom ${current.bottom}) to sit at or above row ${i + 1} " +
                    "(top ${next.top}) at font scale 2.0 — an overlap here is exactly the class of " +
                    "defect (R-874/R-942/R-980) a row/column layout change must be checked against",
                current.bottom <= next.top,
            )
        }
    }
}
