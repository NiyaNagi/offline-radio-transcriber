package org.ort.app.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.font.FontWeight
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
    // R-1050 (register, lead capture `overnight/CF07-settings-export@2x-end.png`): at font scale
    // 2.0 the filename broke mid-token inside its own timestamp
    // (`ort-export-tonight-202609` / `13-022935.adi · 8 KB`), and the two-line label's first line
    // sat flush against the button's top edge while the last line had more room below — the button
    // grew to fit the text without keeping its own vertical padding. `middleEllipsizeFileName`'s own
    // pure-function tests assert the string transform directly (never wraps, never a fragment of
    // the timestamp); `ExportSaveFileButtonContent`'s own render tests assert bounds, not prose.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `R_1050 middleEllipsizeFileName returns the name unchanged when it already fits the budget`() {
        val name = "ort-export-all-20260913-022935.json"
        assertEquals(name, middleEllipsizeFileName(name, maxVisibleLength = name.length))
    }

    @Test
    fun `R_1050 middleEllipsizeFileName cuts only at a real hyphen, never mid-digit-run, for ADIF`() {
        val name = "ort-export-tonight-20260913-022935.adi"
        val result = middleEllipsizeFileName(name, maxVisibleLength = 26)
        val keptPrefix = result.substringBefore("…")
        assertTrue(
            "expected the kept prefix to be a real, hyphen-terminated prefix of the original name " +
                "(or empty), got '$keptPrefix' from '$result'",
            keptPrefix.isEmpty() || (name.startsWith(keptPrefix) && keptPrefix.endsWith("-")),
        )
        assertTrue("expected the real extension kept verbatim, got '$result'", result.endsWith(".adi"))
        val droppedSpan = result.removePrefix(keptPrefix).removePrefix("…").removeSuffix(".adi")
        assertFalse(
            "expected the timestamp dropped whole rather than a partial fragment of it left " +
                "showing, got '$result' (dropped span '$droppedSpan')",
            droppedSpan.any { it.isDigit() },
        )
    }

    @Test
    fun `R_1050 middleEllipsizeFileName keeps the real extension for every export format`() {
        for (ext in listOf("adi", "csv", "json", "txt")) {
            val name = "ort-export-everything-20260913-022935.$ext"
            val result = middleEllipsizeFileName(name, maxVisibleLength = 26)
            assertTrue("format $ext: expected '.$ext' kept verbatim, got '$result'", result.endsWith(".$ext"))
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1050 the filename plus size line renders as one line, never wrapping mid-token, at font scale 2_0`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    Box(modifier = Modifier.width(260.dp)) {
                        ExportSaveFileButtonContent(
                            fileName = "ort-export-tonight-20260913-022935.adi",
                            sizeBytes = 8_000L,
                            color = OrtColors.accentOnGreen,
                        )
                    }
                }
            }
        }
        // `export-save-file-line1` ("Save file") is short enough it can never itself wrap — its own
        // real rendered height at this font scale is the one-line reference; reverting
        // `softWrap = false`/`middleEllipsizeFileName` on line 2 would let a plain `Text` wrap this
        // long a string across several lines at this narrow a width, multiplying its height.
        val line1Bounds = composeTestRule.onNodeWithTag("export-save-file-line1").getUnclippedBoundsInRoot()
        val line2Bounds = composeTestRule.onNodeWithTag("export-save-file-line2").getUnclippedBoundsInRoot()
        val line1Height = (line1Bounds.bottom - line1Bounds.top).value
        val line2Height = (line2Bounds.bottom - line2Bounds.top).value
        assertTrue(
            "expected the filename+size line to render at roughly one line's height " +
                "(line1=${line1Height}dp); got ${line2Height}dp, consistent with wrapping onto more than one line",
            line2Height <= line1Height * 1.5f,
        )
    }

    /**
     * R-1050 follow-up (found on the real device recapture verifying this very fix, not by
     * inspection): the first `MAX_VISIBLE_FILE_NAME_LENGTH` (26) rendered one line as required, but
     * `export-save-file-line2`'s own `overflow = TextOverflow.Ellipsis` backstop silently ate the
     * size suffix once the filename-plus-size string still did not fit the button's real width —
     * "one line" alone (the test above) does not prove *nothing was clipped off the end* of that
     * line. This compares the real, constrained line's rendered width against the *same* string
     * rendered with no width limit at all (its true, natural width) — if they match, the whole
     * string genuinely fit and the backstop never fired; if the constrained one is narrower, some
     * of it — the size, always the tail of this line — was cut.
     *
     * Honest limitation (constitution VIII: a passing test is not evidence on its own): Robolectric
     * cannot reliably reproduce real device font metrics (this package's own `R_260`/`R_552`
     * precedent already states this), so this test did *not* itself fail against the pre-fix
     * `MAX_VISIBLE_FILE_NAME_LENGTH = 26` — the real device recapture that found the defect, and
     * confirmed this fix, is the actual evidence for the exact budget chosen; this test's own job is
     * narrower and still real: proving the *mechanism* (comparing a constrained render against its
     * own natural width) would catch a regression wherever Robolectric's metrics do reflect it.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1050 the filename plus size line never clips the size at font scale 2_0`() {
        val fileName = "ort-export-tonight-20260913-022935.adi"
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    Box(modifier = Modifier.width(390.dp)) {
                        ExportSaveFileButtonContent(
                            fileName = fileName,
                            sizeBytes = 184_000L,
                            color = OrtColors.accentOnGreen,
                        )
                    }
                    // The exact same combined string `ExportSaveFileButtonContent`'s own line 2
                    // renders, but unconstrained (a very wide box) — its real, untruncated width.
                    Box(modifier = Modifier.width(2000.dp)) {
                        Text(
                            text = middleEllipsizeFileName(fileName, MAX_VISIBLE_FILE_NAME_LENGTH) + " · 184 KB",
                            style = OrtType.control.copy(fontWeight = FontWeight.Medium),
                            softWrap = false,
                            modifier = Modifier.testTag("line2-natural-reference"),
                        )
                    }
                }
            }
        }
        val line2Bounds = composeTestRule.onNodeWithTag("export-save-file-line2").getUnclippedBoundsInRoot()
        val naturalBounds = composeTestRule.onNodeWithTag("line2-natural-reference").getUnclippedBoundsInRoot()
        val line2Width = (line2Bounds.right - line2Bounds.left).value
        val naturalWidth = (naturalBounds.right - naturalBounds.left).value
        assertTrue(
            "expected the filename+size line to render at its own real, natural width (never " +
                "clipped) — natural=${naturalWidth}dp, rendered=${line2Width}dp",
            line2Width >= naturalWidth - 2f,
        )
    }

    /**
     * R-1050(b): symmetric insets around [ExportSaveFileButtonContent], measured against an
     * equivalent host (the exact `fillMaxWidth().requiredHeightIn(min = 48.dp)` shape
     * [ExportSaveFileButton]'s own real Box carries) — that composable's own `clearAndSetSemantics`
     * (the R-380/R-543 accessible-name fix) genuinely prunes every descendant from the semantics
     * tree on a real device, so a query from inside a test could never reach a tagged node inside
     * the real button directly (this file's own `ExportSaveFileButton` doc comment has the finding).
     */
    private fun assertSymmetricButtonPadding(widthDp: Int, fontScale: Float) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                OrtTheme {
                    Box(modifier = Modifier.width(widthDp.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .requiredHeightIn(min = 48.dp)
                                .testTag("button-host"),
                            // Matches `ExportSaveFileButton`'s own real outer Box exactly —
                            // without this, a short (1.0-scale) content block that does not reach
                            // the 48dp floor would sit top-aligned in this test-only host instead
                            // of centred like the real button, producing a false asymmetry this
                            // harness itself introduced rather than one the fix left behind.
                            contentAlignment = Alignment.Center,
                        ) {
                            ExportSaveFileButtonContent(
                                fileName = "ort-export-tonight-20260913-022935.adi",
                                sizeBytes = 8_000L,
                                color = OrtColors.accentOnGreen,
                            )
                        }
                    }
                }
            }
        }
        val hostBounds = composeTestRule.onNodeWithTag("button-host").getUnclippedBoundsInRoot()
        val firstLineBounds = composeTestRule.onNodeWithTag("export-save-file-line1").getUnclippedBoundsInRoot()
        val lastLineTag = if (fontScale >= 1.5f) "export-save-file-line2" else "export-save-file-line1"
        val lastLineBounds = composeTestRule.onNodeWithTag(lastLineTag).getUnclippedBoundsInRoot()

        val topInset = (firstLineBounds.top - hostBounds.top).value
        val bottomInset = (hostBounds.bottom - lastLineBounds.bottom).value
        // `OrtSpacing.md` (12dp) is the padding the fix reserves; 2dp of slack for font-metric
        // rounding, matching the drift tolerance this package's own `R_880` geometry test uses.
        val minExpectedInset = OrtSpacing.md.value - 2f

        assertTrue(
            "expected the top inset to be at least the button's own padding ($minExpectedInset dp), " +
                "got ${topInset}dp at ${widthDp}dp/fontScale=$fontScale (host=$hostBounds first=$firstLineBounds)",
            topInset >= minExpectedInset,
        )
        assertTrue(
            "expected the bottom inset to be at least the button's own padding ($minExpectedInset dp), " +
                "got ${bottomInset}dp at ${widthDp}dp/fontScale=$fontScale (host=$hostBounds last=$lastLineBounds)",
            bottomInset >= minExpectedInset,
        )
        assertTrue(
            "expected the top and bottom insets to be equal within 2dp, got top=${topInset}dp " +
                "bottom=${bottomInset}dp at ${widthDp}dp/fontScale=$fontScale",
            abs(topInset - bottomInset) <= 2f,
        )
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `R_1050 the label's top and bottom insets are equal and at least the button's padding at 390dp scale 1_0`() {
        assertSymmetricButtonPadding(widthDp = 390, fontScale = 1f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `R_1050 the label's top and bottom insets are equal and at least the button's padding at 390dp scale 2_0`() {
        assertSymmetricButtonPadding(widthDp = 390, fontScale = 2f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1050 the label's top and bottom insets are equal and at least the button's padding at 480dp scale 1_0`() {
        assertSymmetricButtonPadding(widthDp = 480, fontScale = 1f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1050 the label's top and bottom insets are equal and at least the button's padding at 480dp scale 2_0`() {
        assertSymmetricButtonPadding(widthDp = 480, fontScale = 2f)
    }
}
