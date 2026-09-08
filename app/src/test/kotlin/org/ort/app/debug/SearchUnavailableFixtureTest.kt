package org.ort.app.debug

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.SearchFacetFilter
import org.ort.app.ui.data.SearchPolling
import org.ort.app.ui.data.SearchQueryParams
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
 * Coordinator-reported (round-eleven tooling): `search-unavailable`'s fixture, split out as its
 * own file from the start (the same pattern this round's other fixture-only additions already
 * established: `FieldTier1AudioTest.kt`/`AmbiguousCandidatesFixtureTest.kt`/
 * `VoiceSplitFixtureTest.kt`). See `Scenarios.kt`'s own `searchUnavailable` kdoc, and
 * `SearchViewData.kt`'s own `DebugSearchOverride` kdoc (WP7's file), for the full defect history —
 * including why a real SQL-level break of `transcript_fts` was tried first and rejected as
 * catastrophic (it broke `OrtDatabase.create()` app-wide, not just Search), and why this scenario
 * instead uses the same debug-override-receiver pattern `DebugFailureOverride` already established.
 * Every case here runs against real `BundledSQLiteDriver` SQLite (`OrtDatabase.create`'s own
 * contract — the same driver production and every Robolectric test share), so
 * `a real free-text search genuinely degrades` is not a simulation of the UI layer alone: it proves
 * [SearchPolling.search]'s own real code path (the same `buildResult`/`textSearchUnavailable = true`
 * branch the genuine fts5-missing exception handler also uses) actually returns the degraded shape.
 */
@RunWith(RobolectricTestRunner::class)
class SearchUnavailableFixtureTest {

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

    private fun everyState() = SearchFacetFilter(
        attributionStates = AttributionState.entries.toSet(),
        includeRejected = true,
        includeCorrected = true,
    )

    @Test
    @Requirement("R-110")
    fun `search-unavailable seeds the same real corpus search-corpus does`() = runTest {
        val corpusResult = Scenarios.load(context, "search-corpus")
        val unavailableResult = Scenarios.load(context, "search-unavailable")

        assertTrue(
            "expected search-unavailable to seed the same real corpus (transmissionCount) as " +
                "search-corpus (${corpusResult.transmissionCount}); got " +
                "${unavailableResult.transmissionCount}",
            unavailableResult.transmissionCount == corpusResult.transmissionCount,
        )
        assertTrue(unavailableResult.sessionCount > 0)
    }

    @Test
    fun `a real free-text search genuinely degrades right after search-unavailable loads`() = runTest {
        Scenarios.load(context, "search-unavailable")

        val result = SearchPolling.search(context, SearchQueryParams(text = "park"), everyState())

        assertTrue(
            "expected SearchPolling.search's own real degraded-result shape " +
                "(textSearchUnavailable = true) right after search-unavailable loads",
            result.textSearchUnavailable,
        )
    }

    @Test
    fun `Retry genuinely recovers to real results on the second free-text search`() = runTest {
        Scenarios.load(context, "search-unavailable")

        val first = SearchPolling.search(context, SearchQueryParams(text = "park"), everyState())
        val retry = SearchPolling.search(context, SearchQueryParams(text = "park"), everyState())

        assertTrue("expected the first search to be the forced degrade", first.textSearchUnavailable)
        assertTrue("expected Retry's own second search to genuinely recover", !retry.textSearchUnavailable)
        assertTrue("expected Retry to actually find the real corpus rows", retry.details.isNotEmpty())
    }

    @Test
    fun `a filters-only search is unaffected, since it never depends on transcript_fts at all`() = runTest {
        Scenarios.load(context, "search-unavailable")

        val result = SearchPolling.search(context, SearchQueryParams(text = null), everyState())

        assertTrue(!result.textSearchUnavailable)
        assertTrue(
            "expected the filters-only (no free text) search to still find real rows",
            result.details.isNotEmpty(),
        )
    }

    @Test
    fun `loading a later scenario clears the pending override so it never leaks`() = runTest {
        Scenarios.load(context, "search-unavailable")
        Scenarios.load(context, "search-corpus")

        val result = SearchPolling.search(context, SearchQueryParams(text = "park"), everyState())

        assertTrue(
            "expected loading search-corpus after search-unavailable to clear the pending " +
                "override (resetProcessWideFacets, the same cross-contamination guard every " +
                "process-wide facet already gets)",
            !result.textSearchUnavailable,
        )
    }
}
