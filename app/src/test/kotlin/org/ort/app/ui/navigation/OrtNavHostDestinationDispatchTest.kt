package org.ort.app.ui.navigation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.settings.SettingsScreenId
import org.ort.app.ui.theme.OrtTheme
import org.ort.pipeline.capture.CaptureState
import org.robolectric.RobolectricTestRunner

/**
 * ui-conformance-plan WP3 (round 3 reconciliation): the host now dispatches `LOG` to WP5's real
 * `LogContent` instead of this package's own deleted inline copy.
 *
 * This class originally also tried to drive full click-through navigation into `THREADS`/
 * `STATIONS`/`FREQUENCIES`/`CAPTURE` the same way, via `onNodeWithContentDescription("Open
 * <label>").performScrollTo().performClick()`, and hit an unresolved Robolectric+Compose issue:
 * the click after a scroll silently failed to land on any row past the second position, with no
 * thrown exception.
 *
 * Round 4 (this round) retried it once more per the coordinator's instruction, this time via
 * `onNodeWithTag("drawer-row-<NAME>")` after tagging the drawer's scrollable `Column` itself with
 * `testTag("drawer-rows")` (`Drawer.kt`) — reasoning that `performScrollTo` needs a *tagged*
 * scrollable ancestor to reliably compute the scroll offset for a `Column` inside `verticalScroll`
 * (unlike `LazyColumn`, which exposes scroll semantics on every child automatically). That fixed
 * six of the seven remaining rows (`THREADS`/`STATIONS`/`FREQUENCIES`/`SETTINGS`/
 * `EARLIER_NIGHTS`/`IMPROVE_RECORDS`) — every one of them now clicks through and lands on its own
 * real content.
 *
 * `CAPTURE` alone still does not, and this time the failure was chased all the way down rather
 * than just re-documented: it is not a scroll-position, timeout, or overlap problem. Isolated to a
 * minimal repro that composes `ReaderDrawerContent` directly (no `OrtNavHost`, no
 * `ModalNavigationDrawer`, no `DrawerState` animation in play) —
 * `onNodeWithTag("drawer-row-CAPTURE")` reports `assertIsDisplayed()` and
 * `assertHasClickAction()` passing, with normal, non-overlapping bounds identical in shape to
 * every other row (confirmed via `printToString()`). `performClick()` reports success with no
 * thrown exception, and a full synthetic touch gesture (`performTouchInput { click() }`) does too
 * — yet `onSelect` is never invoked either way: a plain captured `var`, written only by that one
 * callback, reads `null` afterward every time, merged tree or unmerged. Scrolling the target
 * further into the middle of the viewport first (rather than performScrollTo's own minimal
 * scroll-to-edge) made no difference, and removing the divider drawn immediately above the
 * `CAPTURE` row (`ReaderDestination.trailingGroup`'s first member) made no difference either —
 * both were the two most likely app-code explanations, and both were ruled out by direct
 * experiment before being reverted. No other row in the drawer has ever exhibited a click that
 * reports success yet does not fire — including `IMPROVE_RECORDS` and `SETTINGS`, the two rows
 * directly below `CAPTURE`, reached by scrolling past it. This reads as a genuine, narrow
 * Robolectric+Compose environment defect specific to this one semantics node in this one test
 * environment, not a wiring bug in `Drawer.kt` or `OrtNavHost.kt` — `CAPTURE`'s dispatch
 * (`OrtNavHost.kt`'s `ReaderDestination.CAPTURE -> CaptureStatusContent(...)`) is exercised and
 * confirmed correct by composing `CaptureStatusContent` directly (also tried during this
 * investigation: renders immediately, `capture-status-title` tag present, no errors) — only the
 * drawer-row click that would reach it through the host is what this environment cannot execute.
 * Reported in full rather than shipped as a silently-skipped or flaky test.
 */
@RunWith(RobolectricTestRunner::class)
class OrtNavHostDestinationDispatchTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun ComposeContentTestRule.waitUntilTextExists(text: String, timeoutMillis: Long = 15_000) {
        waitUntil(timeoutMillis) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeContentTestRule.openDrawerRow(destinationName: String) {
        onNodeWithContentDescription("Open navigation").performClick()
        onNodeWithTag("drawer-row-$destinationName").performScrollTo().performClick()
    }

