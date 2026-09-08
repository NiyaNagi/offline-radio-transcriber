package org.ort.pipeline.passb

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.asrapi.PassBOutcome
import org.ort.asrapi.fake.FakeAsrEngine
import org.ort.core.AssetRef
import org.ort.core.Attribution
import org.ort.core.PassFingerprint
import org.ort.core.PassId
import org.ort.core.Tier
import org.ort.data.OrtDatabase
import org.ort.lexicon.CallsignCandidate
import org.ort.lexicon.ItuAllocation
import org.ort.lexicon.LatticeSource
import org.ort.lexicon.ParsedCallsign
import org.ort.lexicon.PhoneticLattice
import org.ort.lexicon.PhoneticUnit
import org.ort.lexicon.PriorContribution
import org.ort.lexicon.RankedCandidate
import org.ort.pipeline.PipelineTestFixtures
import org.robolectric.RobolectricTestRunner

/**
 * FR-UI-8, FR-LEX-12 (audit F-009): before this, [DataPassBResultSink] wrote the transcript and
 * the attribution only — the raw [PhoneticLattice] and the ranked candidate list (with each
 * prior's contribution) never reached [org.ort.data.dao.CatalogDao], so the inspection surface
 * (`InspectionSurface.kt`, `ReaderPolling.kt`) was permanently empty. Constitution I: "every
 * machine conclusion MUST be inspectable — the lattice, the candidates, each prior's
 * contribution."
 */
@RunWith(RobolectricTestRunner::class)
class DataPassBResultSinkTest {

    private fun fingerprint() = PassFingerprint(
        passId = PassId.B_OFFLINE,
        codeVersion = 1,
        modelIds = listOf(AssetRef("fake-asr-model", "1")),
        lexiconVersion = null,
        calibrationVersion = null,
        configHash = "test",
        provider = "cpu",
        tier = Tier.T0,
    )

    private fun candidate(callsign: String, prefix: String, country: String) = CallsignCandidate(
        parsed = ParsedCallsign(
            prefix = prefix,
            areaDigit = callsign[prefix.length],
            suffix = callsign.substring(prefix.length + 1),
        ),
        allocation = ItuAllocation(prefix, country, "US"),
        acousticLogProb = 2.0f,
        editPenalty = 0f,
        slotSpan = 0..4,
    )

    private fun lattice() = PhoneticLattice.ofUnits(PhoneticUnit.spell("K7ABC"), LatticeSource.TEXT_DERIVED)

    private fun rankedCandidates(): List<RankedCandidate> = listOf(
        RankedCandidate(
            candidate("K7ABC", "K", "United States"),
            contributions = listOf(
                PriorContribution("database", 1.0f),
                PriorContribution("recency", 0f, coldStart = true),
            ),
        ),
        RankedCandidate(
            candidate("W7XYZ", "W", "United States"),
            contributions = listOf(PriorContribution("database", -0.3f)),
        ),
    )

    private fun acceptedResult(transmissionId: String = "TX1") = PassBResult(
        transmissionId = transmissionId,
        outcome = PassBOutcome.Accepted(FakeAsrEngine.defaultResult(text = "kilo seven alpha bravo charlie")),
        lattice = lattice(),
        ranked = rankedCandidates(),
        attribution = Attribution.confirmed("K7ABC", 0.9),
        fingerprint = fingerprint(),
    )

    private suspend fun freshDb(): OrtDatabase {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(PipelineTestFixtures.session())
        db.transmissionDao().insert(PipelineTestFixtures.transmission("TX1"))
        return db
    }

    @Test
    fun `FR_UI_8 the ranked candidate list is persisted, ordered by rank, with the winner selected`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)

        sink.record(acceptedResult())

        val stored = db.catalogDao().candidatesFor("TX1")
        assertEquals(2, stored.size)
        assertEquals(listOf("K7ABC", "W7XYZ"), stored.map { it.callsign })
        assertEquals(listOf(0, 1), stored.map { it.rank })
        assertEquals(listOf(true, false), stored.map { it.selected })
        assertEquals(listOf(true, false), stored.map { it.databaseHit })
        assertEquals(mapOf("database" to 1.0, "recency" to 0.0), stored[0].priorBreakdown)
        assertEquals("K", stored[0].ituPrefix)
        assertEquals("United States", stored[0].ituCountry)
        assertTrue(stored.all { it.grammarValid })
    }

    @Test
    fun `FR_LEX_12 the phonetic lattice is persisted alongside the transmission it resolved`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)

        sink.record(acceptedResult())

        val stored = db.catalogDao().latticesFor("TX1")
        assertEquals(1, stored.size)
        assertEquals(org.ort.data.entity.LatticeSource.TEXT_DERIVED, stored.single().source)
        assertTrue(stored.single().unitsBlob.isNotBlank())
    }

    @Test
    fun `FR_LEX_12 a second run for the same transmission adds rather than replaces`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)

        sink.record(acceptedResult())
        sink.record(acceptedResult())

        assertEquals(4, db.catalogDao().candidatesFor("TX1").size)
        assertEquals(2, db.catalogDao().latticesFor("TX1").size)
    }

    @Test
    fun `FR_UI_8 a rejected outcome persists no candidates and no lattice`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)

        sink.record(
            PassBResult(
                transmissionId = "TX1",
                outcome = PassBOutcome.Rejected(
                    rule = org.ort.asrapi.rules.RejectionRuleId.TOO_SHORT,
                    detail = "too short",
                    partialResult = null,
                ),
                lattice = null,
                ranked = emptyList(),
                attribution = Attribution.unknown(),
                fingerprint = fingerprint(),
            ),
        )

        assertFalse(db.catalogDao().candidatesFor("TX1").isNotEmpty())
        assertFalse(db.catalogDao().latticesFor("TX1").isNotEmpty())
    }
}
