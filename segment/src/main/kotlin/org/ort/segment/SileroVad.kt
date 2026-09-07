package org.ort.segment

/**
 * The Silero VAD wrapper (technical design §6): threshold logic around a [VadModel] speech
 * probability, with a small amount of hysteresis so a single ambiguous frame does not flip the
 * decision. The frame → boundary state machine lives in [Segmenter]; this class answers only
 * "is there speech in this frame".
 *
 * @param onsetThreshold probability at or above which a SILENCE→SPEECH flip is allowed.
 * @param offsetThreshold probability below which a SPEECH→SILENCE flip is allowed
 *        (`offsetThreshold <= onsetThreshold`, the hysteresis band).
 */
public class SileroVad(
    private val model: VadModel,
    private val onsetThreshold: Float = DEFAULT_ONSET,
    private val offsetThreshold: Float = DEFAULT_OFFSET,
) : Vad {

    init {
        require(onsetThreshold in 0f..1f && offsetThreshold in 0f..1f) { "thresholds must be in [0,1]" }
        require(offsetThreshold <= onsetThreshold) { "offsetThreshold must not exceed onsetThreshold" }
    }

    private var speaking = false

    override fun accept(frame: FloatArray): VadDecision {
        require(frame.size == FrameSpec.SIZE) { "frame must be ${FrameSpec.SIZE} samples, was ${frame.size}" }
        val p = model.speechProbability(frame)
        speaking = if (speaking) p >= offsetThreshold else p >= onsetThreshold
        return if (speaking) VadDecision.SPEECH else VadDecision.SILENCE
    }

    /** Resets the hysteresis state — call at a session boundary or after a capture gap. */
    public fun reset() {
        speaking = false
    }

    public companion object {
        public const val DEFAULT_ONSET: Float = 0.5f
        public const val DEFAULT_OFFSET: Float = 0.35f
    }
}
