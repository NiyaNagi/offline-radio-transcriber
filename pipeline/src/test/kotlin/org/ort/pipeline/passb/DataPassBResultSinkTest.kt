package org.ort.pipeline.passb

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import org.ort.lexicon.SlotAlignment
import org.ort.lexicon.SlotDetail
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

    private fun candidateWithSlots(callsign: String, prefix: String, country: String) = candidate(
        callsign,
        prefix,
        country,
    ).copy(
        slotDetails = listOf(
            SlotDetail(index = 0, unit = "K", score = 0.95, keptAlternate = null, charStart = 0, charEnd = 1),
            SlotDetail(index = 1, unit = "7", score = 0.9, keptAlternate = "1", charStart = 1, charEnd = 2),
        ),
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
    fun `R_320 R_182 each candidates slot details are persisted in the same transaction`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)
        val withSlots = RankedCandidate(
            candidateWithSlots("K7ABC", "K", "United States"),
            contributions = listOf(PriorContribution("database", 1.0f)),
        )
        val result = PassBResult(
            transmissionId = "TX1",
            outcome = PassBOutcome.Accepted(FakeAsrEngine.defaultResult(text = "kilo seven alpha bravo charlie")),
            lattice = lattice(),
            ranked = listOf(withSlots),
            attribution = Attribution.confirmed("K7ABC", 0.9),
            fingerprint = fingerprint(),
        )

        sink.record(result)

        val candidateId = db.catalogDao().candidatesFor("TX1").single().id
        val slots = db.catalogDao().slotDetailsFor("TX1")
        assertEquals(2, slots.size)
        assertTrue(slots.all { it.candidateId == candidateId })
        assertEquals(listOf(0, 1), slots.map { it.index })
        assertEquals(listOf("K", "7"), slots.map { it.unit })
        assertEquals("1", slots[1].keptAlternate)

        val span = db.catalogDao().winningCandidateCharSpan("TX1")
        assertEquals(0, span.spanStart)
        assertEquals(2, span.spanEnd)
    }

    /** R-1148: [SlotDetail] (`unit`/`score`/`keptAlternate`) is a fact about the *lattice* and stays
     * shared across every candidate's rows (unchanged behaviour, R-320); [SlotAlignment]
     * (`candidateUnit`/`offeredByLattice`) is a fact about *that candidate's own path* and must
     * differ between two candidates whose paths genuinely disagreed — a test that would pass
     * against the pre-R-1148 shared-list behaviour is not a test of this change. */
    private fun candidateWithAlignment(
        callsign: String,
        prefix: String,
        country: String,
        ownUnitAtSlot1: String?,
        offered: Boolean,
    ) = candidate(callsign, prefix, country).copy(
        slotDetails = listOf(
            SlotDetail(index = 0, unit = "K", score = 0.95, keptAlternate = null, charStart = 0, charEnd = 1),
            SlotDetail(index = 1, unit = "7", score = 0.9, keptAlternate = null, charStart = 1, charEnd = 2),
        ),
        slotAlignment = listOf(
            SlotAlignment(slotIndex = 0, latticeUnit = "K", candidateUnit = "K", offeredByLattice = true),
            SlotAlignment(slotIndex = 1, latticeUnit = "7", candidateUnit = ownUnitAtSlot1, offeredByLattice = offered),
        ),
    )

    @Test
    fun `R_1148 each candidates own slot alignment is persisted, differing where their paths did`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)
        val winner = RankedCandidate(
            candidateWithAlignment("K7ABC", "K", "United States", ownUnitAtSlot1 = "7", offered = true),
            contributions = listOf(PriorContribution("database", 1.0f)),
        )
        // A runner-up whose own path deleted slot 1 rather than matching it -- a genuinely
        // different fact about this candidate, not a copy of the lattice's own top pick.
        val runnerUp = RankedCandidate(
            candidateWithAlignment("W7XYZ", "W", "United States", ownUnitAtSlot1 = null, offered = false),
            contributions = listOf(PriorContribution("database", -0.3f)),
        )
        val result = PassBResult(
            transmissionId = "TX1",
            outcome = PassBOutcome.Accepted(FakeAsrEngine.defaultResult(text = "kilo seven alpha bravo charlie")),
            lattice = lattice(),
            ranked = listOf(winner, runnerUp),
            attribution = Attribution.confirmed("K7ABC", 0.9),
            fingerprint = fingerprint(),
        )

        sink.record(result)

        val candidates = db.catalogDao().candidatesFor("TX1")
        val winnerId = candidates.single { it.callsign == "K7ABC" }.id
        val runnerUpId = candidates.single { it.callsign == "W7XYZ" }.id
        val slots = db.catalogDao().slotDetailsFor("TX1")

        val winnerSlot1 = slots.single { it.candidateId == winnerId && it.index == 1 }
        val runnerUpSlot1 = slots.single { it.candidateId == runnerUpId && it.index == 1 }

        // The pre-existing, per-lattice fact stays identical across both candidates' rows
        // (unchanged behaviour, R-320).
        assertEquals(winnerSlot1.unit, runnerUpSlot1.unit)
        assertEquals(winnerSlot1.score, runnerUpSlot1.score, 1e-9)

        // The new, per-candidate fact genuinely differs between them.
        assertEquals("7", winnerSlot1.candidateUnit)
        assertEquals(true, winnerSlot1.offeredByLattice)
        assertNull(runnerUpSlot1.candidateUnit)
        assertEquals(false, runnerUpSlot1.offeredByLattice)
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

    private fun rejectedResult(transmissionId: String = "TX1") = PassBResult(
        transmissionId = transmissionId,
        outcome = PassBOutcome.Rejected(
            rule = org.ort.asrapi.rules.RejectionRuleId.TOO_SHORT,
            detail = "too short",
            partialResult = null,
        ),
        lattice = null,
        ranked = emptyList(),
        attribution = Attribution.unknown(),
        fingerprint = fingerprint(),
    )

    private fun failedResult(transmissionId: String = "TX1") = PassBResult(
        transmissionId = transmissionId,
        outcome = PassBOutcome.Failed(reason = "engine threw", cause = null),
        lattice = null,
        ranked = emptyList(),
        attribution = Attribution.unknown(),
        fingerprint = fingerprint(),
    )

    // Register R-1032 (constitution VI "no number without ... execution provider"): before this,
    // PassFingerprint.provider was computed and thrown away -- the column's only writer was the
    // literal `executionProvider = null` at segment-persist time, before any pass ran.

    @Test
    fun `R_1032 executionProvider is persisted on an accepted outcome`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)

        sink.record(acceptedResult())

        assertEquals("cpu", db.transmissionDao().getById("TX1")!!.executionProvider)
    }

    @Test
    fun `R_1032 executionProvider is persisted on a rejected outcome`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)

        sink.record(rejectedResult())

        assertEquals("cpu", db.transmissionDao().getById("TX1")!!.executionProvider)
    }

    @Test
    fun `R_1032 executionProvider is persisted even on a failed outcome -- provenance is a fact about the attempt`() =
        runTest {
            val db = freshDb()
            val sink = DataPassBResultSink(db)

            sink.record(failedResult())

            assertEquals("cpu", db.transmissionDao().getById("TX1")!!.executionProvider)
        }

    // Register R-1033: since this fix, live capture's Pass B (always Tier.T0) stamps the same
    // TransmissionEntity.processedTier column reprocessing already did -- see that field's own doc
    // comment for the full reasoning and why Failed must never write it.

    @Test
    fun `R_1033 processedTier is stamped on an accepted outcome, live tier included`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)

        sink.record(acceptedResult())

        assertEquals(Tier.T0, db.transmissionDao().getById("TX1")!!.processedTier)
    }

    @Test
    fun `R_1033 processedTier is stamped on a rejected outcome -- rejected is a genuine run, not a failure`() =
        runTest {
            val db = freshDb()
            val sink = DataPassBResultSink(db)

            sink.record(rejectedResult())

            assertEquals(Tier.T0, db.transmissionDao().getById("TX1")!!.processedTier)
        }

    @Test
    fun `R_1033 processedTier is left untouched on a failed outcome -- the record is still eligible`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)

        sink.record(failedResult())

        assertNull(db.transmissionDao().getById("TX1")!!.processedTier)
    }

    // Register R-1133 (FR-ASR-5, FR-ASR-6; AC-6; constitution I, VI): before this,
    // PassBOutcome.inertControls -- which hallucination controls could not evaluate on this pass
    // -- was computed on every run and thrown away the instant this sink read it. Inertness was
    // representable (R-1121) but not inspectable: no debug dump could show that AC-6 ran on five
    // controls rather than six for a given over.

    @Test
    fun `R_1133 inertControls is persisted on an accepted outcome`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)

        sink.record(
            acceptedResult().copy(
                outcome = PassBOutcome.Accepted(
                    FakeAsrEngine.defaultResult(text = "kilo seven alpha bravo charlie"),
                    inertControls = setOf(org.ort.asrapi.rules.RejectionRuleId.NO_SPEECH_PROB),
                ),
            ),
        )

        assertEquals("NO_SPEECH_PROB", db.transmissionDao().getById("TX1")!!.inertControls)
    }

    @Test
    fun `R_1133 inertControls is persisted on a rejected outcome`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)

        sink.record(
            rejectedResult().copy(
                outcome = PassBOutcome.Rejected(
                    rule = org.ort.asrapi.rules.RejectionRuleId.REPETITION,
                    detail = "repeated too much",
                    partialResult = null,
                    inertControls = setOf(org.ort.asrapi.rules.RejectionRuleId.NO_SPEECH_PROB),
                ),
            ),
        )

        assertEquals("NO_SPEECH_PROB", db.transmissionDao().getById("TX1")!!.inertControls)
    }

    @Test
    fun `R_1133 inertControls is the honest empty string, never a fabricated null, when every control ran`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)

        // A pre-decode reject (TOO_SHORT) never reaches the post-decode controls at all, so
        // its inertControls is genuinely empty -- distinct from "unknown" (null).
        sink.record(rejectedResult())

        assertEquals("", db.transmissionDao().getById("TX1")!!.inertControls)
    }

    @Test
    fun `R_1133 inertControls is left untouched -- null -- on a failed outcome, the same gate as processedTier`() =
        runTest {
            val db = freshDb()
            val sink = DataPassBResultSink(db)

            sink.record(failedResult())

            assertNull(db.transmissionDao().getById("TX1")!!.inertControls)
        }

    // Register R-1132, D56: before this, no production code path ever inserted a StationEntity --
    // every construction lived in src/test or src/debug, so the Stations screen and CatalogDao's
    // own database-presence/recency priors (R-1124) always read cold on a real device. D56 sets
    // the bar at "AMBIGUOUS or better" precisely because CONFIRMED is unreachable in production
    // without a calibrator (R-1110) -- a bar set at CONFIRMED would re-create this exact bug.

    @Test
    fun `R_1132 an accepted outcome births a station for the top-ranked candidate`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)

        sink.record(acceptedResult())

        val station = db.catalogDao().getStation("K7ABC")!!
        assertEquals("K7ABC", station.callsign)
        assertEquals("CONFIRMED=1,INFERRED=0,AMBIGUOUS=0,UNKNOWN=0", station.overCountsByAttributionState)
    }

    @Test
    fun `R_1132 an AMBIGUOUS attribution still births a station -- unconfirmed, not absent`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)
        val ambiguous = acceptedResult().copy(attribution = Attribution.ambiguous())

        sink.record(ambiguous)

        // AMBIGUOUS carries no attribution.stationId (register R-1110) -- the station is keyed on
        // the top-ranked candidate's own text instead, the same source R-1125 already uses.
        val station = db.catalogDao().getStation("K7ABC")!!
        assertEquals("CONFIRMED=0,INFERRED=0,AMBIGUOUS=1,UNKNOWN=0", station.overCountsByAttributionState)
    }

    @Test
    fun `R_1132 an UNKNOWN attribution never births a station`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)
        val unknown = acceptedResult().copy(attribution = Attribution.unknown(), ranked = emptyList())

        sink.record(unknown)

        assertNull(db.catalogDao().getStation("K7ABC"))
    }

    @Test
    fun `R_1132 a rejected outcome never births a station`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)

        sink.record(rejectedResult())

        assertNull(db.catalogDao().getStation("K7ABC"))
    }

    @Test
    fun `R_1132 a failed outcome never births a station`() = runTest {
        val db = freshDb()
        val sink = DataPassBResultSink(db)

        sink.record(failedResult())

        assertNull(db.catalogDao().getStation("K7ABC"))
    }

    @Test
    fun `R_1132 a second over for the same callsign updates the station rather than duplicating it`() = runTest {
        val db = freshDb()
        // Register R-1134: a genuine second *over* is a second transmission -- re-running Pass B
        // on the *same* transmission id would overwrite TX1's own current attribution, which is
        // exactly the "derive from the transmission's real, current state" behaviour R-1134 fixed
        // overCountsByAttributionState to have (a stale AMBIGUOUS write on TX1 must stop counting
        // as CONFIRMED, not accumulate as though both were still true).
        db.transmissionDao().insert(PipelineTestFixtures.transmission("TX2"))
        val sink = DataPassBResultSink(db)

        sink.record(acceptedResult())
        sink.record(acceptedResult(transmissionId = "TX2").copy(attribution = Attribution.ambiguous()))

        val station = db.catalogDao().getStation("K7ABC")!!
        assertEquals(2, station.transmissionCount)
        assertEquals("CONFIRMED=1,INFERRED=0,AMBIGUOUS=1,UNKNOWN=0", station.overCountsByAttributionState)
    }
}
