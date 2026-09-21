package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.components.LOADING_STATE_TEST_TAG
import org.ort.app.ui.data.TranscriptVersionViewState
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * Register R-1022/R-1051 (halt, constitution I/IV): [DetailRevisionsScreen]'s own `loading`
 * parameter — before it existed, `RevisionsDestination`'s `emptyList()` seed rendered this
 * screen's real "0 versions · none deleted…" header with no cards, a fabricated-looking result
 * (every transmission has at least one version) indistinguishable from a genuine, impossible
 * empty state.
 */
@RunWith(RobolectricTestRunner::class)
class DetailRevisionsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `R_1022 loading true renders the shared loading state, never a 0 versions header`() {
        composeTestRule.setContent {
            OrtTheme {
                DetailRevisionsScreen(
                    parentLabel = "W7NPC · 02:14",
                    versions = emptyList(),
                    onRestore = {},
                    onBack = {},
                    loading = true,
                )
            }
        }

        composeTestRule.onNodeWithTag(LOADING_STATE_TEST_TAG).assertExists()
        composeTestRule.onNodeWithText("0 versions", substring = true).assertDoesNotExist()
    }

    @Test
    fun `loading false with no versions still renders the real (fabricated-looking but honest) header`() {
        // Unchanged pre-existing behaviour, kept as a discriminating counterpart to the test above —
        // `loading` is the only new gate; a caller that has genuinely finished loading and still has
        // no versions is not this task's concern (does not happen in the real app, per this screen's
        // own class doc comment) but must not silently start showing the loading state instead.
        composeTestRule.setContent {
            OrtTheme {
                DetailRevisionsScreen(
                    parentLabel = "W7NPC · 02:14",
                    versions = emptyList(),
                    onRestore = {},
                    onBack = {},
                    loading = false,
                )
            }
        }

        composeTestRule.onNodeWithTag(LOADING_STATE_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithText("0 versions", substring = true).assertExists()
    }

    // R-1142 (constitution I, R-1099): the card built `Attribution.unknown().withCorrection(...)`
    // -- forcing AttributionState.INFERRED and the "corrected" flag -- whenever a version carried
    // a `stationId`, without checking `version.corrected`. The pre-existing "corrected" text
    // `Badge` was already (accidentally) gated on `version.corrected`, so the real, visible defect
    // is the `AttributionRow` itself: a version this build never marked as corrected got the same
    // "outlined circle, KJ7ABC" INFERRED marker a genuine human correction gets, misrepresenting
    // whatever its real attribution state actually was (this `ViewState` carries no other one to
    // render honestly instead — see `TranscriptVersionViewState`'s own doc comment). The fix gates
    // the whole row on `version.corrected` via the shared
    // `ReaderTransmissionViewStateMapper.correctedAttributionOrNull`, so an uncorrected version
    // shows no attribution row at all rather than a fabricated one.

    @Test
    fun `R_1142 a station with no correction shows no attribution row at all`() {
        composeTestRule.setContent {
            OrtTheme {
                DetailRevisionsScreen(
                    parentLabel = "W7NPC · 02:14",
                    versions = listOf(
                        TranscriptVersionViewState(
                            id = "V1",
                            text = "kilo juliet seven",
                            timeLabel = "02:14:07",
                            who = "Pass B · tiny",
                            isCurrent = true,
                            stationId = "KJ7ABC",
                            corrected = false,
                        ),
                    ),
                    onRestore = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText("KJ7ABC").assertDoesNotExist()
        composeTestRule.onNodeWithText("corrected", ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun `R_1142 a station with a real correction still shows the attribution row and the corrected badge`() {
        composeTestRule.setContent {
            OrtTheme {
                DetailRevisionsScreen(
                    parentLabel = "W7NPC · 02:14",
                    versions = listOf(
                        TranscriptVersionViewState(
                            id = "V1",
                            text = "kilo juliet seven",
                            timeLabel = "02:14:07",
                            who = "Pass B · tiny",
                            isCurrent = true,
                            stationId = "KJ7ABC",
                            corrected = true,
                        ),
                    ),
                    onRestore = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText("KJ7ABC").assertExists()
        composeTestRule.onNodeWithText("corrected", ignoreCase = true).assertExists()
    }
}
