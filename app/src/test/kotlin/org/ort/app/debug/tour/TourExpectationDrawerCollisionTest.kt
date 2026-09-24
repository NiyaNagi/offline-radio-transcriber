package org.ort.app.debug.tour

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.DrawerCountsViewState
import org.ort.app.ui.navigation.DrawerBadgeViewState
import org.ort.app.ui.navigation.DrawerSessionHeaderViewState
import org.ort.app.ui.navigation.ReaderDestination
import org.ort.app.ui.navigation.ReaderDrawerContent
import org.ort.app.ui.navigation.StorageFooterViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * Register **R-1201**: the guard that stops a tour step ever again being satisfied by the drawer
 * instead of by its own screen.
 *
 * `ModalNavigationDrawer` composes its `drawerContent` unconditionally, open or closed, and
 * `Drawer.kt:257-267` puts `text = AnnotatedString(description)` on the drawer row node itself -
 * the destination's own `drawerLabel` plus its count. [TourStepsTest] matches with
 * `hasText(substring = true)`. So any [Expected.Text] case naming a destination label was satisfied
 * on *every* screen in the app, forever. R-1201 measured the damage by deleting `NavHostBody` and
 * re-running the suite: **148 of 264 destination steps passed with no screen composed at all**, 144
 * of them through exactly this collision.
 *
 * This test composes [ReaderDrawerContent] **alone** - the same thing `DrawerContentTest` already
 * does - and asserts that **no string any [Expected.Text] case uses matches any node in the
 * drawer-only tree**. That tree is ground truth: a string it can satisfy is a string the tour
 * cannot use to prove anything, whatever screen the step names.
 *
 * Deliberately *not* a blanket lint over short `onNodeWithText` literals (R-1201's own note): that
 * would match ~200 call sites, most of them leaf-composed and correct, and a noisy check gets
 * suppressed - R-1175's failure arriving one level up.
 *
 * **Scope, stated rather than silently assumed.** The drawer's row list and storage footer are
 * static text this test can hold as ground truth. Its session header is not: that title is whatever
 * the current session is labelled ([DrawerSessionHeaderViewState.from], defaulting to "Tonight"),
 * so a scenario could in principle name a session something an expectation also uses. The one
 * remaining case near that line is `reviewSessionView: "DIGEST"` -> `Text("Session")`, which R-1070
 * chose deliberately and which the R-1201 stub cleared; it is recorded here rather than guarded.
 */
@RunWith(RobolectricTestRunner::class)
class TourExpectationDrawerCollisionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** [DrawerBadgeViewState.NONE] and no improve count on purpose: with no trailing figure, each
     * row's own description is the bare `drawerLabel`, which is the widest net for a
     * `substring = true` match - a richer description ("Log, 5") still contains it. */
    private fun composeDrawerOnly() {
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.NOW,
                    sessionHeader = DrawerSessionHeaderViewState(title = "Tonight", rigLabel = "no radio"),
                    storage = StorageFooterViewState(
                        audioUsedBytes = 38_200_000_000L,
                        freeBytes = 21_800_000_000L,
                        hasBudget = false,
                    ),
                    badges = DrawerBadgeViewState.NONE,
                    counts = DrawerCountsViewState(stationCount = 19, frequencyCount = 2),
                    onSelect = {},
                )
            }
        }
    }

    /** `useUnmergedTree`, so the row's own child label `Text` is seen as well as the row node's
     * `clearAndSetSemantics` description - the unmerged tree is a superset of what
     * [TourStepsTest]'s own merged-tree query can match, so a string cleared here is cleared
     * either way. */
    private fun drawerSatisfies(text: String): Boolean =
        composeTestRule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    @Test
    @Requirement("R-1201")
    fun `R_1201 no tour step expectation is satisfied by the drawer alone`() {
        val expectations = TourStepExpectations.destinationExpectations()
        assertTrue("expected at least one destination step", expectations.isNotEmpty())
        composeDrawerOnly()

        val collisions = expectations.mapNotNull { (step, expected) ->
            val text = (expected as? Expected.Text)?.text ?: return@mapNotNull null
            if (drawerSatisfies(text)) "${step.id}: Expected.Text(\"$text\") is satisfied by the drawer" else null
        }

        assertTrue(
            "${collisions.size} of ${expectations.size} tour steps assert text the always-composed " +
                "drawer alone satisfies — each one proves nothing about its own screen (R-1201). " +
                "Assert a testTag rooted in the screen instead:\n${collisions.joinToString("\n")}",
            collisions.isEmpty(),
        )
    }

    /**
     * The discrimination half (constitution II): a guard that cannot fail is not a guard. These are
     * the exact strings the pre-R-1201 table used for the 144 colliding steps - every one of them a
     * `ReaderDestination.drawerLabel` - and the drawer-only tree must still match all of them, or
     * [drawerSatisfies] has silently stopped reading anything.
     */
    @Test
    @Requirement("R-1201")
    fun `R_1201 the guard still flags every drawer label the pre-fix table asserted on`() {
        composeDrawerOnly()
        composeTestRule.onAllNodesWithTag("drawer-rows").fetchSemanticsNodes().let {
            assertTrue("the drawer this guard reads did not compose at all", it.isNotEmpty())
        }

        val preFixCases =
            listOf("Settings", "Log", "Threads", "Stations", "Frequencies", "Recordings", "Improve records")
        val missed = preFixCases.filterNot { drawerSatisfies(it) }
        assertTrue("the guard no longer matches these known drawer labels: $missed", missed.isEmpty())
    }

    /**
     * The other half of the same discrimination check: every string that came through R-1201's own
     * `NavHostBody` stub *correctly* must stay un-flagged, or this guard is a false-positive machine
     * that will be suppressed rather than obeyed.
     */
    @Test
    @Requirement("R-1201")
    fun `R_1201 the guard passes the screen-unique strings the stub cleared`() {
        composeDrawerOnly()

        val screenUnique = listOf(
            // R-1070's own fix: `SessionDetailScreen`'s upper-cased "Coverage" section header.
            "COVERAGE",
            "Filter the log",
            "When they are around",
            "How this station is known",
            "Split this voice",
            "Conversations are not built on this phone yet",
            // R-1070/WPREC: `DigestScreen`'s own `DrillInHeader` parent label.
            "Session",
        )
        val falsePositives = screenUnique.filter { drawerSatisfies(it) }
        assertTrue("the drawer-only tree matched screen-unique text: $falsePositives", falsePositives.isEmpty())
    }
}
