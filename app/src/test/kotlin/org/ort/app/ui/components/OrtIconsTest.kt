package org.ort.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * R-025 (ui-conformance-plan WP2): the stroke icon set from `Icons.dc.html`/guide §7 — none of
 * this existed before; screens drew `=`, `▶`, `‹`, `▸`/`▾`, `▨` as text glyphs.
 */
@RunWith(RobolectricTestRunner::class)
class OrtIconsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val allIcons = listOf(
        "drawer" to OrtIcons.drawer,
        "back" to OrtIcons.back,
        "search" to OrtIcons.search,
        "more" to OrtIcons.more,
        "chevron" to OrtIcons.chevron,
        "dismiss" to OrtIcons.dismiss,
        "expand" to OrtIcons.expand,
        "filters" to OrtIcons.filters,
        "now" to OrtIcons.now,
        "log" to OrtIcons.log,
        "threads" to OrtIcons.threads,
        "stations" to OrtIcons.stations,
        "frequencies" to OrtIcons.frequencies,
        "earlierNights" to OrtIcons.earlierNights,
        "capture" to OrtIcons.capture,
        "improve" to OrtIcons.improve,
        "settings" to OrtIcons.settings,
        "gapWarn" to OrtIcons.gapWarn,
        "halt" to OrtIcons.halt,
        "check" to OrtIcons.check,
        "play" to OrtIcons.play,
        "recent" to OrtIcons.recent,
        "lock" to OrtIcons.lock,
        "call" to OrtIcons.call,
        "thermal" to OrtIcons.thermal,
        "usbAudio" to OrtIcons.usbAudio,
        "headset" to OrtIcons.headset,
        "builtInMic" to OrtIcons.builtInMic,
        "rig" to OrtIcons.rig,
        "storage" to OrtIcons.storage,
        "models" to OrtIcons.models,
        "export" to OrtIcons.export,
        "diagnostics" to OrtIcons.diagnostics,
        "edit" to OrtIcons.edit,
    )

    @Test
    fun `R_025 every required icon exists and renders without crashing`() {
        composeTestRule.setContent {
            OrtTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    allIcons.forEach { (name, vector) ->
                        Icon(
                            imageVector = vector,
                            contentDescription = name,
                            tint = OrtColors.textIconDim,
                            modifier = Modifier.testTag(name),
                        )
                    }
                }
            }
        }

        // A scrollable Column so 34 icons composing at once in a small test window doesn't push
        // later ones off-screen; existence (not visibility) is what "renders without crashing"
        // needs — every one of these is reachable via its own testTag.
        allIcons.forEach { (name, _) -> composeTestRule.onNodeWithTag(name).assertExists() }
    }

    @Test
    fun `R_025 every icon has at least one path, so none renders as an empty glyph`() {
        allIcons.forEach { (name, vector) ->
            assert(vector.root.size > 0) { "$name has no path data" }
        }
    }

    @Test
    fun `an in-progress ring renders without a Context or polling`() {
        composeTestRule.setContent {
            OrtTheme { InProgressRing(modifier = Modifier.testTag("ring")) }
        }

        composeTestRule.onNodeWithTag("ring").assertExists()
    }
}
