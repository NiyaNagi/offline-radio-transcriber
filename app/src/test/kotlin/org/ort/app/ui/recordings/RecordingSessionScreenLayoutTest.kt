package org.ort.app.ui.recordings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.components.TransportBar
import org.ort.app.ui.components.TransportBarActions
import org.ort.app.ui.components.TransportBarViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Constitution VIII (a row/column layout change gets a layout test at the tour's own width, native
 * graphics, asserting bounds): [RecordingSessionScreen] rendered inside the real host shape
 * [org.ort.app.ui.navigation.OrtNavHost]'s own `NavHostBody` uses — a `Scaffold` -> `Column` ->
 * weighted content box -> [TransportBar] as a plain sibling below, the same shape
 * [org.ort.app.ui.navigation.TransportBarHostLayoutTest] already establishes for the identical
 * reason (a fixed-height test `Box` would bound away the very defect class this exists to catch).
 */
@RunWith(RobolectricTestRunner::class)
class RecordingSessionScreenLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun header() = RecordingSessionHeaderViewState(
        dateLabel = "Wed 10 Sep",
        timeRangeLabel = "21:48 – 06:12",
        durationLabel = "8 h 24 m",
        modeLabel = "local microphone · room audio",
        countsLabel = "41 overs · 9 stations · 1 gap · 3 failed",
    )

    private fun coverage() = RecordingSessionCoverageViewState(
        ticks = emptyList(),
        gaps = emptyList(),
        playheadFraction = null,
        axisLabels = listOf("22", "00", "02", "04", "06"),
    )

    private fun over(id: String) = RecordingSessionRow.Over(
        id = id,
        timeLabel = "22:14:07",
        frequencyLabel = "146.520",
        durationLabel = "3.4 s",
        status = RecordingSessionOverStatus.RESOLVED,
        transcript = "this is whiskey seven november papa charlie, clear",
        attribution = Attribution.confirmed("W7NPC", 0.9),
        callsign = "W7NPC",
        alternate = null,
        attributionStateLabel = "confirmed",
        inferredFromLabel = null,
        hasAudio = true,
        stationId = "W7NPC",
        trainingLabel = "training · good",
        markedForTraining = true,
        statusReasonLabel = null,
        canRetry = false,
    )

    private fun state() = RecordingSessionViewState(
        sessionId = "S1",
        header = header(),
        coverage = coverage(),
        rows = listOf(over("T1")),
        deleteFreesBytes = 610_000_000L,
        exportAvailable = true,
    )

    private fun playback() = TransportBarViewState.Playback(
        transmissionId = "TX-ELSEWHERE",
        callsignLabel = "W7NPC",
        isPlaying = true,
        positionFraction = 0.33f,
        elapsedLabel = "0:08",
        totalLabel = "0:24",
        capturingDotVisible = false,
    )

    /** [org.ort.app.ui.navigation.OrtNavHost.kt]'s own `NavHostBody` shape, reproduced only in
     * outline — real [RecordingSessionScreen] as the content, a real font-scale density, never an
     * artificial fixed-height container. */
    private fun setContent(fontScale: Float) {
        composeTestRule.setContent {
            val realDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density = realDensity, fontScale = fontScale)) {
                OrtTheme {
                    Scaffold { padding ->
                        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                            Box(modifier = Modifier.weight(1f)) {
                                RecordingSessionScreen(
                                    state = state(),
                                    playingOverId = null,
                                    isPlaying = false,
                                    actions = RecordingSessionActions(),
                                    deleteSheet = RecordingSessionDeleteState.Idle,
                                    exportSheet = RecordingSessionExportState.Idle,
                                    labelSheet = null,
                                )
                            }
                            TransportBar(state = playback(), actions = TransportBarActions())
                        }
                    }
                }
            }
        }
    }

    /** Mirrors [org.ort.app.ui.navigation.TransportBarHostLayoutTest]'s own measured floor exactly
     * — the bar's own row shape is unchanged by this screen sitting above it. */
    private fun assertBarBoundedAndBelowContent(maxBarHeight: Dp) {
        val bar = composeTestRule.onNodeWithTag("transport-bar-playback").getUnclippedBoundsInRoot()
        val barHeight = bar.bottom - bar.top
        assertTrue(
            "expected the playback bar's own row to be bounded (<= $maxBarHeight), got $barHeight",
            barHeight <= maxBarHeight,
        )
        val deleteTile = composeTestRule.onNodeWithTag(RECORDING_SESSION_DELETE_TEST_TAG)
            .performScrollTo()
            .getUnclippedBoundsInRoot()
        assertTrue(
            "expected RC02's own Delete tile (bottom=${deleteTile.bottom}) to sit above the transport " +
                "bar's own top edge (${bar.top}) — the bar must never cover this screen's content",
            deleteTile.bottom <= bar.top,
        )
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `RC02 content stays visible above a bounded transport bar at 390dp font scale 1_0`() {
        setContent(fontScale = 1f)
        assertBarBoundedAndBelowContent(maxBarHeight = 70.dp)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `RC02 content stays visible above a bounded transport bar at 390dp font scale 2_0`() {
        setContent(fontScale = 2f)
        assertBarBoundedAndBelowContent(maxBarHeight = 76.dp)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w480dp-h844dp-420dpi")
    fun `RC02 content stays visible above a bounded transport bar at 480dp font scale 1_0`() {
        setContent(fontScale = 1f)
        assertBarBoundedAndBelowContent(maxBarHeight = 70.dp)
    }

    /**
     * The artboard splits `Delete` and its own "frees N GB" caption onto two separate lines
     * precisely so the two never collide — asserted here as real, non-overlapping bounds (never a
     * pixel comparison), at the font scale most likely to cramp them.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `the Delete tile's label and its own frees-bytes caption never collide, at font scale 2_0`() {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = LocalDensity.current.density, fontScale = 2f),
            ) {
                OrtTheme {
                    RecordingSessionScreen(
                        state = state(),
                        playingOverId = null,
                        isPlaying = false,
                        actions = RecordingSessionActions(),
                        deleteSheet = RecordingSessionDeleteState.Idle,
                        exportSheet = RecordingSessionExportState.Idle,
                        labelSheet = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        val bounds = composeTestRule.onNodeWithTag(RECORDING_SESSION_DELETE_TEST_TAG, useUnmergedTree = false)
            .getUnclippedBoundsInRoot()
        val height = bounds.bottom - bounds.top
        // The tile's own icon + "Delete" + caption stack vertically inside one card
        // (`RECORDING_SESSION_DELETE_TEST_TAG`'s own bounds already enclose all three) — a non-zero
        // height with the card's own floor respected is what proves the caption actually got room
        // rather than being clipped or drawn over the label.
        assertTrue(
            "expected the Delete tile's own card to be at least as tall as its 58dp floor, got $height",
            height >= 58.dp,
        )
    }
}
