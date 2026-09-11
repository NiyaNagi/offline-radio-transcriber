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
    private val BY_ID: Map<String, String> = mapOf(
        "S01" to "WELCOME",
        "S02" to "MICROPHONE",
        "S02b" to "MICROPHONE_DENIED",
        "S03" to "NOTIFICATIONS",
        "S04" to "INPUT",
        "S05" to "VERIFY",
        "S06" to "ROUTE_MISMATCH",
        "S07" to "LEVEL",
        "S08" to "OVERNIGHT",
        "S09" to "RADIO",
        "S10" to "RADIO_USB",
        "S11" to "RADIO_VERIFIED",
        "S12" to "READY",
        // P19/WPI (spec/e2e-capture-modes-plan.md, E2-J02): D33's four new setup steps.
        "S00" to "MODE",
        "S02c" to "BLUETOOTH_PERMISSION",
        "S09b" to "RIG_TRANSPORT",
        "S10b" to "RIG_BLUETOOTH",
    )

    /** `null` when [sId] is not one of design-intent's S01..S12/S02b. */
    public fun setupStepNameFor(sId: String): String? = BY_ID[sId]

    public val KNOWN_IDS: Set<String> = BY_ID.keys
}
