package org.ort.captureapi

/**
 * A PCM audio format. The capture path deals in signed 16-bit little-endian samples only
 * (FR-CAP-1); the one degree of freedom that matters is the sample rate, because most USB
 * Audio Class adapters expose 44.1 or 48 kHz and nothing else (FR-CAP-2a).
 */
public data class AudioFormat(val sampleRate: Int, val channels: Int = 1) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        require(channels in 1..2) { "channels must be 1 or 2, was $channels" }
    }

    /** Nanoseconds of wall time one frame (one sample per channel) represents. */
    public val nanosPerFrame: Long get() = NANOS_PER_SECOND / sampleRate

    public companion object {
        public const val OUTPUT_SAMPLE_RATE: Int = 16_000
        private const val NANOS_PER_SECOND: Long = 1_000_000_000L

        /** The rate every model downstream consumes, post-resample (FR-CAP-1). */
        public val MODEL_INPUT: AudioFormat = AudioFormat(OUTPUT_SAMPLE_RATE, channels = 1)
    }
}
