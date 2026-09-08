package org.ort.pipeline.capture

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ort.capture.android.GapRecord
import org.ort.capture.android.GapTracker
import org.ort.captureapi.CaptureEvent
import org.ort.testing.Requirement
import org.ort.testing.TestClock

/**
 * F-028 (audit-2026-09-07): before this fix, [RealCaptureService] never constructed a
 * [GapTracker] or a [org.ort.pipeline.GapPersister] -- `AudioRecordSource` emitted
 * `Interrupted`/`Resumed` (and, after F-010, dropped-span causes) but nothing in the running
 * service joined them to `CaptureGapDao`, so `captureGapDao` was always empty in production and
 * P17's not-listening distinction (FR-UI-12) could never show a real gap (FR-RUN-12, AC-48;
 * constitution IV).
 *
 * Following F-005's approach (see [buildHeartbeatRecord]'s doc comment): rather than starting the
 * whole [RealCaptureService] under Robolectric (no `ServiceController` harness exists yet for it
 * -- F-011, still open), this drives the exact seam the fix adds -- [CaptureGapRelay], which is
 * exactly what `RealCaptureService.startCapture()` now constructs and feeds every `CaptureEvent`
 * through, with a fake persist callback standing in for `GapPersister.persist` (itself already
 * proven against a real Room database by `GapPersisterTest`).
 */
public class RealCaptureServiceGapTest {

    @Test
    @Requirement("AC-48", "FR-RUN-12")
    public fun AC_48_an_interruption_seen_by_the_running_service_becomes_a_capture_gap_row() = runTest {
        val clock = TestClock()
        val tracker = GapTracker(clock)
        val persisted = mutableListOf<GapRecord>()
        val relay = CaptureGapRelay(tracker) { gap -> persisted.add(gap) }

        clock.advance(1_000)
        relay.onEvent(CaptureEvent.Interrupted("focus loss"))
        clock.advance(5_000)
        relay.onEvent(CaptureEvent.Resumed)

        assertEquals(1, persisted.size)
        val gap = persisted.single()
        assertEquals("focus loss", gap.cause)
        // TestClock's fixed default wall-time origin (see TestClock.kt) plus the advances above.
        assertEquals(1_600_000_000_000L + 1_000L, gap.startWallMillis)
        assertEquals(1_600_000_000_000L + 6_000L, gap.endWallMillis)
    }

    @Test
    @Requirement("AC-48", "FR-RUN-12")
    public fun AC_48_a_dropped_span_from_F_010_is_persisted_without_waiting_for_resumed() = runTest {
        val clock = TestClock()
        val tracker = GapTracker(clock)
        val persisted = mutableListOf<GapRecord>()
        val relay = CaptureGapRelay(tracker) { gap -> persisted.add(gap) }

        relay.onEvent(
            CaptureEvent.Interrupted("dropped samples: 480 samples over 30ms (stalled consumer or read shortfall)"),
        )
        // The Resumed that follows a dropped-span Interrupted is a no-op in GapTracker -- nothing
        // was left open for it to close -- so this must not persist a second gap.
        relay.onEvent(CaptureEvent.Resumed)

        assertEquals(1, persisted.size)
    }

    @Test
    @Requirement("AC-48", "FR-RUN-12")
    public fun AC_48_frames_and_route_changes_do_not_trigger_a_persist() = runTest {
        val clock = TestClock()
        val tracker = GapTracker(clock)
        val persisted = mutableListOf<GapRecord>()
        val relay = CaptureGapRelay(tracker) { gap -> persisted.add(gap) }

        relay.onEvent(CaptureEvent.Frames(ShortArray(0), 0))
        relay.onEvent(CaptureEvent.RouteChanged)
        relay.onEvent(CaptureEvent.EndOfStream)

        assertEquals(0, persisted.size)
    }
}
