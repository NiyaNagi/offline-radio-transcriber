package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.components.LOADING_STATE_TEST_TAG
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
}
