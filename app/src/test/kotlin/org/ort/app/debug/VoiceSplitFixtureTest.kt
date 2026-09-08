package org.ort.app.debug

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.StationPolling
import org.ort.app.ui.failures.DebugFailureOverride
import org.ort.app.ui.setup.SharedPreferencesSetupStore
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
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * R-272 (halt): `stations-14-nights`' two-voiceprint `WA7HJR` fixture, split out of
 * `ScenariosTest.kt` (detekt's `LargeClass` finding, same reason and same split pattern
 * `FieldTier1AudioTest.kt`/`AmbiguousCandidatesFixtureTest.kt` already established for this
 * round's other fixture fixes). WP8's own `StationPolling.voiceSplitCandidates` derivation and
 * empty-state handling were already fixed and verified by test (register R-272, d39c056); the
 * "unverifiable on device" symptom the V5 pass 3 report named was that no scenario ever seeds a
 * real [org.ort.data.entity.VoiceprintEntity] row at all, so the screen's *multi-voice* branch
 * (`Fail-Cluster.dc.html`/`Station-Identity.dc.html`'s own "two clusters" board) had nothing real
 * to render on the emulator. This proves `WA7HJR` now carries two bound voiceprint clusters, each
 * with a real, non-zero, honestly-tallied `memberCount`, and that its own overs are actually split
 * between the two `voiceprintId`s those counts describe.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceSplitFixtureTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: OrtDatabase

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    @After
    fun resetProcessWideAvailability() {
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
    @Requirement("R-272")
    fun `R_272 stations-14-nights gives WA7HJR two real voiceprint clusters with distinct ids`() = runTest {
        Scenarios.load(context, "stations-14-nights")

        val voiceprints = db.catalogDao().voiceprintsForStation("WA7HJR")

        assertEquals(2, voiceprints.size)
        assertEquals(setOf("voiceprint-wa7hjr-a", "voiceprint-wa7hjr-b"), voiceprints.map { it.id }.toSet())
        voiceprints.forEach { voiceprint ->
            assertEquals("WA7HJR", voiceprint.boundStationId)
            assertTrue("expected a real, non-zero memberCount for ${voiceprint.id}", voiceprint.memberCount > 0)
        }
    }

    @Test
    @Requirement("R-272")
    fun `R_272 each voiceprint's memberCount matches the real overs bound to it, not a fabricated count`() = runTest {
        Scenarios.load(context, "stations-14-nights")

        val voiceprints = db.catalogDao().voiceprintsForStation("WA7HJR")
        val overs = db.activityDao().transmissionsForStation("WA7HJR")

        voiceprints.forEach { voiceprint ->
            val boundOvers = overs.count { it.voiceprintId == voiceprint.id }
            assertEquals(
                "memberCount for ${voiceprint.id} must match its own real bound overs",
                voiceprint.memberCount,
                boundOvers,
            )
        }
    }

    @Test
    @Requirement("R-272")
    fun `R_272 voiceSplitCandidates reaches the larger cluster's own overs, not an empty state`() = runTest {
        Scenarios.load(context, "stations-14-nights")

        val split = StationPolling.voiceSplitCandidates(context, "WA7HJR")

        assertTrue("expected a real split candidate, not the empty-cluster null", split != null)
        assertTrue("expected at least one over to split", split!!.overs.isNotEmpty())
    }
}
