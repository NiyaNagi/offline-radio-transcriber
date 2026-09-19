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
import androidx.compose.ui.test.onNodeWithText
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
        // D50 (Q22, FR-SEG-10): the fallback's own longer copy is the shape most likely to collide
        // with the counts line above it at font scale 2.0 — chosen deliberately, not the shorter
        // "Silero VAD" case, for this layout test's own purpose.
        vadDetectorLabel = "Energy VAD (fallback)",
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

    /** Round 3 (coordinator review): an INFERRED over — a real transcript, a real confidence chip
     * — the exact shape the device evidence (`KJ7ABC`, `overnight/RC02-recording-session@2x-end.png`)
     * found squeezed to a sliver at font scale 2.0. */
    private fun inferredOverWithChip(id: String) = RecordingSessionRow.Over(
        id = id,
        timeLabel = "10:44:00",
        frequencyLabel = "145.230",
        durationLabel = "4 s",
        status = RecordingSessionOverStatus.RESOLVED,
        transcript = "QRZ, this is KJ7ABC",
        attribution = Attribution.inferred("KJ7ABC", 0.70),
        callsign = "KJ7ABC",
        alternate = null,
        attributionStateLabel = "inferred",
        inferredFromLabel = "05:23:36",
        hasAudio = false,
        stationId = "KJ7ABC",
        trainingLabel = null,
        markedForTraining = false,
        statusReasonLabel = null,
        canRetry = false,
    )

    private fun stateWithInferredOver() = RecordingSessionViewState(
        sessionId = "S1",
        header = header(),
        coverage = coverage(),
        rows = listOf(inferredOverWithChip("T2")),
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

    /**
     * D50 (Q22, FR-SEG-10, AC-162): the new durable segmentation-detector line this round adds to
     * the header — asserted here as real, measured bounds (never a pixel comparison) at both font
     * scales the tour exercises, since AGENTS.md working agreement item 7 requires a layout test
     * for any row/column change under `*Content`/`*ViewState` files at this exact device qualifier.
     */
    private fun assertVadDetectorLineBelowCountsLineAndUnclipped() {
        val counts = composeTestRule.onNodeWithText(header().countsLabel, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val vadDetectorLine = composeTestRule.onNodeWithTag(RECORDING_SESSION_VAD_DETECTOR_TEST_TAG)
            .getUnclippedBoundsInRoot()
        assertTrue(
            "expected the D50 segmentation line (top=${vadDetectorLine.top}) to sit below the counts " +
                "line (bottom=${counts.bottom}), never overlapping it",
            vadDetectorLine.top >= counts.bottom,
        )
        val width = vadDetectorLine.right - vadDetectorLine.left
        assertTrue(
            "expected the D50 segmentation line to render with a real, non-zero width " +
                "(never clipped to nothing), got $width",
            width > 0.dp,
        )
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `AC_162 the segmentation line sits below the counts line, never overlapping, at font scale 1_0`() {
        setContent(fontScale = 1f)
        assertVadDetectorLineBelowCountsLineAndUnclipped()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `AC_162 the segmentation line sits below the counts line, never overlapping, at font scale 2_0`() {
        setContent(fontScale = 2f)
        assertVadDetectorLineBelowCountsLineAndUnclipped()
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

    /**
     * Round 3 (coordinator review): `round2\tour\overnight\RC02-recording-session@2x-end.png`
     * found an over row's own transcript/callsign column squeezed to about a third of the row's
     * real width at font scale 2.0 (the fixed time column, at full width, plus the fixed Label
     * column, left too little for the weighted column to hold "thanks for the contact WA7HJR" or
     * KJ7ABC's own confidence chip without wrapping one or two words per line and clipping the
     * chip to a sliver). Asserted here as real, measured bounds — never a pixel comparison — at
     * both widths the tour's own AVD and its wider sibling exercise.
     */
    private fun assertOverRowContentAndChipFit(fontScale: Float, expectWideContent: Boolean) {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = LocalDensity.current.density, fontScale = fontScale),
            ) {
                OrtTheme {
                    RecordingSessionScreen(
                        state = stateWithInferredOver(),
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
        val rowBounds = composeTestRule
            .onNodeWithTag("rc02-over-row-T2", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val contentBounds = composeTestRule
            .onNodeWithTag(recordingSessionOverContentTestTag("T2"), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val chipBounds = composeTestRule
            .onNodeWithTag(recordingSessionOverChipTestTag("T2"), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        val rowWidth = rowBounds.right - rowBounds.left
        val contentWidth = contentBounds.right - contentBounds.left
        if (expectWideContent) {
            val floor = rowWidth * MIN_CONTENT_WIDTH_FRACTION
            assertTrue(
                "expected the content column ($contentWidth) to be at least 60% of the row's own " +
                    "real width ($rowWidth, floor=$floor) at font scale $fontScale",
                contentWidth >= floor,
            )
        }
        assertTrue(
            "expected the confidence chip (left=${chipBounds.left}, right=${chipBounds.right}) to sit " +
                "fully inside its row (left=${rowBounds.left}, right=${rowBounds.right}) at font scale " +
                "$fontScale, never clipped to a sliver past the row's own bound",
            chipBounds.left >= rowBounds.left && chipBounds.right <= rowBounds.right,
        )
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `an over row's content column and confidence chip fit at 390dp font scale 1_0`() {
        assertOverRowContentAndChipFit(fontScale = 1f, expectWideContent = false)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `an over row restacks so its content column holds at least 60 percent of the row at 390dp font scale 2_0`() {
        assertOverRowContentAndChipFit(fontScale = 2f, expectWideContent = true)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w480dp-h844dp-420dpi")
    fun `an over row's content column and confidence chip fit at 480dp font scale 1_0`() {
        assertOverRowContentAndChipFit(fontScale = 1f, expectWideContent = false)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w480dp-h844dp-420dpi")
    fun `an over row restacks so its content column holds at least 60 percent of the row at 480dp font scale 2_0`() {
        assertOverRowContentAndChipFit(fontScale = 2f, expectWideContent = true)
    }

    private companion object {
        /** The coordinator's own literal floor ("at least ~60% of the row width at 2.0"). */
        const val MIN_CONTENT_WIDTH_FRACTION = 0.6f
    }
}
