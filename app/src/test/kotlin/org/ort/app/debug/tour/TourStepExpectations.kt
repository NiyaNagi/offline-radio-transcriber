package org.ort.app.debug.tour

import org.ort.app.ui.settings.SettingsScreenId
import org.ort.app.ui.settings.settingsSubScreenTestTag
import java.io.File

/** One assertion [TourStepsTest] knows how to check against a composed
 * [org.ort.app.ui.navigation.OrtNavHost]. */
internal sealed interface Expected {
    data class Tag(val tag: String) : Expected
    data class AnyTag(val tags: Set<String>) : Expected
    data class Text(val text: String) : Expected

    /** v7 (register R-010..R-014/R-334/R-770): [Tag] alone only proves a node with this tag
     * exists *somewhere* in the tree - `ModalNavigationDrawer`'s own `drawerContent` is always
     * composed, open or closed (confirmed directly: [TourDrawerSeedTest]'s own closed case finds
     * `drawer-rows` present but off-screen), so a bare [Tag] check on it would pass even if
     * `openDrawer` silently did nothing. This variant additionally requires the node's own
     * bounds to actually be on-screen, the same distinction [TourDrawerSeedTest] uses.
     */
    data class DisplayedTag(val tag: String) : Expected
}

/**
 * What every *destination* step in `tools/ui-audit/tour.json` is expected to show, hoisted out of
 * [TourStepsTest] so a second test can enumerate the table rather than only run it — register
 * **R-1201**, which is the reason this object exists at all.
 *
 * **The finding this table was rebuilt for.** `Drawer.kt:257-267` puts
 * `text = AnnotatedString(description)` on the drawer row node itself, where `description` is the
 * destination's own `drawerLabel` plus its count (`drawerRowDescription`, `Drawer.kt:181-195`), and
 * `ModalNavigationDrawer` composes its `drawerContent` **unconditionally, open or closed**. A
 * [Expected.Text] case naming any destination's label was therefore satisfied by the drawer's own
 * row, on every screen, forever. R-1201 measured it by experiment rather than by reading: with
 * `NavHostBody` deleted from `OrtNavHost.kt` - no screen, no drill-in, no live bar composed at
 * all - **148 of `tour.json`'s 264 destination steps still passed**, 144 of them through this
 * collision. `settingsScreen` alone accounted for 50 steps, `transmission` for 33, `LOG` for 21,
 * `EARLIER_NIGHTS` for 12 and `IMPROVE_RECORDS`'s six root steps for the rest of R-1160's own
 * unfinished half (that row fixed the nine *preview* steps and left the roots asserting the
 * identical drawer label).
 *
 * **The repair, which is the one R-1160 already proved.** Every case that came through the stub
 * correctly asserted either a `testTag` rooted in the screen under test or a string unique to that
 * screen - both families were 100% right. So each colliding case now names a tag the screen itself
 * puts on its own root or title; the screens carry a short `R-1201` note at the line that added it.
 * [TourExpectationDrawerCollisionTest] is the guard that keeps it that way: it composes
 * `ReaderDrawerContent` alone and fails on any [Expected.Text] string the drawer-only tree can
 * satisfy. Constitution II - never assert on prose, and a test must be shown to discriminate.
 *
 * Every marker below is a real `testTag` or a real title string this codebase already asserts
 * against elsewhere - never a guessed string invented for this test alone.
 */
internal object TourStepExpectations {

    /** `tools/ui-audit/tour.json` itself, the same file the tour runs from - never a fixture. */
    fun tourSpec(): TourSpec {
        val tourJsonFile = File("../tools/ui-audit/tour.json").canonicalFile
        check(tourJsonFile.exists()) { "expected tools/ui-audit/tour.json at $tourJsonFile" }
        return TourSpec.parse(tourJsonFile.readText())
    }

    /** Every destination step paired with what it claims its screen shows. */
    fun destinationExpectations(spec: TourSpec = tourSpec()): List<Pair<TourStep, Expected>> =
        spec.steps.filter { it.destination != null }.map { step -> step to expectedFor(step) }

    fun expectedFor(step: TourStep): Expected = expectedForStepId(step.id)
        ?: expectedForImprovePreview(step.drillIn)
        ?: expectedForTransmissionDrillIn(step.drillIn)
        ?: expectedForSheetOrDrawer(step.drillIn)
        ?: expectedForStationDrillIn(step.drillIn)
        ?: expectedForRecordDrillIn(step.drillIn)
        ?: expectedForDestination(requireNotNull(step.destination))

