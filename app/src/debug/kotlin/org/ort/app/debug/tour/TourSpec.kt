package org.ort.app.debug.tour

import org.json.JSONArray
import org.json.JSONObject

/**
 * spec/ui-conformance-plan.md WP12 (`tools/ui-audit/tour.json`'s own schema, this package's
 * brief): one capture instruction. Two kinds share this shape — a **destination** step ([setup]
 * null) composes the real [org.ort.app.ui.navigation.OrtNavHost] directly on
 * [ScreenshotTourActivity]'s own destination/settings-screen; a **setup** step ([destination]
 * null) launches the real [org.ort.app.ui.setup.SetupActivity] at the named
 * [org.ort.app.ui.setup.SetupStep].
 *
 * [drillIn] is a map of symbolic keys resolved against the step's own just-loaded scenario by
 * [TourIds.resolveSeed] into a real [org.ort.app.ui.navigation.NavSeed] — v2 (WP3 round 13 added
 * `NavSeed`, a public seam `OrtNavHost`/`rememberReaderNavigator` both accept, closing the v1 gap
 * this class's own history records): `transmission` (`confirmed`|`inferred`|`ambiguous`|`unknown`
 * or a literal id), `station` (a callsign or a literal id), `frequency` (a literal Hz value),
 * `thread` (`any` or a literal id), `logFilterFrequency`/`logFilterFromMillis`/`logFilterToMillis`
 * (register R-1047: any one of these three now resolves to a real `LogFilterSelection` — an
 * hour-window-only step needs no `logFilterFrequency` at all — see `TourIds.resolveLogFilter`'s
 * own doc comment), `logFilterTransmissionIds` (register R-1047: a comma-joined list of literal
 * transmission ids, assembled into `LogFilterSelection.transmissionIds` — the D11/R04-shaped "N
 * curated overs" filter, which needs no frequency or time window at all),
 * `captureLevelMeter` (`true`), `reviewSession` (`self` or a literal session id),
 * `frequencyInitialView` (`Detail`|`Change`), `settingsScreen` (a `SettingsScreenId` name — carried
 * through `NavSeed` now, same key as before). **v4** (WP3 round 14's `NavSeed` fields, after WP5/WP7/
 * WP6 each shipped the composable parameter it needed): `searchQuery`/`searchSubmit`/
 * `searchFiltersOpen`, `logSheetOpen`, `revisionsOpen` (a companion to `transmission`, the same
 * relationship `frequencyInitialView` has to `frequency`). **v6** (WP8 shipped
 * `StationDetailContent.initialSubScreen`, coordinator round 2026-09-08): `stationSubScreen` — a
 * companion to `station` the same way `frequencyInitialView` companions `frequency` — `PATTERN`
 * (ST03, `Station-Pattern.dc.html`) or `IDENTITY` (ST04, `Station-Identity.dc.html`); see
 * [TourIds.resolveSeed]'s own doc comment for why it does nothing without a `station` key in the
 * same step. `Station`'s own Split sub-screen has no `tour.json` step: `design/design-intent.md`
 * lists no `Station-Split.dc.html` board — its "Split cluster" action reached from ST04 opens
 * `F10`/`Fail-Cluster.dc.html` instead, a different, already-independent screen id, not a Station
 * sub-screen this key's own three-value [org.ort.app.ui.data.StationSubScreen] enum needs a fourth
 * tour step for. **v7** (register R-010..R-014/R-334, R-770 — the confirmation sweep's own finding
 * that N00 `Menu.dc.html` had never been in the tour's capture set): `openDrawer` (`true`) opens the
 * drawer over whichever [destination] the step names — a companion to `NavSeed.openDrawer`, this
 * round's own one-field seam, not a `station`/`transmission`/etc. drill-in, since the drawer is not
 * itself a destination. `transmission` also gained a fifth symbolic value this round, `"rejected"`
 * (F04 `Fail-Hallucination.dc.html`'s own detail) — see [TourIds.resolveSeed]'s own doc comment.
 * **R-840**: `reviewSessionView` (`SESSION`|`DIGEST`) — a companion to `reviewSession`, the same
 * relationship `frequencyInitialView` has to `frequency`; lands `Earlier nights` on the seeded
 * session's `Digest` (DG01/DG05) instead of its `Session` (DG04) detail. **R-900** (register, A2's
 * spot-check on tour run 3): `rigLinkState` (`"connecting"`|`"identified"`|`"verified"`|`"dropped"`|
 * `"identify-timed-out"`|`"verify-timed-out"` — the last two, R-1013/R-1014, WPD3's own round)
 * — a *setup*-step-only key, read directly by [ScreenshotTourActivity.renderSetupStep] exactly like
 * `rigBluetoothAddress` (never through [TourIds.resolveSeed]), naming the checklist's own inner
 * `RigLinkState` the settle must observe (via `SetupActivity.rigLinkStateForTest`) before capturing —
 * without it, `S10b-verified`'s own capture could land the instant the address selection landed,
 * before the scripted port had actually progressed past `Identified` to `Verified`, the exact defect
 * the register found. A
 * **WPUI follow-up** (coordinator round, R-1006's on-device proof): `playThenNavigate` /
 * `pauseThenNavigate` (`"true"`) — a *destination*-step-only pair, read directly by
 * [ScreenshotTourActivity] exactly like `tapLiveBar` (never through [TourIds.resolveSeed], which
 * only ever builds a [org.ort.app.ui.navigation.NavSeed], and a `NavSeed` field can only seed a
 * single static initial state, not a sequence of real taps). Meaningful only alongside a
 * `transmission` key naming an over with real retained audio in the same step — use
 * `confirmed-longest` ([TourIds]'s own doc comment), not plain `confirmed`: every other
 * audio-bearing over inherits a 4.2s default, too short to still be playing by the time this same
 * step navigates away and reaches its own final capture. After the transmission drill-in settles,
 * taps its waveform card's real play control
 * (`org.ort.app.ui.components.Inspection.kt`'s own stable `"waveform-glyph-play"` testTag —
 * unowned by this package, not edited by it either; the same bounds-overlap tap
 * [TourAccessibilityTap.tapNodeWithTestTag] already uses for the live bar), confirms real playback
 * started (the glyph flips to `"waveform-glyph-pause"`), then — `pauseThenNavigate` only — taps it
 * again and confirms it flips back to `"waveform-glyph-play"`. Either way, a real system back press
 * (`ComponentActivity.onBackPressedDispatcher.onBackPressed()`, the identical mechanism this
 * package's own `ReaderActivityDestinationSmokeTest` already uses, never `Espresso.pressBack()`)
 * closes the drill-in, landing on this step's own [destination]. **R-1146 (register), corrected
 * 2026-09-21**: this used to close on the transport bar (C10) still showing playback — the C10
 * transport-bar work's own reversal of R-1006, meant to let playback survive the navigation.
 * `AC-168`/build-plan `P26` (register R-1006, `684c3abf`) reversed that reversal, restoring
 * stop-on-leave at the nav-host layer — so [ScreenshotTourActivity] now waits for the ordinary
 * `"live-bar"` tag here instead, proving playback genuinely stopped and the bar reverted to
 * reflecting the still-live capturing session, the on-device proof this restoration needed and no
 * Robolectric test can substitute for (constitution VIII).
 *
 * **R-1117/R-1127 (register, tour-coverage unit)**: `whyOpen` (a companion to `transmission`, the
 * same relationship `revisionsOpen` already has) — `true` opens D05 (`Detail-Why.dc.html`) on the
 * resolved transmission via [org.ort.app.ui.navigation.NavSeed.openTransmissionWhy]. `labelOpen`
 * (the same companion shape): `true` opens the labelled-sample form (R-056) via
 * [org.ort.app.ui.navigation.NavSeed.openTransmissionLabel]. `correctionStep` (the same companion
 * shape): `MAIN` (D08) / `SEARCH` (D09) / `TYPE` (D10) opens the correction sheet directly on that
 * tier via [org.ort.app.ui.navigation.NavSeed.correctionStep] — D08-D11's own seam, since these are
 * genuinely interaction-only sheets with no destination of their own.
 *
 * **R-350/R-1127**: `improveDonePreview` (`"true"`) — a *destination*-step-only key (no
 * `transmission` companion; meaningful only on an `IMPROVE_RECORDS` destination step) starts
 * `ImproveContent` already on R04 (`Improve-Done.dc.html`) with a real-shaped summary, via
 * [org.ort.app.ui.navigation.NavSeed.improveDonePreview] — the tour cannot drive a real reprocess
 * run to completion.
 *
 * **R-770**: `improveSelectPreview` / `improveRunningPreview` (`"true"`) — the identical
 * destination-step-only shape as `improveDonePreview` above, for R02 (`Improve-Select.dc.html`)
 * and R03 (`Improve-Running.dc.html`) — both hold unseeded local state the tour cannot otherwise
 * reach (R02 needs a real qualifying group row to tap; R03 needs a real reprocess run in progress),
 * via [org.ort.app.ui.navigation.NavSeed.improveSelectPreview]/
 * [org.ort.app.ui.navigation.NavSeed.improveRunningPreview].
 *
 * **R-1129**: `tapLicenseNotice` (a licence's bundled asset file name, e.g. `"gemma.txt"`) — a
 * *destination*-step-only key (meaningful only on a `SETTINGS` destination step with
 * `settingsScreen: "LICENSES"`), read directly by [ScreenshotTourActivity] exactly like
 * `tapLiveBar` (never through [TourIds.resolveSeed]): taps the named notice's own stable
 * `"license-row-<file>"` testTag (`org.ort.app.ui.settings.SettingsLicensesScreen.kt`'s own seam,
 * this round) and waits for its detail screen's own `"license-detail"` testTag to appear — the
 * detail screen is reachable by an ordinary tap in production, so this is a tour-only capture seam,
 * not a product navigation seam.
 *
 * A step naming a [drillIn] key outside this set fails loudly (recorded as one
 * `error` line in the manifest, per
 * this file's own contract with [ScreenshotTourActivity] — never a silent skip and never an
 * aborted tour) rather than being silently ignored.
 *
 * [override] (optional): a second scenario name, loaded with [org.ort.app.debug.Scenarios.load]
 * *after* the base [scenario] and after the destination has already composed and settled — the
 * exact in-process shape `results/ui-audit/README.md`'s "Recovery toast recipes" section documents
 * for `scenario.ps1 -NoRestart` (the composed screen's own polling loop observes the change on its
 * next tick; nothing is remounted). Used for the recovery-toast/transition captures (e.g.
 * `rig-lost` → `rig-reconnected`).
 *
 * [scroll] (v5, register R-460; v8, register R-1158): `"end"` scrolls the screen's own primary
 * vertical scroll container to its end, after the ordinary settle and before capture — see
 * [TourAccessibilityScroll.scrollToEnd] for how, and its own doc comment for exactly why an
 * `AccessibilityNodeInfo` walk, not a `SemanticsOwner` one. **R-1158**: a positive integer string
 * (e.g. `"1"`, `"2"`) instead performs exactly that many `ACTION_SCROLL_FORWARD` actions — never
 * looping to convergence the way `"end"` does — via [TourAccessibilityScroll.scrollForward]. This
 * exists because a screen tall enough at font scale 2.0 can put real content strictly *between*
 * the unscrolled top frame and the scrolled-to-end frame, reachable by neither: CF02's "Re-verify
 * the route now" row (R-980) was proven to render correctly there only by a scratch, one-off `adb
 * shell input swipe` nobody else could reproduce (R-985), which is exactly the gap a small,
 * reproducible step count closes. Any other string (a non-integer, zero or negative) fails to
 * parse, the same "programmer/spec-authoring error" class as a malformed step. A step naming
 * `scroll` on a screen with no vertically-scrollable container throws (recorded as one `error`
 * manifest line, same as any other per-step failure) — never silently captured as if nothing had
 * been asked for.
 */
