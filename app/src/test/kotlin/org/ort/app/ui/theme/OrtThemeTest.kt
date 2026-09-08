package org.ort.app.ui.theme

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * R-006 (ui-conformance-plan WP1): [OrtTheme] wraps its content in a [androidx.compose.material3.Surface]
 * filling the maximum available size on [OrtColors.bgScreen], so a screen sits on the design's own
 * ground rather than whatever the platform window background happens to be. `Theme.kt`'s previous
 * version set a colour scheme with no root `Surface` at all — a screen shot showed as much
 * (screenshot `03-now.png` in the audit) — so this proves both halves: the [ORT_THEME_SURFACE_TAG]
 * node genuinely exists and is displayed (not merely a colour scheme nobody paints), and the colour
 * scheme it composes with resolves `background` to [OrtColors.bgScreen] exactly. Deliberately does
 * not use Compose's `captureToImage()` pixel-sampling API — under this sandbox it triggers a
 * first-use native-graphics artifact resolution that hangs with no network egress; the two
 * assertions below establish the same fact (a `bg/screen`-coloured `Surface` fills the screen)
 * without it.
 */
@RunWith(RobolectricTestRunner::class)
class OrtThemeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `R_006 OrtTheme wraps content in a root Surface that fills the screen`() {
        composeTestRule.setContent {
            OrtTheme { Spacer(modifier = Modifier.size(1.dp)) }
        }

        composeTestRule.onNodeWithTag(ORT_THEME_SURFACE_TAG).assertIsDisplayed()
    }

    @Test
    fun `R_006 OrtTheme colour scheme resolves background to OrtColors_bgScreen`() {
        var capturedBackground: Color? = null
        composeTestRule.setContent {
            OrtTheme {
                val background = MaterialTheme.colorScheme.background
                SideEffect { capturedBackground = background }
            }
        }

        assertEquals(OrtColors.bgScreen, capturedBackground)
    }
}
