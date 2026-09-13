package org.ort.app.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.export.ExportCountPreview
import org.ort.app.export.ExportFileFormat
import org.ort.app.export.ExportRequest
import org.ort.app.export.ExportRequestScope
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

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
        composeTestRule.onNodeWithTag("export-screen-scroll").performScrollToNode(hasText(text))
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

    @Test
    fun `R_1009 Save file is enabled by default (Tonight, the initial scope)`() {
        composeTestRule.setContent {
            OrtTheme { SettingsExportScreen(state = state(), onBack = {}, previewCount = fakePreviewCount().fn) }
        }
        scrollToText("Save file")
        composeTestRule.onNodeWithText("Save file").assertIsEnabled()
    }

    @Test
    fun `R_1009 selecting A range of nights disables Save file and shows the honest reason`() {
        composeTestRule.setContent {
            OrtTheme { SettingsExportScreen(state = state(), onBack = {}, previewCount = fakePreviewCount().fn) }
        }
        composeTestRule.onNodeWithTag("export-scope-range").performClick()
        scrollToText("Save file")
        composeTestRule.onNodeWithText("Save file").assertIsNotEnabled()
        composeTestRule.onNodeWithText("A range of nights is not available in this build").assertExists()
    }

    @Test
    fun `R_1009 selecting Everything keeps Save file enabled, no fake unavailability`() {
        composeTestRule.setContent {
            OrtTheme { SettingsExportScreen(state = state(), onBack = {}, previewCount = fakePreviewCount().fn) }
        }
        composeTestRule.onNodeWithTag("export-scope-everything").performClick()
        scrollToText("Save file")
        composeTestRule.onNodeWithText("Save file").assertIsEnabled()
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
                )
            }
        }
        scrollToText("Save file")
        composeTestRule.onNodeWithText("Save file").performClick()
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
                )
            }
        }
        composeTestRule.onNodeWithTag("export-scope-everything").performClick()
        scrollToText("CSV")
        composeTestRule.onNodeWithText("CSV").performClick()
        scrollToText("Save file")
        composeTestRule.onNodeWithText("Save file").performClick()
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
                )
            }
        }
        composeTestRule.onNodeWithTag("export-checkbox-transcripts").performClick()
        scrollToText("Save file")
        composeTestRule.onNodeWithText("Save file").performClick()
        assert(captured?.includeTranscripts == false) { "expected includeTranscripts=false, got $captured" }
    }

    @Test
    fun `R_1009 Digest, history and audio checkboxes are unaffected by a tap — not yet wired`() {
        composeTestRule.setContent {
            OrtTheme { SettingsExportScreen(state = state(), onBack = {}, previewCount = fakePreviewCount().fn) }
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
            OrtTheme { SettingsExportScreen(state = state(), onBack = {}, previewCount = fakePreviewCount().fn) }
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
            OrtTheme { SettingsExportScreen(state = state(), onBack = {}, previewCount = preview.fn) }
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
            OrtTheme { SettingsExportScreen(state = state(), onBack = {}, previewCount = preview.fn) }
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
            OrtTheme { SettingsExportScreen(state = state(), onBack = {}, previewCount = preview.fn) }
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
                )
            }
        }
        composeTestRule.onNodeWithTag("export-scope-everything").performClick()
        scrollToText("JSON")
        composeTestRule.onNodeWithText("JSON").performClick()
        scrollToText("Save file")
        composeTestRule.onNodeWithText("Save file").performClick()
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
            OrtTheme { SettingsExportScreen(state = state(), onBack = {}, previewCount = preview.fn) }
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
                )
            }
        }
        composeTestRule.onNodeWithTag("export-preview").assertDoesNotExist()
        scrollToText("Save file")
        composeTestRule.onNodeWithText("Save file").assertIsEnabled()
        composeTestRule.onNodeWithText("Save file").performClick()
        assertEquals(
            ExportRequest(ExportRequestScope.TONIGHT, ExportFileFormat.ADIF, includeTranscripts = true),
            captured,
        )
    }

    @Test
    fun `R_1035 the preview row is absent while A range of nights disables Save file`() {
        val preview = fakePreviewCount(ExportCountPreview(totalCount = 8, exportableCount = 0, excludedCount = 8))
        composeTestRule.setContent {
            OrtTheme { SettingsExportScreen(state = state(), onBack = {}, previewCount = preview.fn) }
        }
        composeTestRule.onNodeWithTag("export-scope-range").performClick()
        composeTestRule.onNodeWithTag("export-preview").assertDoesNotExist()
    }
}
