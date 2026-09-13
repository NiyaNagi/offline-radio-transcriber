package org.ort.app.ui.failures

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Register R-1061: [bannerCapViewportHeight] is [FailureHost]'s own `BannerOverlay` cap basis,
 * pulled out to a plain function purely so the reservation this file's own [FailureHost] kdoc
 * documents is directly unit-testable — no `BoxWithConstraints`, no font scale, no real device
 * text metrics, none of which this decision depends on.
 *
 * Discriminates against the pre-fix shape directly: before this round, `FailureHost`'s own
 * `BannerOverlay` call always passed the raw `BoxWithConstraints` `maxHeight` straight through as
 * `viewportHeight`, with no reservation at all — equivalent to this function always returning its
 * first argument unchanged, which the second and third cases below would catch (a `0.dp`-reserved
 * call still equalling the input is not enough on its own to prove the reservation exists; a
 * *non-zero* reserve subtracted from the total is).
 */
class BannerCapViewportHeightTest {

    @Test
    fun `no reservation leaves the full viewport height untouched`() {
        assertEquals(800.dp, bannerCapViewportHeight(totalViewportHeight = 800.dp, reservedBottomHeight = 0.dp))
    }

    @Test
    fun `a live bar reservation is subtracted before the cap is applied`() {
        assertEquals(704.dp, bannerCapViewportHeight(totalViewportHeight = 800.dp, reservedBottomHeight = 96.dp))
    }

    @Test
    fun `a reservation taller than the viewport floors at zero, never negative`() {
        assertEquals(0.dp, bannerCapViewportHeight(totalViewportHeight = 50.dp, reservedBottomHeight = 96.dp))
    }
}
