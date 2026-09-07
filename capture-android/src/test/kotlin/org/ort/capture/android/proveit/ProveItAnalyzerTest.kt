package org.ort.capture.android.proveit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.capture.android.heartbeat.HeartbeatRecord
import org.ort.testing.Requirement

class ProveItAnalyzerTest {

    private fun beat(wallMillis: Long) = HeartbeatRecord("s1", wallMillis * 1_000_000, wallMillis, wallMillis / 62)

    @Test
    @Requirement("AC-64")
    fun `AC_64 continuous heartbeats over 30 minutes predict an 8-hour survival`() {
        val heartbeats = (0..60).map { beat(it * 30_000L) } // one every 30s for 30 minutes
        val report = ProveItAnalyzer(expectedIntervalMillis = 30_000).analyze(
            heartbeats,
            durationMillis = 30 * 60 * 1000L,
            processRestarted = false,
        )
        assertTrue(report.predictedToSurviveEightHours)
        assertEquals(0, report.gapCount)
    }

    @Test
    @Requirement("AC-64")
    fun `AC_64 a heartbeat gap during the 30-minute run predicts against 8-hour survival`() {
        val heartbeats = listOf(beat(0), beat(30_000), beat(300_000)) // a 4.5-minute silent gap
        val report = ProveItAnalyzer(expectedIntervalMillis = 30_000).analyze(
            heartbeats,
            durationMillis = 300_000L,
            processRestarted = false,
        )
        assertFalse(report.predictedToSurviveEightHours)
        assertTrue(report.gapCount >= 1)
    }

    @Test
    @Requirement("AC-64")
    fun `AC_64 a process restart during the 30-minute run predicts against 8-hour survival`() {
        val heartbeats = listOf(beat(0), beat(30_000))
        val report = ProveItAnalyzer(expectedIntervalMillis = 30_000).analyze(
            heartbeats,
            durationMillis = 60_000L,
            processRestarted = true,
        )
        assertFalse(report.predictedToSurviveEightHours)
    }
}
