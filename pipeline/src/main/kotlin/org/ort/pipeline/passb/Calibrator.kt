package org.ort.pipeline.passb

import org.ort.core.AssetRef

/**
 * The calibration seam build-plan P33 / register R-1110 require: [CallsignResolver]'s
 * `confirmThreshold` and [org.ort.core.PassFingerprint.calibrationVersion] both come from a real,
 * fitted [Calibrator], or not at all.
 *
 * Constitution VI: "no number without its provenance." An uncalibrated `confirmThreshold` is
 * exactly the number that rule forbids — there is no fold behind it — so this interface has
 * deliberately no "uncalibrated default" implementation that hands one out anyway. Where no real
 * calibration exists yet, a caller passes `null` rather than reaching for a hand-picked constant
 * (see [CallsignResolver]'s own doc comment for what `null` does to the resolvable states).
 *
 * [PassBFactory.create] passes no [Calibrator] today: there is no dev-fold data yet to fit one
 * against. `:eval`'s harness is where a real fit would be produced and evaluated; wiring a real,
 * fitted [Calibrator] into production is the follow-up this unit leaves open.
 */
public interface Calibrator {
    /** [CallsignResolver]'s minimum `totalScore` to assert the top candidate at all — fitted
     * against labelled data, never chosen by hand. */
    public val confirmThreshold: Float

    /** Stamped onto [org.ort.core.PassFingerprint.calibrationVersion] so a later re-fit is
     * provenance-visible on every row a pass produces under it (constitution VI). */
    public val calibrationVersion: AssetRef
}
