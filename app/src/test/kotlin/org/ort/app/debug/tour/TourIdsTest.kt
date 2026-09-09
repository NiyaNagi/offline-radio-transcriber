package org.ort.app.debug.tour

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.debug.Scenarios
import org.ort.app.ui.failures.DebugFailureOverride
import org.ort.app.ui.setup.SharedPreferencesSetupStore
import org.ort.core.AttributionState
import org.ort.data.OrtDatabase
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.pipeline.capture.VadAvailability
import org.robolectric.RobolectricTestRunner

/**
 * spec/ui-conformance-plan.md WP12 v2: proves [TourIds.resolveSeed] resolves every symbolic
 * `drillIn` value against a *real*, just-loaded scenario's data — the same [org.ort.app.debug.Scenarios.load]
 * `tour.json`'s own drill-in steps run against — never a fixture id copied out of the database by
 * hand, which the coordinator's own brief named as exactly the thing to avoid.
 */
@RunWith(RobolectricTestRunner::class)
class TourIdsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun resetProcessWideState() {
        AsrAvailability.reset()
        VadAvailability.reset()
        CaptureState.idle(clearSession = true)
        ShedStatus.reset()
        ThermalStatus.reset()
        RigStatus.reset()
        StorageForecast.reset()
        LevelStatus.reset()
        InputStatus.reset()
        DebugFailureOverride.clear()
        context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `R_TOUR_IDS_EMPTY_IS_NULL an empty drillIn map resolves to no seed at all`() = runTest {
        val seed = TourIds.resolveSeed(context, sessionId = null, drillIn = emptyMap())
        assertNull(seed)
    }

    @Test
    fun `R_TOUR_IDS_TRANSMISSION_STATES each symbolic transmission value resolves to a real row of that state`() =
        runTest {
            val result = Scenarios.load(context, "overnight")
            val sessionId = requireNotNull(result.primarySessionId)
            val db = OrtDatabase.create(context)

            for ((symbol, state) in listOf(
                "confirmed" to AttributionState.CONFIRMED,
                "inferred" to AttributionState.INFERRED,
                "ambiguous" to AttributionState.AMBIGUOUS,
                "unknown" to AttributionState.UNKNOWN,
            )) {
                val seed = TourIds.resolveSeed(context, sessionId, mapOf("transmission" to symbol))
                val transmissionId = requireNotNull(seed?.openTransmissionId) { "no id resolved for '$symbol'" }
                val row = db.transmissionDao().getById(transmissionId)
                assertEquals("'$symbol' should resolve to a real $state row", state, row?.attributionState)
            }
        }

    @Test
    fun `R_TOUR_IDS_TRANSMISSION_LITERAL_FALLBACK an unrecognised symbol is passed through as a literal id`() =
        runTest {
            val seed = TourIds.resolveSeed(context, sessionId = null, mapOf("transmission" to "some-real-id"))
            assertEquals("some-real-id", seed?.openTransmissionId)
        }

    @Test
    fun `R_TOUR_IDS_STATION_CALLSIGN a callsign resolves to that station's real database id`() = runTest {
        Scenarios.load(context, "stations-14-nights")
        val db = OrtDatabase.create(context)
        val expectedId = db.activityDao().listStations().first { it.callsign == "WA7HJR" }.id

        val seed = TourIds.resolveSeed(context, sessionId = null, mapOf("station" to "WA7HJR"))

        assertEquals(expectedId, seed?.openStationId)
    }

    @Test
    fun `R_TOUR_IDS_THREAD_ANY resolves to a real transmission's own threadId`() = runTest {
        val result = Scenarios.load(context, "overnight")
        val sessionId = requireNotNull(result.primarySessionId)
        val db = OrtDatabase.create(context)

        val seed = TourIds.resolveSeed(context, sessionId, mapOf("thread" to "any"))

        val threadId = requireNotNull(seed?.openThreadId)
        val matching = db.transmissionDao().listBySession(sessionId).filter { it.threadId == threadId }
        assertTrue("resolved threadId should belong to at least one real transmission", matching.isNotEmpty())
    }

    @Test
    fun `R_TOUR_IDS_FREQUENCY_LITERAL a Hz string parses directly, no lookup needed`() = runTest {
        val seed = TourIds.resolveSeed(context, sessionId = null, mapOf("frequency" to "145230000"))
        assertEquals(145230000L, seed?.openFrequencyHz)
    }

    @Test
    fun `R_TOUR_IDS_LOG_FILTER assembles frequency and time range into one LogFilterSelection`() = runTest {
        val seed = TourIds.resolveSeed(
            context,
            sessionId = null,
            mapOf("logFilterFrequency" to "145230000", "logFilterFromMillis" to "1000", "logFilterToMillis" to "2000"),
        )
        assertEquals(145230000L, seed?.pendingLogFilter?.frequencyHz)
        assertEquals(1000L, seed?.pendingLogFilter?.fromMillis)
        assertEquals(2000L, seed?.pendingLogFilter?.toMillis)
    }

    @Test
    fun `R_TOUR_IDS_REVIEW_SESSION_SELF resolves to the step's own just-loaded session id`() = runTest {
        val seed = TourIds.resolveSeed(context, sessionId = "scenario-overnight", mapOf("reviewSession" to "self"))
        assertEquals("scenario-overnight", seed?.pendingReviewSessionId)
    }

    @Test
    fun `R_TOUR_IDS_CAPTURE_LEVEL_METER_TRUE parses the literal true string to a real Boolean`() = runTest {
        val seed = TourIds.resolveSeed(context, sessionId = null, mapOf("captureLevelMeter" to "true"))
        assertEquals(true, seed?.openCaptureLevelMeter)
    }

    @Test
    fun `R_TOUR_IDS_UNRESOLVABLE_STATE throws a clear error rather than a silent empty seed`() = runTest {
        // "empty" seeds no transmissions at all, so every attribution-state lookup must fail loudly
        // regardless of which session id is passed - the query itself returns no rows either way.
        Scenarios.load(context, "empty")
        try {
            TourIds.resolveSeed(context, sessionId = "no-such-session", mapOf("transmission" to "confirmed"))
            fail("expected an exception when no CONFIRMED transmission exists")
        } catch (expected: IllegalArgumentException) {
            assertNotNull(expected.message)
        }
    }
}
