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
 * `thread` (`any` or a literal id), `logFilterFrequency`/`logFilterFromMillis`/`logFilterToMillis`,
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
 * session's `Digest` (DG01/DG05) instead of its `Session` (DG04) detail. A
 * step naming a [drillIn] key outside this set fails loudly (recorded as one
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
 * [scroll] (v5, register R-460): `"end"` (the only value this class accepts — anything else fails
 * to parse, the same "programmer/spec-authoring error" class as a malformed step) scrolls the
 * screen's own primary vertical scroll container to its end, after the ordinary settle and before
 * capture — see [TourAccessibilityScroll] for how, and its own doc comment for exactly why an
 * `AccessibilityNodeInfo` walk, not a `SemanticsOwner` one. A step naming `scroll` on a screen with
 * no vertically-scrollable container throws (recorded as one `error` manifest line, same as any
 * other per-step failure) — never silently captured as if nothing had been asked for.
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
        require(scroll == null || scroll == "end") {
            "tour step '$id' names unknown scroll value '$scroll' — only 'end' is supported"
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
            "captureLevelMeter",
            "reviewSession",
            "reviewSessionView",
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
        )
    }
}

/** The parsed contents of `tools/ui-audit/tour.json` — a flat, ordered list of [TourStep]s. */
public data class TourSpec(public val steps: List<TourStep>) {
    public companion object {
        /** Parses `{"steps": [...]}`. Throws [org.json.JSONException] on malformed JSON and
         * [IllegalArgumentException] on a structurally invalid step (see [TourStep]'s own `init`) —
         * both are programmer/spec-authoring errors, never something a tour run should catch and
         * limp past the way a per-step capture failure is. */
        public fun parse(json: String): TourSpec {
            val root = JSONObject(json)
            val stepsArray: JSONArray = root.getJSONArray("steps")
            val steps = (0 until stepsArray.length()).map { index -> parseStep(stepsArray.getJSONObject(index)) }
            return TourSpec(steps)
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
