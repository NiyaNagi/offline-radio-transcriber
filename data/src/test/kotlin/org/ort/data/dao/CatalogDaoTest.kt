package org.ort.data.dao

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.data.OrtDatabase
import org.ort.data.TestFixtures
import org.ort.data.entity.CallsignCandidateEntity
import org.ort.data.entity.LatticeSlotEntity
import org.ort.data.entity.LatticeSource
import org.ort.data.entity.PhoneticLatticeEntity
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * FR-LEX-12 — the raw phonetic lattice and the full candidate list are stored alongside the
 * chosen result, **permanently**, so a resolution can be audited and re-ranked later rather than
 * only trusted (constitution I: "every machine conclusion MUST be inspectable"). Neither
 * [CatalogDao.latticesFor] nor [CatalogDao.candidatesFor] deletes or collapses anything — both
 * return the whole set that was written, which is what "auditable and re-rankable" requires:
 * a UI or a later re-ranking pass needs every candidate, not just the one that won.
 */
@RunWith(RobolectricTestRunner::class)
public class CatalogDaoTest {

    private lateinit var db: OrtDatabase

    @Before
    public fun openDatabase() {
        db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
    }

    @Test
    @Requirement("FR-LEX-12")
    public fun the_full_candidate_list_persists_ordered_by_rank_not_just_the_selected_one(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX1"))
        val dao = db.catalogDao()

        // Three candidates considered; only rank 0 is selected. All three must still be there.
        dao.insert(
            CallsignCandidateEntity(
                id = "C1",
                transmissionId = "TX1",
                callsign = "W7NPC",
                rank = 0,
                score = 0.91,
                grammarValid = true,
                ituPrefix = "W",
                ituCountry = "United States",
                priorBreakdown = mapOf("acoustic" to 0.6, "database" to 0.31),
                databaseHit = true,
                selected = true,
            ),
        )
        dao.insert(
            CallsignCandidateEntity(
                id = "C2",
                transmissionId = "TX1",
                callsign = "W7NBC",
                rank = 1,
                score = 0.42,
                grammarValid = true,
                ituPrefix = "W",
                ituCountry = "United States",
                priorBreakdown = mapOf("acoustic" to 0.42),
                databaseHit = false,
                selected = false,
            ),
        )
        dao.insert(
            CallsignCandidateEntity(
                id = "C3",
                transmissionId = "TX1",
                callsign = "K7LWH",
                rank = 2,
                score = 0.11,
                grammarValid = false,
                ituPrefix = null,
                ituCountry = null,
                priorBreakdown = null,
                databaseHit = false,
                selected = false,
            ),
        )

        val candidates = dao.candidatesFor("TX1")

        assertEquals(listOf("C1", "C2", "C3"), candidates.map { it.id }) // every candidate, in rank order
        assertEquals(listOf(true, false, false), candidates.map { it.selected }) // only one selected, all inspectable
        assertEquals(mapOf("acoustic" to 0.6, "database" to 0.31), candidates.first().priorBreakdown)
    }

    @Test
    @Requirement("FR-LEX-12")
    public fun the_raw_phonetic_lattice_persists_alongside_the_transmission_it_resolved(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX1"))
        val dao = db.catalogDao()

        dao.insert(
            PhoneticLatticeEntity(
                id = "L1",
                transmissionId = "TX1",
                source = LatticeSource.ACOUSTIC,
                unitsBlob = """[{"unit":"W","startMs":0,"endMs":80,"score":0.95,"alternatives":["W","double-u"]}]""",
                modelId = "phonetic-lattice-v3",
                createdAt = 1L,
            ),
        )

        val lattices = dao.latticesFor("TX1")

        assertEquals(1, lattices.size)
        val stored = lattices.single()
        assertEquals(LatticeSource.ACOUSTIC, stored.source)
        assertEquals("phonetic-lattice-v3", stored.modelId)
        assertEquals(
            """[{"unit":"W","startMs":0,"endMs":80,"score":0.95,"alternatives":["W","double-u"]}]""",
            stored.unitsBlob,
        )
    }

    private fun candidate(id: String, transmissionId: String, rank: Int, selected: Boolean) = CallsignCandidateEntity(
        id = id,
        transmissionId = transmissionId,
        callsign = "W7NPC",
        rank = rank,
        score = 0.9,
        grammarValid = true,
        ituPrefix = "W",
        ituCountry = "United States",
        priorBreakdown = null,
        databaseHit = true,
        selected = selected,
    )

