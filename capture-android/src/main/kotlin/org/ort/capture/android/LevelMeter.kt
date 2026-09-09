package org.ort.capture.android

import org.ort.core.Clock
import org.ort.core.SystemClock
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * register R-112: the arithmetic half of the level meter. [AudioRecordSource] hands every verified
 * frame to [onFrame] from its own read loop — see that class's single tap point — so this runs on
 * the same thread that reads the microphone, once per read (roughly 10 Hz at the default read-
 * buffer size and a typical 16-48 kHz device rate). It is deliberately **the only writer**: nothing
 * else ever calls [onFrame], so the per-second ring buffers below need no lock at all — capture
 * can never be blocked by a reader, because a reader never touches anything a writer holds.
 *
 * [snapshot] is a single `@Volatile` field replaced atomically after each [onFrame] call — a caller
 * (`RealCaptureService`, on the same frame path, or a slow UI poller) only ever reads a plain field,
 * never a channel or a lock, so a slow subscriber cannot stall the producer (`LevelMeterTest`'s own
 * concurrency test proves this directly).
 *
 * Two windows, both tracked in **audio time** (derived from the sample count and the device's own
 * rate, never wall-clock) so the result is deterministic regardless of scheduling jitter:
 * - [Snapshot.clipCountLastSecond] is a trailing ~1 s sum, evicting entries as audio time advances.
 * - [Snapshot.noiseFloorDbfs] is the minimum of the last [NOISE_FLOOR_WINDOW_SECONDS] *completed*
 *   one-second RMS buckets — `null` until that many have actually completed (constitution I: never
 *   a floor invented from less than a real window of data).
 * - [Snapshot.peakHistoryDbfs] is the last [HISTORY_SECONDS] completed one-second peak buckets,
 *   oldest first — `Level-Meter.dc.html`'s own 60 s history bar.
 *
 * **[Snapshot.clippedSamplesTotal] is a monotonic running total, never evicted** (register R-419):
 * `Level-Meter.dc.html`'s board row is "Clipped samples this session", but
 * [Snapshot.clipCountLastSecond] is only ever a rolling ~1 s window — summing it tick over tick
 * would count the same clipped sample roughly once per tick for as long as it stays inside that
 * window (`onFrame` runs at roughly 10 Hz), wildly overcounting. This field instead accumulates
 * each frame's own newly-detected clip count directly, unconditionally, for as long as this
 * [LevelMeter] instance exists — which is exactly one capture session's lifetime (a fresh instance
 * per [org.ort.capture.android.AudioRecordSource]), so no separate reset is needed here; the
 * session boundary is the instance boundary.
 */
public class LevelMeter(private val clock: Clock = SystemClock) {

    public data class Snapshot(
        public val peakDbfs: Float,
        public val rmsDbfs: Float,
        public val noiseFloorDbfs: Float?,
        public val clipped: Boolean,
        public val clipCountLastSecond: Int,
        public val sampleRateHz: Int,
        public val updatedAtMillis: Long,
        public val peakHistoryDbfs: List<Float>,
        public val clippedSamplesTotal: Long,
    )

    /** `null` until [onFrame] has processed at least one sample this session. */
    @Volatile
    public var snapshot: Snapshot? = null
        private set

    // Everything below is mutated only from onFrame(), always called on the single audio-reading
    // thread -- see the class kdoc for why that makes a lock unnecessary.
    private var audioTimeMillis: Double = 0.0
    private var currentSecondIndex: Long = -1
    private var secondPeakAbs: Int = 0
    private var secondSumSquares: Double = 0.0
    private var secondSampleCount: Long = 0

    private val peakHistory = ArrayDeque<Float>(HISTORY_SECONDS)
    private val rmsWindow = ArrayDeque<Float>(NOISE_FLOOR_WINDOW_SECONDS)
    private val clipEvents = ArrayDeque<ClipEvent>()

    /** register R-419: this session's real running total -- see class kdoc. Never evicted, unlike
     * [clipEvents]' own rolling window. */
    private var clippedSamplesTotal: Long = 0L

