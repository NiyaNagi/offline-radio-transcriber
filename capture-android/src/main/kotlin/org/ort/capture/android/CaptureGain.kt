package org.ort.capture.android

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The operator's input-gain choice, and the arithmetic that applies it (register R-1168).
 *
 * **What this is, honestly.** Android exposes no input-gain API, and a USB adapter's own ADC gain
 * is not reachable from an app at all. This is therefore a plain software multiply on PCM the
 * converter has *already* quantised: it raises the noise floor by exactly as much as it raises the
 * speech, and it cannot recover an input that was already clipping at the converter. It earns its
 * place in one narrow case — an adapter so quiet that speech peaks near the bottom of the sample's
 * range, wasting most of it — and the UI says so rather than selling it.
 *
 * **Why a process-wide holder** (the same shape `org.ort.pipeline.capture.InputStatus`,
 * `ShedStatus` and `RigStatus` already use): two entirely separate call sites open the device —
 * `RealCaptureService`'s capture path and `:app`'s own `RealLevelCheck` during first-run setup —
 * and the value must take effect on the *next* [AndroidAudioIo.read] of whichever is live, not at
 * the next session start. It deliberately does **not** go through `CaptureConfigurationStore`,
 * which freezes configuration at session start by design (AC-131): a gain put there would give the
 * operator a slider they can move while the meter does not respond, which is a lie about what the
 * control does. `:capture-android` cannot depend on `:app`, so the value is *injected* here by
 * whoever owns the preference — it is never read back across the module boundary.
 *
 * [decibels] is what the operator chose and what the UI shows; [linear] is the multiplier derived
 * from it once, so the hot path does no `pow` per frame.
 */
public object CaptureGain {

    /** No gain at all — and [applyTo] returns without touching a single sample at this value. */
    public const val UNITY: Float = 1.0f

    /** The bottom of the range: unaltered audio, and the default. */
    public const val MIN_GAIN_DB: Int = 0

    /**
     * The top of the range. ~4x, which lifts an adapter peaking near −45 dBFS (the "speech wastes
     * most of the sample's range" case this exists for) to roughly −33 dBFS. Policy, not a
     * spec-mandated figure: more than this amplifies an adapter's own noise far past the point
     * where anything is gained, since the noise floor rises with the signal exactly.
     */
    public const val MAX_GAIN_DB: Int = 12

    /** The slider's step, so every reachable value is a round number the operator can report back. */
    public const val GAIN_STEP_DB: Int = 3

    /** What the operator chose, in dB. [MIN_GAIN_DB] until something sets it. */
    @Volatile
    public var decibels: Int = MIN_GAIN_DB
        private set

    /** [decibels] as the multiplier [AndroidAudioIo.read] actually applies. */
    @Volatile
    public var linear: Float = UNITY
        private set

    /** Sets the live gain, clamped to [MIN_GAIN_DB]..[MAX_GAIN_DB]; takes effect on the next read. */
    public fun setGainDb(db: Int) {
        val clamped = db.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        decibels = clamped
        linear = linearForDb(clamped)
    }

    /** Back to unaltered audio. */
    public fun reset() {
        setGainDb(MIN_GAIN_DB)
    }

    /** `0 dB` is exactly [UNITY], so the default path is provably bit-identical to no gain at all. */
    public fun linearForDb(db: Int): Float =
        if (db == MIN_GAIN_DB) UNITY else DECIBEL_BASE.pow(db / DECIBELS_PER_DECADE).toFloat()

    /**
     * Multiplies the first [frames] samples of [buffer] in place, **saturating** at the 16-bit
     * limits. Saturation is not a nicety: letting a multiplied sample wrap turns a loud passage
     * into a full-scale sign flip — an audible click, permanently baked into audio this project
     * retains losslessly precisely so every pass can be re-run against it (constitution III).
     *
     * At [UNITY] this returns immediately, so an operator who never touches the control gets
     * byte-for-byte what the converter produced.
     */
    public fun applyTo(buffer: ShortArray, frames: Int, gain: Float = linear) {
        if (gain == UNITY) return
        val count = frames.coerceIn(0, buffer.size)
        for (i in 0 until count) {
            val scaled = buffer[i] * gain
            buffer[i] = when {
                scaled >= MAX_SAMPLE -> Short.MAX_VALUE
                scaled <= MIN_SAMPLE -> Short.MIN_VALUE
                else -> scaled.roundToInt().toShort()
            }
        }
    }

    private const val DECIBEL_BASE: Double = 10.0
    private const val DECIBELS_PER_DECADE: Double = 20.0
    private const val MAX_SAMPLE: Float = 32_767f
    private const val MIN_SAMPLE: Float = -32_768f
}
