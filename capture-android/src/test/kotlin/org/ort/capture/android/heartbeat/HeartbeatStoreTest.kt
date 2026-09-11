package org.ort.capture.android.heartbeat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement
import java.io.File

class HeartbeatStoreTest {

    private fun store(): FileHeartbeatStore = FileHeartbeatStore(File(createTempDir("hb-test"), "heartbeat.txt"))

    @Test
    @Requirement("AC-5", "FR-SVC-5b")
    fun `AC_5 an unclean end is reported next launch with its last heartbeat time`() {
        val s = store()
        s.write(HeartbeatRecord("session-A", monotonicNanos = 1_000_000, wallMillis = 5_000, samplePosition = 160))
        // No markCleanShutdown — this session "died".

        val detector = UncleanEndDetector(s)
        val report = detector.detect()

        assertTrue(report != null)
        assertEquals("session-A", report!!.sessionId)
        assertEquals(5_000L, report.lastHeartbeatWallMillis)
    }

    @Test
    @Requirement("AC-5")
    fun `AC_5 a clean shutdown is not reported as an unclean end`() {
        val s = store()
        s.write(HeartbeatRecord("session-A", monotonicNanos = 1_000_000, wallMillis = 5_000, samplePosition = 160))
        s.markCleanShutdown("session-A")

        assertNull(UncleanEndDetector(s).detect())
    }

    @Test
    @Requirement("AC-5")
    fun `no heartbeat ever written means nothing to report`() {
        assertFalse(store().hadUncleanEnd())
        assertNull(UncleanEndDetector(store()).detect())
    }

    @Test
    @Requirement("AC-65", "NFR-8")
    fun `AC_65 liveness comes from heartbeat continuity even when the battery API lies`() {
        val checker = LivenessChecker(maxSilenceMillis = 90_000)
        // isIgnoringBatteryOptimizations() returns true (a "safe" signal) but heartbeats stopped
        // 10 minutes ago — the session must be judged dead anyway.
        val alive = checker.isAlive(
            lastHeartbeatWallMillis = 0L,
            nowWallMillis = 600_000L,
            isIgnoringBatteryOptimizationsDiagnosticOnly = true,
        )
        assertFalse(alive, "a stale heartbeat must read as dead regardless of what the battery API claims")
    }

    @Test
    @Requirement("AC-65")
    fun `AC_65 a recent heartbeat reads as alive regardless of the battery API`() {
        val checker = LivenessChecker(maxSilenceMillis = 90_000)
        val alive = checker.isAlive(
            lastHeartbeatWallMillis = 0L,
            nowWallMillis = 30_000L,
            isIgnoringBatteryOptimizationsDiagnosticOnly = false,
        )
        assertTrue(alive)
    }

    /** A [File] whose [exists] always answers `true` — as if a `stat()` moments ago said so — while
     * the path underneath has genuinely been deleted (a scenario reset, `clear()`, a real device's
     * `pm clear`, racing the reader's own [FileHeartbeatStore.last]/[FileHeartbeatStore.hadUncleanEnd]
     * between their own `exists()` check and their `readLines()` call). Reproduces the TOCTOU race
     * deterministically, without needing real concurrent deletion timing. */
    private class ExistsButGoneFile(path: String) : File(path) {
        override fun exists(): Boolean = true
    }

    @Test
    @Requirement("AC-5", "FR-PLT-1")
    fun `AC_5 last reads null, never throws, when the file vanishes between exists and readLines`() {
        val goneFile = ExistsButGoneFile(File(createTempDir("hb-test"), "heartbeat.txt").absolutePath)
        val s = FileHeartbeatStore(goneFile)

        assertNull(s.last(), "a heartbeat that vanished mid-read is 'no heartbeat', not a crash")
    }

    @Test
    @Requirement("AC-5", "FR-PLT-1")
    fun `AC_5 hadUncleanEnd reads false, never throws, when the file vanishes between exists and readLines`() {
        val goneFile = ExistsButGoneFile(File(createTempDir("hb-test"), "heartbeat.txt").absolutePath)
        val s = FileHeartbeatStore(goneFile)

        assertFalse(
            s.hadUncleanEnd(),
            "a heartbeat that vanished mid-read must never be reported as an unclean end",
        )
    }

    @Test
    @Requirement("AC-5", "FR-PLT-1")
    fun `AC_5 last reads null, never throws, on a truncated numeric line`() {
        val file = File(createTempDir("hb-test"), "heartbeat.txt")
        // A write() torn mid-line (a real crash/kill mid-write, or another TOCTOU: read after
        // write() has truncated the file but before the new content lands) -- "monotonicNanos"
        // parses as neither empty nor a real number.
        file.writeText("session-A\nnot-a-number\n5000\n160\nfalse")
        val s = FileHeartbeatStore(file)

        assertNull(s.last(), "a truncated/corrupt heartbeat line is 'no heartbeat', not a crash")
    }
}
