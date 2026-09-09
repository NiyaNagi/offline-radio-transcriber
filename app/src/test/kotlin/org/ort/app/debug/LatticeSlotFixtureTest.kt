package org.ort.app.debug

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * R-421 (schema v5, register — split from `ScenariosTest.kt`, the same `LargeClass` split pattern
 * `AmbiguousCandidatesFixtureTest.kt`/`VoiceSplitFixtureTest.kt` already established): before this
 * round, no scenario ever wrote a single [org.ort.data.entity.LatticeSlotEntity] row, so
 * `Detail-Confirmed.dc.html`/`Detail-Ambiguous.dc.html`'s transcript highlight
 * ([org.ort.app.ui.data.CorrectionPolling.winningCharSpan]) had nothing to render but its own
 * literal-substring fallback — which can never match a phonetically spelled callsign ("kilo echo
 * seven quebec romeo sierra"). `overnight`'s CONFIRMED (tx1) and INFERRED (tx6, the corrected over)
 * overs, and `stations-14-nights`' mirrored AMBIGUOUS over, now carry real, found-not-guessed char
 * spans (`ScenarioFixtures.latticeSlots`) for their real, already-selected candidate — see that
 * function's own kdoc for why each span is located inside the transcript text rather than
 * hand-computed. The AMBIGUOUS over itself (`overnight`'s own tx3, and `stations-14-nights`' own
 * mirror) deliberately stays unselected — `AmbiguousCandidatesFixtureTest`'s own
 * `no candidate should be pre-selected on an AMBIGUOUS over` already establishes why an AMBIGUOUS
 * over has no winner to highlight inline; its slot rows still feed D05's own `slotDetailsFor` (every
 * candidate, register R-320), just never `winningCandidateCharSpan`.
 */
@RunWith(RobolectricTestRunner::class)
class LatticeSlotFixtureTest {

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
    @Requirement("R-421")
    fun `R_421 overnight's CONFIRMED over carries a real winning char span into its own transcript`() = runTest {
        val result = Scenarios.load(context, "overnight")
        val sessionId = requireNotNull(result.primarySessionId)
        val confirmed = db.transmissionDao().listBySession(sessionId)
            .first { it.attributionState == AttributionState.CONFIRMED && it.stationId == "W7NPC" }
        val transcript = db.transcriptDao().getCurrent(confirmed.id)!!.text

        val span = db.catalogDao().winningCandidateCharSpan(confirmed.id)

        assertTrue("expected a real span, not both bounds null", span.spanStart != null && span.spanEnd != null)
        val highlighted = transcript.substring(span.spanStart!!, span.spanEnd!!)
        assertEquals("whiskey seven november papa charlie", highlighted)
    }

    @Test
    @Requirement("R-421")
    fun `R_421 overnight's corrected INFERRED over also carries a real winning char span`() = runTest {
        val result = Scenarios.load(context, "overnight")
        val sessionId = requireNotNull(result.primarySessionId)
        val correctedInferred = db.transmissionDao().listBySession(sessionId)
            .first { it.attributionState == AttributionState.INFERRED && it.corrected }
        val transcript = db.transcriptDao().getCurrent(correctedInferred.id)!!.text

        val span = db.catalogDao().winningCandidateCharSpan(correctedInferred.id)

        assertTrue(span.spanStart != null && span.spanEnd != null)
        val highlighted = transcript.substring(span.spanStart!!, span.spanEnd!!)
        assertEquals("kilo juliet seven alpha bravo charlie", highlighted)
    }

    @Test
    @Requirement("R-421")
    fun `R_421 overnight's AMBIGUOUS over has real per-candidate slots but honestly no winning span`() = runTest {
        val result = Scenarios.load(context, "overnight")
        val sessionId = requireNotNull(result.primarySessionId)
        val ambiguous = db.transmissionDao().listBySession(sessionId)
            .single { it.attributionState == AttributionState.AMBIGUOUS }

        val slots = db.catalogDao().slotDetailsFor(ambiguous.id)
        val span = db.catalogDao().winningCandidateCharSpan(ambiguous.id)

        assertTrue("expected the top candidate's own 6 anchored slots", slots.count { it.charStart != null } == 6)
        assertNull("an AMBIGUOUS over has no selected candidate to highlight", span.spanStart)
        assertNull(span.spanEnd)
    }

    @Test
    @Requirement("R-421")
    fun `R_421 stations-14-nights' mirrored AMBIGUOUS over also carries real per-candidate slots`() = runTest {
        val result = Scenarios.load(context, "stations-14-nights")
        val sessionId = requireNotNull(result.primarySessionId)
        val ambiguous = db.transmissionDao().listBySession(sessionId)
            .single { it.attributionState == AttributionState.AMBIGUOUS }

        val slots = db.catalogDao().slotDetailsFor(ambiguous.id)

        assertTrue(slots.count { it.charStart != null } == 6)
    }

    @Test
    @Requirement("R-419")
    fun `R_419 level-low now seeds one real over so Weakest over resolved tonight has a value`() = runTest {
        val result = Scenarios.load(context, "level-low")
        val sessionId = requireNotNull(result.primarySessionId)

        val weakest = db.transmissionDao().listBySession(sessionId).mapNotNull { it.signalStrength }.minOrNull()

        assertTrue(
            "expected level-low to seed at least one transmission with a real signalStrength",
            weakest != null,
        )
    }

    @Test
    @Requirement("R-419")
    fun `R_419 level-clip publishes a real, non-zero session-lifetime clip total`() = runTest {
        Scenarios.load(context, "level-clip")

        assertEquals(340L, LevelStatus.clippedSamplesThisSession)
    }

    @Test
    @Requirement("R-419")
    fun `R_419 level-low honestly publishes zero clipped samples this session`() = runTest {
        Scenarios.load(context, "level-low")

        assertEquals(0L, LevelStatus.clippedSamplesThisSession)
    }

    @Test
    @Requirement("R-462")
    fun `R_462 level-low seeds a real verified input so the meter subtitle carries its own device name`() = runTest {
        Scenarios.load(context, "level-low")

        val input = InputStatus.state
        assertTrue("expected a real, verified input, not None", input is InputStatus.State.Opened)
        assertEquals("USB Audio Device", (input as InputStatus.State.Opened).descriptor.label)
    }

    @Test
    @Requirement("R-462")
    fun `R_462 level-clip also seeds the same real verified input`() = runTest {
        Scenarios.load(context, "level-clip")

        val input = InputStatus.state
        assertTrue("expected a real, verified input, not None", input is InputStatus.State.Opened)
        assertEquals("USB Audio Device", (input as InputStatus.State.Opened).descriptor.label)
    }
}
