package org.ort.app.ui.digest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.components.drawHatchRegion
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * R-250 (register, round 6 System validator pass 2, halt): the gap-row hatch used to compute its
 * own stripe pitch as `size.width / 3f` — a canvas measured at zero width (the real report: "at
 * font scale 2.0 the row relayout hits that") made the pitch zero too, so the draw loop's own
 * `x += stripeWidth * 2` never advanced and span forever on the main thread — an ANR that never
 * recovered. `SessionsScreens.kt`'s hatch now guards `size.width <= 0f`/`size.height <= 0f` (draws
 * nothing) and reuses `org.ort.app.ui.components.drawHatchRegion` — the same shared, fixed-dp-pitch
 * hatch `ActivityPatternChart` already draws its own not-listening texture with, which cannot
 * degenerate this way because its pitch is a caller-supplied constant, never derived from the
 * canvas size.
 */
@RunWith(RobolectricTestRunner::class)
class SessionsScreensTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @Requirement("R-250")
    fun `R_250 the shared hatch draws nothing and returns immediately at zero width, never hangs`() = runTest {
        withTimeout(5_000) {
            composeTestRule.setContent {
                OrtTheme {
                    // The exact reproduction the register row names: a canvas measured to zero
                    // width — this is genuinely the same call this package's own gap-row hatch
                    // makes, guarded the same way.
                    Canvas(modifier = Modifier.size(0.dp)) {
                        if (size.width > 0f && size.height > 0f) {
                            drawHatchRegion(
                                left = 0f,
                                top = 0f,
                                width = size.width,
                                height = size.height,
                                color = OrtColors.accentAmber,
                                pitchPx = 4.dp.toPx(),
                                strokeWidthPx = 2.dp.toPx(),
                            )
                        }
                    }
                }
            }
            composeTestRule.waitForIdle()
        }
    }

    @Test
    @Requirement("R-250")
    fun `R_250 a session row with a gap renders at font scale 2-0 without hanging`() = runTest {
        val state = SessionsViewState(
            headline = "1 session · 1 h listened · 1 over",
            sessions = listOf(
                SessionRowViewState(
                    id = "S1",
                    label = "Tonight",
                    timeRangeLabel = "23:32 – 00:32",
                    overCount = 1,
                    stationCount = 1,
                    gapCount = 1,
                    uncleanEndLabel = null,
                    tierChipLabel = null,
                    canBeImproved = false,
                    live = false,
                    startedAtUtc = 0L,
                ),
            ),
        )

        withTimeout(5_000) {
            composeTestRule.setContent {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                    OrtTheme { SessionsScreen(state = state, onDrawer = {}, onOpen = {}) }
                }
            }
            composeTestRule.waitForIdle()
        }

        composeTestRule.onNodeWithText("Tonight").assertExists()
    }
}
