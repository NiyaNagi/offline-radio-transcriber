package org.ort.app.ui.setup

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioIo
import kotlin.math.abs
import kotlin.math.log10

/** Where a measured peak sits relative to `Setup-Level.dc.html`'s target band. Boundaries are a
 * documented policy choice (see [RealLevelCheck]'s doc comment), not a spec-mandated figure. */
public enum class LevelBand { TOO_QUIET, IN_BAND, CLIPPING }

/** One reading S07's live meter renders. [bars] are the last few peak samples as `0f..1f`
 * fractions (for the bar-graph), most-recent last. */
public data class LevelReading(
    val bars: List<Float>,
    val peakDbfs: Double,
    val noiseFloorDbfs: Double,
    val band: LevelBand,
) {
    /** `Setup-Level.dc.html`'s "Headroom" row: how far the peak sits below clipping (0 dBFS). */
    public val headroomDb: Double get() = -peakDbfs
}

/** Everything [LevelCheck.run] can report — including the honest "cannot measure" state guide
 * §6.8 requires when a screen has nothing real to show (never fabricated bars). */
public sealed interface LevelCheckState {
    public data class Reading(val level: LevelReading) : LevelCheckState
    public data class Unavailable(val reason: String) : LevelCheckState
}

/** S07's 60 s live meter (FR-CAP-3, `Setup-Level.dc.html`) — set on the radio, read here. */
public interface LevelCheck {
    public fun run(io: AudioIo, selected: AudioDeviceDescriptor): Flow<LevelCheckState>
}

/**
 * Reads raw PCM from [AudioIo] directly (the same seam [RealRouteCheck] uses — see that class's
 * doc comment for why this stays below `AudioRecordSource`/`:capture-api`, which are not reachable
 * from `:app`'s compile classpath through any edge this package may add).
 *
 * The target-band boundaries below are a documented policy choice, not read from any spec number
 * (`spec/functional-spec.md` names no dBFS thresholds — confirmed by search before writing this).
 * [Setup-Level.dc.html]'s own illustrative reading ("target −18 to −12") is an example on the
 * board, not a tolerance a live meter can realistically hit sample-to-sample; [TOO_QUIET_BELOW_DBFS]
 * and [CLIPPING_AT_OR_ABOVE_DBFS] give that target room to breathe while still rejecting near-silence
 * and true clipping. Every number *shown* to the operator (peak, noise floor, headroom) is real,
 * measured audio — only the three-way banding is policy.
 */
public class RealLevelCheck(
    private val durationMillis: Long = DEFAULT_DURATION_MILLIS,
    private val sampleIntervalMillis: Long = DEFAULT_SAMPLE_INTERVAL_MILLIS,
    private val barCount: Int = DEFAULT_BAR_COUNT,
) : LevelCheck {

    override fun run(io: AudioIo, selected: AudioDeviceDescriptor): Flow<LevelCheckState> = flow {
        if (!io.open()) {
            emit(LevelCheckState.Unavailable("device open failed"))
            return@flow
        }
        val buffer = ShortArray(READ_BUFFER_FRAMES)
        val bars = ArrayDeque<Float>()
        var noiseFloorDbfs = Double.NEGATIVE_INFINITY
        var elapsed = 0L
        var everRead = false

        while (elapsed < durationMillis) {
            val n = io.read(buffer)
            if (n > 0) {
                everRead = true
                val peak = peakDbfs(buffer, n)
                noiseFloorDbfs = if (noiseFloorDbfs == Double.NEGATIVE_INFINITY) peak else minOf(noiseFloorDbfs, peak)
                bars.addLast(dbfsToFraction(peak))
                if (bars.size > barCount) bars.removeFirst()
                emit(LevelCheckState.Reading(LevelReading(bars.toList(), peak, noiseFloorDbfs, bandFor(peak))))
            }
            delay(sampleIntervalMillis)
            elapsed += sampleIntervalMillis
        }
        io.close()
        if (!everRead) emit(LevelCheckState.Unavailable("no signal could be read from the input"))
    }

    private fun peakDbfs(buffer: ShortArray, n: Int): Double {
        var peak = 0
        for (i in 0 until n) {
            val a = abs(buffer[i].toInt())
            if (a > peak) peak = a
        }
        if (peak == 0) return NOISE_FLOOR_SILENCE_DBFS
        return 20.0 * log10(peak / SHORT_FULL_SCALE)
    }

    private fun dbfsToFraction(dbfs: Double): Float {
        // Maps [NOISE_FLOOR_SILENCE_DBFS, 0] onto [0f, 1f] for the bar graph.
        val clamped = dbfs.coerceIn(NOISE_FLOOR_SILENCE_DBFS, 0.0)
        return ((clamped - NOISE_FLOOR_SILENCE_DBFS) / -NOISE_FLOOR_SILENCE_DBFS).toFloat()
    }

    private fun bandFor(peakDbfs: Double): LevelBand = when {
        peakDbfs >= CLIPPING_AT_OR_ABOVE_DBFS -> LevelBand.CLIPPING
        peakDbfs < TOO_QUIET_BELOW_DBFS -> LevelBand.TOO_QUIET
        else -> LevelBand.IN_BAND
    }

    public companion object {
        public const val DEFAULT_DURATION_MILLIS: Long = 60_000L
        public const val DEFAULT_SAMPLE_INTERVAL_MILLIS: Long = 200L
        public const val DEFAULT_BAR_COUNT: Int = 15

        /** Below this, speech is too quiet to trust (policy — see class doc comment). */
        public const val TOO_QUIET_BELOW_DBFS: Double = -24.0

        /** At or above this, the input is clipping. */
        public const val CLIPPING_AT_OR_ABOVE_DBFS: Double = -3.0

        private const val NOISE_FLOOR_SILENCE_DBFS: Double = -90.0
        private const val READ_BUFFER_FRAMES: Int = 1_600
        private const val SHORT_FULL_SCALE: Double = 32_768.0
    }
}
