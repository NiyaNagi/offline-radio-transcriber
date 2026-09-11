package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.debug.Scenarios
import org.ort.app.ui.settings.ModelsContent
import org.ort.app.ui.theme.OrtTheme
import org.ort.data.OrtDatabase
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * Register R-973 (halt, Reviewer D3, run 4a): under `model-missing` (a live/capturing session,
 * every real model uninstalled, no lexicon ever installed) CF04 rendered the Lexicon row's own
 * title and nothing else — no sub-line, no "Install a lexicon from a file", and nothing at all
 * below it (Space, Replacing an asset, the closing footer) — with free space still above the live
 * bar at 1.0 (this is not a scrolling shortfall, R-933's own concern; it reads as composition
 * stopping outright), and `@2x`/`@2x-end` byte-identical (nothing scrollable at 2.0 either, B3's
 * addendum).
 *
 * The prior version of this file hand-built a [org.ort.app.ui.data.LexiconAssetRowViewState] and
 * rendered [ModelsScreen] alone — the exact combination it composed already passed, because it
 * skipped everything [ModelsContent] itself does on the real path: [Scenarios.load] is what
 * `model-missing` actually runs, and [ModelsContent] — never [ModelsScreen] directly — is what
 * `OrtNavHost` mounts. This reproduces the real scenario load end to end (real DB, real
 * `CaptureState`/`AsrAvailability`, real [org.ort.app.ui.data.ModelsController.currentState]/
 * [org.ort.app.ui.data.ModelsController.lexiconRow]) and drives the real composable.
 */
@RunWith(RobolectricTestRunner::class)
class ModelsScreenLexiconMissingTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: OrtDatabase

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    @Test
    @Requirement("R-973")
    fun `R_973 model-missing renders the full screen, not just the Lexicon title`() = runTest {
        Scenarios.load(context, "model-missing")

        composeTestRule.setContent {
            OrtTheme {
                ModelsContent(context = context, modifier = androidx.compose.ui.Modifier)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("LEXICON").assertExists()
        composeTestRule.onNodeWithText("Callsign lexicon").performScrollTo().assertExists()
        composeTestRule
            .onNodeWithContentDescription("Install a lexicon from a file")
            .performScrollTo()
            .assertExists()
        composeTestRule
            .onNodeWithText("A corrupt file is refused and the old one stays", substring = true)
            .performScrollTo()
            .assertExists()
    }
}
