package org.ort.app.ui.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.data.entity.CallsignCandidateEntity
import org.ort.data.entity.LatticeSource
import org.ort.data.entity.PhoneticLatticeEntity

/**
 * Build-plan P16, FR-UI-8: the inspection surface. `:app` cannot depend on `:lexicon`
 * (`ModuleGraph.allowed` — see the prompt's own constraint), so this mapper builds its view state
 * from the plain `:data` entities `PhoneticLatticeEntity`/`CallsignCandidateEntity` already carry
 * (`CallsignCandidateEntity.priorBreakdown`), never from `PriorContribution`/`RankedCandidate`
 * directly.
 *
 * The load-bearing distinction (Principle I): a prior that abstained (cold start, FR-LEX-31 —
 * contributes **exactly** zero) is not the same fact as a prior that argued against the
 * candidate (a negative contribution), and must not render identically. `PriorContribution`'s own
 * invariant (`:lexicon`) is that cold start is `logOdds == 0f` exactly, so that same test — the
 * stored value being exactly `0.0` — is what this mapper uses to tell the two apart from the
 * persisted `Double` alone.
 */
class InspectionViewStateMapperTest {

    private fun candidate(
        id: String = "C1",
        callsign: String = "K7ABC",
        rank: Int = 0,
        priorBreakdown: Map<String, Double>? = null,
    ) = CallsignCandidateEntity(
        id = id,
        transmissionId = "TX1",
        callsign = callsign,
        rank = rank,
        score = 1.5,
        grammarValid = true,
        ituPrefix = "K",
        ituCountry = "United States",
        priorBreakdown = priorBreakdown,
        databaseHit = true,
        selected = rank == 0,
    )

    @Test
    fun `no lattice and no candidates renders an honest empty state`() {
        val view = InspectionViewStateMapper.from(emptyList(), emptyList())

        assertTrue(view.isEmpty)
        assertNull(view.lattice)
        assertTrue(view.candidates.isEmpty())
    }

    @Test
    fun `FR_LEX_31 a cold-start prior (exactly zero) is distinguished from one that argued against`() {
        val view = InspectionViewStateMapper.from(
            emptyList(),
            listOf(
                candidate(
                    priorBreakdown = mapOf(
                        "callsign-history" to 0.0,
                        "propagation" to -0.42,
                        "database" to 0.8,
                    ),
                ),
            ),
        )

        val contributions = view.candidates.single().priorContributions.associateBy { it.priorName }
        assertTrue(contributions.getValue("callsign-history").isColdStart, "exactly-zero must read as cold start")
        assertTrue(
            !contributions.getValue("propagation").isColdStart,
            "a negative contribution is opposition, not abstention",
        )
        assertTrue(!contributions.getValue("database").isColdStart)
        assertEquals(-0.42, contributions.getValue("propagation").logOdds)
        assertEquals(0.8, contributions.getValue("database").logOdds)
    }

    @Test
    fun `candidates with no recorded prior breakdown at all render with no contributions, not fabricated ones`() {
        val view = InspectionViewStateMapper.from(emptyList(), listOf(candidate(priorBreakdown = null)))

        assertTrue(view.candidates.single().priorContributions.isEmpty())
    }

    @Test
    fun `candidates are ordered by their persisted rank`() {
        val view = InspectionViewStateMapper.from(
            emptyList(),
            listOf(
                candidate(id = "C2", callsign = "K9ZZZ", rank = 1),
                candidate(id = "C1", callsign = "K7ABC", rank = 0),
            ),
        )

        assertEquals(listOf("K7ABC", "K9ZZZ"), view.candidates.map { it.callsign })
    }

    @Test
    fun `a recorded lattice carries its source and model, with no fabricated slot content`() {
        val entity = PhoneticLatticeEntity(
            id = "L1",
            transmissionId = "TX1",
            source = LatticeSource.TEXT_DERIVED,
            unitsBlob = "kilo|seven|alpha",
            modelId = "text-derived-v1",
            createdAt = 100L,
        )

        val view = InspectionViewStateMapper.from(listOf(entity), emptyList())

        assertEquals("TEXT_DERIVED", view.lattice!!.source)
        assertEquals("text-derived-v1", view.lattice!!.modelId)
        assertTrue(!view.isEmpty)
    }
}
