package org.ort.core.capture

/**
 * FR-SEG-10 (register R-1054): which voice-activity detector actually produced a segment's
 * boundaries — recorded on both `SessionEntity` and `TransmissionEntity` (`:data`) rather than
 * left to a single diagnostic line, because segmentation is the one decision reprocessing cannot
 * undo (CON-SEG-1) and a boundary nobody can attribute is exactly the unrecoverable, unstated
 * error that requirement exists to prevent.
 *
 * [SILERO] and [TEN_VAD] are the two detectors FR-SEG-1 itself names — either one conforms
 * ([conformsToFrSeg1]). [ENERGY] is the RMS-energy stand-in
 * (`org.ort.pipeline.capture.EnergyVadModel`) `RealCaptureService.buildSegmenter` substitutes when
 * neither is available (register R-1052/R-1054) — never one FR-SEG-1 names, so it never conforms.
 * [UNKNOWN] is the honest value for a session or transmission recorded before this column existed
 * (constitution I: a pre-migration row reads as "not recorded", never a fabricated `SILERO`) — an
 * unrecorded detector is, by the same CON-SEG-1 reasoning, also never conforming.
 */
public enum class VadDetectorKind {
    UNKNOWN,
    SILERO,
    TEN_VAD,
    ENERGY,
}

/**
 * FR-SEG-10: the single, data-layer answer to "does this session/transmission's boundary conform
 * to FR-SEG-1?" — computed from the recorded [VadDetectorKind] alone, so no caller (UI, export, a
 * future report) re-derives the same rule a second way and risks disagreeing with this one
 * (constitution VI: "an attribution without its confidence state is a bug" applies just as much to
 * a boundary without its own conformance state).
 */
public val VadDetectorKind.conformsToFrSeg1: Boolean
    get() = this == VadDetectorKind.SILERO || this == VadDetectorKind.TEN_VAD
