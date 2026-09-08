package org.ort.app.ui.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ort.core.Attribution
import org.ort.core.TransmissionId
import org.ort.testing.Requirement

/**
 * FR-UI-2 — grouping transmissions into conversations and showing *why* each was attributed.
 * `ThreadEntity`/`TransmissionEntity.threadId` exist in the schema (build-plan P5) but nothing
 * populates `threadId` yet (threading is M6), so the honest behaviour today is: every real
 * transmission lands in one explicit "not yet grouped" bucket, never a fabricated conversation.
 * This mapper is exercised entirely with fixed [TransmissionDetail] values — no database — so it
 * proves the grouping and reasoning logic independent of whether any thread ever gets populated.
 */
public class ThreadGroupingMapperTest {

    private fun detail(
        id: String,
        threadId: String? = null,
        attribution: Attribution = Attribution.unknown(),
        startedAtUtcMillis: Long = 0L,
    ) = TransmissionDetail(
        id = id,
        startedAtUtcMillis = startedAtUtcMillis,
        frequencyHz = 145_230_000L,
        durationMs = 1_000L,
        signalStrength = null,
        attribution = attribution,
        currentTranscriptText = "hello",
        supersededTranscriptTexts = emptyList(),
        hasAudio = false,
        threadId = threadId,
    )

    @Test
    @Requirement("FR-UI-2")
    public fun `no transmissions produces no groups, not a fabricated empty conversation`() {
        assertTrue(ThreadGroupingMapper.from(emptyList()).isEmpty())
    }

    @Test
    @Requirement("FR-UI-2")
    public fun `transmissions with no threadId land in one honest ungrouped bucket`() {
        val groups = ThreadGroupingMapper.from(
            listOf(detail("TX1", threadId = null), detail("TX2", threadId = null)),
        )

        assertEquals(1, groups.size)
        assertNull(groups.single().threadId)
        assertEquals(setOf("TX1", "TX2"), groups.single().entries.map { it.listEntry.id }.toSet())
    }

    @Test
    @Requirement("FR-UI-2")
    public fun `transmissions sharing a threadId are grouped together, separate from the ungrouped bucket`() {
        val groups = ThreadGroupingMapper.from(
            listOf(
                detail("TX1", threadId = "THREAD-A"),
                detail("TX2", threadId = "THREAD-A"),
                detail("TX3", threadId = null),
            ),
        )

        assertEquals(2, groups.size)
        val threadA = groups.single { it.threadId == "THREAD-A" }
        assertEquals(setOf("TX1", "TX2"), threadA.entries.map { it.listEntry.id }.toSet())
        val ungrouped = groups.single { it.threadId == null }
        assertEquals(listOf("TX3"), ungrouped.entries.map { it.listEntry.id })
    }

    @Test
    @Requirement("FR-UI-2")
    public fun `a CONFIRMED attribution is reasoned as confirmed in this transmission`() {
        val groups = ThreadGroupingMapper.from(
            listOf(detail("TX1", threadId = "THREAD-A", attribution = Attribution.confirmed("W7NPC", 0.95))),
        )

        assertEquals(
            "callsign confirmed in this transmission",
            groups.single().entries.single().reasoning,
        )
    }

    @Test
    @Requirement("FR-UI-2")
    public fun `an INFERRED attribution names the transmission it was carried from`() {
        val sourceId = TransmissionId.new()
        val groups = ThreadGroupingMapper.from(
            listOf(
                detail("TX-SOURCE", threadId = "THREAD-A", startedAtUtcMillis = 0L),
                detail(
                    sourceId.toString(),
                    threadId = "THREAD-A",
                    attribution = Attribution.confirmed("W7NPC", 0.95),
                    startedAtUtcMillis = 0L,
                ),
                detail(
                    "TX2",
                    threadId = "THREAD-A",
                    attribution = Attribution.inferred("W7NPC", 0.8, sourceId),
                    startedAtUtcMillis = 60_000L,
                ),
            ),
        )
        val thread = groups.single()
        val inferredEntry = thread.entries.single { it.listEntry.id == "TX2" }

        assertTrue(inferredEntry.reasoning.contains(sourceId.toString().takeLast(6)))
    }

    @Test
    @Requirement("FR-UI-2")
    public fun `an AMBIGUOUS attribution is reasoned as too close to choose`() {
        val groups = ThreadGroupingMapper.from(
            listOf(detail("TX1", threadId = "THREAD-A", attribution = Attribution.ambiguous())),
        )

        assertEquals(
            "more than one candidate; the system will not choose",
            groups.single().entries.single().reasoning,
        )
    }

    @Test
    @Requirement("FR-UI-2")
    public fun `an UNKNOWN attribution is reasoned as no callsign resolved`() {
        val groups = ThreadGroupingMapper.from(
            listOf(detail("TX1", threadId = "THREAD-A", attribution = Attribution.unknown())),
        )

        assertEquals(
            "no callsign resolved",
            groups.single().entries.single().reasoning,
        )
    }
}
