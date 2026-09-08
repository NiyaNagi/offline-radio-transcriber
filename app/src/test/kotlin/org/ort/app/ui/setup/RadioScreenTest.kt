package org.ort.app.ui.setup

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/** R-084 (ui-conformance-plan WP9) — `Setup-Rig.dc.html` (S09): a closed set of three navigation
 * rows, no filled `Continue` on this board at all. */
@RunWith(RobolectricTestRunner::class)
class RadioScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `R_084 choosing the TH-D75A row reports that exact choice`() {
        var chosen: RadioChoice? = null
        composeTestRule.setContent {
            OrtTheme { RadioScreen(onChoose = { chosen = it }, onNotNow = {}) }
        }

        composeTestRule.onNodeWithTag("setup-radio-th-d75a").performClick()
        assert(chosen == RadioChoice.TH_D75A)
    }

    @Test
    fun `R_084 choosing No radio reports NONE`() {
        var chosen: RadioChoice? = null
        composeTestRule.setContent {
            OrtTheme { RadioScreen(onChoose = { chosen = it }, onNotNow = {}) }
        }

        composeTestRule.onNodeWithTag("setup-radio-none").performScrollTo().performClick()
        assert(chosen == RadioChoice.NONE)
    }

    @Test
    fun `R_084 Not now invokes its own callback, distinct from choosing a row`() {
        var notNow = false
        composeTestRule.setContent {
            OrtTheme { RadioScreen(onChoose = {}, onNotNow = { notNow = true }) }
        }

        composeTestRule.onNodeWithTag("setup-radio-not-now").performClick()
        assert(notNow)
    }
}
