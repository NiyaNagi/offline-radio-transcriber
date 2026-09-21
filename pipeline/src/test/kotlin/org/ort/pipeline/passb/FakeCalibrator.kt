package org.ort.pipeline.passb

import org.ort.core.AssetRef

/**
 * A behavioural fake for [Calibrator] (constitution II: "every model-bearing interface ships
 * with a behavioural fake, in the same change") — lets a test choose exactly what a fitted
 * calibration would say, on both sides of the "AMBIGUOUS until calibrated" rule build-plan P33
 * establishes, without waiting on real dev-fold data to fit one against.
 */
public class FakeCalibrator(
    override val confirmThreshold: Float,
    override val calibrationVersion: AssetRef = AssetRef("fake-calibration", "1"),
) : Calibrator