    /** A handful of steps share a `destination`/`drillIn` shape with a sibling step that lands on a
     * genuinely different real state (v7, register R-770) - the shape-keyed functions below cannot
     * tell them apart, since they are keyed on shape, not id. Real strings only. */
    private fun expectedForStepId(id: String): Expected? = when (id) {
        // `ThreadListMapper.listState`: no transmission `field-tier1` seeds ever carries a
        // `threadId` (only `OvernightScenario` sets one), and `field-tier1` (unlike
        // `stations-14-nights`, confirmed empirically on-device before switching to this scenario -
        // its own transmissions belong to no single "current" session `ThreadPolling` reads, so it
        // renders the *Empty* state instead) has one real, current session's worth of real overs -
        // `UngroupedThreads`' own real prose, the fact that distinguishes T03
        // (`Threads-Ungrouped.dc.html`) from both Empty and a T01 `Grouped` card list.
        "field-tier1/T03-threads-ungrouped" -> Expected.Text("Conversations are not built on this phone yet")
        else -> null
    }

    /**
     * CI regression (register, `r-r03-ci`, R-1160): the three `ImprovePage` preview seams
     * (`NavSeed.improveSelectPreview`/`improveRunningPreview`/`improveDonePreview`, R-770/
     * R-1127/R-350) used to fall through to the generic `IMPROVE_RECORDS` destination check
     * (`Expected.Text("Improve records")`) - which, read by eye against `ImproveScreens.kt`, is
     * never rendered by `ImproveSelectScreen`/`ImproveRunningScreen`/`ImproveDoneScreen` at all. It
     * only ever passed through the drawer collision this object's own doc comment describes.
     * `improve-running-action-bar`/`improve-done-action-bar` already existed (`ImproveScreens.kt`);
     * `drill-in-header-back` is `DrillInHeader`'s own shared tag, sufficient here since `Select` is
     * the only `ImprovePage` state under this destination that draws one at all.
     */
    private fun expectedForImprovePreview(drillIn: Map<String, String>): Expected? = when {
        drillIn["improveSelectPreview"] == "true" -> Expected.Tag("drill-in-header-back")
        drillIn["improveRunningPreview"] == "true" -> Expected.Tag("improve-running-action-bar")
        drillIn["improveDonePreview"] == "true" -> Expected.Tag("improve-done-action-bar")
        else -> null
    }

    /**
     * R-1201: the 33 steps that drill into one transmission. Every one of them used to assert
     * `Text("Log")` - the drill-in header's own back label, which is also the drawer's own `Log`
     * row - so none of them distinguished D01 from D05, D06 or D08-D10, and none of them
     * distinguished any of those from no screen at all. Each seam now names the screen it reaches.
     */
    private fun expectedForTransmissionDrillIn(drillIn: Map<String, String>): Expected? = when {
        !drillIn.containsKey("transmission") -> null
        // v7 (register R-770): F04's own detail - `TransmissionDetailScreen.kt`'s
        // `RejectedHeaderSection`, real `testTag`, checked first so this is proven to be the
        // rejected detail, not merely any transmission detail.
        drillIn["transmission"] == "rejected" -> Expected.Tag("rejected-section")
        drillIn.containsKey("whyOpen") -> Expected.Tag("detail-why-screen")
        drillIn.containsKey("revisionsOpen") -> Expected.Tag("detail-revisions-screen")
        drillIn.containsKey("correctionStep") -> Expected.Tag("correction-sheet")
        else -> Expected.Tag("transmission-detail-screen")
    }

    /** The seams that open something *over* a destination - a sheet, or the drawer itself. */
    private fun expectedForSheetOrDrawer(drillIn: Map<String, String>): Expected? = when {
        // N08/WPCAP (design-intent `Capture.dc.html`): the level meter is no longer a separate
        // sub-screen - it is always inline on the merged Capture surface, via
        // `LiveMonitorLevelCard`'s own chart, the exact same component N07's own live monitor used.
        drillIn["captureLevelMeter"] == "true" -> Expected.Tag("live-monitor-level-chart")
        // R-261: an open sheet blocks the tree behind it (confirmed by `TourStepsTest` itself, run
        // once and read - the query field genuinely disappears while `search-filters-sheet` shows),
        // so these two check the sheet's own content, never the destination root underneath it.
        drillIn["searchFiltersOpen"] == "true" -> Expected.Tag("search-filters-sheet")
        drillIn["logSheetOpen"] == "true" -> Expected.Text("Filter the log")
        // v7 (register R-010..R-014/R-334/R-770): the drawer's own scrollable row list, required to
        // be genuinely on-screen ([Expected.DisplayedTag]'s own doc comment) - so a drawer step is
        // proven to land on the open drawer itself, never merely on the destination under it.
        drillIn["openDrawer"] == "true" -> Expected.DisplayedTag("drawer-rows")
        else -> null
    }

