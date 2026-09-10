package org.ort.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtTheme
import org.ort.app.ui.theme.OrtType
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * R-133/R-150/R-251 (register, rounds 4 and 6 System validator): the storage screen's new "when
 * space runs low" rows read real signals; its budget-chip row scrolls (WP2's `FilterChipRow`,
 * proven on-device — round 4's own hand-rolled `horizontalScroll` clipped instead of scrolling on
 * a real device, R-251) and its category legend wraps (a `FlowRow`) rather than either wrapping
 * intra-word or clipping at font scale 2.0 — see [SettingsStorageScreen]'s own doc comments for
 * the mechanism. Font-scale-2.0 is applied via `LocalDensity` (`RowsTest.kt`'s own established
 * idiom — `@Config(qualifiers = "fontscale-2.0")` is not a valid Robolectric qualifier string;
 * this project's font-scale coverage has never used it).
 */
@RunWith(RobolectricTestRunner::class)
class SettingsStorageScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val maxFontScale = 2f

    private fun state(warnAtNightsLeft: Int = 3, hardFloorLabel: String = "100 MB") = SettingsStorageViewState(
        usedBytes = 800_000_000L,
        budgetGb = null,
        deviceFreeBytes = 40_000_000_000L,
        categories = listOf(
            SettingsStorageCategoryViewState("Audio", 800_000_000L),
            SettingsStorageCategoryViewState("Models", 200_000_000L),
            SettingsStorageCategoryViewState("Records", 10_000_000L),
            SettingsStorageCategoryViewState("Lexicon", 0L),
        ),
        nightsLeftLabel = null,
        autoPruneEnabled = false,
        warnAtNightsLeft = warnAtNightsLeft,
        hardFloorLabel = hardFloorLabel,
    )

    @Test
    @Requirement("R-133")
    fun `R_133_bar the four-segment usage bar's legend names Audio, Models, Records and Lexicon, real bytes each`() {
        composeTestRule.setContent {
            OrtTheme { SettingsStorageScreen(state = state(), onBack = {}, onSetBudgetGb = {}, onToggleAutoPrune = {}) }
        }

        composeTestRule.onNodeWithText("Audio 0.8 GB").assertExists()
        composeTestRule.onNodeWithText("Models 0.2 GB").assertExists()
        composeTestRule.onNodeWithText("Records 0.0 GB").assertExists()
        // R-133 (round 8): Lexicon is real — `StorageAccounting.lexiconBytes` (`:pipeline`, WP11c)
        // — and honestly `0` today (no on-disk lexicon asset exists yet to measure, see that
        // type's own doc comment), never omitted as if the category itself did not exist.
        composeTestRule.onNodeWithText("Lexicon 0.0 GB").assertExists()
    }

    @Test
    @Requirement("R-351")
    fun `R_351 the usage bar renders at the board's real height with one segment per category`() {
        composeTestRule.setContent {
            OrtTheme { SettingsStorageScreen(state = state(), onBack = {}, onSetBudgetGb = {}, onToggleAutoPrune = {}) }
        }

        // R-351 (register): before this fix, neither the outer nor the per-segment `Row` carried
        // an explicit height, so the whole bar measured to zero regardless of data — the direct
        // proof of the fix is a real, non-zero measured height (the board's own 8dp) and one real
        // segment node per category (four, this fixture's own `state()`).
        val barHeight = composeTestRule.onNodeWithTag(STORAGE_BAR_TEST_TAG).fetchSemanticsNode().size.height
        assert(barHeight > 0) { "expected the usage bar to render at a real, non-zero height, got ${barHeight}px" }
        composeTestRule.onAllNodesWithTag(STORAGE_BAR_SEGMENT_TEST_TAG).assertCountEquals(4)
    }

    @Test
    @Requirement("R-441")
    fun `R_441_zero_total_draws_empty_track`() {
        val emptyStore = state().copy(
            categories = listOf(
                SettingsStorageCategoryViewState("Audio", 0L),
                SettingsStorageCategoryViewState("Models", 0L),
                SettingsStorageCategoryViewState("Records", 0L),
                SettingsStorageCategoryViewState("Lexicon", 0L),
            ),
        )
        composeTestRule.setContent {
            OrtTheme {
                SettingsStorageScreen(state = emptyStore, onBack = {}, onSetBudgetGb = {}, onToggleAutoPrune = {})
            }
        }

        // R-441 (register, halt, Reviewer D): a fresh store used to render almost full-width solid
        // colour instead of an empty track — the direct proof of the fix is the bar's own real,
        // non-zero (R-351) track still there, but zero coloured segment nodes inside it.
        val barHeight = composeTestRule.onNodeWithTag(STORAGE_BAR_TEST_TAG).fetchSemanticsNode().size.height
        assert(barHeight > 0) { "expected the empty track itself to still render, got ${barHeight}px" }
        composeTestRule.onAllNodesWithTag(STORAGE_BAR_SEGMENT_TEST_TAG).assertCountEquals(0)
    }

    @Test
    @Requirement("R-133")
    fun `R_133_next_deletion_row names the real session, over count and size, and Review opens it`() {
        var reviewed: String? = null
        val withNextDeletion = state().copy(
            nextDeletion = SettingsNextDeletionViewState(
                sessionId = "S-OLDEST",
                predictedDateLabel = "Thu 10 Sep",
                sessionDateLabel = "Mon 10 Aug",
                overCount = 2_140,
                sizeLabel = "1.2 GB",
            ),
        )
        composeTestRule.setContent {
            OrtTheme {
                SettingsStorageScreen(
                    state = withNextDeletion,
                    onBack = {},
                    onSetBudgetGb = {},
                    onToggleAutoPrune = {},
                    onReviewSession = { reviewed = it },
                )
            }
        }

        composeTestRule.onNodeWithText("Next deletion: Thu 10 Sep").assertExists()
        composeTestRule.onNodeWithText("audio from Mon 10 Aug, 2140 overs, 1.2 GB · transcripts and attributions stay")
            .assertExists()
        // Below the fold on a real-height screen — the established scroll-before-click idiom this
        // suite's other below-the-fold action tests already use. Two scrollable nodes exist here
        // (this screen's own vertical scroll, and the budget-chip row's horizontal one) — matched
        // on the vertical axis specifically so the right one scrolls.
        val verticalScroll = SemanticsMatcher("has vertical scroll axis") {
            it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
        }
        // WP2's R-380/R-381 fix: `TextAction`'s own `clearAndSetSemantics` now sets a content
        // description on the button's own node and clears its inner Text's semantics entirely —
        // this row's own call site further overrides that description with the more specific
        // "Review the session from …" — either way it is a content description now, never a
        // `Text` node `hasText`/`onNodeWithText` can find; substring covers both.
        composeTestRule
            .onNode(hasScrollAction().and(verticalScroll))
            .performScrollToNode(hasContentDescription("Review", substring = true))
        composeTestRule.onNodeWithContentDescription("Review", substring = true).performClick()

        assert(reviewed == "S-OLDEST") { "expected Review to open the real session id, got $reviewed" }
    }

    @Test
    @Requirement("R-133")
    fun `R_133_next_deletion_row reads Nothing scheduled with the real headroom when a budget is set`() {
        val underBudget = state().copy(budgetGb = 10, usedBytes = 2_000_000_000L, nextDeletion = null)
        composeTestRule.setContent {
            OrtTheme {
                SettingsStorageScreen(state = underBudget, onBack = {}, onSetBudgetGb = {}, onToggleAutoPrune = {})
            }
        }

        composeTestRule.onNodeWithText("Nothing scheduled — 8.0 GB below the budget").assertExists()
    }

    @Test
    @Requirement("R-133")
    fun `R_133_next_deletion_row reads Nothing scheduled, no budget set, when none is set at all`() {
        val noBudget = state().copy(budgetGb = null, nextDeletion = null)
        composeTestRule.setContent {
            OrtTheme {
                SettingsStorageScreen(state = noBudget, onBack = {}, onSetBudgetGb = {}, onToggleAutoPrune = {})
            }
        }

        composeTestRule.onNodeWithText("Nothing scheduled — no budget set").assertExists()
    }

    @Test
    fun `R_133 Warn at and Hard floor rows show the real values this state carries`() {
        composeTestRule.setContent {
            OrtTheme { SettingsStorageScreen(state = state(), onBack = {}, onSetBudgetGb = {}, onToggleAutoPrune = {}) }
        }

        composeTestRule.onNodeWithText("3 nights left").assertExists()
        composeTestRule.onNodeWithText("100 MB").assertExists()
    }

    @Test
    fun `R_150_R_251 the budget chip row (FilterChipRow) scrolls rather than wraps at font scale 2-0`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    SettingsStorageScreen(state = state(), onBack = {}, onSetBudgetGb = {}, onToggleAutoPrune = {})
                }
            }
        }

        // The "Unlimited" chip's own node stays intact inside a scrollable-action ancestor — the
        // pre-fix layout collapsed each chip's Row width instead of scrolling it, so this ancestor
        // search is what actually distinguishes the two. WP2's R-380/R-381 fix (the chip's own
        // `clearAndSetSemantics` sets `contentDescription = label` and clears its inner Text's
        // semantics entirely) means this is now a content-description query, never `hasText`.
        composeTestRule
            .onNode(hasContentDescription("Unlimited").and(hasAnyAncestor(hasScrollAction())))
            .assertExists()
    }

    @Test
    fun `R_291 the budget chip row is given the root's real width, not its own unconstrained content width`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    SettingsStorageScreen(state = state(), onBack = {}, onSetBudgetGb = {}, onToggleAutoPrune = {})
                }
            }
        }

        // R-291 (register, round 7 System validator pass 3, cf. R-277's identical fix on
        // `StationScreen.kt`): the round-6 fix moved to `FilterChipRow` but never called
        // `fillMaxWidth()` on it, so `horizontalScroll`'s own viewport measured the row's
        // unconstrained content width instead of the screen's — nothing overflowed the viewport it
        // measured against, so there was nothing to scroll to and "Unli…" stayed clipped on a real
        // device (Robolectric alone never reproduced the clipping either round). `fillMaxWidth()`
        // makes the row's own measured width equal the root's real available width — the direct,
        // structural proof of the fix.
        val rowWidth = composeTestRule.onNodeWithTag(BUDGET_CHIP_ROW_TEST_TAG).fetchSemanticsNode().size.width
        val rootWidth = composeTestRule.onRoot().fetchSemanticsNode().size.width
        // The row sits inside this screen's own `horizontal = OrtSpacing.lg` content padding
        // (density 1f here, so 1dp == 1px — `lg` subtracts cleanly on both sides).
        val expectedWidth = rootWidth - 2 * OrtSpacing.lg.value.toInt()
        assert(rowWidth == expectedWidth) {
            "expected the budget chip row's width ($rowWidth) to equal the padded content width " +
                "($expectedWidth) — fillMaxWidth() not reaching FilterChipRow (leaving it sized to " +
                "its own unconstrained content instead) is exactly R-291's bug"
        }
    }

    @Test
    @Requirement("R-551")
    fun `R_551 the retention-order row never crushes its value or sub-line to one char per line at fontscale-2_0`() {
        // R-551 (`overnight/CF03-settings-storage@2x-end.png`): before the fix, `KeyValueRow`'s
        // non-weighted key column consumed the row's whole shared width budget with this row's
        // unusually long key text ("Then stop retaining audio, keep capturing text"), leaving the
        // weighted value+sub-line column ~0dp wide — the sub-line then wrapped one character per
        // line and everything below it scrolled off-screen. [key], [value] and [subLine] now each
        // render on their own full-width line (no two of them ever share a `Row`), so none of them
        // can ever be measured against a shared budget a sibling claims first — the direct,
        // geometry-based proof, the same technique `R_260`/`R_552` above already establish for
        // this exact class of defect: a token stacked several characters high (one-per-line) measures
        // dramatically taller than the same text given a real line to itself, whatever a given
        // host's font metrics report in absolute terms.
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    Box(modifier = Modifier.width(350.dp)) {
                        SettingsStorageScreen(state = state(), onBack = {}, onSetBudgetGb = {}, onToggleAutoPrune = {})
                    }
                    // The same value token, unconstrained, as a reference for one real line's height.
                    Text(
                        text = "always",
                        style = OrtType.control,
                        softWrap = false,
                        modifier = Modifier.testTag("value-reference"),
                    )
                    // The same sub-line, unconstrained, as a reference for one real line's height.
                    Text(
                        text = "the order is fixed: audio goes before transcripts, and capture never stops silently",
                        style = OrtType.subLine,
                        softWrap = false,
                        modifier = Modifier.testTag("subline-reference"),
                    )
                }
            }
        }

        // Two scrollable nodes exist here (this screen's own vertical scroll, and the budget-chip
        // row's horizontal one) — matched on the vertical axis specifically, the same idiom the
        // `R_133_next_deletion_row` test above already establishes. The row's own outer node is a
        // `mergeDescendants` boundary (its composed content description carries the sub-line text),
        // so that description — not the inner test tags, hidden from the default merged tree — is
        // what a scroll-to search can actually find.
        val verticalScroll = SemanticsMatcher("has vertical scroll axis") {
            it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
        }
        composeTestRule
            .onNode(hasScrollAction().and(verticalScroll))
            .performScrollToNode(hasContentDescription("the order is fixed", substring = true))

        // `useUnmergedTree = true`: the row's `mergeDescendants` boundary hides these child nodes
        // from the default merged tree — `ui/components/RowsTest.kt`'s own established idiom for
        // querying inside exactly this shape of row.
        val referenceValueLineHeight = composeTestRule.onNodeWithTag("value-reference").fetchSemanticsNode().size.height
        val realValueHeight = composeTestRule
            .onNodeWithTag(RETENTION_ORDER_VALUE_TEST_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .size
            .height
        assert(realValueHeight <= referenceValueLineHeight * 2) {
            "expected the value 'always' to render at roughly one line's height (reference " +
                "${referenceValueLineHeight}px); got ${realValueHeight}px, consistent with wrapping " +
                "one character per line"
        }

        val referenceLineHeight = composeTestRule.onNodeWithTag("subline-reference").fetchSemanticsNode().size.height
        val realSubLineHeight = composeTestRule
            .onNodeWithTag(RETENTION_ORDER_SUBLINE_TEST_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .size
            .height
        assert(realSubLineHeight <= referenceLineHeight * 12) {
            "expected the sub-line to wrap normally across a handful of lines (one-line reference " +
                "${referenceLineHeight}px); got ${realSubLineHeight}px, consistent with wrapping one " +
                "character per line"
        }
    }

    @Test
    @Requirement("R-590")
    fun `R_590 at fontscale_1_0 the retention-order row sits side by side like every sibling row`() {
        // R-590 (Reviewer 5, round 5): R-551's unconditional stack fixed the 2.0 crush but broke
        // the label-left/value-right pattern every sibling row keeps (`Settings-Storage.dc.html`
        // line 81) at 1.0, where there is plenty of real room for both. The direct, geometry-based
        // proof that this scale renders side by side rather than stacked: the value's own top
        // edge sits *above* the key's own bottom edge — the two vertically overlap, exactly what
        // `Arrangement.SpaceBetween` + `verticalAlignment = CenterVertically` produces for two
        // siblings in one `Row`, and exactly what never happens when [value] is the *next item
        // down* from [key] in a stacked `Column` (R-551's own layout), where [value]'s top can
        // never be above [key]'s bottom.
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                OrtTheme {
                    Box(modifier = Modifier.width(350.dp)) {
                        SettingsStorageScreen(state = state(), onBack = {}, onSetBudgetGb = {}, onToggleAutoPrune = {})
                    }
                }
            }
        }

        val verticalScroll = SemanticsMatcher("has vertical scroll axis") {
            it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
        }
        composeTestRule
            .onNode(hasScrollAction().and(verticalScroll))
            .performScrollToNode(hasContentDescription("the order is fixed", substring = true))

        // `useUnmergedTree = true`: this row's own `mergeDescendants` boundary hides these child
        // nodes from the default merged tree, the same allowance `R_551` above already needs.
        val key = composeTestRule.onNodeWithTag(RETENTION_ORDER_KEY_TEST_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
        val value = composeTestRule.onNodeWithTag(RETENTION_ORDER_VALUE_TEST_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
        val keyBottom = key.positionInRoot.y + key.size.height
        val valueTop = value.positionInRoot.y
        assert(valueTop < keyBottom) {
            "expected the value to sit beside the label (its top, ${valueTop}px, above the key's " +
                "own bottom, ${keyBottom}px) at font scale 1.0; got a value positioned below the " +
                "key instead, consistent with the row staying stacked at a scale where there is " +
                "real room for both side by side"
        }

        // R-590 round 2 (coordinator, device-confirmed): being positioned beside the label is not
        // enough on its own — the first below-threshold attempt sat beside the label too, and was
        // still squeezed into a sliver that wrapped "always" one character per line real-device-
        // side, a defect Robolectric's own unreliable width measurement never caught but a real,
        // single-line *height* still proves either way. `KeyValueRow`'s own "Warn at" row on this
        // same screen renders its value ("3 nights left") at a guaranteed single real line — the
        // direct, host-independent reference `waitUntilTextExists`-style: not an absolute pixel
        // count (this host's own font metrics, established elsewhere in this file as unreliable in
        // absolute terms), but a comparison between two real rendered nodes on the same screen, at
        // the same style, same density, same run.
        val warnAtValueHeight = composeTestRule.onNodeWithText("3 nights left").fetchSemanticsNode().size.height
        val valueHeight = value.size.height
        assert(valueHeight <= (warnAtValueHeight * 1.6).toInt()) {
            "expected the value 'always' to render at roughly one real line's height (the 'Warn " +
                "at' row's own single-line value measures ${warnAtValueHeight}px here); got " +
                "${valueHeight}px, consistent with wrapping one character per line on a real device"
        }
    }

    @Test
    @Requirement("R-590")
    fun `R_590 at fontscale_2_0 the retention-order row still stacks, never side by side`() {
        // R-590's other half: above the gate's own threshold, the row must still be the R-551
        // stack — the value's own top can never be above the key's own bottom, since [value] is
        // the very next item down from [key] in one `Column`, never a `Row` sibling beside it.
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    Box(modifier = Modifier.width(350.dp)) {
                        SettingsStorageScreen(state = state(), onBack = {}, onSetBudgetGb = {}, onToggleAutoPrune = {})
                    }
                }
            }
        }

        val verticalScroll = SemanticsMatcher("has vertical scroll axis") {
            it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
        }
        composeTestRule
            .onNode(hasScrollAction().and(verticalScroll))
            .performScrollToNode(hasContentDescription("the order is fixed", substring = true))

        val key = composeTestRule.onNodeWithTag(RETENTION_ORDER_KEY_TEST_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
        val value = composeTestRule.onNodeWithTag(RETENTION_ORDER_VALUE_TEST_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
        val keyBottom = key.positionInRoot.y + key.size.height
        val valueTop = value.positionInRoot.y
        assert(valueTop >= keyBottom) {
            "expected the value to stay stacked below the key (its top, ${valueTop}px, at or below " +
                "the key's own bottom, ${keyBottom}px) at font scale 2.0; got a value positioned " +
                "beside the key instead, consistent with the R-551 crush the stack exists to prevent"
        }
    }

    @Test
    fun `R_150_R_251 the category legend wraps (FlowRow) rather than clipping at font scale 2-0`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    SettingsStorageScreen(state = state(), onBack = {}, onSetBudgetGb = {}, onToggleAutoPrune = {})
                }
            }
        }

        // The full label survives as one intact node — R-251's own screenshot showed it clipped
        // to "Reco"/"U" with no way to reach the rest; a `FlowRow` wraps the whole entry onto its
        // own line instead of clipping or splitting it.
        composeTestRule.onNodeWithText("Records 0.0 GB").assertExists()
    }
}