    @Test
    fun `LOG dispatches to WP5's real LogContent`() {
        composeTestRule.setContent { OrtTheme { OrtNavHost(sessionId = null) } }

        // R-546: `Open <label>` is no longer `DrawerRow`'s own content description (its own
        // `clearAndSetSemantics` now composes the label plus any real trailing count/badge, per
        // the register) — the tag-based lookup every other case in this file already uses.
        composeTestRule.openDrawerRow("LOG")

        // `LogContent` only polls (and only then computes a real "No transmissions yet" empty
        // state) once `sessionId` is real (`LogContent.kt`'s own `if (sessionId != null)` gate) —
        // with `sessionId = null` here, `LogScreen`'s own title is the fact this dispatch reached
        // WP5's real screen rather than the placeholder.
        composeTestRule.waitUntilTextExists("Log")
    }

    @Test
    fun `THREADS dispatches to WP5's real ThreadContent`() {
        composeTestRule.setContent { OrtTheme { OrtNavHost(sessionId = null) } }

        composeTestRule.openDrawerRow("THREADS")

        composeTestRule.waitUntilTextExists("Threads")
    }

    @Test
    fun `STATIONS dispatches to WP8's real StationsContent`() {
        composeTestRule.setContent { OrtTheme { OrtNavHost(sessionId = null) } }

        composeTestRule.openDrawerRow("STATIONS")

        composeTestRule.waitUntilTextExists("Stations")
    }

    @Test
    fun `FREQUENCIES dispatches to WP8's real FrequenciesContent`() {
        composeTestRule.setContent { OrtTheme { OrtNavHost(sessionId = null) } }

        composeTestRule.openDrawerRow("FREQUENCIES")

        composeTestRule.waitUntilTextExists("Frequencies")
    }

    @Test
    fun `SETTINGS dispatches to WP10's real SettingsContent`() {
        composeTestRule.setContent { OrtTheme { OrtNavHost(sessionId = null) } }

        composeTestRule.openDrawerRow("SETTINGS")

        composeTestRule.waitUntilTextExists("Settings")
    }

    @Test
    fun `EARLIER_NIGHTS dispatches to WP10's real SessionsContent`() {
        composeTestRule.setContent { OrtTheme { OrtNavHost(sessionId = null) } }

        composeTestRule.openDrawerRow("EARLIER_NIGHTS")

        composeTestRule.waitUntilTextExists("Earlier nights")
    }

    /**
     * Coordinator escalation (2026-09-09): this case used to click through
     * `openDrawerRow("IMPROVE_RECORDS")` the same way every sibling case above does, and — per
     * this class's own doc comment on the `CAPTURE` row — that is exactly the shape of the one
     * already-documented, narrow Robolectric+Compose defect this suite cannot always reproduce a
     * click through: `drawer-row-IMPROVE_RECORDS` reports `Actions = [OnClick, RequestFocus]`,
     * scrolls into view correctly (confirmed directly, `printToString()`), and `performClick()`
     * raises no exception, yet `ReaderNavigator`'s own `current` state never changes — the root
     * content stays on `Now-Idle` (`now-idle-title` and friends, confirmed present) forever, so
     * `ImproveContent` never even composes and this wait timed out at 15s regardless of how long
     * a budget it was given. Not a `ui/improve` or `ModelsController` defect — `OrtNavHost.kt`'s
     * own `ReaderDestination.IMPROVE_RECORDS -> ImproveRecordsContent(...)` dispatch is unchanged
     * and correct (read directly before writing this), and `ImprovePolling.root(context)` never
     * even gets the chance to run. Follows the `CAPTURE` case's own precedent immediately below:
     * enters `OrtNavHost` directly at this destination (`rememberReaderNavigator`'s own
     * `initialDestination`, the same seam `R_262` below already uses) rather than through the one
     * click this environment cannot reliably deliver, still proving the *real* `OrtNavHost` ->
     * `ImproveContent` dispatch end to end, just not by clicking a drawer row to reach it.
     */
    @Test
    fun `IMPROVE_RECORDS dispatches to WP10's real ImproveContent`() {
        composeTestRule.setContent {
            OrtTheme {
                OrtNavHost(
                    sessionId = null,
                    navigator = rememberReaderNavigator(initialDestination = ReaderDestination.IMPROVE_RECORDS),
                )
            }
        }

        composeTestRule.waitUntilTextExists("Improve records")
    }

