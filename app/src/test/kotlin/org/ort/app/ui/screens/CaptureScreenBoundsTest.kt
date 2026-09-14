package org.ort.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `Capture.dc.html` (N08): a host-level bounds proof at the tour AVD's own geometry
 * (`w390dp-h844dp-420dpi`, spec-test-plan §7.5) and at 480 dp (the operator's own, physically wider
 * device — constitution VIII: "verify against the geometry the operator's own device reports, not
 * the emulator's defaults"), at font scale 1.0 and 2.0, with `@GraphicsMode(NATIVE)` so real text
 * measurement and layout run, not Robolectric's default legacy shadow graphics.
 *
 * The Status section (Input/Radio/Queue/Storage) is exactly the row-stacking shape
 * `CaptureStatusScreenTest`'s own equivalent already proves for N04; what is genuinely new here is
 * the Storage section's own two-row expansion (over-audio warning, archive disclosure + toggle) —
 * this is the layout test constitution VIII requires for "any 2.0 or row/column layout change".
 */
@RunWith(RobolectricTestRunner::class)
class CaptureScreenBoundsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun status() = CaptureStatusMapper.from(
        captureState = CaptureState.State.Capturing,
        shedLevel = 1,
        backlog = 2,
        thermal = ThermalStatus.State.Nominal(osThermalStatus = 0, realTimeFactor = 0.42),
        rig = RigStatus.State.Absent,
        storage = StorageForecast.State.Fine(100_000_000_000L, 3_100_000_000L, nightsLeft = 12.0),
        asr = AsrAvailability.State.NotYetChecked,
        vad = VadAvailability.State.Stub,
        input = InputStatus.State.None,
        level = LevelStatus.State.NotMeasured,
        nowMillis = 0L,
        sinceLabel = "22:00",
        elapsedLabel = "0:31:07",
        heartbeatSecondsAgo = 3L,
        isAlive = true,
        transmissionCount = 7,
        rejectedCount = 0,
        failedCount = 0,
        gapCount = 0,
        batteryPercent = 62,
        batteryCharging = false,
        batteryExemptionReportsIgnoring = false,
    )

    private fun storage() = CaptureStorageViewState(
        overAudio = CaptureOverAudioViewState(
            usedBytes = 9_000_000_000L,
            budgetGb = 8,
            fractionUsed = 1f,
            exceeded = true,
            warningLabel = "Over budget · never deleted without you",
        ),
        archive = CaptureArchiveViewState(
            enabled = true,
            usedBytes = 11_800_000_000L,
            budgetGb = 60,
            fractionUsed = 0.2f,
            monthlyRateLabel = "about 15 GB a month (estimated)",
            isMeasuredRate = false,
        ),
    )

    private val statusRowTags = listOf("capture-input", "capture-radio", "capture-queue")
    private val storageRowTags = listOf(CAPTURE_OVER_AUDIO_ROW_TEST_TAG, CAPTURE_ARCHIVE_ROW_TEST_TAG)

    private fun setContent(fontScale: Float, widthDp: Int = 390) {
        composeTestRule.setContent {
            val realDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density = realDensity, fontScale = fontScale)) {
                Box(modifier = Modifier.width(widthDp.dp)) {
                    OrtTheme {
                        CaptureScreen(
                            status = status(),
                            level = LevelViewState.notMeasured(),
                            hearingText = "and we're clear on the repeater, seven three to you…",
                            overs = LiveMonitorOversViewState.EMPTY,
                            storage = storage(),
                        )
                    }
                }
            }
        }
    }

    /** [statusRowTags] are [org.ort.app.ui.components.KeyValueRow]s, whose own 44dp floor applies
     * unconditionally (R-382) — checked here directly rather than assumed. [storageRowTags] are
     * multi-line disclosure blocks (mirroring `RecordingsScreen.kt`'s own `OverAudioBudgetRow`/
     * `ArchiveBudgetRow`, neither of which carries a 44dp floor either — only their own inner
     * `TextAction` toggle does, checked separately below), so only their stacking is asserted here. */
    private fun assertRowsClearFloor(tags: List<String>) {
        val bounds = tags.map { composeTestRule.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        bounds.forEachIndexed { index, rect ->
            val height = rect.bottom - rect.top
            // A sub-pixel dp/px rounding tolerance (matches `CaptureStatusContentTest`'s own R_232
            // case) — 420dpi's own px-per-dp ratio does not divide evenly, so a row measuring
            // exactly the 44dp floor can round to a hair under it without ever being visually
            // short by a perceptible amount.
            assertTrue("expected ${tags[index]} to clear the 44dp floor; got $height", height >= 44.dp - 0.5.dp)
        }
    }

    private fun assertRowsStackWithoutOverlap(tags: List<String>) {
        val bounds = tags.map { composeTestRule.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        for (i in 0 until bounds.size - 1) {
            assertTrue(
                "expected ${tags[i]} to sit above ${tags[i + 1]} with no overlap " +
                    "(${bounds[i]} vs ${bounds[i + 1]})",
                bounds[i].bottom <= bounds[i + 1].top,
            )
        }
    }

    @Test
    @Requirement("FR-UI-7", "constitution VIII")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `at the tour AVD's own width, the status rows and storage rows stack without overlap`() {
        setContent(fontScale = 1.0f)
        assertRowsClearFloor(statusRowTags)
        assertRowsStackWithoutOverlap(statusRowTags)
        assertRowsStackWithoutOverlap(storageRowTags)
        assertRowsClearFloor(listOf(CAPTURE_ARCHIVE_TOGGLE_TEST_TAG))
    }

    @Test
    @Requirement("FR-UI-7", "constitution VIII", "D40", "D39")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `at font scale 2_0 on the tour AVD's own width, the storage section still stacks without overlap`() {
        setContent(fontScale = 2.0f)
        assertRowsClearFloor(statusRowTags)
        assertRowsStackWithoutOverlap(statusRowTags)
        assertRowsStackWithoutOverlap(storageRowTags)
        // AC-160: the warning is never clipped away at 2.0 — a non-zero, on-screen row.
        val warning = composeTestRule.onNodeWithTag(CAPTURE_OVER_AUDIO_WARNING_TEST_TAG).getUnclippedBoundsInRoot()
        val warningHeight = warning.bottom - warning.top
        assertTrue("expected the over-audio warning to have real height at 2.0", warningHeight > 0.dp)
    }

    @Test
    @Requirement("FR-UI-7", "constitution VIII")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w480dp-h844dp-420dpi")
    fun `at 480dp - the operator's own wider device - rows still stack without overlap`() {
        setContent(fontScale = 1.0f, widthDp = 480)
        assertRowsClearFloor(statusRowTags)
        assertRowsStackWithoutOverlap(statusRowTags)
        assertRowsStackWithoutOverlap(storageRowTags)
    }

    @Test
    @Requirement("FR-UI-7", "constitution VIII", "D40", "D39")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w480dp-h844dp-420dpi")
    fun `at 480dp and font scale 2_0, rows still stack without overlap`() {
        setContent(fontScale = 2.0f, widthDp = 480)
        assertRowsClearFloor(statusRowTags)
        assertRowsStackWithoutOverlap(statusRowTags)
        assertRowsStackWithoutOverlap(storageRowTags)
    }
}
