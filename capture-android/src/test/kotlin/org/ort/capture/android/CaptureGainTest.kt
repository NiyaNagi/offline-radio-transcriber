package org.ort.capture.android

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

/**
 * R-1168: the pure half of the one gain seam this project has — the arithmetic
 * [org.ort.capture.android.AndroidAudioIo.read] applies to every frame, and the process-wide
 * holder `:app` writes the operator's choice into (`:capture-android` cannot depend on `:app`, so
 * the value is injected, never read back across the boundary).
 *
 * The two properties that matter are not conveniences: **unity must be bit-identical** (an
 * operator who never touches the slider must get byte-for-byte what the converter produced —
 * constitution III, the retained audio is the source of truth), and **a multiplied sample must
 * saturate, never wrap** (a wrapped sample is a full-scale sign flip, which is a loud click
 * permanently baked into a lossless archive).
 */
class CaptureGainTest {

    @AfterEach
    fun resetProcessWideGain() {
        CaptureGain.reset()
    }

    @Test
    @Requirement("FR-CAP-1", "R-1168")
    fun `FR_CAP_1 unity gain leaves every sample bit identical`() {
        val original = shortArrayOf(0, 1, -1, 12_345, -12_345, Short.MAX_VALUE, Short.MIN_VALUE)
        val buffer = original.copyOf()

        CaptureGain.applyTo(buffer, buffer.size, CaptureGain.UNITY)

        assertArrayEquals(original, buffer)
    }

    @Test
    @Requirement("FR-CAP-1", "R-1168")
    fun `FR_CAP_1 a gain above unity saturates rather than wrapping at either extreme`() {
        val buffer = shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 20_000, -20_000)

        CaptureGain.applyTo(buffer, buffer.size, gain = 2.0f)

        assertEquals(Short.MAX_VALUE, buffer[0], "a positive full-scale sample must clamp, never wrap negative")
        assertEquals(Short.MIN_VALUE, buffer[1], "a negative full-scale sample must clamp, never wrap positive")
        assertEquals(Short.MAX_VALUE, buffer[2], "2x of 20 000 exceeds full scale and must clamp")
        assertEquals(Short.MIN_VALUE, buffer[3], "-2x of 20 000 exceeds full scale and must clamp")
    }

    @Test
    @Requirement("FR-CAP-1", "R-1168")
    fun `FR_CAP_1 a sample well inside full scale is scaled, not clamped`() {
        val buffer = shortArrayOf(1_000, -1_000)

        CaptureGain.applyTo(buffer, buffer.size, gain = 2.0f)

        assertEquals(2_000.toShort(), buffer[0])
        assertEquals((-2_000).toShort(), buffer[1])
    }

    @Test
    @Requirement("FR-CAP-1", "R-1168")
    fun `FR_CAP_1 only the frames actually read are touched`() {
        val buffer = shortArrayOf(1_000, 1_000, 1_000, 1_000)

        CaptureGain.applyTo(buffer, frames = 2, gain = 2.0f)

        assertArrayEquals(shortArrayOf(2_000, 2_000, 1_000, 1_000), buffer)
    }

    @Test
    @Requirement("FR-CAP-1", "R-1168")
    fun `FR_CAP_1 zero decibels is exactly unity and six decibels is very close to double`() {
        assertEquals(CaptureGain.UNITY, CaptureGain.linearForDb(0))
        assertEquals(2.0f, CaptureGain.linearForDb(6), 0.01f)
    }

    @Test
    @Requirement("FR-CAP-1", "R-1168")
    fun `FR_CAP_1 the process-wide gain is clamped to the supported range in both directions`() {
        CaptureGain.setGainDb(CaptureGain.MAX_GAIN_DB + 9)
        assertEquals(CaptureGain.MAX_GAIN_DB, CaptureGain.decibels)

        CaptureGain.setGainDb(CaptureGain.MIN_GAIN_DB - 9)
        assertEquals(CaptureGain.MIN_GAIN_DB, CaptureGain.decibels)
        assertEquals(CaptureGain.UNITY, CaptureGain.linear)
    }
}
