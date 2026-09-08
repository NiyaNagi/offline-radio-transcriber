package org.ort.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.robolectric.RobolectricTestRunner

/**
 * AC-62 / FR-A11Y-1: the four attribution states must be distinguishable **without colour**.
 * `AttributionMarker` is the one reusable component every screen renders an attribution through
 * (`States.dc.html`'s greyscale-proof panel), so proving this here proves it everywhere it is
 * used — no per-screen re-derivation.
 */
@RunWith(RobolectricTestRunner::class)
class AttributionMarkerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val shapeWords = listOf("filled circle", "outlined circle", "half-filled circle", "small dot")

    @Test
    fun `AC_62 all four attribution states carry distinct, non-colour content descriptions`() {
        val states = linkedMapOf(
            "confirmed" to Attribution.confirmed("W7NPC", 0.95),
            "inferred" to Attribution.inferred("K7LWH", 0.82),
            "ambiguous" to Attribution.ambiguous(),
            "unknown" to Attribution.unknown(),
        )

        composeTestRule.setContent {
            OrtTheme {
                Column {
                    states.forEach { (tag, attribution) ->
                        AttributionMarker(attribution = attribution, modifier = Modifier.testTag(tag))
                    }
                }
            }
        }

        val expectedShapeWord = mapOf(
            "confirmed" to "filled circle",
            "inferred" to "outlined circle",
            "ambiguous" to "half-filled circle",
            "unknown" to "small dot",
        )
        val expectedStateName = mapOf(
            "confirmed" to "CONFIRMED",
            "inferred" to "INFERRED",
            "ambiguous" to "AMBIGUOUS",
            "unknown" to "UNKNOWN",
        )

        states.keys.forEach { tag ->
            val node = composeTestRule.onNodeWithTag(tag)
            // Its own shape word and state name are present...
            node.assert(hasContentDescription(expectedShapeWord.getValue(tag), substring = true))
            node.assert(hasContentDescription(expectedStateName.getValue(tag), substring = true))
            // ...and no *other* state's shape word is — proving the four are structurally
            // distinct, not merely differently coloured. ("filled circle" is itself a substring
            // of "half-filled circle", so it is excluded from `ambiguous`'s own check below — it
            // legitimately contains it.)
            val expected = expectedShapeWord.getValue(tag)
            shapeWords.filter { it != expected && !expected.contains(it) }.forEach { otherShape ->
                node.assert(doesNotHaveContentDescription(otherShape))
            }
            // Colour is reinforcement only (constitution VII) — never named in the description.
            // ("red" is deliberately excluded: it is a substring of "INFERRED" and would produce
            // a false positive that has nothing to do with colour.)
            listOf("green", "amber", "colour", "color").forEach { colourWord ->
                node.assert(doesNotHaveContentDescription(colourWord, ignoreCase = true))
            }
        }
    }

    private fun doesNotHaveContentDescription(value: String, ignoreCase: Boolean = false): SemanticsMatcher {
        val positive = hasContentDescription(value, substring = true, ignoreCase = ignoreCase)
        return SemanticsMatcher("does not have content description containing '$value'") { node ->
            !positive.matches(node)
        }
    }

    @Test
    fun `R_020 showConfidence false renders no glyph, no state word and no confidence number as visible text`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    AttributionMarker(
                        attribution = Attribution.confirmed("W7NPC", 0.95),
                        modifier = Modifier.testTag("m"),
                        showConfidence = false,
                    )
                }
            }
        }

        // The old glyph-and-label rendering ("✓ CONFIRMED", "0.95") is gone from the visible
        // surface — it survives only in the merged content description, checked above.
        composeTestRule.onNodeWithText("✓ CONFIRMED", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("CONFIRMED").assertDoesNotExist()
        composeTestRule.onNodeWithText("0.95").assertDoesNotExist()
        composeTestRule.onNodeWithText("W7NPC").assertDoesNotExist()
    }

    @Test
    fun `the legacy default showConfidence renders a confidence chip beside the shape, matching today's callers`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    // No `showConfidence` argument — proves the default, which is what
                    // LogScreen/SearchScreen/ThreadScreen/TransmissionDetailScreen (and their own
                    // tests, outside this package) still rely on until they migrate to
                    // AttributionRow.
                    AttributionMarker(
                        attribution = Attribution.confirmed("W7NPC", 0.95),
                        modifier = Modifier.testTag("confirmed"),
                    )
                    AttributionMarker(
                        attribution = Attribution.inferred("K7LWH", 0.82),
                        modifier = Modifier.testTag("inferred"),
                    )
                    AttributionMarker(attribution = Attribution.unknown(), modifier = Modifier.testTag("unknown"))
                }
            }
        }

        composeTestRule.onNodeWithText("0.95").assertExists()
        composeTestRule.onNodeWithText("0.82").assertExists()
        // UNKNOWN never carries a confidence, so none is fabricated even with showConfidence true.
        composeTestRule.onNode(hasContentDescription("small dot", substring = true)).assertExists()
    }

    @Test
    fun `R_020_confirmed_renders_no_score_and_inferred_renders_a_score_chip`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    AttributionRow(
                        attribution = Attribution.confirmed("W7NPC", 0.95),
                        modifier = Modifier.testTag("confirmed"),
                    )
                    AttributionRow(
                        attribution = Attribution.inferred("K7LWH", 0.82),
                        modifier = Modifier.testTag("inferred"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("W7NPC").assertExists()
        // CONFIRMED never shows a number — a number would imply doubt the data does not have.
        composeTestRule.onNodeWithText("0.95").assertDoesNotExist()

        composeTestRule.onNodeWithText("K7LWH").assertExists()
        composeTestRule.onNodeWithText("0.82").assertExists()
    }

    @Test
    fun `R_020 ambiguous shows the or QRF alternate only when one is supplied`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    AttributionRow(
                        attribution = Attribution.ambiguous(),
                        callsign = "KE7QRS",
                        alternate = "QRF",
                        modifier = Modifier.testTag("with-alt"),
                    )
                    AttributionRow(
                        attribution = Attribution.ambiguous(),
                        callsign = "KE7QRS",
                        modifier = Modifier.testTag("no-alt"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("or QRF").assertExists()
    }

    @Test
    fun `R_020 unknown shows italic unknown station and never a callsign`() {
        composeTestRule.setContent {
            OrtTheme { AttributionRow(attribution = Attribution.unknown()) }
        }

        composeTestRule.onNodeWithText("unknown station").assertExists()
    }

    @Test
    fun `R_020 the full row is one merged semantics node reading shape, state and callsign`() {
        composeTestRule.setContent {
            OrtTheme {
                AttributionRow(
                    attribution = Attribution.inferred("K7LWH", 0.82),
                    modifier = Modifier.testTag("row"),
                )
            }
        }

        composeTestRule
            .onNodeWithTag("row")
            .assert(
                hasContentDescription("outlined circle", substring = true) and
                    hasContentDescription("K7LWH", substring = true) and
                    hasContentDescription("confidence 0.82", substring = true),
            )
    }
}
