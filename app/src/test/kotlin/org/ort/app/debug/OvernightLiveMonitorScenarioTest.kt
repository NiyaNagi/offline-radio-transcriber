package org.ort.app.debug

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.LiveMonitorOverRow
import org.ort.app.ui.data.LiveMonitorOversPolling
import org.ort.core.AttributionState
import org.ort.data.OrtDatabase
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.LevelStatus
import org.robolectric.RobolectricTestRunner

/**
 * WPW (register R-1007 follow-up): proves `overnight-live-monitor` really writes the seven
 * distinguishable [LiveMonitorOverRow] states `Live-Monitor.dc.html` needs — not merely that the
 * scenario loads without throwing (`ScenariosTest`'s own R-110 sweep already covers that). The same
 * "assert what a scenario actually writes" discipline `WpiScenariosTest.kt` established (register
 * R-804/R-943): a scenario that silently seeds the wrong holder has happened on this project more
 * than once and wasted whole capture rounds.
 */
@RunWith(RobolectricTestRunner::class)
class OvernightLiveMonitorScenarioTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: OrtDatabase

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    @After
    fun closeDatabase() {
        db.close()
        CaptureState.idle(clearSession = true)
        LevelStatus.reset()
    }

    @Test
    fun `R_1007 overnight-live-monitor is genuinely live, so the pinned and embedded live bars are reachable`() =
        runTest {
            val result = Scenarios.load(context, "overnight-live-monitor")

            assertTrue("the scenario must mark capture as genuinely running", CaptureState.isCapturing)
            assertEquals(result.primarySessionId, CaptureState.sessionId)
            assertEquals(7, result.transmissionCount)
        }

    @Test
    fun `R_1007 overnight-live-monitor seeds a real Transcribing row`() = runTest {
        val result = Scenarios.load(context, "overnight-live-monitor")
        val overs = LiveMonitorOversPolling.current(context, result.primarySessionId).overs

        val transcribing = overs.filterIsInstance<LiveMonitorOverRow.Transcribing>()
        assertEquals("expected exactly one Transcribing row", 1, transcribing.size)
    }

    @Test
    fun `R_1007 overnight-live-monitor seeds two Waiting rows, the second with a real non-zero queue position`() =
        runTest {
            val result = Scenarios.load(context, "overnight-live-monitor")
            val overs = LiveMonitorOversPolling.current(context, result.primarySessionId).overs

            val waiting = overs.filterIsInstance<LiveMonitorOverRow.Waiting>().sortedBy { it.timeLabel }
            assertEquals("expected exactly two Waiting rows", 2, waiting.size)
            assertEquals("the earlier Waiting row has nothing ahead of it", 0, waiting[0].aheadCount)
            assertEquals(
                "the later Waiting row must carry a real, non-zero queue position",
                1,
                waiting[1].aheadCount,
            )
        }

    @Test
    fun `R_1007 overnight-live-monitor seeds a Resolved-confirmed row and a Resolved-inferred row linked to it`() =
        runTest {
            val result = Scenarios.load(context, "overnight-live-monitor")
            val overs = LiveMonitorOversPolling.current(context, result.primarySessionId).overs
            val resolved = overs.filterIsInstance<LiveMonitorOverRow.Resolved>()

            val confirmed = resolved.first { it.attribution.state == AttributionState.CONFIRMED }
            assertEquals("W7NPC", confirmed.callsign)

            val inferred = resolved.first { it.attribution.state == AttributionState.INFERRED }
            assertEquals("K7LWH", inferred.callsign)
            assertNotNull(
                "an INFERRED row whose source transmission is still in this session's own list " +
                    "must carry a real 'inferred from' label, never a guessed one",
                inferred.inferredFromLabel,
            )
        }

    @Test
    fun `R_1007 overnight-live-monitor seeds a Resolved-unknown row`() = runTest {
        val result = Scenarios.load(context, "overnight-live-monitor")
        val overs = LiveMonitorOversPolling.current(context, result.primarySessionId).overs
        val resolved = overs.filterIsInstance<LiveMonitorOverRow.Resolved>()

        assertTrue(
            "expected a Resolved row carrying UNKNOWN attribution",
            resolved.any { it.attribution.state == AttributionState.UNKNOWN },
        )
    }

    @Test
    fun `R_1007 overnight-live-monitor derives exactly one listened-to silence row, never seeded as its own row`() =
        runTest {
            val result = Scenarios.load(context, "overnight-live-monitor")
            val overs = LiveMonitorOversPolling.current(context, result.primarySessionId).overs

            val silences = overs.filterIsInstance<LiveMonitorOverRow.ListenedSilence>()
            assertEquals(
                "expected exactly one derived listened-to-silence row (FR-RUN-12) — every other " +
                    "adjacent pair in this fixture is spaced under the one-minute floor",
                1,
                silences.size,
            )
        }

    @Test
    fun `R_1007 overnight-live-monitor seeds a NotTranscribed row with a real WorkQueueItemEntity attempt count`() =
        runTest {
            val result = Scenarios.load(context, "overnight-live-monitor")
            val overs = LiveMonitorOversPolling.current(context, result.primarySessionId).overs

            val failed = overs.filterIsInstance<LiveMonitorOverRow.NotTranscribed>()
            assertEquals("expected exactly one NotTranscribed row", 1, failed.size)
            assertEquals(
                "the real WorkQueueItemEntity.attemptCount seeded for this over, never a guessed count",
                "Pass B errored 2 times",
                failed.single().attemptsLabel,
            )
        }

    @Test
    fun `R_1007 overnight-live-monitor's seven states are all distinguishable at once, in one load`() = runTest {
        val result = Scenarios.load(context, "overnight-live-monitor")
        val overs = LiveMonitorOversPolling.current(context, result.primarySessionId).overs

        assertEquals(1, overs.filterIsInstance<LiveMonitorOverRow.Transcribing>().size)
        assertEquals(2, overs.filterIsInstance<LiveMonitorOverRow.Waiting>().size)
        assertEquals(1, overs.filterIsInstance<LiveMonitorOverRow.NotTranscribed>().size)
        assertEquals(1, overs.filterIsInstance<LiveMonitorOverRow.ListenedSilence>().size)
        val resolved = overs.filterIsInstance<LiveMonitorOverRow.Resolved>()
        assertEquals(3, resolved.size)
        assertEquals(
            setOf(AttributionState.CONFIRMED, AttributionState.INFERRED, AttributionState.UNKNOWN),
            resolved.map { it.attribution.state }.toSet(),
        )
    }

    // --- R-1024 (register, `overnight-live-monitor` seeds no level samples) ---------------------

    @Test
    fun `R_1024 overnight-live-monitor seeds a real Measured level reading, not the honest not-measured default`() =
        runTest {
            Scenarios.load(context, "overnight-live-monitor")

            assertTrue(
                "expected a real LevelStatus.State.Measured reading so N07's level card renders the " +
                    "running envelope instead of its own honest \"No level signal yet this session\" " +
                    "fallback — that fallback is correct behaviour only for genuinely absent data",
                LevelStatus.state is LevelStatus.State.Measured,
            )
        }

    /**
     * R-1024's own trap, named in the register: `setup-level`'s own
     * `speechShapedPeakHistoryDbfs()` centers its two lobes at indices 50/57 because `SetupStep
     * .LEVEL`'s meter only ever reads the last 15 samples of the 60 — everywhere else in the array
     * is invisible to that screen by construction. `LiveMonitorScreen.kt`'s own `LevelEnvelopeChart`
     * has no such window: it iterates [LevelStatus.peakHistoryDbfs] in full. Reusing the setup-level
     * shape unchanged here would seed a real [LevelStatus.State.Measured] reading (passing the test
     * above) whose first 45 entries are flat at the noise floor — genuinely broken for *this*
     * screen even though it is exactly right for S07. This test reads the leading part of the
     * array (what N07 renders first) and fails if it is flat, which the naive reuse would be.
     */
    @Test
    fun `R_1024 the seeded envelope carries real variation, not only a tail bump the way S07's own shape would`() =
        runTest {
            Scenarios.load(context, "overnight-live-monitor")
            val history = LevelStatus.peakHistoryDbfs

            assertEquals(
                "Level-Meter.dc.html's own up-to-60-sample history — see LevelStatus.peakHistoryDbfs's kdoc",
                60,
                history.size,
            )
            val distinctReadings = history.map { kotlin.math.round(it) }.toSet()
            assertTrue(
                "expected real, varying values across the seeded history, not a flat reading or a " +
                    "mechanically-alternating cycle (R-944's own finding for setup-level's earlier bug) " +
                    "— got only $distinctReadings",
                distinctReadings.size > 5,
            )

            // The leading portion is exactly what a two-lobe shape centered near the end (correct
            // for S07, wrong here — see this test's own kdoc) would leave flat at the noise floor.
            val leading = history.take(20)
            val leadingSpread = leading.max() - leading.min()
            assertTrue(
                "expected the leading part of the history (indices 0..19, which this screen's own " +
                    "LevelEnvelopeChart renders first since it never truncates the array) to carry " +
                    "real level variation, got a spread of only ${leadingSpread}dB across $leading",
                leadingSpread > 10f,
            )
        }
}