    @Test
    @Requirement("R-320", "FR-UI-8")
    public fun R_320_slotDetailsFor_returns_every_slot_for_every_candidate_ordered_by_rank_then_index(): Unit =
        runTest {
            db.sessionDao().insert(TestFixtures.session())
            db.transmissionDao().insert(TestFixtures.transmission("TX1"))
            val dao = db.catalogDao()
            dao.insert(candidate("C-RUNNERUP", "TX1", rank = 1, selected = false))
            dao.insert(candidate("C-WINNER", "TX1", rank = 0, selected = true))

            // Deliberately inserted out of both candidate-rank and slot-index order.
            dao.insert(
                LatticeSlotEntity(
                    id = "S-RUNNERUP-0",
                    transmissionId = "TX1",
                    candidateId = "C-RUNNERUP",
                    index = 0,
                    unit = "W",
                    score = 0.7,
                    keptAlternate = null,
                    charStart = null,
                    charEnd = null,
                ),
            )
            dao.insert(
                LatticeSlotEntity(
                    id = "S-WINNER-1",
                    transmissionId = "TX1",
                    candidateId = "C-WINNER",
                    index = 1,
                    unit = "7",
                    score = 0.99,
                    keptAlternate = null,
                    charStart = 1,
                    charEnd = 2,
                ),
            )
            dao.insert(
                LatticeSlotEntity(
                    id = "S-WINNER-0",
                    transmissionId = "TX1",
                    candidateId = "C-WINNER",
                    index = 0,
                    unit = "W",
                    score = 0.95,
                    keptAlternate = "V",
                    charStart = 0,
                    charEnd = 1,
                ),
            )

            val slots = dao.slotDetailsFor("TX1")

            // The winning candidate (rank 0) first, its own slots in index order, then the
            // runner-up (rank 1) -- D05's own ordering.
            assertEquals(listOf("S-WINNER-0", "S-WINNER-1", "S-RUNNERUP-0"), slots.map { it.id })
            assertEquals("V", slots.first { it.id == "S-WINNER-0" }.keptAlternate)
            // Never fabricated: a slot the lattice really gave only one alternative for stays null.
            assertEquals(null, slots.first { it.id == "S-RUNNERUP-0" }.keptAlternate)
        }

    @Test
    @Requirement("R-182", "FR-UI-4")
    public fun R_182_winningCandidateCharSpan_covers_only_the_selected_candidates_slots(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session())
        db.transmissionDao().insert(TestFixtures.transmission("TX1"))
        val dao = db.catalogDao()
        dao.insert(candidate("C-WINNER", "TX1", rank = 0, selected = true))
        dao.insert(candidate("C-LOSER", "TX1", rank = 1, selected = false))
        dao.insert(
            LatticeSlotEntity(
                id = "S-W0",
                transmissionId = "TX1",
                candidateId = "C-WINNER",
                index = 0,
                unit = "W",
                score = 0.9,
                keptAlternate = null,
                charStart = 0,
                charEnd = 1,
            ),
        )
        dao.insert(
            LatticeSlotEntity(
                id = "S-W1",
                transmissionId = "TX1",
                candidateId = "C-WINNER",
                index = 1,
                unit = "7",
                score = 0.9,
                keptAlternate = null,
                charStart = 2,
                charEnd = 3,
            ),
        )
        // The loser's slots claim a wider span -- must never leak into the winner's result.
        dao.insert(
            LatticeSlotEntity(
                id = "S-L0",
                transmissionId = "TX1",
                candidateId = "C-LOSER",
                index = 0,
                unit = "K",
                score = 0.1,
                keptAlternate = null,
                charStart = 10,
                charEnd = 20,
            ),
        )

        val span = dao.winningCandidateCharSpan("TX1")

        assertEquals(0, span.spanStart)
        assertEquals(3, span.spanEnd)
    }

    @Test
    @Requirement("R-182", "FR-UI-4")
    public fun R_182_winningCandidateCharSpan_is_honestly_null_when_the_lattice_has_no_char_anchoring(): Unit =
        runTest {
            db.sessionDao().insert(TestFixtures.session())
            db.transmissionDao().insert(TestFixtures.transmission("TX1"))
            val dao = db.catalogDao()
            dao.insert(candidate("C-WINNER", "TX1", rank = 0, selected = true))
            // An acoustic lattice's slots carry no char span at all (see LatticeSlotEntity's own
            // doc comment) -- never fabricated to 0.
            dao.insert(
                LatticeSlotEntity(
                    id = "S-W0",
                    transmissionId = "TX1",
                    candidateId = "C-WINNER",
                    index = 0,
                    unit = "W",
                    score = 0.9,
                    keptAlternate = null,
                    charStart = null,
                    charEnd = null,
                ),
            )

            val span = dao.winningCandidateCharSpan("TX1")

            assertEquals(null, span.spanStart)
            assertEquals(null, span.spanEnd)
        }
}
