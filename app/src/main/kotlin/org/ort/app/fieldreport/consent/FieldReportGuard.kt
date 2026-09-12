package org.ort.app.fieldreport.consent

/**
 * FR-OBS-10: "the uploader SHALL refuse the over-audio, voiceprint-embedding and screen-frame
 * categories unless a visible Settings switch has been explicitly turned off by the operator,
 * checked at upload time, not at setup time." [gatedCategoriesAllowed] is that check, pulled out
 * as a pure function so both the consent screen (to force the three toggles off and inert) and a
 * future uploader (WPR3) call the identical decision rather than each reimplementing it.
 *
 * [destinationIsPublic] and [publicGuardEnabled] are exactly [org.ort.app.fieldreport.upload.FieldReportDestination.isPublic]
 * and [org.ort.app.fieldreport.settings.FieldReportSettingsStore.publicDestinationGuardEnabled].
 * FR-OBS-8's ungated set is never subject to this gate at all — there is no parameter here that
 * could accidentally gate it, and callers must not route it through this function.
 */
public object FieldReportGuard {
    public fun gatedCategoriesAllowed(destinationIsPublic: Boolean, publicGuardEnabled: Boolean): Boolean =
        !destinationIsPublic || !publicGuardEnabled
}
