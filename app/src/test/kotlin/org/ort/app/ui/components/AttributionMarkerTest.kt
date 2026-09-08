package org.ort.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
}