public data class TourStep(
    public val id: String,
    public val scenario: String,
    public val destination: String? = null,
    public val setup: String? = null,
    public val drillIn: Map<String, String> = emptyMap(),
    public val override: String? = null,
    public val fontScale: Float = 1.0f,
    public val waitMillis: Long = 0L,
    public val scroll: String? = null,
) {
    init {
        require(id.isNotBlank()) { "a tour step must have a non-blank id" }
        require(scenario.isNotBlank()) { "tour step '$id' must name a scenario" }
        require((destination == null) != (setup == null)) {
            "tour step '$id' must name exactly one of destination or setup, not both or neither"
        }
        require(scroll == null || scroll == "end" || (scroll.toIntOrNull() ?: -1) >= 1) {
            "tour step '$id' names unknown scroll value '$scroll' — only 'end' or a positive integer " +
                "step count is supported"
        }
    }

    /** Every [drillIn] key [TourIds.resolveSeed] knows how to resolve — see this class's own doc
     * comment for what each one means. */
    public val unsupportedDrillInKeys: Set<String> get() = drillIn.keys - SUPPORTED_DRILL_IN_KEYS

    public companion object {
        private val SUPPORTED_DRILL_IN_KEYS: Set<String> = setOf(
            "transmission",
            "station",
            "frequency",
            "thread",
            "logFilterFrequency",
            "logFilterFromMillis",
            "logFilterToMillis",
            "logFilterTransmissionIds",
            "captureLevelMeter",
            "reviewSession",
            "reviewSessionView",
            // WPRC02 — see `TourIds.resolveSeed`'s own doc comment.
            "recordingSession",
            "frequencyInitialView",
            "stationSubScreen",
            "settingsScreen",
            "searchQuery",
            "searchSubmit",
            "searchFiltersOpen",
            "logSheetOpen",
            "revisionsOpen",
            "openDrawer",
            // WPD's S10b checklist seam (this round) — see `ScreenshotTourActivity.renderSetupStep`'s
            // own doc comment for why this key is read directly there, never through
            // `TourIds.resolveSeed` (which only ever builds a `NavSeed` for a destination step).
            "rigBluetoothAddress",
            // R-900 (register, A2's spot-check on run 3): the checklist state the settle must
            // actually observe before capturing — `"connecting"`/`"identified"`/`"verified"`/
            // `"dropped"`, resolved against `SetupActivity.rigLinkStateForTest` by
            // `ScreenshotTourActivity`'s own `RIG_LINK_STATE_PREDICATES`. Read directly, same as
            // `rigBluetoothAddress` above, never through `TourIds.resolveSeed`.
            "rigLinkState",
            // WPW (register R-1007 follow-up): `"true"` taps the composed screen's own live bar via
            // `TourAccessibilityTap`, a *destination*-step-only key read directly by
            // `ScreenshotTourActivity` (never through `TourIds.resolveSeed`, which only ever builds
            // a `NavSeed`). **R-1021, corrected 2026-09-21**: no step in `tour.json` sets this any
            // more — `LiveMonitorScreen`, the screen it used to route onto, was merged into
            // `CaptureScreen` (N08, `WPCAP` `3b5d0e0a`) and is no longer reachable from any
            // navigation path, so a step that lands directly on `ReaderDestination.CAPTURE` already
            // shows everything this key used to reach by tapping. Kept, unremoved (P9), for a future
            // real cross-screen tap once `Transport-Bar.dc.html` (C10)'s own `live` → `Capture`
            // affordance replaces the plain pinned live bar this key was written against.
            "tapLiveBar",
            // WPUI follow-up (R-1006's on-device proof) — see this class's own doc comment above.
            "playThenNavigate",
            "pauseThenNavigate",
            // R-1117/R-1127/R-350/R-1129 (register, tour-coverage unit) — see this class's own doc
            // comment above for each.
            "whyOpen",
            "labelOpen",
            "correctionStep",
            "improveDonePreview",
            // R-770 (register) — see this class's own doc comment above.
            "improveSelectPreview",
            "improveRunningPreview",
            "tapLicenseNotice",
        )
    }
}

