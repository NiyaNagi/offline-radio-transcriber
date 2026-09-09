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
 * [drillIn] is carried through from the JSON verbatim (`transmissionId` | `stationId` |
 * `frequencyHz` | `sessionId` | `settingsScreen` | `openLogFilter`, per this package's brief) so
 * the spec format matches what a validator brief already names, but **only `settingsScreen` is
 * honored by v1** — see `ScreenshotTourActivity`'s own doc comment and this package's report for
 * exactly why: `OrtNavHost`'s public surface (`sessionId`, `navigator`, `failureActions`) and
 * `ReaderNavigator`'s (`open`, `openSettings`, `openSetupInput`) have no way to seed a drill-in id
 * at all — every one of `NavHostNavState`'s `openTransmissionId`/`openStationId`/`openFrequencyHz`/
 * `openThreadId`/`pendingLogFilter`/`openCaptureLevelMeter`/`pendingReviewSessionId` fields is
 * `private` in `OrtNavHost.kt` (WP3's file, not this package's row), populated only by a callback a
 * real tap fires. A step naming any other [drillIn] key fails loudly (recorded as one `error` line
 * in the manifest, per this file's own contract with [ScreenshotTourActivity] — never a silent
 * skip and never an aborted tour) rather than being silently ignored.
 *
 * [override] (optional): a second scenario name, loaded with [org.ort.app.debug.Scenarios.load]
 * *after* the base [scenario] and after the destination has already composed and settled — the
 * exact in-process shape `results/ui-audit/README.md`'s "Recovery toast recipes" section documents
 * for `scenario.ps1 -NoRestart` (the composed screen's own polling loop observes the change on its
 * next tick; nothing is remounted). Used for the recovery-toast/transition captures (e.g.
 * `rig-lost` → `rig-reconnected`).
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
) {
    init {
        require(id.isNotBlank()) { "a tour step must have a non-blank id" }
        require(scenario.isNotBlank()) { "tour step '$id' must name a scenario" }
        require((destination == null) != (setup == null)) {
            "tour step '$id' must name exactly one of destination or setup, not both or neither"
        }
    }

    /** The one [drillIn] key v1 honors — see this class's own doc comment. */
    public val settingsScreen: String? get() = drillIn["settingsScreen"]

    /** Every [drillIn] key besides `settingsScreen` — present only when this step needs a seed
     * v1's host surface cannot provide (see this class's own doc comment). */
    public val unsupportedDrillInKeys: Set<String> get() = drillIn.keys - "settingsScreen"
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
            )
        }
    }
}
