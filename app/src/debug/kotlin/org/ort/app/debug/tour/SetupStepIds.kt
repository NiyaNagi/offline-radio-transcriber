package org.ort.app.debug.tour

/**
 * Maps `design/design-intent.md` §2's S-ids to [org.ort.app.ui.setup.SetupStep] names — kept as a
 * plain `String -> String` table (not a dependency on `SetupStep` itself) so this file compiles
 * without importing anything under `ui/setup`, matching this package's brief ("own only the files
 * it names").
 * [org.ort.app.ui.setup.SetupActivity.EXTRA_STEP] takes the enum's own `name`, which is what a
 * setup [TourStep.setup] value is resolved to before that extra is set.
 */
public object SetupStepIds {
    /**
     * **P39 (D58) removed five ids and repointed one.** `S02` (the microphone explainer), `S03`
     * (notifications), `S01a` (the jurisdiction notice) and `S11b` (analytics consent) name screens
     * that no longer exist — the first two were deleted outright, the second two folded onto `S01`.
     * `S05` and `S07` are gone for a different reason: they merged *into* `S04`, which now names the
     * whole input / route-check / level screen rather than the route list alone.
     *
     * `S08` stays although [org.ort.app.ui.setup.SetupStateMachine] never returns `OVERNIGHT` any
     * more (AC-189 as amended). The screen is still in the code, its artboard still exists, and
     * constitution VIII requires an artboard to be reachable by a capture — the tour opens it by
     * `EXTRA_STEP` exactly as the *Keep capture running* prompt that owns it next will.
     */
    private val BY_ID: Map<String, String> = mapOf(
        "S01" to "WELCOME",
        "S00" to "MODE",
        "S02b" to "MICROPHONE_DENIED",
        "S02c" to "BLUETOOTH_PERMISSION",
        "S04" to "LISTEN",
        "S06" to "ROUTE_MISMATCH",
        "S08" to "OVERNIGHT",
        "S09" to "RADIO",
        "S09b" to "RIG_TRANSPORT",
        "S10" to "RADIO_USB",
        "S10b" to "RIG_BLUETOOTH",
        "S11" to "RADIO_VERIFIED",
        "S11a" to "MODELS",
        "S12" to "READY",
    )

    /** `null` when [sId] is not one of design-intent's S01..S12/S02b. */
    public fun setupStepNameFor(sId: String): String? = BY_ID[sId]

    public val KNOWN_IDS: Set<String> = BY_ID.keys
}
