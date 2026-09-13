package org.ort.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.components.TransportBar
import org.ort.app.ui.components.TransportBarActions
import org.ort.app.ui.components.TransportBarViewState
import org.ort.app.ui.screens.LogContent
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Register R-1049 (halt, severity halt — coordinator round, on top of the WPUI follow-up's own
 * R-1006 on-device proof): in Playback mode the transport bar filled almost the entire screen — a
 * full-height dark panel from just under the header to the bottom edge, the destination's own
 * content reduced to a sliver at the top. [TransportBarLayoutTest]'s own existing coverage never
 * caught this because it composes [TransportBar] alone inside an artificially fixed-height
 * `Box(Modifier.height(200.dp))` — bounding the very growth this register entry is about, so its
 * own "at least 44dp" floor assertion passed trivially against a bar that had already grown to fill
 * the fixed 200dp it was given. This file composes the *same scaffold shape*
 * [OrtNavHost.kt]'s own `NavHostBody` actually uses — a `Scaffold`, a `Column` filling it, a
 * `weight(1f)` content box on top (real [LogContent], not a stand-in — the coordinator's own "a
 * destination with content"), and [TransportBar] as a plain, unweighted sibling below — at the
 * tour's own real device height (`@Config(qualifiers = "w390dp-h844dp-420dpi")`/`"w480dp-h844dp-
 * 420dpi"`, both far larger than any fixed test `Box`), so an unbounded-height defect in the bar's
 * own composition has real room to run wild, exactly as it did on device.
 *
 * Deliberately **not** [OrtNavHost] itself with a real tapped-into-play transmission: this app's
 * only way to reach a genuine [TransportBarViewState.Playback] is a real
 * [org.ort.app.ui.audio.RealTransmissionAudioPlayer] decode-then-`AudioTrack` cycle, and that
 * file's own class doc says outright "Robolectric's `AudioTrack` shadow does not play sound" /
 * "does not faithfully reproduce real hardware initialization" — no UI-level test in this codebase
 * drives that real path end to end for exactly that reason (every one of them, `InspectionTest`
 * included, seeds a view state or a [org.ort.app.ui.audio.FakeTransmissionAudioPlayer] directly).
 * Seeding [TransportBarViewState.Playback] as a plain value here is the same discipline, applied to
 * a *layout* defect that has nothing to do with how playback state is produced — only with what the
 * real host does with it once it exists.
 */
@RunWith(RobolectricTestRunner::class)
class TransportBarHostLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun playback() = TransportBarViewState.Playback(
        transmissionId = "TX1",
        callsignLabel = "W7NPC",
        isPlaying = true,
        positionFraction = 0.33f,
        elapsedLabel = "0:08",
        totalLabel = "0:24",
        capturingDotVisible = true,
    )

    /** [OrtNavHost.kt]'s own `NavHostBody`, reproduced only in shape (`Scaffold` → `Column` →
     * `weight(1f)` content box → the bar as a plain sibling) — real [LogContent] as the content, a
     * real font-scale density, never an artificial fixed-height container. */
    private fun setContent(fontScale: Float) {
        composeTestRule.setContent {
            val realDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density = realDensity, fontScale = fontScale)) {
                OrtTheme {
                    Scaffold { padding ->
                        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                            Box(modifier = Modifier.weight(1f)) {
                                LogContent(context = context, sessionId = null, onOpen = {})
                            }
                            TransportBar(state = playback(), actions = TransportBarActions())
                        }
                    }
                }
            }
        }
    }

    /**
     * At font scale 1.0, `TransportBar.kt`'s own row is `heightIn(min = 44.dp)` content
     * (`transport-bar-toggle`/`transport-bar-clear` are fixed `.size(44.dp)` boxes, the tallest
     * fixed elements in the row) plus `10.dp` vertical padding on each side — 64dp exactly (measured
     * post-fix: 64.0dp at 390dp width, 63.76dp at 480dp width), nothing content-dependent to push it
     * taller. 70dp leaves a few px of real-density rounding headroom while still failing hard
     * against the pre-fix defect: run against the unfixed `ScrubTrack`, this same test measured the
     * row at 842.67dp (390dp width) / 684.67dp (480dp width) — effectively this 844dp-tall root's
     * own full height minus the header, not a rounding difference.
     */
    private fun assertBarBoundedAndBelowContent(maxBarHeight: Dp) {
        // Bar bounds read first, unconditionally: register R-1049's own defect squeezes the
        // `weight(1f)` content box so hard that its content can end up not displayed at all (found
        // running this very test against the unfixed code — asserting displayed-ness first only
        // obscured the real number this test exists to report).
        val bar = composeTestRule.onNodeWithTag("transport-bar-playback").getUnclippedBoundsInRoot()
        val barHeight = bar.bottom - bar.top
        assertTrue(
            "expected the playback bar's own row to be bounded (<= $maxBarHeight), got $barHeight — " +
                "register R-1049: a full-height panel, not a ~44-48dp strip",
            barHeight <= maxBarHeight,
        )

        composeTestRule.onNodeWithText("No overs yet.", substring = true).assertIsDisplayed()
        val content = composeTestRule.onNodeWithText("No overs yet.", substring = true).getUnclippedBoundsInRoot()
        assertTrue(
            "expected the destination's own content ('No overs yet.', bottom=${content.bottom}) to sit " +
                "above the bar's own top edge (${bar.top}) — register R-1049: the bar covered the content",
            content.bottom <= bar.top,
        )
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `R_1049 the playback bar is a bounded strip, not a full-height panel, at 390dp font scale 1_0`() {
        setContent(fontScale = 1f)
        assertBarBoundedAndBelowContent(maxBarHeight = 70.dp)
    }

    /**
     * Font scale 2.0: measured (fixed code) at 64.0dp at 390dp width and 63.76dp at 480dp width —
     * identical to the 1.0 case, since the row's own text at 2x still fits inside the 44dp icons'
     * own height, never forcing the row taller. 76dp leaves real headroom above that measured
     * number without coming anywhere near this 844dp-tall root's own height — the actual pre-fix
     * failure mode (measured at 842.67dp/684.67dp before the fix, this test's own commit history).
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `R_1049 the playback bar is a bounded strip, not a full-height panel, at 390dp font scale 2_0`() {
        setContent(fontScale = 2f)
        assertBarBoundedAndBelowContent(maxBarHeight = 76.dp)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w480dp-h844dp-420dpi")
    fun `R_1049 the playback bar is a bounded strip, not a full-height panel, at 480dp font scale 1_0`() {
        setContent(fontScale = 1f)
        assertBarBoundedAndBelowContent(maxBarHeight = 70.dp)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w480dp-h844dp-420dpi")
    fun `R_1049 the playback bar is a bounded strip, not a full-height panel, at 480dp font scale 2_0`() {
        setContent(fontScale = 2f)
        assertBarBoundedAndBelowContent(maxBarHeight = 76.dp)
    }
}
