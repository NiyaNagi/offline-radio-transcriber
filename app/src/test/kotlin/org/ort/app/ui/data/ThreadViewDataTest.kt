package org.ort.app.ui.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ort.core.Attribution
import org.ort.core.TransmissionId
import org.ort.core.TransmissionState
import org.ort.core.Ulid

/**
 * R-044 (ui-conformance WP5): [ThreadListMapper]'s pure rules — `Threads.dc.html`'s cards,
 * `Threads-Ungrouped.dc.html`'s honest "not grouped yet" state (real today, since nothing
 * populates `threadId` before M6) with its "by frequency, meanwhile" list, and
 * `Thread-Detail.dc.html`'s "how these were attributed" lines.
 */
class ThreadViewDataTest {

    private fun detail(
        id: String,
        startedAtUtcMillis: Long,
        threadId: String? = null,
        frequencyHz: Long? = 145_230_000L,
        attribution: Attribution = Attribution.unknown(),
    ) = TransmissionDetail(
        id = id,
        startedAtUtcMillis = startedAtUtcMillis,
        frequencyHz = frequencyHz,
        durationMs = 4_200L,
        signalStrength = 7.0,
        attribution = attribution,
        currentTranscriptText = "roger that",
        supersededTranscriptTexts = emptyList(),
        hasAudio = true,
        threadId = threadId,
        processingState = TransmissionState.COMPLETE,
    )

    @Test
    fun `R_044 no transmissions at all is the Empty state`() {
        assertEquals(ThreadListViewState.Empty, ThreadListMapper.listState(emptyList(), emptySet()))
    }

    @Test
    fun `R_044 every threadId null renders the honest Ungrouped state, never a fabricated conversation`() {
        val details = listOf(
            detail("TX1", 0L, frequencyHz = 145_230_000L),
            detail("TX2", 1_000L, frequencyHz = 145_230_000L, attribution = Attribution.confirmed("W7NPC", 0.9)),
            detail("TX3", 2_000L, frequencyHz = 146_960_000L),
        )

        val state = ThreadListMapper.listState(details, emptySet()) as ThreadListViewState.Ungrouped

        assertEquals(3, state.totalOvers)
        assertEquals(2, state.byFrequency.size)
        val primary = state.byFrequency.first { it.frequencyHz == 145_230_000L }
        assertEquals(2, primary.overCount)
        assertEquals(1, primary.stationCount)
    }

    @Test
    fun `R_044 a real threadId group becomes a card naming its participants and counts`() {
        val details = listOf(
            detail("TX1", 0L, threadId = "T1", attribution = Attribution.confirmed("W7NPC", 0.9)),
            detail("TX2", 1_000L, threadId = "T1", attribution = Attribution.confirmed("K7LWH", 0.9)),
        )

        val state = ThreadListMapper.listState(details, emptySet()) as ThreadListViewState.Grouped

        val card = state.cards.single()
        assertEquals("T1", card.threadId)
        assertEquals(2, card.overCount)
        assertEquals("W7NPC and K7LWH", card.titleText)
        assertNull(card.kindLabel) // never guessed — no real classification column populated yet.
        assertTrue(card.metaText.contains("2 confirmed"))
    }

    @Test
    fun `R_044 kind is never guessed even when it could look like a QSO`() {
        val details = listOf(detail("TX1", 0L, threadId = "T1", attribution = Attribution.confirmed("W7NPC", 0.9)))

        val state = ThreadListMapper.listState(details, emptySet()) as ThreadListViewState.Grouped

        assertNull(state.cards.single().kindLabel)
    }

    @Test
    fun `R_044 an ambiguous-only thread is tinted and titled honestly, not with a guessed callsign`() {
        val details = listOf(detail("TX1", 0L, threadId = "T1", attribution = Attribution.ambiguous()))

        val state = ThreadListMapper.listState(details, emptySet()) as ThreadListViewState.Grouped

        val card = state.cards.single()
        assertTrue(card.ambiguous)
        assertEquals("Ambiguous stations", card.titleText)
    }

    @Test
    fun `R_044 a first-heard station in the thread carries the NEW flag`() {
        val details = listOf(detail("TX1", 0L, threadId = "T1", attribution = Attribution.confirmed("W7NPC", 0.9)))

        val state = ThreadListMapper.listState(details, firstHeardIds = setOf("TX1")) as ThreadListViewState.Grouped

        assertTrue(state.cards.single().isNew)
    }

    @Test
    fun `R_044 how these were attributed names a CONFIRMED station's overs and an INFERRED voice match's source`() {
        val tx1Id = TransmissionId(Ulid.generate())
        val tx2Id = TransmissionId(Ulid.generate())
        val details = listOf(
            detail(tx1Id.toString(), 0L, threadId = "T1", attribution = Attribution.confirmed("W7NPC", 0.9)),
            detail(
                tx2Id.toString(),
                1_000L,
                threadId = "T1",
                attribution = Attribution.inferred("K7LWH", 0.82, sourceTransmissionId = tx1Id),
            ),
        )

        val detailState = ThreadListMapper.detailState("T1", details)!!

        assertTrue(detailState.howAttributed.any { it.text.contains("W7NPC heard in over 1") })
        assertTrue(detailState.howAttributed.any { it.text.contains("matched K7LWH's voice from over 1") })
        assertEquals(2, detailState.overs.size)
    }

    @Test
    fun `R_044 detailState is null for a threadId with no transmissions`() {
        assertNull(ThreadListMapper.detailState("NOPE", listOf(detail("TX1", 0L, threadId = "T1"))))
    }
}
