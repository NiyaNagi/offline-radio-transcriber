package org.ort.app.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.export.ExportCoordinator
import org.ort.app.export.ExportCountPreview
import org.ort.app.export.ExportFileFormat
import org.ort.app.export.ExportRequest
import org.ort.app.export.ExportRequestScope
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Register R-1009 (WPX): the real `Save file` producer replacing the always-`FailedState`
 * screen. `state()` mirrors `SettingsPolling.export`'s own real shape, never a board literal.
 *
 * `Save file` and the `Format` chips sit below the fold of the screen's own (outer, vertical)
 * scroll — every click on either scrolls to it first via the `export-screen-scroll` tag. A bare
 * `performScrollTo()` is not enough here: `FilterChipRow` nests its own *horizontal* scroll, so
 * `performScrollTo()`'s "nearest scrollable ancestor" rule scrolls that one, never the outer
 * vertical container the chip also needs to be vertically visible in — found by adding a debug
 * print inside the chip's own `onClick` and seeing it never fire despite `performScrollTo()`
 * "succeeding" with no exception. `performScrollToNode` against the outer container's own tag is
 * the fix, the same technique (by node, not the ambiguous `hasScrollAction()`, since two
 * scrollables exist on this screen) `SettingsDiagnosticsScreenTest`'s own `Save bundle` click uses.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsExportScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun state() = SettingsExportViewState(
        tonightOverCount = 12,
        tonightSpanLabel = "22:04 – 05:11",
        allSessionCount = 6,
        allOverCount = 214,
    )

    private fun scrollToText(text: String) {
        // R-1045(b): substring, not exact — the `Save file` button's own merged text now carries
        // the real filename/size after it (`Save file · <filename> · <size>`), so an exact match
        // on the bare word would no longer find it. Every text this helper is asked to scroll to
        // (`Save file`, `CSV`, `JSON`) is still unique enough on this screen for a substring match
        // to resolve to exactly one node.
        composeTestRule.onNodeWithTag("export-screen-scroll").performScrollToNode(hasText(text, substring = true))
    }

    private fun scrollToTag(tag: String) {
        composeTestRule.onNodeWithTag("export-screen-scroll").performScrollToNode(hasTestTag(tag))
    }

    /**
     * R-1035: every test in this class injects this instead of the real
     * [org.ort.app.export.ExportCoordinator.previewCount] default — that default reads a real
     * `:data` Room database from a real `Context`, which is exactly the "no unit test reads a real
     * bundled model" class of cost this project's own gate rules out for this layer (`Data module
     * gotchas`: Robolectric has no FTS5 SQLite either). A behavioural fake instead — records every
     * request it was asked to preview, in order, and returns whatever [result] the test configured
     * — never a stub that always answers the same regardless of what it was asked (constitution II).
     */
    private class FakePreviewCount(private val result: ExportCountPreview) {
        val requests = mutableListOf<ExportRequest>()
        val fn: suspend (Context, ExportRequest) -> ExportCountPreview = { _, request ->
            requests += request
            result
        }
    }

    private fun fakePreviewCount(result: ExportCountPreview = ExportCountPreview(0, 0, 0)) = FakePreviewCount(result)

    /**
     * R-1045(b): the same reasoning as [FakePreviewCount] just above, for
     * [org.ort.app.export.ExportCoordinator.previewSizeBytes]'s own real `Context`-backed
     * `ExportCoordinator.build` call — every test in this class injects this instead.
     */
    private class FakePreviewSize(private val result: Long) {
        val requests = mutableListOf<ExportRequest>()
        val fn: suspend (Context, ExportRequest) -> Long = { _, request ->
            requests += request
            result
        }
    }

    private fun fakePreviewSize(result: Long = 0L) = FakePreviewSize(result)

    @Test
    fun `R_1009 Save file is enabled by default (Tonight, the initial scope)`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsExportScreen(
                    state = state(),
                    onBack = {},
                    previewCount = fakePreviewCount().fn,
                    previewSizeBytes = fakePreviewSize().fn,
                )
            }
        }
        scrollToText("Save file")
        composeTestRule.onNodeWithTag("export-save-file-button").assertIsEnabled()
    }

    @Test
    fun `R_1009 selecting A range of nights disables Save file and shows the honest reason`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsExportScreen(
                    state = state(),
                    onBack = {},
                    previewCount = fakePreviewCount().fn,
                    previewSizeBytes = fakePreviewSize().fn,
                )
            }
        }
        composeTestRule.onNodeWithTag("export-scope-range").performClick()
        scrollToText("Save file")
        composeTestRule.onNodeWithTag("export-save-file-button").assertIsNotEnabled()
        composeTestRule.onNodeWithText("A range of nights is not available in this build").assertExists()
    }

    @Test
    fun `R_1009 selecting Everything keeps Save file enabled, no fake unavailability`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsExportScreen(
                    state = state(),
                    onBack = {},
                    previewCount = fakePreviewCount().fn,
                    previewSizeBytes = fakePreviewSize().fn,
                )
            }
        }
        composeTestRule.onNodeWithTag("export-scope-everything").performClick()
        scrollToText("Save file")
        composeTestRule.onNodeWithTag("export-save-file-button").assertIsEnabled()
        composeTestRule.onNodeWithText("Export is not available in this build").assertDoesNotExist()
    }

    @Test
    fun `R_1009 clicking Save file with the default selections requests Tonight ADIF with transcripts`() {
        var captured: ExportRequest? = null
        composeTestRule.setContent {
            OrtTheme {
                SettingsExportScreen(
                    state = state(),
                    onBack = {},
                    onSaveFile = { captured = it },
                    previewCount = fakePreviewCount().fn,
                    previewSizeBytes = fakePreviewSize().fn,
                )
            }
        }
        scrollToText("Save file")
        composeTestRule.onNodeWithTag("export-save-file-button").performClick()
        assert(
            captured == ExportRequest(ExportRequestScope.TONIGHT, ExportFileFormat.ADIF, includeTranscripts = true),
        ) {
            "expected the default Tonight/ADIF/transcripts-on request, got $captured"
        }
    }

    @Test
    fun `R_1009 selecting Everything and CSV requests exactly that scope and format`() {
        var captured: ExportRequest? = null
        composeTestRule.setContent {
            OrtTheme {
                SettingsExportScreen(
                    state = state(),
                    onBack = {},
                    onSaveFile = { captured = it },
                    previewCount = fakePreviewCount().fn,
                    previewSizeBytes = fakePreviewSize().fn,
                )
            }
        }
        composeTestRule.onNodeWithTag("export-scope-everything").performClick()
        scrollToText("CSV")
        composeTestRule.onNodeWithText("CSV").performClick()
        scrollToText("Save file")
        composeTestRule.onNodeWithTag("export-save-file-button").performClick()
        assert(captured?.scope == ExportRequestScope.EVERYTHING) { "expected EVERYTHING, got $captured" }
        assert(captured?.format == ExportFileFormat.CSV) { "expected CSV, got $captured" }
    }

    @Test
    fun `R_1009 unchecking Transcripts requests includeTranscripts false`() {
        var captured: ExportRequest? = null
        composeTestRule.setContent {
            OrtTheme {
                SettingsExportScreen(
                    state = state(),
                    onBack = {},
                    onSaveFile = { captured = it },
                    previewCount = fakePreviewCount().fn,
                    previewSizeBytes = fakePreviewSize().fn,
                )
            }
        }
        scrollToTag("export-checkbox-transcripts")
        composeTestRule.onNodeWithTag("export-checkbox-transcripts").assertIsOn()
        composeTestRule.onNodeWithTag("export-checkbox-transcripts").performClick()
        composeTestRule.onNodeWithTag("export-checkbox-transcripts").assertIsOff()
        scrollToText("Save file")
        composeTestRule.onNodeWithTag("export-save-file-button").performClick()
        assert(captured?.includeTranscripts == false) { "expected includeTranscripts=false, got $captured" }
    }

    @Test
    fun `R_1009 Digest, history and audio checkboxes are unaffected by a tap — not yet wired`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsExportScreen(
                    state = state(),
                    onBack = {},
                    previewCount = fakePreviewCount().fn,
                    previewSizeBytes = fakePreviewSize().fn,
                )
            }
        }
        composeTestRule.onNodeWithTag("export-checkbox-digest").performClick()
        composeTestRule.onNodeWithTag("export-checkbox-history").performClick()
        composeTestRule.onNodeWithTag("export-checkbox-audio").performClick()
        // Three rows, each still stating honestly that a tap changes nothing real — never
        // silently becoming checked (constitution I).
        composeTestRule.onAllNodesWithText("not yet written by this exporter").assertCountEquals(3)
    }

    @Test
    fun `R_135 the never-included promise banner renders regardless of scope`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsExportScreen(
                    state = state(),
                    onBack = {},
                    previewCount = fakePreviewCount().fn,
                    previewSizeBytes = fakePreviewSize().fn,
                )
            }
        }
        composeTestRule.onNodeWithTag("export-scope-range").performClick()
        composeTestRule.onNodeWithText(
            "Never exported, by any option: voiceprints, names you gave stations, notes, your " +
                "location, the level of any signal that would locate you.",
        ).assertExists()
    }

    /**
     * **R-1035** (register, from the operator's own device): an ADIF export "wrote a header and
     * zero QSO records" — correct per FR-EXP-4, and honest about why, but discovered only *after*
     * the write. These tests assert the wiring that closes that gap, on the real `Int`s involved
     * (a request's own fields, an [ExportCountPreview]'s own fields, a tagged node's own bare
     * digit), never a rendered caption's prose — see [ExportPreviewRow]'s own doc comment for why.
     */
    @Test
    fun `R_1035 previewCount is asked for exactly the default Tonight ADIF transcripts-on request`() {
        val preview = fakePreviewCount()
        composeTestRule.setContent {
            OrtTheme {
                SettingsExportScreen(
                    state = state(),
                    onBack = {},
                    previewCount = preview.fn,
                    previewSizeBytes = fakePreviewSize().fn,
                )
            }
        }
        assertEquals(
            listOf(ExportRequest(ExportRequestScope.TONIGHT, ExportFileFormat.ADIF, includeTranscripts = true)),
            preview.requests,
        )
    }

    @Test
    fun `R_1035 selecting Everything and CSV re-previews exactly that combination`() {
        val preview = fakePreviewCount()
        composeTestRule.setContent {
            OrtTheme {
                SettingsExportScreen(
                    state = state(),
                    onBack = {},
                    previewCount = preview.fn,
                    previewSizeBytes = fakePreviewSize().fn,
                )
            }
        }
        composeTestRule.onNodeWithTag("export-scope-everything").performClick()
        scrollToText("CSV")
        composeTestRule.onNodeWithText("CSV").performClick()
        composeTestRule.waitForIdle()
        val last = preview.requests.last()
        assertEquals(ExportRequestScope.EVERYTHING, last.scope)
        assertEquals(ExportFileFormat.CSV, last.format)
    }

    @Test
    fun `R_1035 selecting A range of nights never asks for an impossible preview, and never crashes`() {
        val preview = fakePreviewCount()
        composeTestRule.setContent {
            OrtTheme {
                SettingsExportScreen(
                    state = state(),
                    onBack = {},
                    previewCount = preview.fn,
                    previewSizeBytes = fakePreviewSize().fn,
                )
            }
        }
        val callsBeforeRange = preview.requests.size
        // toRequestScope() has no RANGE branch at all (it error()s) — if the screen ever built a
        // request for RANGE this would throw and fail the test with an exception, not a quiet miss.
        composeTestRule.onNodeWithTag("export-scope-range").performClick()
        assertEquals(
            "selecting RANGE must never place a new (impossible) preview request",
            callsBeforeRange,
            preview.requests.size,
        )
    }

    @Test
    fun `R_1035 Save file writes exactly the request the operator was last shown a preview for`() {
        val preview = fakePreviewCount()
        var captured: ExportRequest? = null
        composeTestRule.setContent {
            OrtTheme {
                SettingsExportScreen(
                    state = state(),
                    onBack = {},
                    onSaveFile = { captured = it },
                    previewCount = preview.fn,
                    previewSizeBytes = fakePreviewSize().fn,
                )
            }
        }
        composeTestRule.onNodeWithTag("export-scope-everything").performClick()
        scrollToText("JSON")
        composeTestRule.onNodeWithText("JSON").performClick()
        scrollToText("Save file")
        composeTestRule.onNodeWithTag("export-save-file-button").performClick()
        assertEquals(
            "the write must never use a different request than the one the operator was last shown a count for",
            preview.requests.last(),
            captured,
        )
    }

    @Test
    fun `R_1035 a zero-exportable ADIF preview shows the real counts, in their own tagged nodes`() {
        val preview = fakePreviewCount(ExportCountPreview(totalCount = 8, exportableCount = 0, excludedCount = 8))
        composeTestRule.setContent {
            OrtTheme {
                SettingsExportScreen(
                    state = state(),
                    onBack = {},
                    previewCount = preview.fn,
                    previewSizeBytes = fakePreviewSize().fn,
                )
            }
        }
        scrollToText("Save file")
        composeTestRule.onNodeWithTag("export-preview-exportable").assertTextEquals("0")
        composeTestRule.onNodeWithTag("export-preview-total").assertTextEquals("8")
        composeTestRule.onNodeWithTag("export-preview-excluded").assertTextEquals("8")
    }

    /**
     * **R-1035 follow-up** (found running the real tour against the real `overnight` scenario
     * while verifying this fix, not by inspection): `ExportCoordinator.previewCount`'s own real
     * `effectiveRecords` read *used to* throw — a real device crash, `IllegalArgumentException:
     * CONFIRMED transmission ... has no resolvable callsign — data integrity defect`
     * (`ExportCoordinator.toExportAttribution`) — for data this build can produce. Register R-1039
     * (halt) fixed that specific crash at the coordinator layer itself, so this exact scenario no
     * longer throws in practice; this test still injects a `previewCount` that throws directly
     * (never the real coordinator), because the screen must stay safe against *any* unexpected
     * failure from a real Room read, not just the one R-1039 already closed — see
     * `SettingsExportScreen.kt`'s own updated doc comment on why `runCatching` stays as
     * defence-in-depth. A failing preview is exactly the `notMeasuredReason`/absent-signal shape
     * constitution I already asks for elsewhere: honestly absent, never a crash and never a
     * fabricated count.
     */
    @Test
    fun `R_1035 a previewCount that throws never crashes the screen — no preview row, Save file still works`() {
        var captured: ExportRequest? = null
        composeTestRule.setContent {
            OrtTheme {
                SettingsExportScreen(
                    state = state(),
                    onBack = {},
                    onSaveFile = { captured = it },
                    previewCount = { _, _ -> error("simulated previewCount failure") },
                    previewSizeBytes = fakePreviewSize().fn,
                )
            }
        }
        composeTestRule.onNodeWithTag("export-preview").assertDoesNotExist()
        scrollToText("Save file")
        composeTestRule.onNodeWithTag("export-save-file-button").assertIsEnabled()
        composeTestRule.onNodeWithTag("export-save-file-button").performClick()
        assertEquals(
            ExportRequest(ExportRequestScope.TONIGHT, ExportFileFormat.ADIF, includeTranscripts = true),
            captured,
        )
    }

    @Test
    fun `R_1035 the preview row is absent while A range of nights disables Save file`() {
        val preview = fakePreviewCount(ExportCountPreview(totalCount = 8, exportableCount = 0, excludedCount = 8))
        composeTestRule.setContent {
            OrtTheme {
                SettingsExportScreen(
                    state = state(),
                    onBack = {},
                    previewCount = preview.fn,
                    previewSizeBytes = fakePreviewSize().fn,
                )
            }
        }
        composeTestRule.onNodeWithTag("export-scope-range").performClick()
        composeTestRule.onNodeWithTag("export-preview").assertDoesNotExist()
    }

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

    // -----------------------------------------------------------------------------------------
    // R-1045(b) (register, design): the `Save file` button reads `Save file · <filename> ·
    // <size>` on the artboard; the build showed only `Save file`. [ExportCoordinator
    // .suggestedFileName] (bound to a fixed `Instant` here) is the exact function the real save
    // handler already names the file with — never a second, independently-invented naming rule.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `R_1045b the Save file button shows the real filename and the real size, from the same producer`() {
        val fixedInstant = Instant.parse("2026-09-07T23:14:00Z")
        composeTestRule.setContent {
            OrtTheme {
                SettingsExportScreen(
                    state = state(),
                    onBack = {},
                    previewCount = fakePreviewCount().fn,
                    previewSizeBytes = fakePreviewSize(184_000L).fn,
                    suggestedFileName = { request -> ExportCoordinator.suggestedFileName(request, fixedInstant) },
                )
            }
        }
        val expectedFileName = ExportCoordinator.suggestedFileName(
            ExportRequest(ExportRequestScope.TONIGHT, ExportFileFormat.ADIF),
            fixedInstant,
        )
        scrollToTag("export-save-file-button")
        composeTestRule.onNodeWithText(expectedFileName, substring = true).assertExists()
        composeTestRule.onNodeWithText("184 KB", substring = true).assertExists()
    }

    @Test
    fun `R_1045b the Save file button shows the filename alone, never a fabricated size, if the size fails`() {
        val fixedInstant = Instant.parse("2026-09-07T23:14:00Z")
        composeTestRule.setContent {
            OrtTheme {
                SettingsExportScreen(
                    state = state(),
                    onBack = {},
                    previewCount = fakePreviewCount().fn,
                    previewSizeBytes = { _, _ -> error("simulated previewSizeBytes failure") },
                    suggestedFileName = { request -> ExportCoordinator.suggestedFileName(request, fixedInstant) },
                )
            }
        }
        val expectedFileName = ExportCoordinator.suggestedFileName(
            ExportRequest(ExportRequestScope.TONIGHT, ExportFileFormat.ADIF),
            fixedInstant,
        )
        scrollToTag("export-save-file-button")
        composeTestRule.onNodeWithText(expectedFileName, substring = true).assertExists()
        composeTestRule.onAllNodesWithText("KB", substring = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("MB", substring = true).assertCountEquals(0)
    }

    @Test
    fun `R_1045b selecting A range of nights shows plain Save file, never naming a file that will not be written`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsExportScreen(
                    state = state(),
                    onBack = {},
                    previewCount = fakePreviewCount().fn,
                    previewSizeBytes = fakePreviewSize().fn,
                )
            }
        }
        composeTestRule.onNodeWithTag("export-scope-range").performClick()
        composeTestRule.onNodeWithTag("export-save-file-button").assertTextEquals("Save file")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1045b the Save file button grows to fit the wrapped label instead of clipping it at font scale 2_0`() {
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
        // `PrimaryButton`'s own `requiredHeightIn(min = 48.dp)` is a floor a single-line label never
        // exceeds by much even at this font scale — a button this much taller is only possible if
        // the real filename/size label actually wrapped onto more than one line, proving it got
        // room to wrap rather than being clipped or overflowing the button (constitution VIII: a
        // passing assertion here is not evidence on its own — the capture in this report is).
        assert(buttonHeight > 70.dp) {
            "expected the button to grow past a single line's worth of height at font scale 2.0 to " +
                "fit the wrapped filename/size label; got $buttonHeight"
        }
    }
}
