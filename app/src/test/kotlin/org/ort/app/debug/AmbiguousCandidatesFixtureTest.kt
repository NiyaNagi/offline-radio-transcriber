package org.ort.app.debug

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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
 * R-184 (halt): split out of `ScenariosTest.kt` (detekt's `LargeClass` finding, same reason and
 * same split pattern `FieldTier1AudioTest.kt` already established for the same round's other
 * fixture fix). WP6's own commit a9c24c6 verified the correction sheet's Tier A candidate
 * derivation by test; the "zero rows" symptom seen on a real device was every scenario's fixture
 * seeding at most one resolver candidate per over — `CorrectionSheet`'s Tier A list
 * (`MainTier`'s own `others = candidates.filterNot { it.callsign == currentCallsign }`) filters out
 * a single-candidate row whenever that candidate *is* the over's own current callsign, leaving
 * nothing to show. This proves both `overnight` and `stations-14-nights` now carry an AMBIGUOUS
 * over (no current callsign, so nothing gets filtered) with three ranked, distinctly-scored
 * candidates — real rows for `Detail-Correct-A.dc.html`'s own board to render.
 */
@RunWith(RobolectricTestRunner::class)
class AmbiguousCandidatesFixtureTest {

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
    @Requirement("R-184")
    fun `R_184 overnight's AMBIGUOUS over carries three ranked, distinctly-scored Tier A candidates`() = runTest {
        val result = Scenarios.load(context, "overnight")
        val sessionId = requireNotNull(result.primarySessionId)
        val ambiguous = db.transmissionDao().listBySession(sessionId)
            .single { it.attributionState == AttributionState.AMBIGUOUS }
        val candidates = db.catalogDao().candidatesFor(ambiguous.id)

        assertEquals(3, candidates.size)
        assertEquals(setOf("KE7QRS", "KE7QRF", "KE7QRZ"), candidates.map { it.callsign }.toSet())
        assertEquals("expected three distinct scores", 3, candidates.map { it.score }.toSet().size)
        assertTrue("no candidate should be pre-selected on an AMBIGUOUS over", candidates.none { it.selected })
    }

    @Test
    @Requirement("R-184")
    fun `R_184 stations-14-nights also carries an AMBIGUOUS over with three ranked candidates`() = runTest {
        val result = Scenarios.load(context, "stations-14-nights")
        val sessionId = requireNotNull(result.primarySessionId)
        val ambiguous = db.transmissionDao().listBySession(sessionId)
            .single { it.attributionState == AttributionState.AMBIGUOUS }
        val candidates = db.catalogDao().candidatesFor(ambiguous.id)

        assertEquals(3, candidates.size)
        assertEquals(3, candidates.map { it.score }.toSet().size)
    }
}
