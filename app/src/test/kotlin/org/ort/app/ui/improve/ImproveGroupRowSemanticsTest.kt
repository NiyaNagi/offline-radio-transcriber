package org.ort.app.ui.improve

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-1108 (register; R-1090's own sweep left `ImproveScreens.kt` out of its list): `ImproveGroupRow`
 * carried the identical `clickable` + trailing `semantics(mergeDescendants = true)` shape a real
 * device dump proved exports an *empty* `content-desc` on the clickable node, stranding the real
 * label on a non-clickable child (`CheckboxRow`/`ToggleRow`'s own R-1090 finding, `Controls.kt`'s
 * doc comment). The fix — `clearAndSetSemantics` with an explicit, multi-entry
 * `SemanticsProperties.Text` list — is confirmed working there and in `SearchScreen.kt`'s
 * `RecentRow`; this test proves the same shape for this row.
 *
 * At `w390dp-h844dp-420dpi` per the working agreement's own layout-test requirement for a change
 * under `app/src/main/kotlin/org/ort/app/ui` (recursively).
 *
 * What this test proves and does not: it proves the row's own clickable node carries a real,
 * non-empty `ContentDescription` and that its label/sub-line stay independently findable by
 * `onNodeWithText` on Robolectric's merged semantics tree — the same two things
 * `ToggleCheckboxSemanticsTest` already proves for `CheckboxRow`/`ToggleRow`. It does **not** prove
 * what a real device's TalkBack announces: `Controls.kt`'s own doc comment for `CheckboxRow`
 * records that a plain `semantics(mergeDescendants = true)` block passes this exact kind of
 * Robolectric check while a real `uiautomator` dump of the same shape showed an empty
 * `content-desc` — only a device dump (the lead's own batch tour) settles that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-420dpi")
class ImproveGroupRowSemanticsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val group = ImproveGroupViewState(
        id = "field-thu",
        headline = "Field, Thu 3 Sep",
        subLine = "12 overs · tier 1 · this phone can do more",
        overCount = 12,
        transmissionIds = (1..12).map { "TX$it" },
        tierOrdinal = 1,
    )

    private fun setContent(onOpenGroup: (ImproveGroupViewState) -> Unit = {}) {
        composeTestRule.setContent {
            OrtTheme {
                ImproveScreen(
                    state = ImproveRootViewState(
                        totalOverCount = group.overCount,
                        allTransmissionIds = group.transmissionIds,
                        currentTierLabel = "3",
                        groups = listOf(group),
                        everythingElseCount = 0,
                    ),
                    onDrawer = {},
                    onImproveAll = {},
                    onOpenGroup = onOpenGroup,
                )
            }
        }
    }

    @Test
    @Requirement("R-1108")
    fun `R_1108 the group row carries a real ContentDescription on its own clickable node`() {
        setContent()

        val rowTag = "improve-group-row-${group.id}"
        val description = composeTestRule.onNodeWithTag(rowTag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString()

        assert(description?.contains(group.headline) == true && description?.contains(group.subLine) == true) {
            "expected '$rowTag' to carry a ContentDescription naming both the headline and the " +
                "sub-line, got $description"
        }
    }

    @Test
    @Requirement("R-1108")
    fun `R_1108 the headline and sub-line stay findable by text, and tapping the row opens it`() {
        var opened: ImproveGroupViewState? = null
        setContent(onOpenGroup = { opened = it })

        composeTestRule.onNodeWithText(group.headline).assertExists()
        composeTestRule.onNodeWithText(group.subLine).assertExists()

        composeTestRule.onNodeWithText(group.headline).performClick()
        assert(opened == group) { "expected tapping the row to open group $group, got $opened" }
    }
}
