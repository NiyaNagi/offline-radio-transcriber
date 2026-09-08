package org.ort.app.ui.setup

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.app.ui.data.LevelViewState
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.capture.android.fake.FakeAudioIo

/** R-082 (ui-conformance-plan WP9): [RealLevelCheck] against `:capture-android`'s
 * [org.ort.capture.android.fake.FakeAudioIo] (constitution II). */
class LevelCheckTest {

    private val usb = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Device")

    private fun frame(amplitude: Int, n: Int = 1_600): ShortArray = ShortArray(n) { amplitude.toShort() }

    @Test
    fun `R_082 a strong steady speech-level signal classifies in band`() = runTest {
        val io = FakeAudioIo(deviceSampleRate = 16_000)
        // -14 dBFS peak, comfortably inside [-24, -3).
        repeat(400) { io.enqueueFrames(frame(6_500)) }

        val states = RealLevelCheck(durationMillis = 600L, sampleIntervalMillis = 100L).run(io, usb).toList()

        val readings = states.filterIsInstance<LevelCheckState.Reading>()
        assertTrue(readings.isNotEmpty(), "must have produced at least one real reading")
        assertEquals(LevelBand.IN_BAND, readings.last().level.band)
    }

    @Test
    fun `R_082 a near-silent input classifies too quiet, never fabricating bars`() = runTest {
        val io = FakeAudioIo(deviceSampleRate = 16_000)
        repeat(400) { io.enqueueFrames(frame(5)) } // essentially silent

        val states = RealLevelCheck(durationMillis = 600L, sampleIntervalMillis = 100L).run(io, usb).toList()

        val readings = states.filterIsInstance<LevelCheckState.Reading>()
        assertTrue(readings.isNotEmpty())
        assertEquals(LevelBand.TOO_QUIET, readings.last().level.band)
    }

    @Test
    fun `R_082 a full-scale signal classifies clipping`() = runTest {
        val io = FakeAudioIo(deviceSampleRate = 16_000)
        repeat(400) { io.enqueueFrames(frame(32_000)) }

        val states = RealLevelCheck(durationMillis = 600L, sampleIntervalMillis = 100L).run(io, usb).toList()

        val readings = states.filterIsInstance<LevelCheckState.Reading>()
        assertEquals(LevelBand.CLIPPING, readings.last().level.band)
    }

    @Test
    fun `R_082 a device that never yields a sample reports Unavailable rather than fabricated bars`() = runTest {
        val io = FakeAudioIo(deviceSampleRate = 16_000) // never enqueued -- read() always returns 0

        val states = RealLevelCheck(durationMillis = 300L, sampleIntervalMillis = 100L).run(io, usb).toList()

        assertTrue(states.none { it is LevelCheckState.Reading }, "no real sample was ever read")
        assertTrue(states.last() is LevelCheckState.Unavailable)
    }

    @Test
    fun `R_082 a device that will not open reports Unavailable immediately`() = runTest {
        val io = FakeAudioIo(deviceSampleRate = 16_000)
        io.openSucceeds = false

        val states = RealLevelCheck().run(io, usb).toList()

        assertEquals(1, states.size)
        assertTrue(states.single() is LevelCheckState.Unavailable)
    }

    @Test
    fun `R_082 headroom is the distance from the measured peak to clipping`() = runTest {
        val io = FakeAudioIo(deviceSampleRate = 16_000)
        repeat(400) { io.enqueueFrames(frame(6_500)) } // roughly -14 dBFS

        val states = RealLevelCheck(durationMillis = 600L, sampleIntervalMillis = 100L).run(io, usb).toList()

        val last = states.filterIsInstance<LevelCheckState.Reading>().last().level
        assertEquals(-last.peakDbfs, last.headroomDb, 0.001)
    }

    @Test
    fun `R_124 headroom at exactly 0 dBFS peak reads a clean positive zero, never the string -0 dB`() {
        val reading =
            LevelReading(bars = emptyList(), peakDbfs = 0.0, noiseFloorDbfs = null, band = LevelBand.CLIPPING)
        val formatted = "%.0f".format(reading.headroomDb)

        assertEquals("0", formatted, "got \"$formatted dB\"")
    }

    @Test
    fun `R_124 a genuinely over-0dBFS peak still reads a real negative headroom`() {
        val reading = LevelReading(bars = emptyList(), peakDbfs = 0.3, noiseFloorDbfs = null, band = LevelBand.CLIPPING)

        assertEquals(-0.3, reading.headroomDb, 0.001)
    }

    @Test
    fun `R_124 levelBarFraction uses the same -60 to 0 dBFS scale as WP4's real level chart`() {
        assertEquals(0f, levelBarFraction(LevelViewState.CHART_FLOOR_DBFS.toDouble()), 0.001f)
        assertEquals(1f, levelBarFraction(LevelViewState.CHART_CEILING_DBFS.toDouble()), 0.001f)
        // -38 dBFS (too-quiet territory) must sit visibly below half height on the real chart scale
        // -- on the old -90 dBFS floor this fraction was ~0.58 (looked nearly full); R-124's fix
        // makes clipping (0 dBFS, fraction 1.0) and a quiet -38 dBFS signal actually distinguishable.
        assertTrue(
            levelBarFraction(-38.0) < 0.4f,
            "a -38 dBFS bar must read well under half height on the real chart scale",
        )
    }

    @Test
    fun `R_124 levelBarFraction never exceeds 0f-1f even for a peak past 0 dBFS or below the floor`() {
        assertEquals(1f, levelBarFraction(5.0), 0.001f)
        assertEquals(0f, levelBarFraction(-120.0), 0.001f)
    }
}
