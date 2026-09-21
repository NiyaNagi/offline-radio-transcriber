package org.ort.pipeline.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ort.testing.Requirement
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Register R-1115: before this, the heartbeat was written only inside `CaptureEvent.Frames`, into
 * a one-line file overwritten on every beat -- no trail survived on the device, so a
 * frames-starved stretch (the process alive, no frames arriving) could not be told apart from an
 * OS kill (the process gone outright). These prove the two pieces of the fix in isolation, without
 * a `ServiceController` harness (none of this needs Android): [FileHeartbeatTrailStore] actually
 * keeps a bounded history rather than one row, and never drops the overflow without saying so
 * (constitution III); [heartbeatGapMillisOrNull] is the real seam [RealCaptureService]'s ticker
 * calls into [org.ort.capture.android.proveit.ProveItAnalyzer] through, on every beat.
 */
public class HeartbeatTrailTest {

    private fun tempFile(): File = File(createTempDirectory("heartbeat-trail-test").toFile(), "heartbeat-trail.log")

    private fun entry(
        sessionId: String = "SESSION01",
        wallMillis: Long,
        framesObserved: Boolean = true,
        monotonicNanos: Long = wallMillis * 1_000_000,
        samplePosition: Long = 0L,
    ) = HeartbeatTrailEntry(sessionId, wallMillis, monotonicNanos, samplePosition, framesObserved)

    @Test
    @Requirement("R-1115")
    public fun `R_1115 the trail keeps every beat, not just the last one`() {
        val store = FileHeartbeatTrailStore(tempFile())

        store.append(entry(wallMillis = 1_000L))
        store.append(entry(wallMillis = 2_000L))
        store.append(entry(wallMillis = 3_000L))

        val recent = store.recent()
        assertEquals(3, recent.size)
        assertEquals(listOf(1_000L, 2_000L, 3_000L), recent.map { it.wallMillis })
    }

    @Test
    @Requirement("R-1115")
    public fun `R_1115 a frame-starved beat is recorded, not silently indistinguishable from a live one`() {
        val store = FileHeartbeatTrailStore(tempFile())

        store.append(entry(wallMillis = 1_000L, framesObserved = true))
        store.append(entry(wallMillis = 31_000L, framesObserved = false))
        store.append(entry(wallMillis = 61_000L, framesObserved = false))

        val recent = store.recent()
        assertEquals(listOf(true, false, false), recent.map { it.framesObservedSinceLastBeat })
    }

    @Test
    @Requirement("R-1115")
    public fun `R_1115 the trail is bounded -- it does not grow without limit`() {
        val store = FileHeartbeatTrailStore(tempFile(), maxEntries = 5)

        repeat(8) { i -> store.append(entry(wallMillis = (i + 1) * 1_000L)) }

        val recent = store.recent(limit = 100)
        assertEquals(5, recent.size)
        // The oldest three (wallMillis 1000, 2000, 3000) rotated out; the newest five remain.
        assertEquals(listOf(4_000L, 5_000L, 6_000L, 7_000L, 8_000L), recent.map { it.wallMillis })
    }

    @Test
    @Requirement("R-1115")
    public fun `R_1115 rotation never deletes quietly -- the dropped rows are counted and dated`() {
        val store = FileHeartbeatTrailStore(tempFile(), maxEntries = 5)

        repeat(8) { i -> store.append(entry(wallMillis = (i + 1) * 1_000L)) }

        val rotation = store.rotationSummary()
        assertTrue("expected a rotation record once the trail overflowed", rotation != null)
        assertEquals(3, rotation!!.droppedCount)
        assertEquals(1_000L, rotation.oldestDroppedWallMillis)
        assertEquals(3_000L, rotation.newestDroppedWallMillis)
    }

    @Test
    @Requirement("R-1115")
    public fun `R_1115 a second rotation accumulates onto the first, never replaces it`() {
        val store = FileHeartbeatTrailStore(tempFile(), maxEntries = 3)

        repeat(8) { i -> store.append(entry(wallMillis = (i + 1) * 1_000L)) }

        val rotation = store.rotationSummary()!!
        // 8 appended, 3 retained -- 5 dropped across two rotations, spanning 1000..5000.
        assertEquals(5, rotation.droppedCount)
        assertEquals(1_000L, rotation.oldestDroppedWallMillis)
        assertEquals(5_000L, rotation.newestDroppedWallMillis)
    }

    @Test
    @Requirement("R-1115")
    public fun `R_1115 an absent trail reads as empty, never a crash`() {
        val store = FileHeartbeatTrailStore(File(tempFile().parentFile, "never-written.log"))

        assertEquals(emptyList<HeartbeatTrailEntry>(), store.recent())
        assertNull(store.rotationSummary())
    }

    // -- heartbeatGapMillisOrNull: the real ProveItAnalyzer/DiagnosticsLog.logHeartbeatGap wiring --

    @Test
    @Requirement("R-1115")
    public fun `R_1115 no previous beat means no gap to report yet`() {
        val gap = heartbeatGapMillisOrNull(
            previous = null,
            current = entry(wallMillis = 30_000L),
            expectedIntervalMillis = 30_000L,
        )
        assertNull(gap)
    }

    @Test
    @Requirement("R-1115")
    public fun `R_1115 consecutive on-schedule beats report no gap, frames observed or not`() {
        val previous = entry(wallMillis = 0L, framesObserved = false)
        val current = entry(wallMillis = 30_000L, framesObserved = false)

        val gap = heartbeatGapMillisOrNull(previous, current, expectedIntervalMillis = 30_000L)

        assertNull("a frame-starved but still-alive stretch beats on schedule -- no gap", gap)
    }

    @Test
    @Requirement("R-1115")
    public fun `R_1115 a beat that lands far late reports the real gap -- the OS-kill signal`() {
        val previous = entry(wallMillis = 0L)
        // A relaunch after being killed for ten minutes: the next beat this (new) process writes
        // lands 600s after the last beat the PREVIOUS process ever wrote to the same file.
        val current = entry(sessionId = "SESSION02", wallMillis = 600_000L)

        val gap = heartbeatGapMillisOrNull(previous, current, expectedIntervalMillis = 30_000L)

        assertEquals(600_000L, gap)
    }
}
