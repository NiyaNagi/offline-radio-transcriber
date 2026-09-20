package org.ort.pipeline

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ort.capture.android.heartbeat.FileHeartbeatStore
import org.ort.capture.android.heartbeat.HeartbeatRecord
import org.ort.pipeline.analytics.AnalyticsBridge
import org.ort.pipeline.analytics.CaptureHeartbeatAnalytics
import org.ort.pipeline.shed.FakeShedSignals
import org.ort.pipeline.shed.ShedController
import org.ort.telemetry.AnalyticsEvent
import org.ort.telemetry.AnalyticsTier1Payload
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import java.io.File

class CaptureStatusRepositoryTest {

    // CaptureHeartbeatAnalytics/AnalyticsBridge are process-wide singletons (their own doc
    // comments explain why) -- reset on both sides of every test so this class's own analytics
    // assertions never leak into, or are polluted by, another test class sharing this JVM worker.
    @BeforeEach
    @AfterEach
    fun resetAnalytics() {
        AnalyticsBridge.resetForTest()
        CaptureHeartbeatAnalytics.resetForTest()
    }

    @Test
    @Requirement("FR-ANL-2", "NFR-8")
    fun `FR_ANL_2_current emits no heartbeat analytics before the emit interval has elapsed`() {
        val store = FileHeartbeatStore(File(createTempDir("status-repo-4"), "hb.txt"))
        store.write(HeartbeatRecord("s1", 0, 0, 0))
        val clock = TestClock()
        val repo = CaptureStatusRepository(store, ShedController(FakeShedSignals(), clock), clock)
        val submitted = mutableListOf<AnalyticsEvent>()
        AnalyticsBridge.submit = { submitted += it }

        repo.current(
            sessionId = "s1",
            isCapturing = true,
            elapsedMillis = 1_000,
            transmissionCount = 0,
            gapCount = 0,
            isIgnoringBatteryOptimizationsDiagnosticOnly = false,
        )

        assertTrue(submitted.isEmpty(), "no heartbeat analytics before the 5-minute interval elapses")
    }

    @Test
    @Requirement("FR-ANL-2", "NFR-8", "FR-RUN-1")
    fun `FR_ANL_2_current emits a CaptureHeartbeat event once the interval elapses, never while idle`() {
        val store = FileHeartbeatStore(File(createTempDir("status-repo-5"), "hb.txt"))
        store.write(HeartbeatRecord("s1", 0, 0, 0))
        val clock = TestClock()
        val repo = CaptureStatusRepository(store, ShedController(FakeShedSignals(), clock), clock)
        val submitted = mutableListOf<AnalyticsEvent>()
        AnalyticsBridge.submit = { submitted += it }

        repo.current(
            sessionId = "s1",
            isCapturing = true,
            elapsedMillis = CaptureHeartbeatAnalytics.EMIT_INTERVAL_MILLIS,
            transmissionCount = 0,
            gapCount = 0,
            isIgnoringBatteryOptimizationsDiagnosticOnly = false,
        )

        assertEquals(1, submitted.size)
        val payload = submitted.single().payload as AnalyticsTier1Payload.CaptureHeartbeat
        assertEquals(CaptureHeartbeatAnalytics.EMIT_INTERVAL_MILLIS, payload.uptimeMs)

        submitted.clear()
        repo.current(
            sessionId = "s2",
            isCapturing = false,
            elapsedMillis = CaptureHeartbeatAnalytics.EMIT_INTERVAL_MILLIS,
            transmissionCount = 0,
            gapCount = 0,
            isIgnoringBatteryOptimizationsDiagnosticOnly = false,
        )
        assertTrue(submitted.isEmpty(), "no heartbeat analytics is ever submitted while capture is not active")
    }

    @Test
    @Requirement("AC-5", "AC-65", "FR-UI-7")
    fun `AC_5 the status surface reports the previous launch's unclean end with its last heartbeat`() {
        val store = FileHeartbeatStore(File(createTempDir("status-repo"), "hb.txt"))
        store.write(HeartbeatRecord("prev-session", 0, 5_000, 160))
        // No markCleanShutdown — an unclean end.

        val repo = CaptureStatusRepository(store, ShedController(FakeShedSignals(), TestClock()), TestClock())
        val report = repo.uncleanEndFromPreviousLaunch()

        assertNotNull(report)
        assertEquals("prev-session", report!!.sessionId)
        assertEquals(5_000L, report.lastHeartbeatWallMillis)
    }

    @Test
    @Requirement("AC-65", "NFR-8")
    fun `AC_65 status liveness never trusts the battery-optimisation API alone`() {
        val store = FileHeartbeatStore(File(createTempDir("status-repo-2"), "hb.txt"))
        store.write(HeartbeatRecord("s1", 0, 0, 0))
        val clock = TestClock(startWallMillis = 10_000_000) // "now" is far past the last heartbeat
        val repo = CaptureStatusRepository(store, ShedController(FakeShedSignals(), clock), clock)

        val status = repo.current(
            sessionId = "s1",
            isCapturing = true,
            elapsedMillis = 0,
            transmissionCount = 0,
            gapCount = 0,
            isIgnoringBatteryOptimizationsDiagnosticOnly = true,
        )

        assertFalse(status.isAlive, "a stale heartbeat must read as dead even when the battery API claims otherwise")
    }

    @Test
    fun `a clean previous shutdown reports no unclean end`() {
        val store = FileHeartbeatStore(File(createTempDir("status-repo-3"), "hb.txt"))
        store.write(HeartbeatRecord("s1", 0, 0, 0))
        store.markCleanShutdown("s1")
        val repo = CaptureStatusRepository(store, ShedController(FakeShedSignals(), TestClock()), TestClock())
        assertNull(repo.uncleanEndFromPreviousLaunch())
        assertTrue(true)
    }
}
