package org.ort.pipeline.analytics

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

/**
 * FR-ANL-2/NFR-8: [CaptureHeartbeatAnalytics] is the pure sampler `CaptureStatusRepository.current`
 * drives on every status poll — tested directly here, isolated from any real `HeartbeatStore` or
 * Android type, so the gap-detection and throttling arithmetic is proven independently of the
 * wiring `CaptureStatusRepositoryTest` covers.
 */
class CaptureHeartbeatAnalyticsTest {

    @BeforeEach
    @AfterEach
    fun reset() {
        CaptureHeartbeatAnalytics.resetForTest()
    }

    @Test
    @Requirement("FR-ANL-2")
    fun `FR_ANL_2_no sample is emitted before the interval has elapsed`() {
        val sample = CaptureHeartbeatAnalytics.sample(
            "s1",
            elapsedMillis = 1_000,
            isAlive = true,
            nowWallMillis = 1_000,
        )
        assertNull(sample)
    }

    @Test
    @Requirement("FR-ANL-2", "NFR-8")
    fun `FR_ANL_2_a sample at the interval boundary reports the accumulated uptime`() {
        val sample = CaptureHeartbeatAnalytics.sample(
            "s1",
            elapsedMillis = CaptureHeartbeatAnalytics.EMIT_INTERVAL_MILLIS,
            isAlive = true,
            nowWallMillis = CaptureHeartbeatAnalytics.EMIT_INTERVAL_MILLIS,
        )
        assertEquals(CaptureHeartbeatAnalytics.EMIT_INTERVAL_MILLIS, sample?.uptimeMs)
        assertEquals(0, sample?.gapCount)
        assertEquals(0L, sample?.gapDurationMs)
    }

    @Test
    @Requirement("FR-ANL-2", "NFR-8")
    fun `FR_ANL_2_a heartbeat gap that opens and closes is counted once with its real duration`() {
        // Alive, then a gap opens at t=1000 and closes at t=4000 (a 3s gap) -- both calls land
        // before the emit interval, so the accumulated gap only surfaces once a later sample
        // finally crosses the threshold.
        CaptureHeartbeatAnalytics.sample("s1", elapsedMillis = 500, isAlive = true, nowWallMillis = 500)
        CaptureHeartbeatAnalytics.sample("s1", elapsedMillis = 1_000, isAlive = false, nowWallMillis = 1_000)
        CaptureHeartbeatAnalytics.sample("s1", elapsedMillis = 4_000, isAlive = true, nowWallMillis = 4_000)

        val sample = CaptureHeartbeatAnalytics.sample(
            "s1",
            elapsedMillis = CaptureHeartbeatAnalytics.EMIT_INTERVAL_MILLIS,
            isAlive = true,
            nowWallMillis = CaptureHeartbeatAnalytics.EMIT_INTERVAL_MILLIS,
        )

        assertEquals(1, sample?.gapCount)
        assertEquals(3_000L, sample?.gapDurationMs)
    }

    @Test
    @Requirement("FR-ANL-2")
    fun `FR_ANL_2_a gap that never closes is not counted until it does`() {
        CaptureHeartbeatAnalytics.sample("s1", elapsedMillis = 500, isAlive = true, nowWallMillis = 500)
        CaptureHeartbeatAnalytics.sample("s1", elapsedMillis = 1_000, isAlive = false, nowWallMillis = 1_000)

        val sample = CaptureHeartbeatAnalytics.sample(
            "s1",
            elapsedMillis = CaptureHeartbeatAnalytics.EMIT_INTERVAL_MILLIS,
            isAlive = false,
            nowWallMillis = CaptureHeartbeatAnalytics.EMIT_INTERVAL_MILLIS,
        )

        assertEquals(0, sample?.gapCount, "a gap that has not yet closed contributes no completed gap")
        assertEquals(0L, sample?.gapDurationMs)
    }

    @Test
    @Requirement("FR-ANL-2")
    fun `FR_ANL_2_two sessions are tracked independently`() {
        CaptureHeartbeatAnalytics.sample("s1", elapsedMillis = 0, isAlive = false, nowWallMillis = 0)
        val s2Sample = CaptureHeartbeatAnalytics.sample(
            "s2",
            elapsedMillis = CaptureHeartbeatAnalytics.EMIT_INTERVAL_MILLIS,
            isAlive = true,
            nowWallMillis = CaptureHeartbeatAnalytics.EMIT_INTERVAL_MILLIS,
        )

        assertEquals(0, s2Sample?.gapCount, "s1's open gap must never leak into s2's own accumulator")
    }

    @Test
    @Requirement("FR-ANL-2")
    fun `FR_ANL_2_resetForTest clears every session's accumulated state`() {
        CaptureHeartbeatAnalytics.sample(
            "s1",
            elapsedMillis = CaptureHeartbeatAnalytics.EMIT_INTERVAL_MILLIS,
            isAlive = true,
            nowWallMillis = CaptureHeartbeatAnalytics.EMIT_INTERVAL_MILLIS,
        )

        CaptureHeartbeatAnalytics.resetForTest()

        val sample = CaptureHeartbeatAnalytics.sample(
            "s1",
            elapsedMillis = 1_000,
            isAlive = true,
            nowWallMillis = 1_000,
        )
        assertNull(sample, "a fresh session after reset must not inherit the previous emission's throttle")
    }
}
