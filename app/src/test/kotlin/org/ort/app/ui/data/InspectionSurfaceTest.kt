package org.ort.app.ui.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ort.data.entity.CallsignCandidateEntity
import org.ort.data.entity.LatticeSlotEntity

/**
 * Register R-320 (schema v5), `Detail-Why.dc.html` section 1: [InspectionViewStateMapper.from]'s
 * pure per-candidate slot grouping and threshold — no Robolectric, no database.
 */
class InspectionSurfaceTest {

    private fun candidate(id: String, rank: Int, selected: Boolean) = CallsignCandidateEntity(
        id = id,
        transmissionId = "TX1",
        callsign = "K7LWH",
        rank = rank,
        score = 8.6,
        grammarValid = true,
        ituPrefix = "K",
        ituCountry = "United States",
        priorBreakdown = null,
        databaseHit = true,
        selected = selected,
    )

    private fun slot(
        candidateId: String,
        index: Int,
        unit: String,
        score: Double,
        keptAlternate: String? = null,
        candidateUnit: String? = null,
        offeredByLattice: Boolean? = null,
    ) = LatticeSlotEntity(
        id = "$candidateId-slot$index",
        transmissionId = "TX1",
        candidateId = candidateId,
        index = index,
        unit = unit,
        score = score,
        keptAlternate = keptAlternate,
        charStart = null,
        charEnd = null,
        candidateUnit = candidateUnit,
        offeredByLattice = offeredByLattice,
    )

    @Test
    fun R_320_slots_are_grouped_onto_their_own_candidate_by_real_candidateId() {
        val candidates = listOf(candidate("c1", rank = 0, selected = true), candidate("c2", rank = 1, selected = false))
        val slots = listOf(
            slot("c1", 0, "K", 0.96),
            slot("c1", 1, "7", 0.99),
            slot("c2", 0, "K", 0.5),
        )

        val inspection = InspectionViewStateMapper.from(emptyList(), candidates, slots)

        val winner = inspection.candidates.single { it.id == "c1" }
        assertEquals(2, winner.slots.size)
        assertEquals(listOf("K", "7"), winner.slots.map { it.unit })
        val other = inspection.candidates.single { it.id == "c2" }
        assertEquals(1, other.slots.size)
    }

    @Test
    fun R_320_slots_render_in_lattice_index_order_even_if_the_query_did_not() {
        val candidates = listOf(candidate("c1", rank = 0, selected = true))
        val slots = listOf(slot("c1", 2, "H", 0.88), slot("c1", 0, "K", 0.96), slot("c1", 1, "7", 0.99))

        val inspection = InspectionViewStateMapper.from(emptyList(), candidates, slots)

        assertEquals(listOf("K", "7", "H"), inspection.candidates.single().slots.map { it.unit })
    }

    @Test
    fun R_320_a_slot_below_the_disclosed_threshold_reads_belowThreshold() {
        val candidates = listOf(candidate("c1", rank = 0, selected = true))
        val slots = listOf(slot("c1", 0, "W", 0.64, keptAlternate = "V"), slot("c1", 1, "L", 0.91))

        val inspection = InspectionViewStateMapper.from(emptyList(), candidates, slots)

        val bySlot = inspection.candidates.single().slots.associateBy { it.unit }
        assertTrue(bySlot.getValue("W").belowThreshold)
        assertEquals("V", bySlot.getValue("W").keptAlternate)
        assertFalse(bySlot.getValue("L").belowThreshold)
    }

    @Test
    fun R_320_a_candidate_with_no_slot_rows_reads_an_empty_list_not_a_crash() {
        val candidates = listOf(candidate("c1", rank = 0, selected = true))

        val inspection = InspectionViewStateMapper.from(emptyList(), candidates, emptyList())

        assertTrue(inspection.candidates.single().slots.isEmpty())
    }

    @Test
    fun R_1148_a_slot_that_agrees_with_the_lattice_is_not_a_mismatch() {
        val candidates = listOf(candidate("c1", rank = 0, selected = true))
        val slots = listOf(slot("c1", 0, "K", 0.96, candidateUnit = "K", offeredByLattice = true))

        val inspection = InspectionViewStateMapper.from(emptyList(), candidates, slots)

        assertTrue(inspection.candidates.single().slotMismatches.isEmpty())
    }

    @Test
    fun R_1148_a_substitution_the_lattice_offered_is_a_mismatch_marked_offered() {
        val candidates = listOf(candidate("c1", rank = 0, selected = true))
        val slots = listOf(slot("c1", 3, "W", 0.64, keptAlternate = "V", candidateUnit = "V", offeredByLattice = true))

        val inspection = InspectionViewStateMapper.from(emptyList(), candidates, slots)

        val mismatch = inspection.candidates.single().slotMismatches.single()
        assertEquals(3, mismatch.slotIndex)
        assertEquals("W", mismatch.latticeUnit)
        assertEquals("V", mismatch.candidateUnit)
        assertTrue(mismatch.offeredByLattice)
    }

    @Test
    fun R_1148_a_substitution_the_lattice_never_offered_is_a_mismatch_marked_not_offered() {
        val candidates = listOf(candidate("c1", rank = 0, selected = true))
        val slots = listOf(slot("c1", 3, "W", 0.64, candidateUnit = "V", offeredByLattice = false))

        val inspection = InspectionViewStateMapper.from(emptyList(), candidates, slots)

        val mismatch = inspection.candidates.single().slotMismatches.single()
        assertFalse(mismatch.offeredByLattice)
    }

    @Test
    fun R_1148_a_deleted_slot_is_a_mismatch_with_no_candidate_unit() {
        val candidates = listOf(candidate("c1", rank = 0, selected = true))
        val slots = listOf(slot("c1", 3, "X", 0.0, candidateUnit = null, offeredByLattice = false))

        val inspection = InspectionViewStateMapper.from(emptyList(), candidates, slots)

        val mismatch = inspection.candidates.single().slotMismatches.single()
        assertEquals(null, mismatch.candidateUnit)
        assertFalse(mismatch.offeredByLattice)
    }

    @Test
    fun R_1148_a_pre_migration_row_with_no_recorded_alignment_is_never_a_mismatch() {
        // offeredByLattice == null: this row predates schema v17 -- candidateUnit happens to be
        // null too (never written), which must not be confused with a genuine deletion.
        val candidates = listOf(candidate("c1", rank = 0, selected = true))
        val slots = listOf(slot("c1", 0, "K", 0.96, candidateUnit = null, offeredByLattice = null))

        val inspection = InspectionViewStateMapper.from(emptyList(), candidates, slots)

        assertTrue(inspection.candidates.single().slotMismatches.isEmpty())
    }
}
