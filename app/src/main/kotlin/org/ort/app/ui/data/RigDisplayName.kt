package org.ort.app.ui.data

/**
 * R-845/R-916/R-920 (register): [org.ort.pipeline.capture.RigStatus.State.Connected.descriptor] is
 * [org.ort.rig.descriptor.RigDescriptor.displayName] verbatim (`RigSupervisor`'s own real producer,
 * confirmed by reading its source before writing this) — "Kenwood TH-D75A" for the one bundled,
 * verified radio (`rig/src/main/resources/descriptors/kenwood-thd75a.json`). Every screen that
 * names the rig drops the leading manufacturer word(s): CF06 (R-845, `ui/settings/SettingsPolling.kt`),
 * N04's own Radio row title (R-916, `CaptureStatusViewState.radioFacts`), and DG04's own Rig link
 * row (R-920, `DigestPolling.liveRigNameFor`) all used to carry their own private copy of the same
 * rule — a real risk of the three silently drifting apart the moment one of them was ever edited
 * alone. Moved here, `ui/data`, as the one shared helper all three now call.
 *
 * The general, principled rule (rather than a hardcoded `"Kenwood "` string replace, which would
 * silently stop working for any future second manufacturer) is "keep from the first
 * space-separated word that itself contains a digit onward" — a model designator like `TH-D75A`
 * always has one, a plain manufacturer or generic-protocol word (`Kenwood`, `Generic`, `ASCII`,
 * `CAT`) never does. A name with no such word at all (`"Generic ASCII CAT"`,
 * `NullRigModule.DISPLAY_NAME`) is returned unchanged — there is no manufacturer prefix to drop
 * from a name that is not `<manufacturer> <model>` shaped in the first place.
 */
public fun stripRigManufacturerPrefix(displayName: String): String {
    val words = displayName.split(' ')
    val modelIndex = words.indexOfFirst { word -> word.any { it.isDigit() } }
    return if (modelIndex <= 0) displayName else words.subList(modelIndex, words.size).joinToString(" ")
}
