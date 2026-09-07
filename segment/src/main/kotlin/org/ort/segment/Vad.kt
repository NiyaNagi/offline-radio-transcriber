package org.ort.segment

/**
 * Voice activity detection over fixed 32 ms frames (technical design §6). Runs *before* any ASR
 * model (FR-SEG-1); its only job is a per-frame speech / no-speech call.
 */
public interface Vad {
    /** @param frame [FrameSpec.SIZE] mono samples in `[-1.0, 1.0]`. */
    public fun accept(frame: FloatArray): VadDecision
}

public enum class VadDecision { SPEECH, SILENCE }

/** The frame geometry Silero VAD uses at 16 kHz: 512 samples ≈ 32 ms (technical design §6). */
public object FrameSpec {
    public const val SIZE: Int = 512
    public const val SAMPLE_RATE: Int = 16_000
    public const val DURATION_MS: Int = SIZE * 1000 / SAMPLE_RATE
}

/**
 * The model behind [SileroVad] — a single scalar speech probability per frame. Kept as a
 * narrow SAM interface so the onnx/sherpa-backed implementation can arrive in a later
 * build-plan wave without disturbing the segmenter, and so tests drive it directly.
 */
public fun interface VadModel {
    /** Speech probability in `[0.0, 1.0]` for one [FrameSpec.SIZE]-sample frame. */
    public fun speechProbability(frame: FloatArray): Float
}