    /** ST02-ST04 and the Split sub-screen. R-1201 changed only the bare `station` case: the three
     * sub-screens already named real, screen-unique titles and came through the stub correctly. */
    private fun expectedForStationDrillIn(drillIn: Map<String, String>): Expected? = when {
        // ST03/ST04 (WP8's `initialSubScreen` seam): `Station-Pattern`/`Station-Identity` draw their
        // own `DrillInHeader` with `parentLabel` set to the station's own callsign/label, never the
        // "Stations" origin label the plain station root (ST02) shows - so these two real, on-screen
        // titles (`StationPatternScreen.kt`'s "When they are around", `StationIdentityScreen.kt`'s
        // "How this station is known") are the honest check here, not a guessed one.
        drillIn["stationSubScreen"] == "PATTERN" -> Expected.Text("When they are around")
        drillIn["stationSubScreen"] == "IDENTITY" -> Expected.Text("How this station is known")
        // tour-coverage unit (register R-272/R-770): `SplitSubScreen`'s own real, unconditional
        // title (`StationIdentityScreen.kt`'s `StationSplitScreen`).
        drillIn["stationSubScreen"] == "SPLIT" -> Expected.Text("Split this voice")
        // R-1201: was `Text("Stations")` - this screen's own back label *and* the drawer's row.
        drillIn.containsKey("station") -> Expected.Tag("station-detail-screen")
        else -> null
    }

    /**
     * The remaining drill-ins, each now keyed on a tag the screen puts on its own root (R-1201) -
     * except the two session-review cases, which R-1070 had already moved off a drawer-matchable
     * string and which came through R-1201's own stub correctly.
     */
    private fun expectedForRecordDrillIn(drillIn: Map<String, String>): Expected? = when {
        drillIn.containsKey("thread") -> Expected.Tag("thread-detail-screen")
        // R-1201: `frequencyInitialView` shares its step shape with the plain `frequency` drill-in,
        // so both asserted the same `Text("Frequencies")` and told the two views apart not at all.
        drillIn["frequencyInitialView"] == "Change" -> Expected.Tag("frequency-change-screen")
        drillIn.containsKey("frequency") -> Expected.Tag("frequency-detail-screen")
        drillIn.containsKey("logFilterFrequency") -> Expected.Tag("log-title")
        // R-1201: RC02 had no case at all and fell through to `EARLIER_NIGHTS`'s own default.
        drillIn.containsKey("recordingSession") -> Expected.Tag("recording-session-screen")
        // WPREC/R-1070: `DigestScreen`'s own real header ("Session") for `DIGEST`; "COVERAGE" is
        // `SessionDetailScreen`'s own unconditional section header (`SectionHeader` upper-cases
        // every label it is given, confirmed by reading `Rows.kt`) and is unique to that screen -
        // "Recordings" would not be, since `EARLIER_NIGHTS.drawerLabel` is now that same word.
        drillIn["reviewSessionView"] == "DIGEST" -> Expected.Text("Session")
        drillIn.containsKey("reviewSession") -> Expected.Text("COVERAGE")
        // R-1201: all 50 `settingsScreen` steps used to assert the bare "Settings" back chevron,
        // which is also the drawer's own `Settings` row - one check that could not tell thirteen
        // sub-screens apart, and could not tell any of them from nothing at all.
        drillIn.containsKey("settingsScreen") -> settingsSubScreenExpectation(drillIn.getValue("settingsScreen"))
        else -> null
    }

    private fun settingsSubScreenExpectation(name: String): Expected {
        val screen = SettingsScreenId.entries.firstOrNull { it.name == name }
            ?: error("TourStepExpectations has no expected marker for settingsScreen '$name'")
        return Expected.Tag(settingsSubScreenTestTag(screen))
    }

    private fun expectedForDestination(destination: String): Expected = when (destination) {
        "NOW" -> Expected.AnyTag(setOf("now-idle-title", "now-active-title"))
        // R-1201: was `Text("Log")`, i.e. `ReaderDestination.LOG.drawerLabel`. `log-title` is
        // `LogScreen.kt`'s own unconditional screen-title row - drawn before every branch, so the
        // empty, partial and rejected states of this screen all still satisfy it.
        "LOG" -> Expected.Tag("log-title")
        "SEARCH" -> Expected.Tag("search-query-field")
        // R-1201: the roots carry the tag rather than their titles, because `Loading`/`Empty` are
        // real states a tour step lands on and neither draws the title.
        "THREADS" -> Expected.Tag("threads-screen")
        "STATIONS" -> Expected.Tag("stations-screen")
        "FREQUENCIES" -> Expected.Tag("frequencies-screen")
        // N08/WPCAP: `CaptureStatusContent` now always renders the merged `CaptureScreen`, whose own
        // title row carries `capture-title` (never the old N04 `capture-status-title`).
        "CAPTURE" -> Expected.Tag("capture-title")
        "IMPROVE_RECORDS" -> Expected.Tag("improve-root-title")
        "EARLIER_NIGHTS" -> Expected.Tag("recordings-title")
        "SETTINGS" -> Expected.Tag("settings-root-title")
        else -> error("TourStepExpectations has no expected marker for destination '$destination'")
    }
}
