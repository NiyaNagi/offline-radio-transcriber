package org.ort.app.debug

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
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
 * `ScenariosTest.kt` split — detekt's `LargeClass` finding, the same fix `RowsTest.kt`'s own
 * `NavRowTest.kt` split (and this package's own `FailureOverrideScenariosTest.kt`) already
 * establishes as house style: moved into its own file rather than suppressed, `@Before`/`@After`
 * duplicated from `ScenariosTest` rather than shared, since this class's own single test sweeps
 * every declared scenario (including the live ones that publish process-wide capture facets) the
 * same way `ScenariosTest`'s own `R_110` sweeps do.
 *
 * R-1071 (register, WPDETAILRES): a real CONFIRMED attribution never reaches `:data` without a
 * resolver row alongside it — [org.ort.pipeline.passb.CallsignResolver.resolve] never returns
 * `CONFIRMED` unless the same call also produced a non-empty ranked candidate list, and
 * [org.ort.pipeline.passb.DataPassBResultSink.record] persists that lattice/candidate pair in the
 * same transaction as the attribution write (confirmed by reading both classes directly — see
 * [ScenarioFixtures.seedConfirmedResolverOutput]'s own doc comment). Before this fix, most of this
 * simulator's own scenarios seeded a CONFIRMED-with-confidence transmission with no lattice/
 * candidate row at all — a shape production cannot produce, which is exactly what let
 * `Detail-Why.dc.html`'s own "No resolver output recorded for this transmission yet." directly
 * contradict `Detail.dc.html`'s own header sentence above it, "Heard in this over. Resolved from
 * the phonetics at 0.95." (capture `audio-removed-by-operator/D06-audio-removed.png`).
 */
@RunWith(RobolectricTestRunner::class)
class ResolverOutputScenariosTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: OrtDatabase

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    /** See `ScenariosTest.closeDatabase`'s own doc comment for why this matters across many loads. */
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
        DebugBundledAssetSourceOverride.clear()
        context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    /**
     * This sweeps every declared scenario, not only the one the capture named, since the same gap
     * was present in most of them (`Scenarios.kt`, `OvernightScenario.kt`, `StationsFixtures.kt`
     * and `FrequencyChangeFixtures.kt` all needed the identical fix).
     *
     * **INFERRED is deliberately not checked here.** A real INFERRED attribution is a voice match
     * or a propagated/human correction, never a phonetic resolution
     * ([org.ort.pipeline.passb.CallsignResolver]'s own kdoc: "Only CONFIRMED, AMBIGUOUS and UNKNOWN
     * are reachable from here — never INFERRED"), and its own header sentence never claims one
     * either ([org.ort.app.ui.data.DetailViewStateMapper.bodyFor]'s INFERRED branch reads "Matched
     * by voice ..."/"The operator corrected this", never "Resolved from the phonetics"). A real
     * INFERRED-with-confidence row legitimately carries no resolver row of its own — the row that
     * matters, if any, belongs to a *different* transmission (its
     * `attributionSourceTransmissionId`) — so asserting this for INFERRED would demand this fixture
     * suite fabricate resolver output production itself never writes (constitution I).
     */
    @Test
    @Requirement("R-1071")
    fun `R_1071 every CONFIRMED attribution with a confidence carries a selected resolver candidate`() = runTest {
        DebugBundledAssetSourceOverride.override = TinyFixtureBundledAssetSource(context.filesDir)
        try {
            Scenarios.NAMES.forEach { name ->
                Scenarios.load(context, name)
                db.sessionDao().listAll()
                    .flatMap { session -> db.transmissionDao().listBySession(session.id) }
                    .filter { it.attributionState == AttributionState.CONFIRMED && it.attributionConfidence != null }
                    .forEach { tx ->
                        assertTrue(
                            "scenario '$name': ${tx.id} is CONFIRMED at ${tx.attributionConfidence} with no " +
                                "selected resolver candidate",
                            db.catalogDao().candidatesFor(tx.id).any { it.selected },
                        )
                    }
            }
        } finally {
            DebugBundledAssetSourceOverride.clear()
        }
    }
}
