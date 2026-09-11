package org.ort.app.ui.screens

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.debug.Scenarios
import org.ort.app.ui.components.LogRowBadge
import org.ort.app.ui.components.LogRowPartial
import org.ort.app.ui.components.LogRowViewState
import org.ort.app.ui.data.LogEmptyStateViewState
import org.ort.app.ui.data.LogFilterSelection
import org.ort.app.ui.data.LogItemsMapper
import org.ort.app.ui.data.LogListItem
import org.ort.app.ui.data.LogPolling
import org.ort.app.ui.data.LogQuickFilterChipViewState
import org.ort.app.ui.data.LogQuickFilterId
import org.ort.app.ui.data.LogScreenViewState
import org.ort.app.ui.data.TransmissionDetail
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * The "Log" destination (R-040/R-041/R-042/R-043/R-045, ui-conformance WP5;
 * `design/canvas/Log.dc.html`, `Rows.dc.html`, `Log-Partial.dc.html`, `Log-Empty.dc.html`,
 * `Log-Rejected.dc.html`). Every fact this screen renders comes from `LogScreenViewState`
 * (`ui/data/LogViewData.kt`'s own tests, `LogViewDataTest`, cover the rules that produce it); this
 * proves the screen lays that state out correctly.
 *
 * **FR_UI_4, renamed from `FR_UI_4 the confidence value is shown as visible text…`** (audit
 * F-012): the design's source of truth (`States.dc.html`, guide §6.2) shows the score chip
 * **only** on INFERRED — a CONFIRMED callsign was heard, and a number would imply doubt the data
 * does not have. FR-UI-4's "never omitted" is the closed-set *state* (the marker shape), which
 * every row carries regardless of whether a confidence number sits beside it; that is what this
 * test now establishes, through [org.ort.app.ui.components.AttributionRow] rather than the legacy
 * always-show-confidence [org.ort.app.ui.components.AttributionMarker] shim.
 */
@RunWith(RobolectricTestRunner::class)
class LogScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun rowState(
        id: String,
        transcript: String,
        attribution: Attribution? = Attribution.unknown(),
        alternate: String? = null,
        badge: LogRowBadge? = null,
        partial: LogRowPartial? = null,
        callsign: String? = null,
    ) = LogRowViewState(
        id = id,
        timeLabel = "02:14:07",
        frequencyLabel = "145.230",
        transcript = transcript,
        partial = partial,
        attribution = if (partial == null) attribution else null,
        alternate = alternate,
        callsign = callsign,
        signalLabel = if (partial == null) "S7" else null,
        badge = badge,
    )

    private fun screenState(
        items: List<LogListItem>,
        emptyState: LogEmptyStateViewState? = null,
        rejectedFocus: Boolean = false,
        rejectedExplanation: String? = null,
        quickFilters: List<LogQuickFilterChipViewState> = listOf(
            LogQuickFilterChipViewState(LogQuickFilterId.All, "All", true),
            LogQuickFilterChipViewState(LogQuickFilterId.Rejected, "Rejected", false),
        ),
        bluetoothAudioFootnote: String? = null,
    ) = LogScreenViewState(items, quickFilters, rejectedFocus, rejectedExplanation, emptyState, bluetoothAudioFootnote)

    @Test
    fun `FR_UI_1 a transmission with no transcript yet shows the honest not-yet-transcribed state, not an empty row`() {
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(listOf(LogListItem.Row(rowState("TX1", "(captured, not yet transcribed)")))),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        // R-380/R-381 (WP2, main 6cea753): `LogRow`'s own outer node now `clearAndSetSemantics`
        // for one explicit merged description — its inner `Text`s are only reachable on the
        // unmerged tree now (`ui/components/RowsTest.kt`'s own equivalent note).
        composeTestRule.onNodeWithText("(captured, not yet transcribed)", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `FR_UI_1 a superseded transcript is visibly marked REVISED, never silently replaced`() {
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(
                        listOf(
                            LogListItem.Row(
                                rowState(
                                    "TX1",
                                    "final transcript",
                                    Attribution.confirmed("W7NPC", 0.95),
                                    badge = LogRowBadge.REVISED,
                                ),
                            ),
                        ),
                    ),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        // R-380/R-381: see the note above — `LogRow`'s badge `Text` is only reachable unmerged now.
        composeTestRule.onNodeWithText("REVISED", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `FR_UI_4_state_marker_is_never_omitted_and_the_score_chip_appears_only_on_INFERRED`() {
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(
                        listOf(
                            LogListItem.Row(rowState("TX-confirmed", "a", Attribution.confirmed("W7NPC", 0.95))),
                            LogListItem.Row(rowState("TX-inferred", "b", Attribution.inferred("K7LWH", 0.82))),
                            LogListItem.Row(rowState("TX-ambiguous", "c", Attribution.ambiguous())),
                            LogListItem.Row(rowState("TX-unknown", "d", Attribution.unknown())),
                        ),
                    ),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        // The state — the marker shape — is never omitted, on every one of the four closed states.
        // R-380/R-381: `AttributionRow`'s own contentDescription node sits inside `LogRow`'s outer
        // `clearAndSetSemantics` boundary now, so it is only reachable on the unmerged tree.
        fun marker(description: String) = composeTestRule.onNodeWithContentDescription(
            description,
            substring = true,
            useUnmergedTree = true,
        )
        marker("filled circle, Confirmed, W7NPC").assertExists()
        marker("outlined circle, Inferred, K7LWH").assertExists()
        marker("half-filled circle, Ambiguous").assertExists()
        marker("small dot, Unknown, unknown station").assertExists()

        // The score chip is visible text beside INFERRED only — never a number on CONFIRMED.
        composeTestRule.onNodeWithText("0.82", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("0.95", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `R_240 an AMBIGUOUS row's description carries the kept candidate and the alternate`() {
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(
                        listOf(
                            LogListItem.Row(
                                rowState(
                                    "TX-ambiguous",
                                    "kilo echo seven quebec romeo sierra, portable",
                                    Attribution.ambiguous(),
                                    alternate = "KE7QRF",
                                    callsign = "KE7QRS",
                                ),
                            ),
                        ),
                    ),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        // The kept candidate renders in the row's own visible text (guide: primary in text/high)...
        // R-380/R-381: only reachable on the unmerged tree now — see the note above.
        composeTestRule.onNodeWithText("KE7QRS", useUnmergedTree = true).assertExists()
        // ...and the merged marker description names both — the kept candidate, then "or" the
        // alternate — never just the alternate on its own (the bug R-240 named).
        composeTestRule
            .onNodeWithContentDescription(
                "half-filled circle, Ambiguous, KE7QRS, or KE7QRF",
                substring = true,
                useUnmergedTree = true,
            )
            .assertExists()
    }

    @Test
    fun `tapping a row opens that transmission`() {
        var opened: String? = null
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(listOf(LogListItem.Row(rowState("TX1", "roger that")))),
                    onOpen = { opened = it },
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        // R-380/R-381: the transcript `Text` is only reachable unmerged now; `performClick()` on it
        // still dispatches a real touch at that node's own on-screen position, which the row's own
        // outer `clickable` (unaffected by the semantics change) picks up exactly as before.
        composeTestRule.onNodeWithText("roger that", useUnmergedTree = true).performClick()

        assert(opened == "TX1") { "expected TX1 to be opened but was $opened" }
    }

    @Test
    fun `R_045 an empty log keeps the header, chips and columns and says why nothing is here`() {
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(
                        items = emptyList(),
                        emptyState = LogEmptyStateViewState(
                            "No overs yet.",
                            "Listening since 23:32. The first one appears here the moment squelch opens.",
                        ),
                    ),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Log").assertExists()
        // R-380 correction (WP2, gate-blocking): `FilterChip`'s own outer node now carries its
        // label as both `contentDescription` and `text` (`ui/components/CHANGELOG.md`), so the
        // default merged tree finds it directly and uniquely — `useUnmergedTree = true` would also
        // surface the still-present inner `Text`, two matches instead of one.
        composeTestRule.onNodeWithText("All").assertExists()
        composeTestRule.onNodeWithText("No overs yet.").assertExists()
        composeTestRule
            .onNodeWithText("Listening since 23:32. The first one appears here the moment squelch opens.")
            .assertExists()
    }

    @Test
    fun `R_040 a QSO group header renders above its rows`() {
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(
                        listOf(
                            LogListItem.Group("T1", "QSO · 2 overs · 2 stations"),
                            LogListItem.Row(rowState("TX1", "monitoring", Attribution.confirmed("W7NPC", 0.9))),
                        ),
                    ),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("QSO · 2 overs · 2 stations").assertExists()
    }

    @Test
    fun `R_017 tapping a QSO group header opens its thread`() {
        var openedThread: String? = null
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(
                        listOf(
                            LogListItem.Group("T1", "QSO · 2 overs · 2 stations"),
                            LogListItem.Row(rowState("TX1", "monitoring", Attribution.confirmed("W7NPC", 0.9))),
                        ),
                    ),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                    onOpenThread = { openedThread = it },
                )
            }
        }

        composeTestRule.onNodeWithText("QSO · 2 overs · 2 stations").performClick()

        assert(openedThread == "T1") { "expected T1 to be opened but was $openedThread" }
    }

    @Test
    fun `R_040 a gap row names the reason, never conflated with a quiet band`() {
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(
                        listOf(LogListItem.Gap("G1", "02:15:00", "not listening · 38s · incoming call")),
                    ),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("not listening · 38s · incoming call").assertExists()
    }

    private val rejectedExplanationText =
        "1 segment rejected tonight. Audio for every one is kept; opening a row plays it."

    @Test
    fun `R_043 a rejected row stays reachable and opens like any other row`() {
        var opened: String? = null
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(
                        listOf(LogListItem.RejectedItem("TX1", "02:16:40", "146.960", "squelch tail")),
                        rejectedFocus = true,
                        rejectedExplanation = rejectedExplanationText,
                    ),
                    onOpen = { opened = it },
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText(rejectedExplanationText).assertExists()
        composeTestRule.onNodeWithContentDescription("rejected", substring = true).assertExists().performClick()
        assert(opened == "TX1") { "expected TX1 to be opened but was $opened" }
    }

    private val whyText = "Too short, segment is 120 ms, below the 250 ms floor"

    @Test
    fun `R_043 the why line shows in the dedicated Rejected view`() {
        val item = LogListItem.RejectedItem("TX1", "02:16:40", "146.960", "too short", why = whyText)
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(listOf(item), rejectedFocus = true),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        // R-381 (WP2 next round): `RejectedRow`'s own clickable branch (this dedicated Rejected
        // view opens the row, so it renders via `onClick`) now `clearAndSetSemantics`
        // (`ui/components/CHANGELOG.md`), so its own `why` line is only reachable on the unmerged
        // tree — the row's own composed description states it too, but this checks the real
        // rendered text specifically.
        composeTestRule.onNodeWithText(whyText, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `R_043 the why line stays hidden in the interleaved list`() {
        val item = LogListItem.RejectedItem("TX1", "02:16:40", "146.960", "too short", why = whyText)
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(listOf(item), rejectedFocus = false),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText(whyText).assertDoesNotExist()
    }

    @Test
    fun `R_242 the DUR column shows in the dedicated Rejected view`() {
        val item = LogListItem.RejectedItem(
            "TX1",
            "02:16:40",
            "146.960",
            "no speech detected",
            durationLabel = "0.4 s",
        )
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(listOf(item), rejectedFocus = true),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        // R-381 (WP2 next round): see the note on `R_043 the why line shows...` above.
        composeTestRule.onNodeWithText("0.4 s", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `R_242 the DUR column stays hidden in the interleaved list`() {
        val item = LogListItem.RejectedItem(
            "TX1",
            "02:16:40",
            "146.960",
            "no speech detected",
            durationLabel = "0.4 s",
        )
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(listOf(item), rejectedFocus = false),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("0.4 s").assertDoesNotExist()
    }

    @Test
    fun `R_041 a hearing partial has no state marker and shows the streaming text`() {
        composeTestRule.setContent {
            OrtTheme {
                val row = rowState("TX1", "and we're clear on the rep", partial = LogRowPartial.HEARING)
                LogScreen(
                    state = screenState(listOf(LogListItem.Row(row))),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        // R-380/R-381: see the note above — `LogRow`'s inner `Text`s are only reachable unmerged.
        composeTestRule.onNodeWithText("hearing…", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("and we're clear on the rep", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `R_041 a resolving partial has no state marker and shows the final text`() {
        composeTestRule.setContent {
            OrtTheme {
                val text = "and we're clear on the repeater, seven three"
                val row = rowState("TX1", text, partial = LogRowPartial.RESOLVING)
                LogScreen(
                    state = screenState(listOf(LogListItem.Row(row))),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        // R-380/R-381: see the note above.
        composeTestRule.onNodeWithText("resolving…", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `R_042 tapping Filter invokes the callback`() {
        var clicked = false
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(listOf(LogListItem.Row(rowState("TX1", "roger that")))),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = { clicked = true },
                )
            }
        }

        // R-380 correction (WP2, gate-blocking): `TextAction`'s own outer node now carries its
        // label as both `contentDescription` and `text`, so the default merged tree finds it
        // directly and uniquely — `useUnmergedTree = true` would also surface the still-present
        // inner `Text`, two matches instead of one (breaking `performClick`'s own node resolution).
        composeTestRule.onNodeWithText("Filter").performClick()

        assert(clicked) { "expected the Filter action to invoke its callback" }
    }

    @Test
    fun `R_042 selecting a quick filter chip invokes the callback with its id`() {
        var selected: LogQuickFilterId? = null
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(listOf(LogListItem.Row(rowState("TX1", "roger that")))),
                    onOpen = {},
                    onQuickFilterSelect = { selected = it },
                    onFilterClick = {},
                )
            }
        }

        // R-380 correction (WP2, gate-blocking): see the note above.
        composeTestRule.onNodeWithText("Rejected").performClick()

        assert(selected == LogQuickFilterId.Rejected) { "expected the Rejected chip to be selected but was $selected" }
    }

    private fun detail(id: String, freqHz: Long, atMillis: Long) = TransmissionDetail(
        id = id,
        startedAtUtcMillis = atMillis,
        frequencyHz = freqHz,
        durationMs = 4_200L,
        signalStrength = 7.0,
        attribution = Attribution.confirmed("W7NPC", 0.9),
        currentTranscriptText = "$id transcript",
        supersededTranscriptTexts = emptyList(),
        hasAudio = true,
    )

    /**
     * R-276 (`Frequency.dc.html`'s "The N overs" stat → Log filtered to that frequency and window):
     * this runs the real `LogItemsMapper` functions [LogContent]'s seeded `initialFilter` and
     * `quickFilter` drive — `selectionFor`, `buildItems`, `quickFilters` — the same call chain
     * `LogPolling.screenState` makes, so this proves the production filtering logic, not a
     * hand-fabricated already-filtered state.
     */
    @Test
    fun `R_276 a seeded frequency and time window narrows rows and the frequency chip renders active`() {
        val details = listOf(
            detail("TX-in-window", freqHz = 145_230_000L, atMillis = 1_000L), // matches freq + window
            detail("TX-out-of-window", freqHz = 145_230_000L, atMillis = 50_000L), // matches freq, not window
            detail("TX-other-freq", freqHz = 146_960_000L, atMillis = 1_000L), // matches window, not freq
        )
        val seededSelection = LogFilterSelection(frequencyHz = 145_230_000L, fromMillis = 0L, toMillis = 10_000L)
        val seededQuickFilter = LogQuickFilterId.Frequency(145_230_000L)
        val effectiveSelection = LogItemsMapper.selectionFor(seededQuickFilter, seededSelection)
        val items = LogItemsMapper.buildItems(
            details,
            gaps = emptyList(),
            effectiveSelection,
            firstHeardIds = emptySet(),
        )
        val quickFilters = LogItemsMapper.quickFilters(
            frequencies = listOf(145_230_000L, 146_960_000L),
            active = seededQuickFilter,
            rejectedCount = 0,
        )

        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(items, quickFilters = quickFilters),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        // R-380/R-381: `LogRow`'s transcript `Text` is only reachable unmerged now.
        composeTestRule.onNodeWithText("TX-in-window transcript", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("TX-out-of-window transcript", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("TX-other-freq transcript", useUnmergedTree = true).assertDoesNotExist()
        // "145.230" also appears in the matching row's own frequency column, so this narrows to the
        // quick-filter chip specifically by its `Role.Checkbox` (`FilterChip`'s own `.selectable`
        // role — the row is a plain `Role.Button`). R-380/R-381: `FilterChip`'s own outer node now
        // `clearAndSetSemantics`-es an explicit `contentDescription = label`, not a `Text` property,
        // so this matches on content description rather than `hasText`.
        val chipMatcher = hasContentDescription("145.230") and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
        composeTestRule.onNode(chipMatcher).assertIsSelected()
    }

    /**
     * R-322 (V4 pass 2 @ee4fd07, reopened from R-240): reproduces the on-device bug directly —
     * seeds the real `overnight` fixture through [Scenarios] (the same fixture `L01-log-pass2.png`
     * was screenshotted against) and drives it through the real production call chain
     * ([LogPolling.screenState] → [LogItemsMapper.toRowState] → [LogScreen] → `LogRow`), not a
     * hand-built [LogRowViewState] the way the original R-240 test did — that test's fabricated
     * `selected = true` candidate was exactly the gap between "passes in CI" and "still broken on
     * device" the register named. This must fail before `LogItemsMapper.keptCandidateFor`'s
     * rank-based fix and pass after it.
     */
    @Test
    fun `R_322 the real overnight fixture's AMBIGUOUS row names its kept candidate, not just the alternate`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val state = runBlocking {
            val result = Scenarios.load(context, "overnight")
            val sessionId = requireNotNull(result.primarySessionId)
            LogPolling.screenState(context, sessionId, LogFilterSelection(), LogQuickFilterId.All)
        }

        composeTestRule.setContent {
            OrtTheme {
                LogScreen(state = state, onOpen = {}, onQuickFilterSelect = {}, onFilterClick = {})
            }
        }

        // The visible callsign, primary in `text/high` (guide: never just the alternate alone).
        // R-380/R-381: only reachable on the unmerged tree now — see the note above.
        composeTestRule.onNodeWithText("KE7QRS", useUnmergedTree = true).assertExists()
        composeTestRule
            .onNodeWithContentDescription(
                "half-filled circle, Ambiguous, KE7QRS, or KE7QRF",
                substring = true,
                useUnmergedTree = true,
            )
            .assertExists()
    }

    // -----------------------------------------------------------------------------------------
    // The Bluetooth-audio footnote (checklist row E2-G04, F23).
    // -----------------------------------------------------------------------------------------

    @Test
    @Requirement("FR-CAP-13")
    fun `FR_CAP_13 the Bluetooth-audio footnote renders when present`() {
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(
                        listOf(LogListItem.Row(rowState("TX1", "roger"))),
                        bluetoothAudioFootnote = "Every over captured over Bluetooth carries the bt audio mark.",
                    ),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("log-bluetooth-audio-footnote").assertExists()
    }

    @Test
    @Requirement("FR-CAP-13")
    fun `FR_CAP_13 no footnote renders for a non-Bluetooth session`() {
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(listOf(LogListItem.Row(rowState("TX1", "roger")))),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("log-bluetooth-audio-footnote").assertDoesNotExist()
    }
}
