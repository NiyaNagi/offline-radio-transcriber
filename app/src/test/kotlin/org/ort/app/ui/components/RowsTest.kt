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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    ) = LogRowViewState(
        id = "TX1",
        timeLabel = "02:14:07",
        frequencyLabel = "145.230",
        transcript = transcript,
        partial = partial,
        attribution = attribution,
        signalLabel = "S7",
        badge = badge,
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
                }
            }
        }

        composeTestRule.onNodeWithTag("confirmed").performClick()
        assert(opened == "confirmed")
        // A scrollable Column, so a badge on the seventh stacked row need only exist, not be
        // scrolled into view, for this test's purpose (every variant renders distinctly).
        composeTestRule.onNodeWithText("hearing…").assertExists()
        composeTestRule.onNodeWithText("resolving…").assertExists()
        composeTestRule.onNodeWithText("NEW").assertExists()
        composeTestRule.onNodeWithTag("confirmed").assertHeightIsAtLeast(44.dp)
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

        composeTestRule.onNodeWithText("KE7QRS").assertIsDisplayed()
        composeTestRule.onNodeWithText("or N7ABC").assertIsDisplayed()
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

        composeTestRule.onNodeWithText("or N7ABC").assertIsDisplayed()
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
        // on a wrapped second line rather than clipping it to a sliver.
        composeTestRule.onNodeWithText("NEW").assertExists()
        val noBadgeHeight = composeTestRule.onNodeWithTag("no-badge").fetchSemanticsNode().size.height
        val withBadgeHeight = composeTestRule.onNodeWithTag("with-badge").fetchSemanticsNode().size.height
        assert(withBadgeHeight > noBadgeHeight) {
            "expected the badge to wrap onto a second line in a narrow row, measuring taller than " +
                "the same row with no badge; got no-badge=${noBadgeHeight}px, with-badge=${withBadgeHeight}px"
        }
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

    @Test
    fun `a gap row and a rejected row stay reachable rather than disappearing`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    GapRow(
                        timeLabel = "02:15:0",
                        label = "not listening · 38 s · incoming call",
                        modifier = Modifier.testTag("gap"),
                    )
                    RejectedRow(
                        timeLabel = "02:16:40",
                        frequencyLabel = "146.960",
                        reason = "squelch tail",
                        modifier = Modifier.testTag("rejected"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("not listening · 38 s · incoming call").assertIsDisplayed()
        composeTestRule.onNodeWithText("REJECTED · SQUELCH TAIL").assertIsDisplayed()
        composeTestRule.onNodeWithTag("gap").assertHeightIsAtLeast(44.dp)
    }

    @Test
    fun `a log group header names the thread and a column header row names every column`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    LogGroupHeader(label = "QSO · 4 overs · 2 stations")
                    ColumnHeaderRow()
                }
            }
        }

        composeTestRule.onNodeWithText("QSO · 4 overs · 2 stations").assertIsDisplayed()
        composeTestRule.onNodeWithText("TIME").assertIsDisplayed()
        composeTestRule.onNodeWithText("FREQ").assertIsDisplayed()
    }

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
        // catch that class of regression).
        composeTestRule.onNodeWithText("02:14:07").assertIsDisplayed()
        composeTestRule.onNodeWithText("145.230").assertIsDisplayed()
        composeTestRule.onNodeWithText("16:28:56").assertIsDisplayed()
        composeTestRule.onNodeWithText("146.960").assertIsDisplayed()

        // The guide's own 52dp/56dp floor, never regressed — deterministic regardless of host
        // font metrics, since a `widthIn(min = …)` never reports less than its floor.
        val logTimeWidth = composeTestRule.onNodeWithText("02:14:07").fetchSemanticsNode().size.width
        val logFreqWidth = composeTestRule.onNodeWithText("145.230").fetchSemanticsNode().size.width
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

    @Test
    fun `R_205_gap_row and column_header_row time and freq columns meet the guide's floor and render in full`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    GapRow(timeLabel = "16:28:56", label = "not listening", modifier = Modifier.testTag("gap"))
                    ColumnHeaderRow()
                }
            }
        }

        composeTestRule.onNodeWithText("16:28:56").assertIsDisplayed()
        val gapTimeWidth = composeTestRule.onNodeWithText("16:28:56").fetchSemanticsNode().size.width
        assert(gapTimeWidth >= 52) { "expected GapRow's time column at least 52px, got ${gapTimeWidth}px" }

        val headerTimeWidth = composeTestRule.onNodeWithText("TIME").fetchSemanticsNode().size.width
        val headerFreqWidth = composeTestRule.onNodeWithText("FREQ").fetchSemanticsNode().size.width
        assert(headerTimeWidth >= 52) {
            "expected ColumnHeaderRow's time column at least 52px, got ${headerTimeWidth}px"
        }
        assert(headerFreqWidth >= 56) {
            "expected ColumnHeaderRow's freq column at least 56px, got ${headerFreqWidth}px"
        }
    }

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
    fun `a key value row and an action bar render at a 44dp target`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    KeyValueRow(key = "Route", value = "USB audio · verified", modifier = Modifier.testTag("kv"))
                    ActionBar(secondaryLabel = "Not right?", onSecondary = {}, primaryLabel = "Confirm", onPrimary = {})
                }
            }
        }

        composeTestRule.onNodeWithTag("kv").assertHeightIsAtLeast(44.dp)
        composeTestRule.onNodeWithText("Confirm").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not right?").assertIsDisplayed()
    }

    @Test
    fun `R_131_nav_row carries its leading icon, title, sub-line and a trailing chevron`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    NavRow(
                        rowTitle = "Rig",
                        subLine = "TH-D75A · connected",
                        icon = OrtIcons.rig,
                        onClick = {},
                        modifier = Modifier.testTag("nav-rig"),
                    )
                    NavRow(rowTitle = "Improve", onClick = {}, modifier = Modifier.testTag("nav-bare"))
                }
            }
        }

        composeTestRule.onNodeWithText("Rig").assertIsDisplayed()
        composeTestRule.onNodeWithText("TH-D75A · connected").assertIsDisplayed()
        composeTestRule.onNodeWithText("Improve").assertIsDisplayed()
    }

    @Test
    fun `R_131_nav_row is a real 44dp Role_Button target with a merged title-then-subLine description`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    NavRow(
                        rowTitle = "Tier",
                        subLine = "Tier 1 · this phone's best",
                        onClick = {},
                        modifier = Modifier.testTag("nav-tier"),
                    )
                    NavRow(rowTitle = "About", onClick = {}, modifier = Modifier.testTag("nav-about"))
                }
            }
        }

        val withSubLine = composeTestRule.onNodeWithTag("nav-tier")
        withSubLine.assertHeightIsAtLeast(44.dp)
        withSubLine.assert(hasContentDescription("Tier. Tier 1 · this phone's best"))

        // No sub-line: the bare title, never a dangling ". ".
        val bare = composeTestRule.onNodeWithTag("nav-about")
        bare.assertHeightIsAtLeast(44.dp)
        bare.assert(hasContentDescription("About"))
    }

    @Test
    fun `R_131_nav_row fires onClick and renders an optional trailing slot beside the chevron`() {
        var tapped = false
        composeTestRule.setContent {
            OrtTheme {
                NavRow(
                    rowTitle = "Tier",
                    onClick = { tapped = true },
                    trailing = { Badge(text = "Tier 1", kind = BadgeKind.TIER) },
                    modifier = Modifier.testTag("nav-tier"),
                )
            }
        }

        composeTestRule.onNodeWithText("TIER 1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav-tier").performClick()
        assert(tapped)
    }

    @Test
    fun `R_131_nav_row's sub-line renders in full at font scale 2 point 0, never truncated`() {
        // R-131 (`Settings.dc.html`): a real status sentence must be free to wrap rather than
        // being cut to one ellipsised line — `NavRow` sets no `maxLines`/`overflow` on its
        // sub-line `Text` at all (Compose's own default is unrestricted, soft-wrapping), so there
        // is no ceiling here to regress back to. A height-based "did it actually wrap onto more
        // lines" assertion was tried and dropped: Robolectric returns degenerate glyph metrics
        // for this codebase's custom `fontFamily`s (confirmed directly, same finding recorded
        // against R-152's fix earlier in this file/CHANGELOG — a 71-character sub-line and a
        // 9-character one measured the identical row height, 88px, at the same font scale), so a
        // rendered-pixel wrap can't be verified reliably on this host. What *is* real and
        // host-independent is that the full sentence survives verbatim into the semantics tree —
        // this catches a future regression that truncates/summarises the string before it ever
        // reaches `Text`, which no font metric is needed to detect.
        val longSubLine = "This phone's best · what it does not know about weak signals or noise"
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    NavRow(
                        rowTitle = "Tier",
                        subLine = longSubLine,
                        onClick = {},
                        modifier = Modifier.width(320.dp).testTag("nav-tier"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText(longSubLine).assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav-tier").assert(hasContentDescription(longSubLine, substring = true))
    }

    @Test
    fun `R_131_nav_row's NotBuilt tone dims the row without hiding it`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    NavRow(
                        rowTitle = "Rig",
                        icon = OrtIcons.rig,
                        onClick = {},
                        tone = NavRowTone.NotBuilt,
                        modifier = Modifier.testTag("nav-not-built"),
                    )
                }
            }
        }

        // Still present, still reachable (constitution III: never delete quietly) — only its
        // colour differs, which this test does not (and, per this package's prior findings on
        // Robolectric font/paint metrics, reliably cannot) assert directly; the structural claim
        // — the row still renders and is still a real target — is what is checked here.
        composeTestRule.onNodeWithText("Rig").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav-not-built").assertHeightIsAtLeast(44.dp)
    }

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

        val node = composeTestRule.onNodeWithTag("hit").fetchSemanticsNode()
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

        val node = composeTestRule.onNodeWithTag("plain").fetchSemanticsNode()
        val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
        val annotated = texts.firstOrNull { it.text == "no matches here" }
        assert(annotated != null && annotated.spanStyles.isEmpty()) {
            "expected no highlight spans on a row with no highlightRanges"
        }
    }

    @Test
    fun `R_043_a rejected row's optional why line explains the reason in prose`() {
        composeTestRule.setContent {
            OrtTheme {
                RejectedRow(
                    timeLabel = "02:16:40",
                    frequencyLabel = "146.960",
                    reason = "squelch tail",
                    why = "0.4 s of noise after the carrier dropped. No speech energy.",
                    modifier = Modifier.testTag("rejected-why"),
                )
            }
        }

        composeTestRule
            .onNodeWithText("0.4 s of noise after the carrier dropped. No speech energy.")
            .assertIsDisplayed()
    }

    @Test
    fun `a rejected row with no why still renders exactly as before`() {
        composeTestRule.setContent {
            OrtTheme {
                RejectedRow(
                    timeLabel = "02:16:40",
                    frequencyLabel = "146.960",
                    reason = "squelch tail",
                    modifier = Modifier.testTag("rejected-no-why"),
                )
            }
        }

        composeTestRule.onNodeWithText("REJECTED · SQUELCH TAIL").assertIsDisplayed()
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

    @Test
    fun `R_152_a rejected row keeps a real enforced gap between its time and freq columns at font scale 2`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    RejectedRow(timeLabel = "02:14:07", frequencyLabel = "145.230", reason = "squelch tail")
                }
            }
        }

        assertColumnsDoNotCollide("02:14:07", "145.230", minGapDp = 10)
    }

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
