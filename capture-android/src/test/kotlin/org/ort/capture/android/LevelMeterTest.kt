package org.ort.capture.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.system.measureTimeMillis

/**
 * register R-112: the arithmetic half of the level meter (see [LevelMeter]'s own kdoc for why it
 * is the single writer of a lock-free `@Volatile` snapshot).
 */
class LevelMeterTest {

    private fun expectedDbfs(value: Double): Float {
        if (value <= 0.0) return LevelMeter.FLOOR_DBFS
        val dbfs = (20.0 * log10(value / LevelMeter.FULL_SCALE)).toFloat()
        return if (dbfs < LevelMeter.FLOOR_DBFS) LevelMeter.FLOOR_DBFS else dbfs
    }

    @Test
    @Requirement("R-112")
    fun `R_112_peak_and_rms_are_exact_for_a_known_sine`() {
        val amplitude = 16_384
        val sampleRateHz = 16_000
        val samples = ShortArray(320) { i ->
            (amplitude * sin(2.0 * Math.PI * 440.0 * i / sampleRateHz)).toInt().toShort()
        }
        val expectedPeak = samples.maxOf { kotlin.math.abs(it.toInt()) }
        val expectedRms = sqrt(samples.sumOf { it.toInt().toDouble() * it.toInt().toDouble() } / samples.size)

        val meter = LevelMeter(TestClock())
        meter.onFrame(samples, samples.size, sampleRateHz)

        val snapshot = requireNotNull(meter.snapshot)
        assertEquals(expectedDbfs(expectedPeak.toDouble()), snapshot.peakDbfs, 0.01f)
        assertEquals(expectedDbfs(expectedRms), snapshot.rmsDbfs, 0.01f)
        assertEquals(sampleRateHz, snapshot.sampleRateHz)
        assertTrue(!snapshot.clipped, "a half-scale sine must not read as clipped")
        assertEquals(0, snapshot.clipCountLastSecond)
    }

    @Test
    @Requirement("R-112")
    fun `R_112_clipping_is_counted_at_full_scale`() {
        val sampleRateHz = 16_000
        val samples = ShortArray(100) { i -> if (i % 10 == 0) Short.MAX_VALUE else 1_000 }
        samples[5] = Short.MIN_VALUE

        val meter = LevelMeter(TestClock())
        meter.onFrame(samples, samples.size, sampleRateHz)

        val snapshot = requireNotNull(meter.snapshot)
        assertTrue(snapshot.clipped, "a frame touching full scale must read as clipped")
        assertEquals(0f, snapshot.peakDbfs, 0.001f, "a full-scale sample is exactly 0 dBFS")
        // 10 samples at Short.MAX_VALUE (i % 10 == 0, i in 0..99) plus the one forced to Short.MIN_VALUE.
        assertEquals(11, snapshot.clipCountLastSecond)
    }

    @Test
    @Requirement("R-112")
    fun `R_112_a_sample_just_under_full_scale_does_not_count_as_clipped`() {
        val samples = ShortArray(50) { 30_000 }
        val meter = LevelMeter(TestClock())

        meter.onFrame(samples, samples.size, 16_000)

        val snapshot = requireNotNull(meter.snapshot)
        assertTrue(!snapshot.clipped)
        assertEquals(0, snapshot.clipCountLastSecond)
    }

    @Test
    @Requirement("R-112")
    fun `R_112_noise_floor_is_null_until_ten_seconds_of_rms_history_have_completed`() {
        val meter = LevelMeter(TestClock())
        val quiet = ShortArray(16_000) { 200 } // one full second of quiet audio at 16 kHz

        // A second is only flushed into the RMS window when a *later* call's audio time crosses
        // into the next second index -- the in-progress (most recent) second is never counted as
        // completed. NOISE_FLOOR_WINDOW_SECONDS calls therefore flush only
        // (NOISE_FLOOR_WINDOW_SECONDS - 1) completed seconds; one more call is needed to flush the
        // window's last second.
        repeat(LevelMeter.NOISE_FLOOR_WINDOW_SECONDS) {
            meter.onFrame(quiet, quiet.size, 16_000)
        }
        val beforeCompletion = requireNotNull(meter.snapshot).noiseFloorDbfs
        assertEquals(null, beforeCompletion, "must not invent a floor before a full window")

        meter.onFrame(quiet, quiet.size, 16_000)
        assertTrue(requireNotNull(meter.snapshot).noiseFloorDbfs != null, "the window has now completed")
    }

    @Test
    @Requirement("R-112")
    fun `R_112_peak_history_accumulates_one_entry_per_completed_second_capped_at_sixty`() {
        val meter = LevelMeter(TestClock())
        val oneSecond = ShortArray(16_000) { 500 }

        repeat(LevelMeter.HISTORY_SECONDS + 5) {
            meter.onFrame(oneSecond, oneSecond.size, 16_000)
        }

        assertEquals(LevelMeter.HISTORY_SECONDS, requireNotNull(meter.snapshot).peakHistoryDbfs.size)
    }

    @Test
    @Requirement("R-112")
    fun `R_112_a_slow_consumer_never_blocks_the_frame_path`() {
        val meter = LevelMeter()
        val samples = ShortArray(1_600) { (it % 1_000).toShort() }
        val consumerRunning = AtomicBoolean(true)
        val consumer = thread(start = true) {
            while (consumerRunning.get()) {
                meter.snapshot // a slow subscriber: reads the published snapshot, then sleeps
                Thread.sleep(50)
            }
        }

        val elapsedMillis = try {
            measureTimeMillis {
                repeat(2_000) { meter.onFrame(samples, samples.size, 16_000) }
            }
        } finally {
            consumerRunning.set(false)
            consumer.join(2_000)
        }

        assertTrue(
            elapsedMillis < 2_000,
            "producing 2000 frames took ${elapsedMillis}ms with a slow subscriber reading concurrently " +
                "-- the frame path must never be blocked by a reader",
        )
    }
}
