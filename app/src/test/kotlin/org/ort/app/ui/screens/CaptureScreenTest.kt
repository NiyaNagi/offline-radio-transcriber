package org.ort.app.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.CaptureArchiveViewState
import org.ort.app.ui.data.CaptureOverAudioViewState
import org.ort.app.ui.data.CaptureStatusMapper
import org.ort.app.ui.data.CaptureStorageViewState
import org.ort.app.ui.data.LevelViewState
import org.ort.app.ui.data.LiveMonitorOversViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.pipeline.capture.VadAvailability
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * `Capture.dc.html` (N08): [CaptureScreen] driven directly with constructed view-states — no
 * polling, no `Context`, no database — so the toggle/warning behaviours this screen adds beyond
 * what N04/N06/N07 already proved are each checked deterministically, in one recomposition, rather
 * than depending on [CaptureStatusContent]'s own 2 s poll cadence (that composable's own test file
 * proves the real facts reach this screen at all; this file proves what the screen does with them).
 */
@RunWith(RobolectricTestRunner::class)
class CaptureScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun status() = CaptureStatusMapper.from(
        captureState = CaptureState.State.Capturing,
        shedLevel = 0,
        backlog = 0,
        thermal = ThermalStatus.State.Nominal(osThermalStatus = 0, realTimeFactor = null),
        rig = RigStatus.State.Absent,
        storage = StorageForecast.State.NotYetMeasured(0L, 0L),
        asr = AsrAvailability.State.NotYetChecked,
        vad = VadAvailability.State.Stub,
        input = InputStatus.State.None,
        level = LevelStatus.State.NotMeasured,
        nowMillis = 0L,
        sinceLabel = "22:00",
        elapsedLabel = "0:31:07",
        heartbeatSecondsAgo = 3L,
        isAlive = true,
        transmissionCount = 0,
        rejectedCount = 0,
        failedCount = 0,
        gapCount = 0,
        batteryPercent = null,
        batteryCharging = false,
        batteryExemptionReportsIgnoring = false,
    )

    private fun storage(overAudioExceeded: Boolean, archiveEnabled: Boolean) = CaptureStorageViewState(
        overAudio = CaptureOverAudioViewState(
            usedBytes = 9_000_000_000L,
            budgetGb = 8,
            fractionUsed = 1f,
            exceeded = overAudioExceeded,
            warningLabel = if (overAudioExceeded) {
                "Over budget · never deleted without you"
            } else {
                "warns when full · never deleted without you"
            },
        ),
        archive = CaptureArchiveViewState(
            enabled = archiveEnabled,
            usedBytes = 11_800_000_000L,
            budgetGb = 60,
            fractionUsed = 0.2f,
            monthlyRateLabel = "about 15 GB a month (estimated)",
            isMeasuredRate = false,
        ),
    )

    @Test
    @Requirement("D40", "FR-STO-3e", "AC-160")
    fun `the over-audio warning renders the exceeded copy and colour only when the real budget is exceeded`() {
        composeTestRule.setContent {
            OrtTheme {
                CaptureScreen(
                    status = status(),
                    level = LevelViewState.notMeasured(),
                    hearingText = null,
                    overs = LiveMonitorOversViewState.EMPTY,
                    storage = storage(overAudioExceeded = true, archiveEnabled = true),
                )
            }
        }

        composeTestRule.onNodeWithTag(CAPTURE_OVER_AUDIO_WARNING_TEST_TAG)
            .assertTextContains("Over budget", substring = true)
    }

    @Test
    @Requirement("D40", "FR-STO-3e")
    fun `the over-audio row reads the nominal copy when the real budget is not exceeded`() {
        composeTestRule.setContent {
            OrtTheme {
                CaptureScreen(
                    status = status(),
                    level = LevelViewState.notMeasured(),
                    hearingText = null,
                    overs = LiveMonitorOversViewState.EMPTY,
                    storage = storage(overAudioExceeded = false, archiveEnabled = true),
                )
            }
        }

        composeTestRule.onNodeWithTag(CAPTURE_OVER_AUDIO_WARNING_TEST_TAG)
            .assertTextContains("warns when full", substring = true)
    }

    @Test
    @Requirement("D39", "FR-STO-3f", "AC-158", "AC-159")
    fun `tapping the archive toggle invokes the caller's onTurnOffArchive, beside the disclosure it changes`() {
        var toggled = false
        composeTestRule.setContent {
            OrtTheme {
                CaptureScreen(
                    status = status(),
                    level = LevelViewState.notMeasured(),
                    hearingText = null,
                    overs = LiveMonitorOversViewState.EMPTY,
                    storage = storage(overAudioExceeded = false, archiveEnabled = true),
                    onTurnOffArchive = { toggled = true },
                )
            }
        }

        composeTestRule.onNodeWithTag(CAPTURE_ARCHIVE_TOGGLE_TEST_TAG).assertTextContains("Turn off", substring = true)
        composeTestRule.onNodeWithTag(CAPTURE_ARCHIVE_TOGGLE_TEST_TAG).performScrollTo().performClick()
        assert(toggled)
    }

    @Test
    @Requirement("D39", "FR-STO-3f", "AC-158", "AC-159")
    fun `the archive row reads Turn on and the off state when the real archive is disabled`() {
        composeTestRule.setContent {
            OrtTheme {
                CaptureScreen(
                    status = status(),
                    level = LevelViewState.notMeasured(),
                    hearingText = null,
                    overs = LiveMonitorOversViewState.EMPTY,
                    storage = storage(overAudioExceeded = false, archiveEnabled = false),
                )
            }
        }

        composeTestRule.onNodeWithTag(CAPTURE_ARCHIVE_RATE_TEST_TAG).assertTextContains("off ·", substring = true)
        composeTestRule.onNodeWithTag(CAPTURE_ARCHIVE_TOGGLE_TEST_TAG).assertTextContains("Turn on", substring = true)
    }

    /**
     * R-1079 (register): the Capture storage card's own "Raw archive 0.0 GB of 60 GB" — a real,
     * non-zero archive rounded to nothing via `Long.toGigabyteLabel()`, reading as empty. Fixed with
     * R-1068's own adaptive B/KB/MB/GB formatter (`recordingSessionByteLabel`), reused, not
     * re-derived. Discrimination: reverting `CaptureArchiveRow`/`CaptureOverAudioRow`'s own
     * formatter call back to `toGigabyteLabel()` makes this fail with "0.0 GB of ...".
     */
    @Test
    @Requirement("R-1079")
    fun `R_1079 a small non-zero archive or over-audio size never rounds to 0-0 GB`() {
        composeTestRule.setContent {
            OrtTheme {
                CaptureScreen(
                    status = status(),
                    level = LevelViewState.notMeasured(),
                    hearingText = null,
                    overs = LiveMonitorOversViewState.EMPTY,
                    storage = CaptureStorageViewState(
                        overAudio = CaptureOverAudioViewState(
                            usedBytes = 32_000L,
                            budgetGb = 8,
                            fractionUsed = 0.0001f,
                            exceeded = false,
                            warningLabel = "warns when full · never deleted without you",
                        ),
                        archive = CaptureArchiveViewState(
                            enabled = true,
                            usedBytes = 32_000L,
                            budgetGb = 60,
                            fractionUsed = 0.0001f,
                            monthlyRateLabel = "about 15 GB a month (estimated)",
                            isMeasuredRate = false,
                        ),
                    ),
                )
            }
        }

        // Two separate "N KB of M GB" labels — the over-audio row's and the archive row's own —
        // each its own `Text` node.
        composeTestRule.onAllNodesWithText("32 KB", substring = true, useUnmergedTree = true)
            .assertCountEquals(2)
    }
}
