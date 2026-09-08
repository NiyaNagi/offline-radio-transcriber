package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.components.LiveBarTone
import org.ort.data.OrtDatabase
import org.ort.data.entity.StationEntity
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * R-010/R-022 (ui-conformance-plan WP3): the drawer's Stations/Frequencies counts, and this
 * package's own fallback [org.ort.app.ui.components.LiveBarViewState] builder — see
 * [DrawerCounts]'s own doc comment for why the latter exists at all (`ui/data/LiveBarPolling.kt`,
 * WP4's real read path, is not on this branch — confirmed by search before writing this file).
 */
@RunWith(RobolectricTestRunner::class)
class DrawerCountsTest {

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    @After
    fun resetProcessWideHolders() {
        CaptureState.idle(clearSession = true)
        ThermalStatus.reset()
        StorageForecast.reset()
    }

    private fun station(id: String) = StationEntity(
        id = id,
        callsign = id,
        firstHeardAt = 0L,
        lastHeardAt = 0L,
        transmissionCount = 1,
        isUserPinned = false,
        notes = null,
        userName = null,
        frequenciesHeard = null,
        activityByHourDow = null,
        potaRefs = null,
        spokenGrids = null,
        ituRegionFromPrefix = null,
        overCountsByAttributionState = null,
    )

    @Test
    @Requirement("R-010")
    fun `current reads the real station count from the station table`(): Unit = runTest {
        db.catalogDao().insert(station("W7NPC"))
        db.catalogDao().insert(station("K7LWH"))

        val counts = DrawerCounts.current(context)

        assertEquals(2, counts.stationCount)
    }

    @Test
    @Requirement("R-010")
    fun `current reports zero, never a fabricated count, with nothing recorded yet`(): Unit = runTest {
        val counts = DrawerCounts.current(context)

        assertEquals(0, counts.stationCount)
        assertEquals(0, counts.frequencyCount)
    }

    @Test
    @Requirement("R-022")
    fun `liveBar is null with no session id`() {
        assertNull(DrawerCounts.liveBar(sessionId = null))
    }

    @Test
    @Requirement("R-022")
    fun `liveBar is null when CaptureState belongs to a different session`() {
        CaptureState.capturing(sessionId = "other-session")

        assertNull(DrawerCounts.liveBar(sessionId = "this-session"))
    }

    @Test
    @Requirement("R-022")
    fun `liveBar is nominal while capturing with nothing degraded`() {
        CaptureState.capturing(sessionId = "S1")

        val state = DrawerCounts.liveBar(sessionId = "S1")

        assertEquals(LiveBarTone.NOMINAL, state?.tone)
        assertEquals("Live", state?.label)
    }

    @Test
    @Requirement("R-022")
    fun `liveBar degrades, never halts, on thermal Hot`() {
        CaptureState.capturing(sessionId = "S1")
        ThermalStatus.update(osThermalStatus = ThermalStatus.THERMAL_STATUS_SEVERE, realTimeFactor = 1.5)

        val state = DrawerCounts.liveBar(sessionId = "S1")

        assertEquals(LiveBarTone.DEGRADED, state?.tone)
    }

    @Test
    @Requirement("R-022")
    fun `liveBar is halted, the one red tone, when capture has failed`() {
        CaptureState.capturing(sessionId = "S1")
        CaptureState.failed("storage exhausted")

        val state = DrawerCounts.liveBar(sessionId = "S1")

        assertEquals(LiveBarTone.HALTED, state?.tone)
    }

    @Test
    @Requirement("R-022")
    fun `liveBar never invents a level meter or a partial transcript`() {
        CaptureState.capturing(sessionId = "S1")

        val state = DrawerCounts.liveBar(sessionId = "S1")

        assertEquals(emptyList<Float>(), state?.level)
        assertNull(state?.partialText)
    }
}