/** The parsed contents of `tools/ui-audit/tour.json` (or, on device, the filtered spec `tour.ps1`
 * pushes for one invocation) — a flat, ordered list of [TourStep]s, plus an optional [runId].
 *
 * [runId] (R-1083, register — the tour's stale-manifest false green): `tour.ps1` generates a fresh
 * id (a GUID) for every invocation and writes it into the spec it pushes alongside `steps`;
 * [ScreenshotTourActivity] reads it back here and hands it to [TourRunner], which stamps it onto
 * every manifest line the run writes, including the trailing `done` marker
 * ([appendManifestDone]). The script then refuses to treat a `done` line as *its own* run's result
 * unless its `runId` matches — the fix for a poll that used to accept whichever manifest happened
 * to be on disk (a previous run's leftover, or one written by a concurrent invocation) the moment
 * it carried a trailing `done` line at all. `null` — never a placeholder string — for `tour.json`
 * itself (checked into the repo, never carries one) and for any other caller with no run to
 * identify; [ScreenshotTourActivity] falls back to `"unknown"` only at the point it hands this to
 * [TourRunner], never here. */
public data class TourSpec(public val steps: List<TourStep>, public val runId: String? = null) {
    public companion object {
        /** Parses `{"steps": [...], "runId": "..."}` (`runId` optional). Throws
         * [org.json.JSONException] on malformed JSON and [IllegalArgumentException] on a
         * structurally invalid step (see [TourStep]'s own `init`) — both are
         * programmer/spec-authoring errors, never something a tour run should catch and limp past
         * the way a per-step capture failure is. */
        public fun parse(json: String): TourSpec {
            val root = JSONObject(json)
            val stepsArray: JSONArray = root.getJSONArray("steps")
            val steps = (0 until stepsArray.length()).map { index -> parseStep(stepsArray.getJSONObject(index)) }
            val runId = root.optString("runId").takeIf { it.isNotEmpty() }
            return TourSpec(steps, runId)
        }

        private fun parseStep(obj: JSONObject): TourStep {
            val drillInObj = obj.optJSONObject("drillIn")
            val drillIn = if (drillInObj == null) {
                emptyMap()
            } else {
                drillInObj.keys().asSequence().associateWith { key -> drillInObj.getString(key) }
            }
            return TourStep(
                id = obj.getString("id"),
                scenario = obj.getString("scenario"),
                destination = obj.optString("destination").takeIf { it.isNotEmpty() },
                setup = obj.optString("setup").takeIf { it.isNotEmpty() },
                drillIn = drillIn,
                override = obj.optString("override").takeIf { it.isNotEmpty() },
                fontScale = obj.optDouble("fontScale", 1.0).toFloat(),
                waitMillis = obj.optLong("waitMillis", 0L),
                scroll = obj.optString("scroll").takeIf { it.isNotEmpty() },
            )
        }
    }
}
