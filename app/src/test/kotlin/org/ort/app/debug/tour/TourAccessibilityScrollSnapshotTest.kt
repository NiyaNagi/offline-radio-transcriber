package org.ort.app.debug.tour

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.components.LOADING_STATE_TEST_TAG
import org.ort.app.ui.components.LoadingState
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/** See [TourAccessibilityTapTest]'s own identical helper — `testTag` is invisible to the platform
 * accessibility tree unless some ancestor opts into [testTagsAsResourceId]. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WithTestTagsAsResourceId(content: @Composable () -> Unit) {
    Box(modifier = Modifier.semantics { testTagsAsResourceId = true }) { content() }
}

/**
 * Register R-1022 (halt): [TourAccessibilityScroll.snapshot]'s own `hasPlaceholder` used to be a
 * case-insensitive substring match against the word "loading" in rendered text — the actual cause
 * of R-1051, since a false-empty screen's real copy ("No overs yet.") never contained that word.
 * Replaced with the structural marker [LOADING_STATE_TEST_TAG] this session's
 * [org.ort.app.ui.components.LoadingState] carries. These tests prove both directions: the marker
 * gates the wait regardless of what the node's own text says, and text alone — even the literal
 * word "loading" — never does, so a designer freely rewording any screen's copy can never flip
 * this check either way.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TourAccessibilityScrollSnapshotTest {

    @Test
    fun `R_1022 the real LoadingState is detected as a placeholder by its structural marker`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            WithTestTagsAsResourceId { LoadingState(message = "Fetching tonight's overs") }
        }
        shadowOf(Looper.getMainLooper()).idle()

        val snapshot = TourAccessibilityScroll.snapshot(activity.window.decorView)

        assertEquals(true, snapshot.hasPlaceholder)
    }

    @Test
    fun `R_1022 a screen whose real copy contains the word loading is never treated as a placeholder`() {
        // The exact class of screen the old substring match could false-negative or false-positive
        // on — real, designer-owned prose that happens to contain "loading", carrying no structural
        // marker at all. Constitution II: this must never gate the tour's own readiness check.
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            WithTestTagsAsResourceId { Text("Now loading the lexicon into the resolver, this is real data") }
        }
        shadowOf(Looper.getMainLooper()).idle()

        val snapshot = TourAccessibilityScroll.snapshot(activity.window.decorView)

        assertEquals(false, snapshot.hasPlaceholder)
    }

    @Test
    fun `R_1022 a real empty state carries no placeholder marker`() {
        // R-1051's own false-empty shape: honest copy with no "loading" substring and, correctly,
        // no structural marker either — this is the real "genuinely nothing recorded" case, which
        // must let the tour proceed, not stall it.
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            WithTestTagsAsResourceId { Text("No overs yet. Listening since 23:32.") }
        }
        shadowOf(Looper.getMainLooper()).idle()

        val snapshot = TourAccessibilityScroll.snapshot(activity.window.decorView)

        assertEquals(false, snapshot.hasPlaceholder)
    }

    @Test
    fun `snapshot still collects every node's own text, independent of the placeholder check`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            WithTestTagsAsResourceId { LoadingState(message = "Fetching tonight's overs") }
        }
        shadowOf(Looper.getMainLooper()).idle()

        val snapshot = TourAccessibilityScroll.snapshot(activity.window.decorView)

        assertEquals(true, snapshot.text.contains("Fetching tonight's overs"))
    }
}