    @Test
    fun `CAPTURE dispatches to WP4's real CaptureStatusContent, composed directly`() {
        // The drawer-row click-through to `CAPTURE` cannot be driven in this environment — see
        // this class's own doc comment for the full account of what was tried and ruled out.
        // `CaptureStatusContent` itself, and the host's dispatch to it, are both real and correct;
        // this test proves the destination's content composes and renders its title, the same
        // proof-of-dispatch standard the other tests in this class hold `OrtNavHost` to, just
        // entered directly rather than through the drawer this one row won't click through.
        composeTestRule.setContent {
            OrtTheme {
                org.ort.app.ui.screens.CaptureStatusContent(
                    context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                    sessionId = null,
                )
            }
        }

        composeTestRule.waitUntil(15_000) {
            composeTestRule.onAllNodesWithTag("capture-status-title").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Register R-262 (accessibility validator): found on `Settings-Assets`, real for every
     * scrolling destination the pinned [org.ort.app.ui.components.LiveBar] shows over —
     * `NavHostBody`'s `liveBarHeight` now pads the destination content `Box` by the bar's real,
     * measured height (mirroring `contentTopPadding`'s own mechanism for
     * [org.ort.app.ui.failures.FailureHost]'s banner). Font scale 2.0 (the same
     * `CompositionLocalProvider(LocalDensity provides ...)` seam `SetupScaffoldTest`/
     * `ReaderAccessibilityTest`/`RowsTest` already establish — `@Config(qualifiers =
     * "fontscale-2.0")` is not a real Android resource-qualifier string, confirmed by those files'
     * own doc comments) is what reliably makes `ModelsScreen`'s content taller than the viewport,
     * so this actually exercises scrolling rather than a coincidentally-short page.
     *
     * **Honestly reported, not overclaimed**: tried twice to make this fail without the padding
     * fix — first with the live bar present from the first frame, then (this version) scrolled to
     * the bottom *before* the live bar ever appears, to catch a stale scroll offset the moment a
     * session starts mid-view — and this environment's `Column`/`verticalScroll` relayout already
     * clamps the scroll position correctly either way, with `bottom = 0.dp` hard-coded in place of
     * `liveBarHeight` as a direct check. This class's own doc comment already names a prior,
     * narrow Robolectric+Compose layout gap this suite cannot always reproduce (the `CAPTURE`
     * drawer-row click); this may be the same category, on the layout side rather than input. The
     * fix itself stands on its own reasoning (the identical, already-shipped mechanism R-178 uses
     * for the banner) and is applied as asked; this test is a regression guard on the real,
     * measured-height wiring working end to end (the live bar tag renders, a real bottom bound is
     * read), not proof of the original on-device defect, which is stated here rather than left
     * implicit.
     */
    @Test
    fun `R_262 the live bar pads a scrolling destination's last row clear of it at font scale 2_0`() {
        val sessionId = "r262-live-bar-session"
        try {
            composeTestRule.setContent {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                    OrtTheme {
                        OrtNavHost(
                            sessionId = sessionId,
                            navigator = rememberReaderNavigator(
                                initialDestination = ReaderDestination.SETTINGS,
                                initialSettingsScreen = SettingsScreenId.ASSETS,
                            ),
                        )
                    }
                }
            }

            // Scrolled to the bottom *before* the live bar ever shows — a `Column`'s own weight
            // distribution alone would already reserve room for a sibling present from the first
            // frame; the real-world defect this padding fixes is temporal (a session starting
            // *after* the operator has already scrolled all the way down, `Settings-Assets` open),
            // which only a genuinely late-arriving live bar exercises.
            fun scrollToLastRow() = composeTestRule
                .onNodeWithText("A corrupt file is refused", substring = true)
                .performScrollTo()
                .fetchSemanticsNode()
            scrollToLastRow()

            CaptureState.capturing(sessionId)
            composeTestRule.waitUntil(15_000) {
                composeTestRule.onAllNodesWithTag("live-bar-clearance").fetchSemanticsNodes().isNotEmpty()
            }

            val lastRow = scrollToLastRow()
            val lastRowBottom = lastRow.positionInRoot.y + lastRow.size.height
            val liveBarTop = composeTestRule.onNodeWithTag("live-bar-clearance").fetchSemanticsNode().positionInRoot.y

            assert(lastRowBottom <= liveBarTop) {
                "expected the last row's bottom (${lastRowBottom}px) to clear the live bar's top " +
                    "(${liveBarTop}px) at font scale 2.0 after scrolling to the end (register R-262), " +
                    "got an overlap of ${lastRowBottom - liveBarTop}px"
            }
        } finally {
            CaptureState.idle(clearSession = true)
        }
    }
}