    private data class ClipEvent(val atMillis: Double, val count: Int)

    /** O(n) over [count] samples, no allocation beyond the one immutable [Snapshot] published at the end. */
    public fun onFrame(samples: ShortArray, count: Int, sampleRateHz: Int) {
        if (count <= 0 || sampleRateHz <= 0) return

        var peakAbs = 0
        var sumSquares = 0.0
        var clipCount = 0
        for (i in 0 until count) {
            val sample = samples[i].toInt()
            val magnitude = abs(sample)
            if (magnitude > peakAbs) peakAbs = magnitude
            sumSquares += sample.toDouble() * sample.toDouble()
            if (magnitude >= CLIP_THRESHOLD_ABS) clipCount++
        }
        val rms = sqrt(sumSquares / count)

        val frameStartMillis = audioTimeMillis
        audioTimeMillis += count * MILLIS_PER_SECOND / sampleRateHz.toDouble()

        if (clipCount > 0) {
            clipEvents.addLast(ClipEvent(audioTimeMillis, clipCount))
            clippedSamplesTotal += clipCount
        }
        while (clipEvents.isNotEmpty() && clipEvents.first().atMillis < audioTimeMillis - MILLIS_PER_SECOND) {
            clipEvents.removeFirst()
        }
        val clipCountLastSecond = clipEvents.sumOf { it.count }

        accumulateSecond(frameStartMillis, peakAbs, sumSquares, count)

        val noiseFloorDbfs = if (rmsWindow.size >= NOISE_FLOOR_WINDOW_SECONDS) rmsWindow.min() else null

        snapshot = Snapshot(
            peakDbfs = toDbfs(peakAbs.toDouble()),
            rmsDbfs = toDbfs(rms),
            noiseFloorDbfs = noiseFloorDbfs,
            clipped = clipCount > 0,
            clipCountLastSecond = clipCountLastSecond,
            sampleRateHz = sampleRateHz,
            updatedAtMillis = clock.wallMillis(),
            peakHistoryDbfs = peakHistory.toList(),
            clippedSamplesTotal = clippedSamplesTotal,
        )
    }

    private fun accumulateSecond(frameStartMillis: Double, peakAbs: Int, sumSquares: Double, count: Int) {
        val secondIndex = (frameStartMillis / MILLIS_PER_SECOND).toLong()
        if (secondIndex != currentSecondIndex) {
            if (currentSecondIndex >= 0 && secondSampleCount > 0) flushSecond()
            currentSecondIndex = secondIndex
            secondPeakAbs = 0
            secondSumSquares = 0.0
            secondSampleCount = 0
        }
        if (peakAbs > secondPeakAbs) secondPeakAbs = peakAbs
        secondSumSquares += sumSquares
        secondSampleCount += count
    }

    private fun flushSecond() {
        if (peakHistory.size >= HISTORY_SECONDS) peakHistory.removeFirst()
        peakHistory.addLast(toDbfs(secondPeakAbs.toDouble()))

        if (rmsWindow.size >= NOISE_FLOOR_WINDOW_SECONDS) rmsWindow.removeFirst()
        rmsWindow.addLast(toDbfs(sqrt(secondSumSquares / secondSampleCount)))
    }

    private fun toDbfs(value: Double): Float {
        if (value <= 0.0) return FLOOR_DBFS
        val dbfs = (20.0 * log10(value / FULL_SCALE)).toFloat()
        return if (dbfs < FLOOR_DBFS) FLOOR_DBFS else dbfs
    }

    public companion object {
        /** A sample at or above this magnitude is at full scale (`Short.MAX_VALUE`). */
        public const val CLIP_THRESHOLD_ABS: Int = 32_767
        public const val FULL_SCALE: Double = 32_768.0
        public const val HISTORY_SECONDS: Int = 60
        public const val NOISE_FLOOR_WINDOW_SECONDS: Int = 10

        /** The quietest reading ever reported — genuine digital silence is `-infinity` dBFS, not renderable. */
        public const val FLOOR_DBFS: Float = -120f

        private const val MILLIS_PER_SECOND: Double = 1_000.0
    }
}
