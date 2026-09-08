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
}
