package org.ort.app.debug

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.CorrectionPolling
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
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * R-771 (register, confirmation sweep): split out of `ScenariosTest.kt`, the same reason and split
 * pattern `AmbiguousCandidatesFixtureTest.kt`/`FieldTier1AudioTest.kt` already established. While
 * fixing R-720, WP6 found `TransmissionDetailScreen`'s D01/D03 own inline lattice-slot grid had a
 * real fixture to render against (`OvernightScenario.kt`'s `tx1`/`tx3` both call
 * `ScenarioFixtures.latticeSlots(...)`) — but `tx2`, the scenario's own INFERRED over, never did.
 * D02 alone fell back to the honest "not recorded yet" line, and the board's own inferred case
 * (`Detail-Inferred.dc.html`'s per-slot grid) was never exercised by a capture. `tx2`'s own real
 * transcript ("roger that, good copy on the repeater this morning") never spells its winning
 * callsign (K7LWH) phonetically — the attribution comes from a voice match, not from what was
 * said — so `OvernightScenario.kt` now seeds `tx2`'s slots directly (not via
 * `ScenarioFixtures.latticeSlots(...)`, which requires each unit's word to be a real transcript
 * substring): real, ordered, per-unit scores with no char span, exactly the shape
 * [org.ort.data.entity.LatticeSlotEntity]'s own doc comment gives an acoustic-sourced lattice's
 * slots — deliberately weaker than `tx1`'s confirmed run, never a copy of it.
 */
@RunWith(RobolectricTestRunner::class)
class InferredLatticeFixtureTest {

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
    @Requirement("R-771")
    fun `R_771 overnight's INFERRED over (D02) carries a real, non-empty winning lattice`() = runTest {
        val result = Scenarios.load(context, "overnight")
        val sessionId = requireNotNull(result.primarySessionId)
        // `OvernightScenario.kt`'s own readable id for its case 2 (the D02 over this row is about)
        // — not derived by filtering `listBySession` for `INFERRED`, which the scenario's own later
        // generated overs (case 5 onward) also produce more than one of.
        val inferredId = "$sessionId-tx02"
        val inferred = requireNotNull(db.transmissionDao().getById(inferredId)) {
            "expected OvernightScenario's own tx2 ($inferredId) to exist"
        }
        assertTrue(
            "expected tx2 to still be the scenario's own INFERRED D02 case",
            inferred.attributionState == AttributionState.INFERRED,
        )

        val inspection = CorrectionPolling.inspectionWithSlots(context, inferred.id)
        val winning = inspection.candidates.firstOrNull { it.selected } ?: inspection.candidates.firstOrNull()

        assertTrue("expected a winning candidate on the INFERRED over", winning != null)
        assertFalse(
            "expected D02's own winning lattice slots to be real and non-empty, not the pre-R-771 " +
                "fixture gap (an INFERRED over with no slots at all)",
            winning!!.slots.isEmpty(),
        )
        // The scores are real but deliberately weaker than a CONFIRMED over's own run — never a
        // fabricated copy of `tx1`'s stronger lattice standing in for a voice-matched attribution.
        assertTrue(
            "expected every real slot score to stay within a real, ordered [0.5, 1.0] range",
            winning.slots.all { it.score in 0.5..1.0 },
        )
    }
}
