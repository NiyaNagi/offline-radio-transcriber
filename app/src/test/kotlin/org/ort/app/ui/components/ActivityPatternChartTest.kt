package org.ort.app.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.HourActivityBucket
import org.ort.app.ui.data.HourActivityState
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek

/**
 * FR-UI-11/FR-UI-12 (build-plan P17; R-021, ui-conformance-plan WP2): the activity-by-hour
 * component must be able to represent every hour that was never listened to, and must say so in
 * terms a screen reader can distinguish from "quiet" — not merely colour it differently
 * (constitution VII / FR-A11Y-1's floor, applied here the same way `AttributionMarkerTest` proves
 * it for the four attribution states). R-021 additionally requires the legend to be a shape (a
 * hatch swatch), never the `▨` font glyph (guide §7) — this file's assertions were updated from
 * "the glyph exists" to "the glyph never exists, the swatch legend does" when that fix landed.
 */
@RunWith(RobolectricTestRunner::class)
class ActivityPatternChartTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun bucket(hour: Int, state: HourActivityState, heardCount: Int = 0) =
        HourActivityBucket(hourOfDayUtc = hour, state = state, heardCount = heardCount)

    @Test
    fun `FR_UI_12 a pattern with a not-listening hour is announced as such, not silently as quiet`() {
        val pattern = (0..23).map { hour ->
            when (hour) {
                3 -> bucket(hour, HourActivityState.NOT_LISTENING)
                5 -> bucket(hour, HourActivityState.HEARD, heardCount = 2)
                else -> bucket(hour, HourActivityState.SILENT_WHILE_LISTENING)
            }
        }

        composeTestRule.setContent { OrtTheme { ActivityPatternChart(pattern = pattern) } }

        composeTestRule
            .onNode(hasContentDescription("not listening", substring = true, ignoreCase = true))
            .assertIsDisplayed()
    }

    @Test
    fun `R_021 the not-listening legend is a hatch swatch, never the font glyph`() {
        val pattern = (0..23).map { hour ->
            if (hour == 3) {
                bucket(hour, HourActivityState.NOT_LISTENING)
            } else {
                bucket(hour, HourActivityState.SILENT_WHILE_LISTENING)
            }
        }

        composeTestRule.setContent { OrtTheme { ActivityPatternChart(pattern = pattern, notListeningLabel = "38 s") } }

        composeTestRule.onNodeWithText("▨ not listening").assertDoesNotExist()
        composeTestRule.onNodeWithText("not listening · 38 s").assertIsDisplayed()
    }

    @Test
    fun `a pattern with no not-listening hours at all shows no not-listening legend`() {
        val pattern = (0..23).map { hour -> bucket(hour, HourActivityState.SILENT_WHILE_LISTENING) }

        composeTestRule.setContent { OrtTheme { ActivityPatternChart(pattern = pattern) } }

        composeTestRule.onNodeWithText("not listening").assertDoesNotExist()
    }

    @Test
    fun `the summary content description reports honest counts for every state`() {
        val pattern = listOf(
            bucket(0, HourActivityState.HEARD, heardCount = 1),
            bucket(1, HourActivityState.SILENT_WHILE_LISTENING),
            bucket(2, HourActivityState.NOT_LISTENING),
        )

        composeTestRule.setContent { OrtTheme { ActivityPatternChart(pattern = pattern) } }

        composeTestRule.onNode(
            hasContentDescription(
                "Activity by hour of day: 1 hours heard, 1 hours quiet while listening, 1 hours not listening",
            ),
        ).assertIsDisplayed()
    }

    @Test
    fun `R_021 axis labels render at both ends when supplied`() {
        val pattern = (0..23).map { hour -> bucket(hour, HourActivityState.SILENT_WHILE_LISTENING) }

        composeTestRule.setContent {
            OrtTheme { ActivityPatternChart(pattern = pattern, axisStart = "22:00", axisEnd = "06:00") }
        }

        composeTestRule.onNodeWithText("22:00").assertIsDisplayed()
        composeTestRule.onNodeWithText("06:00").assertIsDisplayed()
    }

    @Test
    fun `AC_62 a day-of-week grid distinguishes heard, quiet and not-listening cells without colour alone`() {
        val cells = DayOfWeek.entries.flatMap { day ->
            (0 until 24).map { hour ->
                val state = when {
                    day == DayOfWeek.SATURDAY -> HourActivityState.NOT_LISTENING
                    day == DayOfWeek.TUESDAY && hour == 19 -> HourActivityState.HEARD
                    else -> HourActivityState.SILENT_WHILE_LISTENING
                }
                DayHourCell(dayOfWeek = day, hourOfDayUtc = hour, state = state)
            }
        }

        composeTestRule.setContent { OrtTheme { DayOfWeekGrid(cells = cells) } }

        composeTestRule
            .onNode(hasContentDescription("not listening", substring = true, ignoreCase = true))
            .assertIsDisplayed()
    }

    @Test
    fun `R_209_the default orientation lays days out as rows, Mon through Sun, per Station-Pattern_dc_html`() {
        val cells = DayOfWeek.entries.flatMap { day ->
            (0 until 24).map { hour -> DayHourCell(day, hour, HourActivityState.SILENT_WHILE_LISTENING) }
        }

        composeTestRule.setContent { OrtTheme { DayOfWeekGrid(cells = cells) } }

        // The board's own 3-letter day labels down the left edge — not the transposed layout's
        // single-letter initials, which give six of seven days no way to tell them apart at all
        // ("M"/"T"/"W"/"T"/"F"/"S"/"S").
        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach {
            composeTestRule.onNodeWithText(it).assertIsDisplayed()
        }
        // An hour axis along the top — entirely absent from the pre-R-209 layout.
        composeTestRule.onNodeWithText("00").assertIsDisplayed()
        composeTestRule.onNodeWithText("06").assertIsDisplayed()
        composeTestRule.onNodeWithText("12").assertIsDisplayed()
        composeTestRule.onNodeWithText("18").assertIsDisplayed()
    }

    @Test
    fun `R_209_the previous HoursAsRows orientation is still available for a caller that needs it`() {
        val cells = DayOfWeek.entries.flatMap { day ->
            (0 until 24).map { hour -> DayHourCell(day, hour, HourActivityState.SILENT_WHILE_LISTENING) }
        }

        composeTestRule.setContent {
            OrtTheme {
                DayOfWeekGrid(cells = cells, orientation = DayOfWeekGridOrientation.HoursAsRows)
            }
        }

        // The previous layout's exact single-letter day initials, unchanged — proving the old
        // behaviour survives, opted into, rather than being deleted (constitution III).
        composeTestRule.onNodeWithText("M").assertIsDisplayed()
        composeTestRule.onNodeWithText("W").assertIsDisplayed()
        composeTestRule.onNodeWithText("F").assertIsDisplayed()
        // No 3-letter labels or hour axis in this orientation.
        composeTestRule.onNodeWithText("Mon").assertDoesNotExist()
        composeTestRule.onNodeWithText("00").assertDoesNotExist()
    }

    @Test
    fun `R_209_the legend reads the board's own wording, not the previous heard-slash-quiet copy`() {
        val cells = DayOfWeek.entries.flatMap { day ->
            (0 until 24).map { hour -> DayHourCell(day, hour, HourActivityState.HEARD) }
        }

        composeTestRule.setContent { OrtTheme { DayOfWeekGrid(cells = cells) } }

        composeTestRule.onNodeWithText("listened, not heard").assertIsDisplayed()
        composeTestRule.onNodeWithText("heard often").assertIsDisplayed()
        // The old, imprecise pair — gone, not merely joined by the new one.
        composeTestRule.onNodeWithText("heard").assertDoesNotExist()
        composeTestRule.onNodeWithText("quiet").assertDoesNotExist()
    }

    @Test
    fun `R_072_a null title draws no title row rather than requiring callers to pass an empty string`() {
        val pattern = (0..23).map { hour -> bucket(hour, HourActivityState.SILENT_WHILE_LISTENING) }

        composeTestRule.setContent {
            OrtTheme { ActivityPatternChart(pattern = pattern, title = null) }
        }

        composeTestRule.onNodeWithText("ACTIVITY BY HOUR (UTC)").assertDoesNotExist()
    }

    @Test
    fun `the default title is unchanged for callers that do not pass one`() {
        val pattern = (0..23).map { hour -> bucket(hour, HourActivityState.SILENT_WHILE_LISTENING) }

        composeTestRule.setContent { OrtTheme { ActivityPatternChart(pattern = pattern) } }

        composeTestRule.onNodeWithText("ACTIVITY BY HOUR (UTC)").assertIsDisplayed()
    }

    @Test
    fun `a sparkline reports how many of its nights were not-listening rather than quiet`() {
        val nights = listOf(
            HourActivityState.HEARD,
            HourActivityState.SILENT_WHILE_LISTENING,
            HourActivityState.NOT_LISTENING,
        )

        composeTestRule.setContent { OrtTheme { Sparkline(nights = nights) } }

        composeTestRule.onNodeWithContentDescription("3 nights: 1 heard, 1 not listening").assertIsDisplayed()
    }

    @Test
    fun `R_271_hour label stride doubles once font scale reaches 2_0, never before`() {
        assertEquals(6, hourLabelStride(1f))
        assertEquals(6, hourLabelStride(1.5f))
        assertEquals(12, hourLabelStride(2f))
        assertEquals(12, hourLabelStride(3f))
    }

    private fun daysAsRowsCells() = DayOfWeek.entries.flatMap { day ->
        (0 until 24).map { hour -> DayHourCell(day, hour, HourActivityState.SILENT_WHILE_LISTENING) }
    }

    private fun assertHourAxisNeverWrapsOrCollides(fontScale: Float, expectedLabels: List<String>) {
        // The register's own repro (`stations-14-nights/ST03-hourxday-pass2.png`): at a large font
        // scale a 2-digit mono hour label no longer fits its own 1/24-width grid column. This
        // proves, at a real device content width, both halves of the fix: the stride drop (fewer
        // labels shown at 2.0) and `softWrap = false` (the shown labels never break onto a second
        // line) leave no two labels' own rendered bounds overlapping.
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                OrtTheme {
                    DayOfWeekGrid(cells = daysAsRowsCells(), modifier = Modifier.width(340.dp))
                }
            }
        }

        val rects = expectedLabels.map { label ->
            composeTestRule.onNodeWithText(label, useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        }
        // Never wrapped: a label that broke onto a second line would report a taller-than-one-line
        // box; every shown label here is the same fixed-height single glyph row.
        val heights = rects.map { it.height }.distinct()
        assertEquals(
            "labels at font scale $fontScale rendered at different heights (a wrap changes height): " +
                "$heights",
            1,
            heights.size,
        )
        // Never collide: no two labels' own horizontal extents overlap.
        for (i in rects.indices) {
            for (j in i + 1 until rects.size) {
                val a = rects[i]
                val b = rects[j]
                val noOverlap = a.right <= b.left || b.right <= a.left
                assertTrue(
                    "labels '${expectedLabels[i]}' and '${expectedLabels[j]}' overlap at font scale " +
                        "$fontScale: $a vs $b",
                    noOverlap,
                )
            }
        }
    }

    @Test
    fun `R_271_the DaysAsRows hour axis never wraps and its labels never collide, at font scale 1_0`() {
        assertHourAxisNeverWrapsOrCollides(fontScale = 1f, expectedLabels = listOf("00", "06", "12", "18"))
    }

    @Test
    fun `R_271_the DaysAsRows hour axis never wraps and its labels never collide, at font scale 2_0`() {
        assertHourAxisNeverWrapsOrCollides(fontScale = 2f, expectedLabels = listOf("00", "12"))
    }

    @Test
    fun `R_271_the HoursAsRows day axis also never wraps at font scale 2_0`() {
        // "Both orientations" — this layout's own axis row (day initials along the bottom) carries
        // the same explicit `maxLines = 1, softWrap = false` guarantee, even though a single mono
        // letter was never observed to wrap.
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    DayOfWeekGrid(
                        cells = daysAsRowsCells(),
                        orientation = DayOfWeekGridOrientation.HoursAsRows,
                        modifier = Modifier.width(340.dp),
                    )
                }
            }
        }

        // M, W, F only — T (Tue/Thu) and S (Sat/Sun) each render twice, ambiguous for
        // onNodeWithText; the existing R_209 test for this orientation avoids them for the same
        // reason.
        val rects = listOf("M", "W", "F").map { label ->
            composeTestRule.onNodeWithText(label, useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        }
        val heights = rects.map { it.height }.distinct()
        assertEquals("day initials at font scale 2.0 rendered at different heights: $heights", 1, heights.size)
    }
}
