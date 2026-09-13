package org.ort.app.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.export.ExportCountPreview
import org.ort.app.export.ExportRequest
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtTheme
import org.ort.app.ui.theme.OrtType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The geometry/bounds half of [SettingsExportScreenTest] — split into its own file purely to keep
 * either class under detekt's `LargeClass` threshold (`SettingsExportScreenTest`'s own doc comment
 * carries the screen's general shape/scroll-helper rationale, restated here for the small helper
 * set this file also needs). R-1044 (option-row dividers/padding), R-1045(a) (the count-preview
 * row's `FlowRow` wrap) and R-1045(b)/R-1050 (the `Save file` button's real filename/size label,
 * its two-line split at font scale 2.0, and its symmetric padding) — every one of them a real
 * on-device finding from the lead's own capture of `overnight/CF07-settings-export{,@2x,@2x-end}
 * .png`, not a hypothetical.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsExportScreenGeometryTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun state() = SettingsExportViewState(
        tonightOverCount = 12,
        tonightSpanLabel = "22:04 – 05:11",
        allSessionCount = 6,
        allOverCount = 214,
    )

    private fun scrollToTag(tag: String) {
        composeTestRule.onNodeWithTag("export-screen-scroll").performScrollToNode(hasTestTag(tag))
    }

    /** See [SettingsExportScreenTest]'s own identical fake — duplicated here (not shared) so each
     * file's own test class stays self-contained; both are small and deliberately behavioural
     * (constitution II), never a stub that always answers the same regardless of what it was asked. */
    private class FakePreviewCount(private val result: ExportCountPreview) {
        val requests = mutableListOf<ExportRequest>()
        val fn: suspend (Context, ExportRequest) -> ExportCountPreview = { _, request ->
            requests += request
            result
        }
    }

    private fun fakePreviewCount(result: ExportCountPreview = ExportCountPreview(0, 0, 0)) = FakePreviewCount(result)

    /** See [SettingsExportScreenTest]'s own identical fake for
     * [org.ort.app.export.ExportCoordinator.previewSizeBytes] — duplicated for the same reason as
     * [FakePreviewCount] above. */
    private class FakePreviewSize(private val result: Long) {
        val requests = mutableListOf<ExportRequest>()
        val fn: suspend (Context, ExportRequest) -> Long = { _, request ->
            requests += request
            result
        }
    }

    private fun fakePreviewSize(result: Long = 0L) = FakePreviewSize(result)

    // -----------------------------------------------------------------------------------------
    // R-1044 (register, design, `overnight/CF07-settings-export@2x-end.png`): the artboard's `.opt`
    // rows carry a divider above every row and 4px of vertical padding — the build drew neither, so
    // at font scale 2.0 a wrapped sub-line's own last line ran straight into the next row's title.
    // Discriminating: reverting `OptionDivider`/the rows' own `padding(vertical = OrtSpacing.xs)`
    // collapses two adjacent rows' bounds to touch or overlap (gap <= 0dp); these tests fail for
    // exactly that reason against the pre-fix layout.
    // -----------------------------------------------------------------------------------------

    private fun assertPositiveGapBetweenRows(topTag: String, bottomTag: String, fontScale: Float) {
        val preview = fakePreviewCount()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                OrtTheme {
                    SettingsExportScreen(
                        state = state(),
                        onBack = {},
                        previewCount = preview.fn,
                        previewSizeBytes = fakePreviewSize().fn,
                    )
                }
            }
        }
        scrollToTag(bottomTag)
        val topBounds = composeTestRule.onNodeWithTag(topTag).getUnclippedBoundsInRoot()
        val bottomBounds = composeTestRule.onNodeWithTag(bottomTag).getUnclippedBoundsInRoot()
        val gap = (bottomBounds.top - topBounds.bottom).value
        assert(gap > 0f) {
            "expected a positive vertical gap between $topTag and $bottomTag at fontScale=$fontScale, " +
                "got ${gap}dp ($topTag=$topBounds, $bottomTag=$bottomBounds)"
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `R_1044 a positive vertical gap separates the Tonight and A range of nights rows at font scale 2_0`() {
        assertPositiveGapBetweenRows("export-scope-tonight", "export-scope-range", fontScale = 2f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `R_1044 a positive vertical gap separates the A range of nights and Everything rows at font scale 1_0`() {
        assertPositiveGapBetweenRows("export-scope-range", "export-scope-everything", fontScale = 1f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `R_1044 a positive vertical gap separates the Digest and history rows at font scale 2_0`() {
        assertPositiveGapBetweenRows("export-checkbox-digest", "export-checkbox-history", fontScale = 2f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `R_1044 a positive vertical gap separates the history and Audio rows at font scale 2_0`() {
        assertPositiveGapBetweenRows("export-checkbox-history", "export-checkbox-audio", fontScale = 2f)
    }

    // -----------------------------------------------------------------------------------------
    // R-1045(a) (register, design, `overnight/CF07-settings-export@2x-end.png`): the preview row's
    // amber "have no identified station" segment squeezed into a narrow hanging column at font
    // scale 2.0 because a plain `Row` cannot wrap — the same defect class, and the same `FlowRow`
    // fix, `ui/screens/NowScreen.kt`'s own R-260/R-552 already established. Discriminating:
    // reverting the `FlowRow` (back to `Row`) leaves the excluded segment's own left edge deep
    // inside the row (a narrow trailing column, never the row's own left edge) whenever it wraps.
    // -----------------------------------------------------------------------------------------

    private fun assertPreviewRowWrapsFromLeftEdge(widthDp: Int, fontScale: Float) {
        val preview = fakePreviewCount(ExportCountPreview(totalCount = 42, exportableCount = 39, excludedCount = 3))
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                OrtTheme {
                    Box(modifier = Modifier.width(widthDp.dp)) {
                        SettingsExportScreen(
                            state = state(),
                            onBack = {},
                            previewCount = preview.fn,
                            previewSizeBytes = fakePreviewSize().fn,
                        )
                    }
                }
            }
        }
        scrollToTag("export-preview")
        val rowBounds = composeTestRule
            .onNodeWithTag("export-preview", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val exportableBounds = composeTestRule
            .onNodeWithTag("export-preview-exportable", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        // The reason phrase ("have no identified station") is the segment the register's own
        // report names as the one that hung in a narrow trailing column — the tagged number
        // ("3") alone is short enough it can still share a line even when this one cannot.
        val reasonBounds = composeTestRule
            .onNodeWithTag("export-preview-excluded-reason", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        val wrapped = reasonBounds.top >= exportableBounds.bottom
        assert(wrapped) {
            "test setup failed to force a real wrap at ${widthDp}dp/fontScale=$fontScale — " +
                "exportable=$exportableBounds reason=$reasonBounds"
        }
        val drift = (reasonBounds.left - rowBounds.left).value
        assert(drift < 4f) {
            "expected the amber reason text to start at the row's own left edge on its " +
                "continuation line, drifted ${drift}dp at ${widthDp}dp/fontScale=$fontScale " +
                "(row=$rowBounds reason=$reasonBounds)"
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1045a the amber reason text starts at the row's own left edge on its wrapped line at 260dp scale 2_0`() {
        // A narrow width, not a device size — any width that forces the real wrap this fix targets
        // discriminates the bug; 390dp/480dp (this screen's own content width after margins) turned
        // out wide enough for this particular string to still fit on one line even at scale 2.0.
        assertPreviewRowWrapsFromLeftEdge(widthDp = 260, fontScale = 2f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1045a the amber reason text starts at the row's own left edge on its wrapped line at 300dp scale 2_0`() {
        assertPreviewRowWrapsFromLeftEdge(widthDp = 300, fontScale = 2f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1045b the Save file button grows to fit its two-line label instead of clipping it at font scale 2_0`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    Box(modifier = Modifier.width(390.dp)) {
                        SettingsExportScreen(
                            state = state(),
                            onBack = {},
                            previewCount = fakePreviewCount().fn,
                            previewSizeBytes = fakePreviewSize(184_000L).fn,
                        )
                    }
                }
            }
        }
        scrollToTag("export-save-file-button")
        val buttonBounds = composeTestRule.onNodeWithTag("export-save-file-button").getUnclippedBoundsInRoot()
        val buttonHeight = buttonBounds.bottom - buttonBounds.top
        // `ExportSaveFileButton`'s own `requiredHeightIn(min = 48.dp)` is a floor a single-line
        // label never exceeds by much even at this font scale — a button this much taller is only
        // possible if `ExportSaveFileButtonContent` genuinely split onto its own two lines (R-1050),
        // with real padding around them, not clipped or overflowing the button (constitution VIII: a
        // passing assertion here is not evidence on its own — the capture in this report is).
        assert(buttonHeight > 70.dp) {
            "expected the button to grow past a single line's worth of height at font scale 2.0 to " +
                "fit the two-line filename/size label; got $buttonHeight"
        }
    }

    // -----------------------------------------------------------------------------------------
    // R-1050 round 2 (register — round 1, a fixed character budget, was sent back): the round 1
    // fix kept `Save file` inside the button and never mid-token, but at a 12-character budget the
    // label named nothing — no scope, no timestamp — while using only about half the button's own
    // real width. `filenameCandidates`'s own pure-function tests assert the string transform
    // directly; `ExportSaveFileButtonContent`'s own render tests assert bounds and real text
    // content, never prose.
    // -----------------------------------------------------------------------------------------

    private companion object {
        private const val REAL_FILE_NAME = "ort-export-tonight-20260913-022935.adi"
    }

    @Test
    fun `R_1050 filenameCandidates' first candidate is always the real, untouched name`() {
        assertEquals(REAL_FILE_NAME, filenameCandidates(REAL_FILE_NAME).first())
    }

    @Test
    fun `R_1050 filenameCandidates' shortened candidate cuts only at a real hyphen, keeps the timestamp whole`() {
        val shortened = filenameCandidates(REAL_FILE_NAME)[1]
        assertEquals(
            "expected the scope word replaced by one ellipsis, the timestamp and extension kept " +
                "whole, got '$shortened'",
            "ort-export-…-20260913-022935.adi",
            shortened,
        )
    }

    @Test
    fun `R_1050 filenameCandidates keeps the real extension and full timestamp for every export format`() {
        for (ext in listOf("adi", "csv", "json", "txt")) {
            val name = "ort-export-everything-20260913-022935.$ext"
            val shortened = filenameCandidates(name)[1]
            assertTrue("format $ext: expected '.$ext' kept verbatim, got '$shortened'", shortened.endsWith(".$ext"))
            assertTrue(
                "format $ext: expected the timestamp's own digits kept whole, got '$shortened'",
                shortened.contains("20260913") && shortened.contains("022935"),
            )
        }
    }

    @Test
    fun `R_1050 filenameCandidates never invents a shortened form when there is no scope word to drop`() {
        // No token between the fixed prefix and the timestamp — nothing is actually droppable, so
        // inventing an ellipsis here would claim information was omitted when none was.
        val name = "ort-export-20260913-022935.adi"
        assertEquals(listOf(name), filenameCandidates(name))
    }

    /** Reads a tagged `Text` node's own real semantics text (not a substring match) — used below to
     * prove the rendered filename line is *exactly* one of [filenameCandidates]' own real rungs
     * (optionally with the size suffix appended), never a string Compose's own
     * `overflow = TextOverflow.Ellipsis` backstop additionally truncated (round 2's own real-device
     * finding: even the one "drop the scope word" rung this round started with did not always fit
     * the real available width, and the backstop then cut into the timestamp *and* the extension
     * both — see the ladder [filenameCandidates] now builds, and this file's own CHANGELOG entry). */
    private fun renderedText(tag: String): String = composeTestRule.onNodeWithTag(tag).fetchSemanticsNode()
        .config[SemanticsProperties.Text].joinToString(separator = "") { it.text }

    /**
     * R-1050 round 2 (register): renders [ExportSaveFileButtonContent] with the button's own real
     * horizontal padding reproduced around it (`ExportSaveFileButton`'s own outer `Box` already
     * subtracts that padding before this composable ever sees the width). The *root* width comes
     * from the caller's own `@Config(qualifiers = "wNNNdp-...")`, never a nested
     * `Box(Modifier.width(N.dp))` alone: `OrtTheme` wraps content in a `Surface(fillMaxSize())`
     * bounded by the real Robolectric root, and — a round 2 finding, not a report — a nested Box
     * requesting a width *wider* than that root is silently clamped down to it, so several of this
     * file's own first-draft "480dp"/"600dp" tests were silently measuring Robolectric's own
     * unconfigured default root width instead (its own real behaviour still held; the *label* did
     * not match what was actually rendered — a `@Config` per real width is the only way `OrtTheme`'s
     * own [org.ort.app.ui.theme.ortScaleFor] and this composable's own [BoxWithConstraints] see the
     * width a test claims to give them).
     */
    private fun setContentAtRootWidth(fontScale: Float, sizeBytes: Long = 184_000L) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                OrtTheme {
                    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        ExportSaveFileButtonContent(
                            fileName = REAL_FILE_NAME,
                            sizeBytes = sizeBytes,
                            color = OrtColors.accentOnGreen,
                        )
                    }
                }
            }
        }
    }

    /**
     * Asserting an exact match against one of [filenameCandidates]' own rungs (with or without the
     * size suffix) is deliberately chosen over asserting specific digits are present: real
     * measurement at these two real widths turns out to need the ladder's shorter rungs even for
     * this one real filename (`OrtType.control` at 2x plus the button's own padding leaves less
     * room than the round 1 discussion assumed) — proven honestly, not asserted away, by the
     * wide-width test below, which confirms the *preference* (timestamp over scope word) does hold
     * once there is genuinely enough room. What must never happen regardless of which rung is
     * chosen: the extension lost, or the text corrupted into something that is not a real,
     * intentional candidate — both would fail this exact-match check.
     */
    private fun assertFilenameLineIsARealCandidateNeverCorrupted() {
        setContentAtRootWidth(fontScale = 2f)
        val line2Text = renderedText("export-save-file-line2")
        val candidates = filenameCandidates(REAL_FILE_NAME)
        assertTrue(
            "expected the filename line to exactly match one real candidate, with or without " +
                "' · 184 KB' appended — never a string further truncated by the overflow backstop " +
                "— got '$line2Text', real candidates were $candidates",
            candidates.any { line2Text == it || line2Text == "$it · 184 KB" },
        )
        assertTrue("expected the real extension kept verbatim, got '$line2Text'", line2Text.contains(".adi"))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `R_1050 the filename line is a real, uncorrupted candidate with its extension kept at 390dp scale 2_0`() {
        assertFilenameLineIsARealCandidateNeverCorrupted()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w480dp-h844dp-420dpi")
    fun `R_1050 the filename line is a real, uncorrupted candidate with its extension kept at 480dp scale 2_0`() {
        assertFilenameLineIsARealCandidateNeverCorrupted()
    }

    /**
     * R-1050 round 2 (register): [planSaveFileLabel]'s own decision ladder, tested directly against
     * an explicit, known `availableWidthPx` rather than through a real layout pass — Robolectric's
     * own font/layout metrics do not reliably match a real device's (this package's own R-260/R-552
     * precedent; this round's own real-device recapture confirms it again the other way too: this
     * file's first attempt at rendering through real layout at 390dp/480dp/600dp measured the
     * *entire* name-plus-size fitting on Robolectric alone, never once reaching a shorter rung, while
     * the real device needed one). [measureCandidateWidths] gets the ladder's own real, measured
     * pixel widths from a real [androidx.compose.ui.text.TextMeasurer] and the button's own real
     * style — the same measurer [planSaveFileLabel] itself uses — so the thresholds below are real
     * numbers for this real filename and style, never invented ones.
     */
    private fun measureCandidateWidths(fontScale: Float = 2f): Pair<TextMeasurer, TextStyle> {
        lateinit var measurer: TextMeasurer
        lateinit var style: TextStyle
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                OrtTheme {
                    measurer = rememberTextMeasurer()
                    style = OrtType.control.copy(fontWeight = FontWeight.Medium)
                }
            }
        }
        return measurer to style
    }

    private fun widthOf(measurer: TextMeasurer, style: TextStyle, text: String): Int =
        measurer.measure(text = text, style = style).size.width

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1050 planSaveFileLabel keeps the full name and size together once both measure within the width`() {
        val (measurer, style) = measureCandidateWidths()
        val fullName = filenameCandidates(REAL_FILE_NAME).first()
        val ampleWidth = widthOf(measurer, style, "$fullName · 184 KB") + 10
        val plan = planSaveFileLabel(REAL_FILE_NAME, 184_000L, ampleWidth, style, measurer)
        assertEquals("$fullName · 184 KB", plan.filenameLine)
        assertEquals(null, plan.sizeLine)
    }

    /**
     * "Put the size on its own third line rather than cutting the name further" (the register's own
     * instruction): a width that fits the full name alone but not the full name plus the size
     * together must keep the full name — including the scope word — never drop to a shorter rung
     * just to make the size fit alongside it.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1050 planSaveFileLabel keeps the full name and gives the size its own line rather than shortening it`() {
        val (measurer, style) = measureCandidateWidths()
        val fullName = filenameCandidates(REAL_FILE_NAME).first()
        val fullNameWidth = widthOf(measurer, style, fullName)
        val combinedWidth = widthOf(measurer, style, "$fullName · 184 KB")
        val width = (fullNameWidth + combinedWidth) / 2 // fits the name alone, not name-plus-size
        val plan = planSaveFileLabel(REAL_FILE_NAME, 184_000L, width, style, measurer)
        assertEquals(fullName, plan.filenameLine)
        assertEquals(" · 184 KB", plan.sizeLine)
    }

    /**
     * "Prefer keeping the timestamp over the scope word when something must go": once the width
     * cannot fit the full name at all, the next rung — scope word dropped, full timestamp and
     * extension kept — must win, never a shorter rung and never a corrupted string.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1050 planSaveFileLabel drops the scope word before ever touching the timestamp`() {
        val (measurer, style) = measureCandidateWidths()
        val candidates = filenameCandidates(REAL_FILE_NAME)
        val keepFullTimestamp = candidates[1]
        val fullName = candidates.first()
        val width = (widthOf(measurer, style, keepFullTimestamp) + widthOf(measurer, style, fullName)) / 2
        val plan = planSaveFileLabel(REAL_FILE_NAME, 184_000L, width, style, measurer)
        assertEquals(keepFullTimestamp, plan.filenameLine)
        assertEquals(" · 184 KB", plan.sizeLine)
        assertFalse("expected the scope word gone, got '${plan.filenameLine}'", plan.filenameLine.contains("tonight"))
        assertTrue(
            "expected the full timestamp kept, got '${plan.filenameLine}'",
            plan.filenameLine.contains("20260913-022935"),
        )
    }

    /**
     * Once even the scope-less candidate does not fit, the ladder degrades further (the date kept,
     * the time-of-day dropped) rather than falling straight to the shortest rung — still never the
     * extension, and still never a fragment of whichever timestamp token survives.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1050 planSaveFileLabel keeps the date over the time-of-day once the scope-less candidate no longer fits`() {
        val (measurer, style) = measureCandidateWidths()
        val candidates = filenameCandidates(REAL_FILE_NAME)
        val keepFullTimestamp = candidates[1]
        val keepDateOnly = candidates[2]
        val width = (widthOf(measurer, style, keepDateOnly) + widthOf(measurer, style, keepFullTimestamp)) / 2
        val plan = planSaveFileLabel(REAL_FILE_NAME, 184_000L, width, style, measurer)
        assertEquals(keepDateOnly, plan.filenameLine)
        assertTrue("expected the date kept, got '${plan.filenameLine}'", plan.filenameLine.contains("20260913"))
        assertFalse(
            "expected the time-of-day dropped, got '${plan.filenameLine}'",
            plan.filenameLine.contains("022935"),
        )
        assertTrue("expected the real extension kept, got '${plan.filenameLine}'", plan.filenameLine.contains(".adi"))
    }

    /**
     * Narrower than every real rung: the shortest candidate (extension always kept) is still shown
     * in full, never a silently dropped identity.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1050 planSaveFileLabel shows the shortest real candidate when narrower than every rung`() {
        val (measurer, style) = measureCandidateWidths()
        val plan = planSaveFileLabel(REAL_FILE_NAME, 184_000L, availableWidthPx = 1, style, measurer)
        assertEquals(filenameCandidates(REAL_FILE_NAME).last(), plan.filenameLine)
        assertTrue(plan.filenameLine.endsWith(".adi"))
    }

    /**
     * R-1050 round 2: the real, formatted size string must appear in full somewhere in the label —
     * on line 2 beside the filename if both measured within the available width, otherwise on its
     * own line 3 ("put the size on its own third line rather than cutting the name further", the
     * register's own instruction) — never silently dropped or ellipsis-truncated by the
     * `overflow = TextOverflow.Ellipsis` backstop the way round 1's own 26-character budget let
     * happen on the real device.
     */
    private fun assertSizeFullyVisible() {
        setContentAtRootWidth(fontScale = 2f)
        val hasLine3 = composeTestRule.onAllNodesWithTag("export-save-file-line3").fetchSemanticsNodes().isNotEmpty()
        val sizeLineTag = if (hasLine3) "export-save-file-line3" else "export-save-file-line2"
        composeTestRule.onNodeWithTag(sizeLineTag).assertTextContains("184 KB", substring = true)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `R_1050 the real size is fully visible, never ellipsized, at 390dp scale 2_0`() {
        assertSizeFullyVisible()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w480dp-h844dp-420dpi")
    fun `R_1050 the real size is fully visible, never ellipsized, at 480dp scale 2_0`() {
        assertSizeFullyVisible()
    }

    /**
     * R-1050(b): symmetric insets around [ExportSaveFileButtonContent], measured against an
     * equivalent host (the exact `fillMaxWidth().requiredHeightIn(min = 48.dp)` shape
     * [ExportSaveFileButton]'s own real Box carries) — that composable's own `clearAndSetSemantics`
     * (the R-380/R-543 accessible-name fix) genuinely prunes every descendant from the semantics
     * tree on a real device, so a query from inside a test could never reach a tagged node inside
     * the real button directly (this file's own `ExportSaveFileButton` doc comment has the finding).
     * The label may now be one, two or three lines ([planSaveFileLabel]'s own measured choice) — the
     * *last* real line, whichever tag it is, is what the bottom inset is measured against. The root
     * width comes from the caller's own `@Config` — see [setContentAtRootWidth]'s own doc comment
     * for why a nested `Box(Modifier.width(N.dp))` alone cannot be trusted for a width wider than
     * Robolectric's own unconfigured default root.
     */
    private fun assertSymmetricButtonPadding(fontScale: Float) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                OrtTheme {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .requiredHeightIn(min = 48.dp)
                            .padding(horizontal = 20.dp)
                            .testTag("button-host"),
                        // Matches `ExportSaveFileButton`'s own real outer Box exactly — without
                        // this, a short (1.0-scale) content block that does not reach the 48dp
                        // floor would sit top-aligned in this test-only host instead of centred
                        // like the real button, producing a false asymmetry this harness itself
                        // introduced rather than one the fix left behind.
                        contentAlignment = Alignment.Center,
                    ) {
                        ExportSaveFileButtonContent(
                            fileName = REAL_FILE_NAME,
                            sizeBytes = 184_000L,
                            color = OrtColors.accentOnGreen,
                        )
                    }
                }
            }
        }
        val hostBounds = composeTestRule.onNodeWithTag("button-host").getUnclippedBoundsInRoot()
        val firstLineBounds = composeTestRule.onNodeWithTag("export-save-file-line1").getUnclippedBoundsInRoot()
        val hasLine3 = composeTestRule.onAllNodesWithTag("export-save-file-line3").fetchSemanticsNodes().isNotEmpty()
        val lastLineTag = when {
            fontScale < 1.5f -> "export-save-file-line1"
            hasLine3 -> "export-save-file-line3"
            else -> "export-save-file-line2"
        }
        val lastLineBounds = composeTestRule.onNodeWithTag(lastLineTag).getUnclippedBoundsInRoot()

        val topInset = (firstLineBounds.top - hostBounds.top).value
        val bottomInset = (hostBounds.bottom - lastLineBounds.bottom).value
        // `OrtSpacing.md` (12dp) is the padding the fix reserves; 2dp of slack for font-metric
        // rounding, matching the drift tolerance this package's own `R_880` geometry test uses.
        val minExpectedInset = OrtSpacing.md.value - 2f

        assertTrue(
            "expected the top inset to be at least the button's own padding ($minExpectedInset dp), " +
                "got ${topInset}dp at fontScale=$fontScale (host=$hostBounds first=$firstLineBounds)",
            topInset >= minExpectedInset,
        )
        assertTrue(
            "expected the bottom inset to be at least the button's own padding ($minExpectedInset dp), " +
                "got ${bottomInset}dp at fontScale=$fontScale (host=$hostBounds last=$lastLineBounds)",
            bottomInset >= minExpectedInset,
        )
        assertTrue(
            "expected the top and bottom insets to be equal within 2dp, got top=${topInset}dp " +
                "bottom=${bottomInset}dp at fontScale=$fontScale",
            abs(topInset - bottomInset) <= 2f,
        )
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `R_1050 the label's top and bottom insets are equal and at least the button's padding at 390dp scale 1_0`() {
        assertSymmetricButtonPadding(fontScale = 1f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `R_1050 the label's top and bottom insets are equal and at least the button's padding at 390dp scale 2_0`() {
        assertSymmetricButtonPadding(fontScale = 2f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w480dp-h844dp-420dpi")
    fun `R_1050 the label's top and bottom insets are equal and at least the button's padding at 480dp scale 1_0`() {
        assertSymmetricButtonPadding(fontScale = 1f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w480dp-h844dp-420dpi")
    fun `R_1050 the label's top and bottom insets are equal and at least the button's padding at 480dp scale 2_0`() {
        assertSymmetricButtonPadding(fontScale = 2f)
    }
}
