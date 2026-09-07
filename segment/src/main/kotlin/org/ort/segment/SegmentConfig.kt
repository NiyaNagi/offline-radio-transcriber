package org.ort.segment

/**
 * Segmentation parameters (technical design §6, FR-SEG-2, FR-SEG-3, FR-SEG-8).
 *
 * **There is deliberately no `Tier` here, and [Segmenter] has no `Tier` constructor argument
 * either** (FR-SEG-7, CON-SEG-1 → AC-94). Segmentation is the one decision reprocessing cannot
 * undo: only gated audio is retained, so a boundary error is permanent. A weak device must
 * segment identically to the reference device. Making the type incapable of carrying a tier is
 * how that rule is enforced structurally rather than remembered.
 */
public data class SegmentConfig(
    val minSpeechMs: Int = DEFAULT_MIN_SPEECH_MS,
    val minSilenceMs: Int = DEFAULT_MIN_SILENCE_MS,
    val maxSegmentMs: Int = DEFAULT_MAX_SEGMENT_MS,
    val preRollMs: Int = DEFAULT_PRE_ROLL_MS,
    val postRollMs: Int = DEFAULT_POST_ROLL_MS,
    val sampleRate: Int = FrameSpec.SAMPLE_RATE,
) {
    init {
        require(minSpeechMs > 0) { "minSpeechMs must be positive" }
        require(minSilenceMs > 0) { "minSilenceMs must be positive" }
        require(maxSegmentMs >= minSpeechMs) { "maxSegmentMs must be at least minSpeechMs" }
        require(preRollMs >= PRE_ROLL_FLOOR_MS) { "preRoll must be >= ${PRE_ROLL_FLOOR_MS}ms (FR-CAP-4)" }
        require(postRollMs >= 0) { "postRollMs must not be negative" }
        require(sampleRate > 0) { "sampleRate must be positive" }
    }

    internal fun msToSamples(ms: Int): Int = ms * sampleRate / 1000

    public companion object {
        public const val DEFAULT_MIN_SPEECH_MS: Int = 250
        public const val DEFAULT_MIN_SILENCE_MS: Int = 600
        public const val DEFAULT_MAX_SEGMENT_MS: Int = 60_000
        public const val DEFAULT_PRE_ROLL_MS: Int = 1_200
        public const val DEFAULT_POST_ROLL_MS: Int = 400

        /** FR-CAP-4's hard floor: at least one second of pre-roll. */
        public const val PRE_ROLL_FLOOR_MS: Int = 1_000
    }
}
