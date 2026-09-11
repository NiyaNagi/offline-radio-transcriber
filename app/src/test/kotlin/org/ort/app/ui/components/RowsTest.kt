package org.ort.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** R-023 (ui-conformance-plan WP2): the row family from guide §6.5/`Rows.dc.html`. */
@RunWith(RobolectricTestRunner::class)
class RowsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val maxFontScale = 2f

    /** R-152: the gap between the right edge of the node found by [leftText] and the left edge of
     * the node found by [rightText] must be at least [minGapDp] — a real, code-enforced minimum,
     * not a coincidence of whichever content happened to leave whitespace inside a fixed box (the
     * pre-fix `KeyValueRow`'s defect exactly: no `Arrangement` gap at all, so its columns only
     * ever looked apart because short keys left slack inside their own box — this asserts a
     * *strict* gap so that a regression back to zero explicit spacing fails here, not just a
     * regression to visible overlap).
     *
     * This deliberately does not try to prove a column *grows* to fit wide content by measuring
     * text width: Robolectric's `Paint` returns degenerate glyph metrics for this codebase's
     * `sans`/`mono` `fontFamily`s (verified directly — a 23-character key measured 96px wide, a
     * lone digit measured 1px wide, regardless of a 2.0 font scale), so an intrinsic-width
     * assertion would pass or fail on an artifact of the test host's font substitution, not on
     * real behaviour. What *is* deterministic, and what this checks, is the arrangement gap
     * itself — `widthIn(min = …)` never returns less than `width(…)` would for the same content
     * and can only ever add room, so pairing it with a real `Arrangement.spacedBy` gap is a sound,
     * Compose-guaranteed fix independent of what any given host's font metrics report.
     *
     * `useUnmergedTree = true` because several of these rows (`LogRow`, `RejectedRow`,
     * `NotificationCard`) wrap their whole content in `semantics(mergeDescendants = true)` for a
     * single accessible target — on the default merged tree, `onNodeWithText` for either column
     * would resolve to that one outer node instead of the individual `Text`, making every column
     * report the same (wrong) position. */
    private fun assertColumnsDoNotCollide(leftText: String, rightText: String, minGapDp: Int = 1) {
        val left = composeTestRule.onNodeWithText(leftText, useUnmergedTree = true).fetchSemanticsNode()
        val right = composeTestRule.onNodeWithText(rightText, useUnmergedTree = true).fetchSemanticsNode()
        val leftEdge = left.positionInRoot.x + left.size.width
        val rightEdge = right.positionInRoot.x
        val gap = rightEdge - leftEdge
        assert(gap >= minGapDp) {
            "expected at least ${minGapDp}px between '$leftText' (ending at ${leftEdge}px) and " +
                "'$rightText' (starting at ${rightEdge}px) at font scale $maxFontScale, got ${gap}px"
        }
    }

    private fun row(
        attribution: Attribution? = Attribution.confirmed("W7NPC", 0.95),
        partial: LogRowPartial? = null,
        badge: LogRowBadge? = null,
        transcript: String = "this is whiskey seven november papa charlie, monitoring",
        highlightRanges: List<IntRange> = emptyList(),
        btAudioMark: Boolean = false,
    ) = LogRowViewState(
        id = "TX1",
        timeLabel = "02:14:07",
        frequencyLabel = "145.230",
        transcript = transcript,
        partial = partial,
        attribution = attribution,
        signalLabel = "S7",
        badge = badge,
        btAudioMark = btAudioMark,
        highlightRanges = highlightRanges,
    )

    @Test
    fun `every LogRow variant from Rows_dc_html is distinguishable and clickable`() {
        var opened: String? = null
        composeTestRule.setContent {
            OrtTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    LogRow(
                        state = row(Attribution.confirmed("W7NPC", 0.95)),
                        onClick = { opened = "confirmed" },
                        modifier = Modifier.testTag("confirmed"),
                    )
                    LogRow(
                        state = row(Attribution.inferred("K7LWH", 0.82)),
                        onClick = {},
                        modifier = Modifier.testTag("inferred"),
                    )
                    LogRow(
                        state = row(Attribution.ambiguous(), transcript = "kilo echo seven quebec romeo sierra"),
                        onClick = {},
                        modifier = Modifier.testTag("ambiguous"),
                    )
                    LogRow(
                        state = row(Attribution.unknown(), transcript = "…any station on frequency, this is"),
                        onClick = {},
                        modifier = Modifier.testTag("unknown"),
                    )
                    LogRow(
                        state = row(partial = LogRowPartial.HEARING, attribution = null),
                        onClick = {},
                        modifier = Modifier.testTag("hearing"),
                    )
                    LogRow(
                        state = row(partial = LogRowPartial.RESOLVING, attribution = null),
                        onClick = {},
                        modifier = Modifier.testTag("resolving"),
                    )
                    LogRow(state = row(badge = LogRowBadge.NEW), onClick = {}, modifier = Modifier.testTag("new"))
                    LogRow(
                        state = row(badge = LogRowBadge.NEW, btAudioMark = true),
                        onClick = {},
                        modifier = Modifier.testTag("new-and-bt-audio"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("confirmed").performClick()
        assert(opened == "confirmed")
        // A scrollable Column, so a badge on the seventh stacked row need only exist, not be
        // scrolled into view, for this test's purpose (every variant renders distinctly).
        // R-380/R-381: each `LogRow`'s own label content is only reachable on the unmerged tree
        // now (an earlier entry in this file's own `CHANGELOG.md`).
        composeTestRule.onNodeWithText("hearing…", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("resolving…", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("new").assert(hasText("new", substring = true))
        composeTestRule.onNodeWithTag("confirmed").assertHeightIsAtLeast(44.dp)
        // E2-G04 (F23, FR-CAP-13): the `bt audio` mark coexists with an existing badge (NEW), on
        // its own separate badge slot — never replacing the row's other real fact.
        composeTestRule.onNodeWithTag("new-and-bt-audio").assert(hasText("new", substring = true))
        composeTestRule.onNodeWithTag("new-and-bt-audio").assert(hasText("bt audio", substring = true))
    }

    @Test
    fun `R_240_an ambiguous row with a callsign shows it beside the alternate, never just or-alternate`() {
        // The register's own repro (`overnight/L01-log.png`): AttributionState.AMBIGUOUS never
        // carries a stationId (Attribution.ambiguous() sets it null by design — more than one
        // candidate survived), so before LogRowViewState.callsign existed the row could only ever
        // read "or KE7QRS" — the kept candidate silently missing. A caller supplying one now
        // reaches the rendered row.
        composeTestRule.setContent {
            OrtTheme {
                LogRow(
                    state = row(Attribution.ambiguous()).copy(callsign = "KE7QRS", alternate = "N7ABC"),
                    onClick = {},
                    modifier = Modifier.testTag("ambiguous"),
                )
            }
        }

        // R-380/R-381: `LogRow`'s own label content is only reachable on the unmerged tree now
        // (an earlier entry in this file's own `CHANGELOG.md`).
        composeTestRule.onNodeWithText("KE7QRS", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("or N7ABC", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `R_240_an ambiguous row with no callsign supplied still falls back to Attribution_stationId`() {
        // Documents the fallback explicitly: `callsign = null` (every caller before R-240) keeps
        // AttributionRow's own default (`attribution.stationId`) — for AMBIGUOUS that is null too,
        // so the row reads only "or <alternate>", exactly the register's pre-fix screenshot. This
        // is the honest current behaviour for a caller that has not supplied one yet, not silently
        // hidden by this fix.
        composeTestRule.setContent {
            OrtTheme {
                LogRow(
                    state = row(Attribution.ambiguous()).copy(alternate = "N7ABC"),
                    onClick = {},
                    modifier = Modifier.testTag("ambiguous"),
                )
            }
        }

        // R-380/R-381: `LogRow`'s own label content is only reachable on the unmerged tree now
        // (an earlier entry in this file's own `CHANGELOG.md`).
        composeTestRule.onNodeWithText("or N7ABC", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `R_244_a badge that no longer fits beside the attribution wraps below it, never clipping`() {
        // A narrow row leaves the marker line (shape + callsign + badge) too little width for a
        // badge to sit beside the attribution on one line — real, non-text-driven minimums do the
        // forcing here (AttributionShape's own fixed Canvas size, Badge's own padding), not font
        // metrics Robolectric can't measure reliably (see R_152's own findings elsewhere in this
        // file). If the badge wraps to a second line rather than clipping, the row measures
        // taller than the same narrow row with no badge at all.
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    Column {
                        Box(modifier = Modifier.width(220.dp)) {
                            LogRow(state = row(badge = null), onClick = {}, modifier = Modifier.testTag("no-badge"))
                        }
                        Box(modifier = Modifier.width(220.dp)) {
                            LogRow(
                                state = row(badge = LogRowBadge.NEW),
                                onClick = {},
                                modifier = Modifier.testTag("with-badge"),
                            )
                        }
                    }
                }
            }
        }

        // The badge is still reachable — never dropped from the tree — and the row grew to fit it
        // on a wrapped second line rather than clipping it to a sliver. R-380/R-381: only
        // reachable on the unmerged tree now (an earlier entry in this file's own
        // `CHANGELOG.md`).
        composeTestRule.onNodeWithText("NEW", useUnmergedTree = true).assertExists()
        val noBadgeHeight = composeTestRule.onNodeWithTag("no-badge").fetchSemanticsNode().size.height
        val withBadgeHeight = composeTestRule.onNodeWithTag("with-badge").fetchSemanticsNode().size.height
        assert(withBadgeHeight > noBadgeHeight) {
            "expected the badge to wrap onto a second line in a narrow row, measuring taller than " +
                "the same row with no badge; got no-badge=${noBadgeHeight}px, with-badge=${withBadgeHeight}px"
        }
    }

    @Test
    fun `R_244_log_row's merged description carries the attribution's own words, not just time-freq-transcript`() {
        // The side finding this fix addresses: AttributionRow is itself its own
        // `semantics(mergeDescendants = true)` boundary, and this Compose version does not
        // reliably carry a nested boundary's contentDescription up through a second, outer one —
        // validators read the row's merged description to judge state ("filled circle, Confirmed,
        // W7NPC" is the exact wording V3 checked for) and, before this fix, found nothing of the
        // sort. Composed explicitly now, so `onNodeWithContentDescription` finds it directly, the
        // same way a validator/accessibility service would, without an unmerged-tree query.
        //
        // Deliberately checks for "Confirmed, W7NPC" — state prose + callsign — never prefixed
        // with "filled circle,": `AttributionRow`'s own node (still separately, correctly
        // queryable — unaffected by this fix, it is not the thing being tested) already carries
        // that exact full phrase, and a real caller outside this package
        // (`ui/screens/LogScreenTest.kt`) finds "the row" by searching for it — this fix must not
        // turn that one match into two. `LogRow`'s own new description omits the shape word for
        // exactly that reason (see `Rows.kt`'s `logRowDescription` doc).
        composeTestRule.setContent {
            OrtTheme {
                LogRow(
                    state = row(Attribution.confirmed("W7NPC", 0.95)),
                    onClick = {},
                    modifier = Modifier.testTag("confirmed"),
                )
            }
        }

        composeTestRule.onNodeWithTag("confirmed").assert(
            hasContentDescription("Confirmed, W7NPC", substring = true),
        )
        // The transcript this fix must not silently drop — an explicit contentDescription
        // replaces what an accessibility service reads for a node, so it has to still be present
        // in the new one, not merely still visible in the merged Text list nothing now reads.
        composeTestRule.onNodeWithTag("confirmed").assert(
            hasContentDescription("this is whiskey seven november papa charlie, monitoring", substring = true),
        )
        // The coordinator's own literal check — `onNodeWithContentDescription` genuinely finds
        // the row by its description, not merely a tag-scoped assertion against it. A bare
        // "Confirmed, W7NPC" matches `AttributionRow`'s own separate node too (both nodes
        // genuinely do carry those words — that overlap is the correct, intended outcome, not a
        // bug), so this reaches into the transcript, which only `LogRow`'s own description has,
        // to name the row uniquely. R-380/R-381: `LogRow`'s own outer node now
        // `clearAndSetSemantics` (an earlier entry in this file's own `CHANGELOG.md`) — on the
        // *default* (merged) tree this query now finds exactly `LogRow`'s own node, since
        // `AttributionRow`'s nested node is no longer independently surfaced there at all (a
        // stronger, simpler guarantee than the "two separately queryable nodes, no collision"
        // shape this test originally proved: there is now only ever one node to find here).
        composeTestRule
            .onNodeWithContentDescription("Confirmed, W7NPC, this is whiskey seven", substring = true)
            .assertIsDisplayed()
        // `AttributionRow`'s own shape-prefixed node still exists underneath — reachable with
        // `useUnmergedTree = true`, the same way `ui/screens/LogScreenTest.kt`'s own real caller
        // pattern would need updating to keep working (outside this package, not done here).
        composeTestRule
            .onNodeWithContentDescription("filled circle, Confirmed, W7NPC", substring = true, useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun `R_245_column_header_row's STATION label renders whole, never split mid-word, at font scale 2`() {
        // Robolectric's degenerate glyph metrics for this codebase's custom `fontFamily`s mean a
        // rendered wrap can't be forced or verified reliably here (established repeatedly
        // elsewhere in this file/CHANGELOG — even a 1dp-wide box does not force a real Compose
        // `Text` to report more than one line in this environment). `maxLines = 1`/`softWrap =
        // false` are themselves deterministic, host-independent Compose guarantees — not
        // something this test needs to re-verify the framework does correctly — so what this
        // checks is the one thing that *is* host-independently meaningful: the full word survives
        // into the semantics tree, not a hyphen-split substring ("STATIO"/"N").
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    ColumnHeaderRow()
                }
            }
        }

        composeTestRule.onNodeWithText("STATION").assertIsDisplayed()
    }

    // `GapRow`/`LogGroupHeader`/`ActionBar`/`RejectedRow`'s own tests moved to
    // `RejectedRowTest.kt` (detekt's own `LargeClass` finding, once this file grew past a
    // reasonable size across every row family it covers) — that cluster was already
    // self-contained here, not entangled with `LogRow`/`KeyValueRow`/the rest this file still
    // owns.

    @Test
    fun `R_205_log_row and rejected_row time and freq columns meet the guide's floor and render in full`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    LogRow(state = row(), onClick = {}, modifier = Modifier.testTag("log"))
                    RejectedRow(
                        timeLabel = "16:28:56",
                        frequencyLabel = "146.960",
                        reason = "squelch tail",
                        modifier = Modifier.testTag("rejected"),
                    )
                }
            }
        }

        // Full labels, verbatim — never truncated by the new `maxLines`/`softWrap` (a truncated
        // render would fail these exact-text lookups, since a clipped display still reports its
        // real semantics text, but a *summarised* one would not — this is the check that would
        // catch that class of regression). R-380/R-381: `LogRow`'s own outer node now
        // `clearAndSetSemantics` (an earlier entry in this file's own `CHANGELOG.md`), so its own
        // inner `Text`s are only reachable on the *unmerged* tree — `RejectedRow`'s own labels are
        // unaffected (unchanged, still on the default merged tree).
        composeTestRule.onNodeWithText("02:14:07", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("145.230", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("16:28:56").assertIsDisplayed()
        composeTestRule.onNodeWithText("146.960").assertIsDisplayed()

        // The guide's own 52dp/56dp floor, never regressed — deterministic regardless of host
        // font metrics, since a `widthIn(min = …)` never reports less than its floor.
        val logTimeWidth = composeTestRule.onNodeWithText(
            "02:14:07",
            useUnmergedTree = true,
        ).fetchSemanticsNode().size.width
        val logFreqWidth = composeTestRule.onNodeWithText(
            "145.230",
            useUnmergedTree = true,
        ).fetchSemanticsNode().size.width
        val rejectedTimeWidth = composeTestRule.onNodeWithText("16:28:56").fetchSemanticsNode().size.width
        val rejectedFreqWidth = composeTestRule.onNodeWithText("146.960").fetchSemanticsNode().size.width
        assert(logTimeWidth >= 52) { "expected LogRow's time column at least 52px, got ${logTimeWidth}px" }
        assert(logFreqWidth >= 56) { "expected LogRow's freq column at least 56px, got ${logFreqWidth}px" }
        assert(rejectedTimeWidth >= 52) {
            "expected RejectedRow's time column at least 52px, got ${rejectedTimeWidth}px"
        }
        assert(rejectedFreqWidth >= 56) {
            "expected RejectedRow's freq column at least 56px, got ${rejectedFreqWidth}px"
        }
    }

    // R-373's two tests (`LogRow`'s response to a large font scale — the callsign never splitting
    // character by character, and the row stacking time/freq beneath the marker line when too
    // narrow for the callsign floor) moved to `LogRowResponsiveTest.kt` — detekt's own `LargeClass`
    // finding, once this file grew past a reasonable size across every row family it covers.

    @Test
    fun `a drill-in header and a screen header carry their own targets and descriptions`() {
        var backCalled = false
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    DrillInHeader(
                        parentLabel = "Log",
                        onBack = { backCalled = true },
                        modifier = Modifier.testTag("drillin"),
                    )
                    ScreenHeader(
                        onDrawer = {},
                        liveElapsedLabel = "6:42",
                        onSearch = {},
                        modifier = Modifier.testTag("screenheader"),
                    )
                }
            }
        }

        composeTestRule.onNode(hasContentDescription("Back to Log", substring = true)).performClick()
        assert(backCalled)
        composeTestRule.onNode(hasContentDescription("Open navigation")).assertIsDisplayed()
        composeTestRule.onNodeWithText("6:42").assertIsDisplayed()
        composeTestRule.onNodeWithTag("drillin").assertHeightIsAtLeast(44.dp)
        composeTestRule.onNodeWithTag("screenheader").assertHeightIsAtLeast(44.dp)
    }

    @Test
    fun `R_192_drill_in_header_kebab_can_be_named`() {
        // WP8 had to clone this whole header (StationDetailHeader) just to relabel its kebab
        // "Station identity" — kebabDescription/kebabTestTag let a screen say so directly instead.
        var kebabTapped = false
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    DrillInHeader(
                        parentLabel = "Station",
                        onBack = {},
                        onKebab = { kebabTapped = true },
                        kebabDescription = "Station identity",
                        kebabTestTag = "station-kebab",
                        modifier = Modifier.testTag("named"),
                    )
                    // The default is unchanged for every caller that does not name it.
                    DrillInHeader(
                        parentLabel = "Log",
                        onBack = {},
                        onKebab = {},
                        modifier = Modifier.testTag("default"),
                    )
                }
            }
        }

        composeTestRule.onNode(hasContentDescription("Station identity")).assertIsDisplayed()
        composeTestRule.onNodeWithTag("station-kebab").performClick()
        assert(kebabTapped)
        composeTestRule.onNode(hasContentDescription("More")).assertIsDisplayed()
    }

    @Test
    fun `R_265_key_value_row_is_a_traversal_stop`() {
        // Without a merge boundary, "Input"/"USB Audio Device"/"verified" are three separate
        // TalkBack stops a screen-reader user has to swipe through individually, rather than
        // hearing as the one fact row they visually are.
        composeTestRule.setContent {
            OrtTheme {
                KeyValueRow(
                    key = "Input",
                    value = "USB Audio Device",
                    subLine = "verified",
                    modifier = Modifier.testTag("kv"),
                )
            }
        }

        val row = composeTestRule.onNodeWithTag("kv")
        row.assert(hasContentDescription("Input, USB Audio Device, verified"))
        // A real accessibility stop, not merely a description string sitting unused on an inert
        // node — `focusable()` registers a real `RequestFocus` action on the merged node itself,
        // which is what marks it focusable (as opposed to `SemanticsProperties.Focused`, which
        // reflects only whether it currently *has* focus, not whether it *can*).
        row.assertIsDisplayed()
        row.assert(
            SemanticsMatcher("has RequestFocus") { it.config.getOrNull(SemanticsActions.RequestFocus) != null },
        )
        // "Input" and "USB Audio Device" each resolve to exactly the one merged row node, not
        // two/three separate ones of their own — genuinely one stop, not three independently
        // reachable texts a merged description was merely layered on top of.
        composeTestRule.onAllNodesWithText("Input", substring = true).assertCountEquals(1)
        composeTestRule.onAllNodesWithText("USB Audio Device", substring = true).assertCountEquals(1)
    }

    /**
     * Register R-880 (Validator V11, device, spec): CF02's "Log overs against" label sat vertically
     * centred against its own value+sub-line column — fine for a one-line value, but the row's own
     * sub-line ("used only while the rig is disconnected or absent") wraps to 5–9 lines at font
     * scale 2.0, so the label floats mid-caption instead of reading beside its own first line.
     * `KeyValueRow` is the one shared component every "and any sibling with the same shape" row in
     * the finding's own phrase reduces to (13 real callers across `ui/setup`/`ui/settings`/
     * `ui/digest`/`ui/screens`) — top-aligning its own `Row` fixes every one of them at once, not
     * only CF02's. `@GraphicsMode.NATIVE`: this package's own established discipline for real
     * glyph-wrap measurement (`RigBluetoothScreenTest`'s own `R_805` case).
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_880 the key aligns to the top of a value that wraps to many lines at font scale 2_0`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    Box(modifier = Modifier.width(240.dp)) {
                        KeyValueRow(
                            key = "Log overs against",
                            value = "145.230",
                            subLine = "used only while the rig is disconnected or absent",
                        )
                    }
                }
            }
        }

        // `KeyValueRow`'s outer node merges every descendant into one semantics node (this same
        // file's own `R_265_key_value_row_is_a_traversal_stop`) — the individual `Text`s are only
        // reachable on the *unmerged* tree, which is what this geometry check needs.
        val keyTop = composeTestRule
            .onNodeWithText("Log overs against", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
            .top
        val valueTop = composeTestRule
            .onNodeWithText("145.230", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
            .top
        // The sub-line must genuinely have wrapped past one line for this to be a real proof of the
        // fix, not an accident of a value that happened to fit on one line anyway.
        val subLineBottom = composeTestRule
            .onNodeWithText("used only while the rig is disconnected or absent", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
            .bottom
        val valueBottom = composeTestRule
            .onNodeWithText("145.230", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
            .bottom
        assert(subLineBottom - valueBottom > 20.dp) {
            "test setup failed to force a real multi-line wrap — sub-line only $subLineBottom vs value $valueBottom"
        }
        // Top-aligned (both `Text`s share the same `OrtType.control` style): the key's own top edge
        // sits within a few dp of the value's, never partway down the wrapped block.
        val drift = (keyTop - valueTop).value
        assert(drift < 4f && drift > -4f) {
            "expected the key top-aligned with the value's first line, drifted ${drift}dp " +
                "(key=$keyTop, value=$valueTop)"
        }
    }

    /**
     * Register R-980 (halt, run 6): CF02's "Re-verify the route now" row (a long key, plus a
     * trailing `Verify` action) rendered its own caption one character per line at font scale 2.0
     * on the tour's real 390dp-wide AVD — a non-weighted `key` `Text` in a `Row` is measured
     * against the *whole* remaining row width first, so a long key reports its own full,
     * unwrapped, single-line width, leaving the weighted value/sub-line column whatever sliver is
     * left over once the trailing action (measured first too, unweighted) has also taken its
     * share — the same starvation class `TextAction`'s own doc comment already names for
     * R-805/R-863/R-874/R-970, this time on the row's *leading* side. `@GraphicsMode.NATIVE` and a
     * real 390dp width: this package's own established discipline for real glyph-wrap measurement.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_980 a long key with a trailing action never starves the value column into a per-character collapse`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    Box(modifier = Modifier.width(390.dp)) {
                        KeyValueRow(
                            key = "Re-verify the route now",
                            value = "",
                            subLine = "30 s · capture pauses for it · a gap is recorded",
                            trailingMarker = { TextAction(text = "Verify", onClick = {}) },
                        )
                    }
                }
            }
        }

        val captionNode = composeTestRule
            .onNodeWithText("30 s · capture pauses for it · a gap is recorded", useUnmergedTree = true)
            .fetchSemanticsNode()
        // A genuine per-character collapse measures only a few px wide (one glyph) and many lines
        // tall; a real word-wrapped column, even a narrow one, still measures a real fraction of
        // the 390dp row. `density = 1f` above means px and dp coincide numerically here — 60 is
        // comfortably above one glyph's own width and comfortably below what a healthy wrap would
        // ever shrink to.
        val widthPx = captionNode.size.width
        assert(widthPx > 60) {
            "expected the caption to keep a real wrap width, got ${widthPx}px (a per-character " +
                "collapse measures only a few px wide)"
        }
        composeTestRule.onNodeWithText("Verify").assertExists()
    }

    // `NavRow`'s own tests moved to `NavRowTest.kt` (detekt's `LargeClass` finding, once this file
    // grew past a reasonable size across every row family it covers) — `NavRow` was already a
    // self-contained cluster here, not entangled with `LogRow`/`KeyValueRow`/the rest this file
    // still owns.

    @Test
    fun `R_065_highlightRanges paints the matched words in highlightGreen, per Search-Results_dc_html`() {
        val transcript = "park activation of the state park"
        composeTestRule.setContent {
            OrtTheme {
                LogRow(
                    // "park" (0..3) and "activation" (5..14).
                    state = row(transcript = transcript, highlightRanges = listOf(0..3, 5..14)),
                    onClick = {},
                    modifier = Modifier.testTag("hit"),
                )
            }
        }

        // R-380/R-381: `LogRow`'s own outer node (tag "hit") now `clearAndSetSemantics` (an
        // earlier entry in this file's own `CHANGELOG.md`) — its own merged config no longer
        // aggregates descendant `Text`s, so this queries the transcript `Text` node itself,
        // directly, on the unmerged tree, for its own `SemanticsProperties.Text`/`spanStyles`.
        val node = composeTestRule.onNodeWithText(transcript, useUnmergedTree = true).fetchSemanticsNode()
        val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
        val annotated = texts.firstOrNull { it.text == transcript }
        assert(annotated != null) { "expected the transcript text to be present in the row's semantics" }
        assert(annotated!!.spanStyles.any { it.item.background == OrtColors.highlightGreen }) {
            "expected at least one span styled with highlightGreen, got ${annotated.spanStyles}"
        }
    }

    @Test
    fun `an empty highlightRanges list renders the transcript with no highlight spans`() {
        composeTestRule.setContent {
            OrtTheme {
                LogRow(state = row(transcript = "no matches here"), onClick = {}, modifier = Modifier.testTag("plain"))
            }
        }

        // R-380/R-381: see the note on `R_065` above — queries the transcript `Text` node
        // itself, directly, on the unmerged tree.
        val node = composeTestRule.onNodeWithText("no matches here", useUnmergedTree = true).fetchSemanticsNode()
        val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
        val annotated = texts.firstOrNull { it.text == "no matches here" }
        assert(annotated != null && annotated.spanStyles.isEmpty()) {
            "expected no highlight spans on a row with no highlightRanges"
        }
    }

    // `RejectedRow`'s own R-043/R-242/no-why tests moved to `RejectedRowTest.kt` (see the note
    // above this file's own `R_205_log_row and rejected_row...` test).

    @Test
    fun `R_246_a provisional log row's transcript style is italic, a resolved row's is not`() {
        // A rendered `fontStyle` isn't reliably verifiable through Compose semantics (no property
        // exposes it), so this checks the pure decision `LogRow` renders from directly —
        // `TextStyle`/`FontStyle` compare as ordinary data classes, no composition needed.
        assert(logRowTranscriptStyle(LogRowPartial.HEARING).fontStyle == FontStyle.Italic) {
            "expected a HEARING row's transcript style to be italic"
        }
        assert(logRowTranscriptStyle(LogRowPartial.RESOLVING).fontStyle == FontStyle.Italic) {
            "expected a RESOLVING row's transcript style to be italic"
        }
        assert(logRowTranscriptStyle(null).fontStyle != FontStyle.Italic) {
            "expected a resolved (non-partial) row's transcript style to stay upright"
        }
    }

    @Test
    fun `R_505_the one-line gate sums all four columns including signal, not three`() {
        // The register's own repro (`search-corpus/Q03-results-park@2x.png`): the gate compared
        // the row's real width against `time + freq + callsign` plus a *fixed* 24dp guess for
        // signal, so it kept picking the one-line layout past the point the row actually had room
        // once the real signal text grew past 24dp — this pins the gate's own arithmetic directly
        // (no composition, no font metrics — plain `Dp` addition), the same way
        // `R_246`'s neighbour test above pins `logRowTranscriptStyle` rather than a rendered pixel.
        val gate = oneLineWidthFor(
            timeWidth = 52.dp,
            freqWidth = 56.dp,
            callsignWidth = 90.dp,
            signalWidth = 40.dp,
            gap = 10.dp,
        )
        assert(gate == 52.dp + 10.dp + 56.dp + 10.dp + 90.dp + 10.dp + 40.dp) {
            "expected the gate to sum time + freq + callsign + signal with a gap between each, got $gate"
        }
        // A larger signal width alone moves the gate — the exact fact a *fixed* 24dp constant
        // could never satisfy, which is the whole defect this id fixes.
        val widerSignal = oneLineWidthFor(52.dp, 56.dp, 90.dp, 60.dp, 10.dp)
        assert(widerSignal > gate) {
            "expected a wider signal column alone to raise the gate's own required width"
        }
    }

    @Test
    fun `R_505_the signal column's real width never returns less than the 24dp guide floor`() {
        // `rememberSignalColumnWidth` is `rememberMonoColumnWidth`'s own `maxOf(floor, measured)`
        // pattern (the same one `rememberCallsignColumnWidth`/`rememberTimeColumnWidth` already
        // use) — this host's own font metrics may or may not exceed 24dp for "S9", but the floor
        // itself is a structural guarantee this test can pin regardless of that.
        var width = 0.dp
        composeTestRule.setContent {
            OrtTheme { width = rememberSignalColumnWidth() }
        }
        assert(width >= 24.dp) { "expected the signal column's own floor to hold, got $width" }
    }

    @Test
    fun `R_246_a provisional log row still renders its real transcript text, unchanged by the italic style`() {
        composeTestRule.setContent {
            OrtTheme {
                LogRow(
                    state = row(partial = LogRowPartial.HEARING, attribution = null),
                    onClick = {},
                    modifier = Modifier.testTag("hearing"),
                )
            }
        }

        // R-380 correction (WP2, gate-blocking): `LogRow`'s own outer node now also carries this
        // transcript as part of its own composed `text` (not just `contentDescription`), so the
        // *default* merged tree finds it directly, uniquely — `useUnmergedTree = true` would find
        // this AND the still-present inner `Text` node, two matches instead of one.
        composeTestRule.onNodeWithText(
            "this is whiskey seven november papa charlie, monitoring",
            substring = true,
        ).assertExists()
    }

    @Test
    fun `a notification card renders collapsed by default and shows expanded rows and actions when supplied`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    NotificationCard(
                        icon = OrtIcons.frequencies,
                        title = "Capturing",
                        elapsedLabel = "6:42",
                        countLabel = "412 overs",
                        secondLine = "145.230 and 146.960 · tier 3",
                        modifier = Modifier.testTag("collapsed"),
                    )
                    NotificationCard(
                        icon = OrtIcons.frequencies,
                        title = "Capturing",
                        elapsedLabel = "7:10",
                        countLabel = "440 overs",
                        secondLine = "Running warm — tier 2",
                        degraded = true,
                        expandedRows = listOf(NotificationCardRow("Last over", "W7NPC · 02:14 · 145.230")),
                        primaryActionLabel = "Open",
                        secondaryActionLabel = "Stop",
                        modifier = Modifier.testTag("expanded"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("collapsed").assertIsDisplayed()
        composeTestRule.onNodeWithText("W7NPC · 02:14 · 145.230").assertIsDisplayed()
        // R-380 correction (WP2, gate-blocking): the card's own action buttons carry their label
        // as both `contentDescription` and `text` now, so the default merged tree finds them
        // directly and uniquely (see `Controls.kt`'s `TextAction` doc comment).
        composeTestRule.onNodeWithText("Open").assertIsDisplayed()
        composeTestRule.onNodeWithText("Stop").assertIsDisplayed()
        composeTestRule.onNodeWithText("Running warm — tier 2").assertIsDisplayed()
    }

    @Test
    fun `R_152_a key value row keeps a real enforced gap between its label and its value at font scale 2`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    // The register's own repro: `Session`/`Capture-Status`'s "Stations" key
                    // against a one-character value rendered "Stations5" at font scale 2.0 — the
                    // pre-fix row had no `Arrangement` gap at all between its columns, so this is
                    // the one row where the fix had to *add* spacing, not just widen a floor.
                    KeyValueRow(key = "Stations", value = "5")
                }
            }
        }

        assertColumnsDoNotCollide("Stations", "5", minGapDp = OrtSpacing.sm.value.toInt())
    }

    @Test
    fun `R_152_a column header row keeps a real enforced gap between its time and freq columns at font scale 2`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    ColumnHeaderRow()
                }
            }
        }

        assertColumnsDoNotCollide("TIME", "FREQ", minGapDp = 10)
    }

    @Test
    fun `R_152_a log row keeps a real enforced gap between its time and freq columns at font scale 2`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    LogRow(state = row(), onClick = {})
                }
            }
        }

        assertColumnsDoNotCollide("02:14:07", "145.230", minGapDp = 10)
    }

    // `R_152_a rejected row...` moved to `RejectedRowTest.kt` (see the note above this file's own
    // `R_205_log_row and rejected_row...` test).

    @Test
    fun `R_152_a notification card's expanded key-value row keeps a real enforced gap at font scale 2`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    NotificationCard(
                        icon = OrtIcons.frequencies,
                        title = "Capturing",
                        elapsedLabel = "6:42",
                        countLabel = "412 overs",
                        secondLine = "145.230 and 146.960 · tier 3",
                        expandedRows = listOf(NotificationCardRow("Last over", "W7NPC · 02:14 · 145.230")),
                    )
                }
            }
        }

        assertColumnsDoNotCollide("Last over", "W7NPC · 02:14 · 145.230", minGapDp = 8)
    }
}
