package org.ort.app.ui.navigation

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.DrawerCountsViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * The drawer exposes every *built or reachable* destination `Menu.dc.html` lists, in its order,
 * and carries the D26 storage footer (canvas.json's `integrated` annotation) — build-plan P13,
 * brought to conformance (R-010..R-014) by ui-conformance-plan WP3.
 *
 * Audit F-020: the footer (FR-STO-5) and the Log/Threads/Stations/Frequencies/Capture badges
 * (FR-UI-7) must report only real, measured facts — see [StorageFooterViewState],
 * [DrawerBadgeViewState] and [DrawerCountsViewState] for what "real" means for each.
 */
@RunWith(RobolectricTestRunner::class)
class DrawerContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val someStorage = StorageFooterViewState(
        audioUsedBytes = 38_200_000_000L,
        freeBytes = 21_800_000_000L,
        hasBudget = false,
    )

    private val someSessionHeader = DrawerSessionHeaderViewState(title = "Tonight", rigLabel = "no radio")

    private val someCounts = DrawerCountsViewState(stationCount = 19, frequencyCount = 2)

    @Test
    fun `the drawer lists every built or reachable Menu dc html destination`() {
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.NOW,
                    sessionHeader = someSessionHeader,
                    storage = someStorage,
                    badges = DrawerBadgeViewState.NONE,
                    counts = someCounts,
                    onSelect = {},
                )
            }
        }

        listOf(
            "Now",
            "Log",
            "Threads",
            "Stations",
            "Frequencies",
            "Earlier nights",
            "Capture",
            "Improve records",
            "Settings",
        ).forEach { label ->
            // R-546: `DrawerRow`'s own clickable node now carries `clearAndSetSemantics` (the same
            // shape `Controls.kt`'s `FilterChip`/`TextAction` already established), so this label's
            // own child `Text` no longer merges up into it and is only reachable on the unmerged
            // tree — `onAllNodesWithText(substring = true)`, not `onNodeWithText`, since a row with
            // a real trailing count (`Stations`/`Frequencies` here) now carries a *second*,
            // composed match too (its own row-level description, e.g. "Stations, 19") alongside the
            // bare-label child this loop is actually checking for; either one existing proves the
            // label itself is genuinely reachable and not silently dropped.
            val matches = composeTestRule
                .onAllNodesWithText(label, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
            assert(matches.isNotEmpty()) { "expected a node whose text contains '$label', found none" }
        }
    }

    @Test
    @Requirement("R-015")
    fun `R_015 Search is reached from the header, never rendered as its own drawer row`() {
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.NOW,
                    sessionHeader = someSessionHeader,
                    storage = someStorage,
                    badges = DrawerBadgeViewState.NONE,
                    counts = someCounts,
                    onSelect = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Open Search").assertDoesNotExist()
    }

    @Test
    @Requirement("R-010")
    fun `R_010 the session header shows the session title and the real rig state`() {
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.NOW,
                    sessionHeader = DrawerSessionHeaderViewState(
                        title = "Repeater watch",
                        rigLabel = "TH-D75A · both bands",
                    ),
                    storage = someStorage,
                    badges = DrawerBadgeViewState.NONE,
                    counts = someCounts,
                    onSelect = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Repeater watch").assertExists()
        composeTestRule.onNodeWithText("TH-D75A · both bands").assertExists()
    }

    @Test
    @Requirement("R-010")
    fun `R_010 Stations and Frequencies show the real, global counts`() {
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.NOW,
                    sessionHeader = someSessionHeader,
                    storage = someStorage,
                    badges = DrawerBadgeViewState.NONE,
                    counts = DrawerCountsViewState(stationCount = 19, frequencyCount = 2),
                    onSelect = {},
                )
            }
        }

        // R-546: the trailing figure's own `Text` no longer merges into the row's clickable node —
        // see the "lists every built or reachable" test's own comment above.
        composeTestRule.onNodeWithText("19", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("2", useUnmergedTree = true).assertExists()
    }

    @Test
    @Requirement("R-011")
    fun `R_011 every drawer row is at least 44dp tall`() {
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.NOW,
                    sessionHeader = someSessionHeader,
                    storage = someStorage,
                    badges = DrawerBadgeViewState.NONE,
                    counts = someCounts,
                    onSelect = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("drawer-row-${ReaderDestination.NOW.name}").assertHeightIsAtLeast(44.dp)
        composeTestRule.onNodeWithTag("drawer-row-${ReaderDestination.EARLIER_NIGHTS.name}")
            .assertHeightIsAtLeast(44.dp)
    }

    @Test
    @Requirement("R-013")
    fun `R_013 an unbuilt destination says so in the row, a built one does not`() {
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.NOW,
                    sessionHeader = someSessionHeader,
                    storage = someStorage,
                    badges = DrawerBadgeViewState.NONE,
                    counts = someCounts,
                    onSelect = {},
                )
            }
        }

        // Earlier nights has no screen yet (ReaderDestination.hasScreen == false) — the row must
        // say so; a built destination like "Now" carries no "not built" text anywhere (there is
        // exactly one unbuilt-but-shown row selected in this composition, EARLIER_NIGHTS, so a
        // single match proves the built rows are silent about it).
        // R-546: unaffected today (every destination is `hasScreen = true`, so this is 0 either
        // way) but `useUnmergedTree = true` matches every other assertion below this row's own
        // `clearAndSetSemantics` change touches, in case that ever stops being true.
        composeTestRule.onAllNodesWithText("not built", useUnmergedTree = true).assertCountEquals(
            ReaderDestination.entries.count { !it.hasScreen && it != ReaderDestination.SEARCH },
        )
    }

    @Test
    @Requirement("R-014")
    fun `R_014 the current destination announces selected, others do not`() {
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.LOG,
                    sessionHeader = someSessionHeader,
                    storage = someStorage,
                    badges = DrawerBadgeViewState.NONE,
                    counts = someCounts,
                    onSelect = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("drawer-row-${ReaderDestination.LOG.name}").assertIsSelected()
        composeTestRule.onNodeWithTag("drawer-row-${ReaderDestination.NOW.name}").assertIsNotSelected()
    }

    @Test
    @Requirement("R-546")
    fun `R_546_drawer_rows_own_their_description`() {
        // The register's own real-device finding: `DrawerRow` reproduced R-380's defect verbatim —
        // a plain, trailing `semantics(mergeDescendants = true) { contentDescription = "Open
        // <label>" }` depended on merge-from-descendants alone, unreliable on a real device
        // (`ControlsTest.kt`'s own `R_380` doc comment), and never carried the trailing count/badge
        // a sighted operator reads next to the label either. Checked on the *unmerged* tree
        // specifically, the same reason `R_380`'s own test is — this is about the row's one
        // physical clickable node's own semantics config, not whatever a merged-tree view of its
        // (now `clearAndSetSemantics`-cleared) descendants would report.
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.NOW,
                    sessionHeader = someSessionHeader,
                    storage = someStorage,
                    badges = DrawerBadgeViewState(logCount = 5, threadsCount = null, captureElapsedLabel = "6:42"),
                    counts = DrawerCountsViewState(stationCount = 12, frequencyCount = 4),
                    improveRecordsCount = 3,
                    onSelect = {},
                )
            }
        }

        fun descriptionOf(tag: String) = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString()

        fun hasOnClick(tag: String) = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.OnClick) != null

        // FR-UI-7: the count/badge that renders visibly next to each label is present in its own
        // spoken description too — "Improve, 3 records" (`IMPROVE_RECORDS`'s own real trailing
        // count-pill), not a bare "Open Improve records" that silently drops the figure.
        listOf(
            "NOW" to "Now",
            "LOG" to "Log, 5",
            "STATIONS" to "Stations, 12",
            "FREQUENCIES" to "Frequencies, 4",
            "CAPTURE" to "Capture, 6:42 elapsed",
            "IMPROVE_RECORDS" to "Improve, 3 records",
            "SETTINGS" to "Settings",
            // R-163: the placeholder dash carries nothing to announce — never read aloud as
            // literal punctuation ("Threads, dash").
            "THREADS" to "Threads",
        ).forEach { (name, expected) ->
            val tag = "drawer-row-$name"
            assert(hasOnClick(tag)) { "expected '$tag' to carry OnClick on its own node" }
            assert(descriptionOf(tag) == expected) {
                "expected '$tag' to carry description '$expected', got ${descriptionOf(tag)}"
            }
        }
    }

    @Test
    @Requirement("FR-STO-5")
    fun `FR_STO_5 the footer reports the real audio usage and admits no budget exists`() {
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.NOW,
                    sessionHeader = someSessionHeader,
                    storage = someStorage,
                    badges = DrawerBadgeViewState.NONE,
                    counts = someCounts,
                    onSelect = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Audio 38.2 GB, no budget set").assertExists()
        composeTestRule.onNodeWithText("Audio 38.2 GB · no budget set").assertExists()
    }

    @Test
    @Requirement("R-012")
    fun `R_012 a real budget shows the of N line and never falls back to no budget set`() {
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.NOW,
                    sessionHeader = someSessionHeader,
                    storage = someStorage.copy(hasBudget = true, budgetBytes = 60_000_000_000L),
                    badges = DrawerBadgeViewState.NONE,
                    counts = someCounts,
                    onSelect = {},
                )
            }
        }

        composeTestRule.onNodeWithText("of 60.0 GB").assertExists()
        composeTestRule.onNodeWithText("no budget set", substring = true).assertDoesNotExist()
    }

    @Test
    @Requirement("FR-STO-5")
    fun `FR_STO_5 the footer never fabricates a total budget`() {
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.NOW,
                    sessionHeader = someSessionHeader,
                    storage = someStorage,
                    badges = DrawerBadgeViewState.NONE,
                    counts = someCounts,
                    onSelect = {},
                )
            }
        }

        // "of 60"-style totals are exactly the fabricated D26 budget F-020 found — must never render.
        composeTestRule.onNodeWithText("of 60", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("of 60.0 GB", substring = true).assertDoesNotExist()
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 Log shows the session's real transmission count`() {
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.NOW,
                    sessionHeader = someSessionHeader,
                    storage = someStorage,
                    badges = DrawerBadgeViewState(logCount = 412, threadsCount = null, captureElapsedLabel = null),
                    counts = someCounts,
                    onSelect = {},
                )
            }
        }

        // R-546: see the "lists every built or reachable" test's own comment above.
        composeTestRule.onNodeWithText("412", useUnmergedTree = true).assertExists()
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 Threads never shows a numeric badge while threadId is always null`() {
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.NOW,
                    sessionHeader = someSessionHeader,
                    storage = someStorage,
                    badges = DrawerBadgeViewState(logCount = 412, threadsCount = null, captureElapsedLabel = null),
                    counts = someCounts,
                    onSelect = {},
                )
            }
        }

        // Not "0" (fabricated grouping) and not any digit at all — only the honest "—" marker.
        // R-546: see the "lists every built or reachable" test's own comment above.
        composeTestRule.onNodeWithText("—", useUnmergedTree = true).assertExists()
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 Capture shows the running session's elapsed time`() {
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.NOW,
                    sessionHeader = someSessionHeader,
                    storage = someStorage,
                    badges = DrawerBadgeViewState(logCount = 0, threadsCount = null, captureElapsedLabel = "6:42"),
                    counts = someCounts,
                    onSelect = {},
                )
            }
        }

        // R-546: see the "lists every built or reachable" test's own comment above.
        composeTestRule.onNodeWithText("6:42", useUnmergedTree = true).assertExists()
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 no Log or Capture badge renders while idle`() {
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.NOW,
                    sessionHeader = someSessionHeader,
                    storage = someStorage,
                    badges = DrawerBadgeViewState.NONE,
                    counts = someCounts,
                    onSelect = {},
                )
            }
        }

        composeTestRule.onNodeWithText("0").assertDoesNotExist()
    }
}
