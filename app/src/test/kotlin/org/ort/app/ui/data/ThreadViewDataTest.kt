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
 * `Thread-Detail.dc.html`'s "how these were attributed" lines. Extended (audit V3 @3e2d4ee,
 * R-160/R-161/R-163): kind derivation, mode, span duration and the shared [pluralize] helper.
 */
class ThreadViewDataTest {

    private fun detail(
        id: String,
        startedAtUtcMillis: Long,
        threadId: String? = null,
        frequencyHz: Long? = 145_230_000L,
        attribution: Attribution = Attribution.unknown(),
        mode: String? = null,
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
        mode = mode,
    )

    @Test
    fun `R_044 no transmissions at all is the Empty state`() {
        assertEquals(ThreadListViewState.Empty, ThreadListMapper.listState(emptyList(), emptySet(), currentTier = 3))
    }

    @Test
    fun `R_044 every threadId null renders the honest Ungrouped state, never a fabricated conversation`() {
        val details = listOf(
            detail("TX1", 0L, frequencyHz = 145_230_000L),
            detail("TX2", 1_000L, frequencyHz = 145_230_000L, attribution = Attribution.confirmed("W7NPC", 0.9)),
            detail("TX3", 2_000L, frequencyHz = 146_960_000L),
        )

        val state = ThreadListMapper.listState(details, emptySet(), currentTier = 1) as ThreadListViewState.Ungrouped

        assertEquals(3, state.totalOvers)
        assertEquals(2, state.byFrequency.size)
        assertEquals(1, state.currentTier)
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

        val state = ThreadListMapper.listState(details, emptySet(), currentTier = 3) as ThreadListViewState.Grouped

        val card = state.cards.single()
        assertEquals("T1", card.threadId)
        assertEquals(2, card.overCount)
        assertEquals("W7NPC and K7LWH", card.titleText)
        // two distinct stations, one over each, trivially alternating — the QSO rule (R-160) applies;
        // the null/Activity/QSO boundary cases each get their own dedicated `R_160` test below.
        assertEquals("QSO", card.kindLabel)
        assertTrue(card.metaText.contains("2 confirmed"))
    }

    @Test
    fun `R_044 kind is never guessed even when it could look like a QSO`() {
        val details = listOf(detail("TX1", 0L, threadId = "T1", attribution = Attribution.confirmed("W7NPC", 0.9)))

        val state = ThreadListMapper.listState(details, emptySet(), currentTier = 3) as ThreadListViewState.Grouped

        assertNull(state.cards.single().kindLabel)
    }

    @Test
    fun `R_160 two stations strictly alternating on the thread render as QSO`() {
        val details = listOf(
            detail("TX1", 0L, threadId = "T1", attribution = Attribution.confirmed("W7NPC", 0.9)),
            detail("TX2", 1_000L, threadId = "T1", attribution = Attribution.confirmed("K7LWH", 0.9)),
            detail("TX3", 2_000L, threadId = "T1", attribution = Attribution.confirmed("W7NPC", 0.9)),
            detail("TX4", 3_000L, threadId = "T1", attribution = Attribution.confirmed("K7LWH", 0.9)),
        )

        val state = ThreadListMapper.listState(details, emptySet(), currentTier = 3) as ThreadListViewState.Grouped

        assertEquals("QSO", state.cards.single().kindLabel)
    }

    @Test
    fun `R_160 one station repeating renders as Activity`() {
        val details = listOf(
            detail("TX1", 0L, threadId = "T1", attribution = Attribution.confirmed("W7NPC", 0.9)),
            detail("TX2", 1_000L, threadId = "T1", attribution = Attribution.confirmed("W7NPC", 0.9)),
            detail("TX3", 2_000L, threadId = "T1", attribution = Attribution.confirmed("W7NPC", 0.9)),
        )

        val state = ThreadListMapper.listState(details, emptySet(), currentTier = 3) as ThreadListViewState.Grouped

        assertEquals("Activity", state.cards.single().kindLabel)
    }

    @Test
    fun `R_160 three distinct stations never guesses a kind`() {
        val details = listOf(
            detail("TX1", 0L, threadId = "T1", attribution = Attribution.confirmed("W7NPC", 0.9)),
            detail("TX2", 1_000L, threadId = "T1", attribution = Attribution.confirmed("K7LWH", 0.9)),
            detail("TX3", 2_000L, threadId = "T1", attribution = Attribution.confirmed("N7ABC", 0.9)),
        )

        val state = ThreadListMapper.listState(details, emptySet(), currentTier = 3) as ThreadListViewState.Grouped

        assertNull(state.cards.single().kindLabel)
    }

    @Test
    fun `R_160 two stations not alternating never guesses a kind`() {
        val details = listOf(
            detail("TX1", 0L, threadId = "T1", attribution = Attribution.confirmed("W7NPC", 0.9)),
            detail("TX2", 1_000L, threadId = "T1", attribution = Attribution.confirmed("W7NPC", 0.9)),
            detail("TX3", 2_000L, threadId = "T1", attribution = Attribution.confirmed("K7LWH", 0.9)),
        )

        val state = ThreadListMapper.listState(details, emptySet(), currentTier = 3) as ThreadListViewState.Grouped

        assertNull(state.cards.single().kindLabel)
    }

    @Test
    fun `R_160 an unresolved over in the thread never guesses a kind`() {
        val details = listOf(
            detail("TX1", 0L, threadId = "T1", attribution = Attribution.confirmed("W7NPC", 0.9)),
            detail("TX2", 1_000L, threadId = "T1", attribution = Attribution.unknown()),
        )

        val state = ThreadListMapper.listState(details, emptySet(), currentTier = 3) as ThreadListViewState.Grouped

        assertNull(state.cards.single().kindLabel)
    }

    @Test
    fun `R_044 an ambiguous-only thread is tinted and titled honestly, not with a guessed callsign`() {
        val details = listOf(detail("TX1", 0L, threadId = "T1", attribution = Attribution.ambiguous()))

        val state = ThreadListMapper.listState(details, emptySet(), currentTier = 3) as ThreadListViewState.Grouped

        val card = state.cards.single()
        assertTrue(card.ambiguous)
        assertEquals("Ambiguous stations", card.titleText)
    }

    @Test
    fun `R_044 a first-heard station in the thread carries the NEW flag`() {
        val details = listOf(detail("TX1", 0L, threadId = "T1", attribution = Attribution.confirmed("W7NPC", 0.9)))

        val state = ThreadListMapper.listState(details, setOf("TX1"), currentTier = 3) as ThreadListViewState.Grouped

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

    @Test
    fun `R_161 mode renders when the data has it and is null when no over in the thread recorded one`() {
        val withMode = listOf(
            detail("TX1", 0L, threadId = "T1", attribution = Attribution.confirmed("W7NPC", 0.9), mode = "FM"),
        )
        val withoutMode = listOf(
            detail("TX2", 0L, threadId = "T2", attribution = Attribution.confirmed("W7NPC", 0.9)),
        )

        assertEquals("FM", ThreadListMapper.detailState("T1", withMode)!!.modeLabel)
        assertNull(ThreadListMapper.detailState("T2", withoutMode)!!.modeLabel)
    }

    @Test
    fun `R_161 the span line reports seconds under a minute and minutes and seconds beyond it`() {
        val underAMinute = listOf(
            detail("TX1", 0L, threadId = "T1"),
            detail("TX2", 38_000L, threadId = "T1"),
        )
        val overAMinute = listOf(
            detail("TX3", 0L, threadId = "T2"),
            detail("TX4", 115_000L, threadId = "T2"),
        )

        assertTrue(ThreadListMapper.detailState("T1", underAMinute)!!.metaText.endsWith("38 s"))
        assertTrue(ThreadListMapper.detailState("T2", overAMinute)!!.metaText.endsWith("1 m 55 s"))
    }

    @Test
    fun `R_163 pluralize never drifts into 1 overs`() {
        assertEquals("1 over", pluralize(1, "over"))
        assertEquals("2 overs", pluralize(2, "over"))
        assertEquals("0 overs", pluralize(0, "over"))
        assertEquals("1 station", pluralize(1, "station"))
        assertEquals("3 stations", pluralize(3, "station"))
    }

    // -- ThreadGroupingMapper.reasoningFor, still used by detailState's per-over reasoning line
    // (its own dedicated test file, ThreadGroupingMapperTest, was removed with the legacy
    // ThreadGroupViewState/currentThreadGroups it tested — this keeps reasoningFor itself covered). --

    @Test
    fun `R_044 each over's reasoning names its real attribution state, not a generic label`() {
        val details = listOf(
            detail("TX1", 0L, threadId = "T1", attribution = Attribution.confirmed("W7NPC", 0.9)),
            detail("TX2", 1_000L, threadId = "T1", attribution = Attribution.ambiguous()),
            detail("TX3", 2_000L, threadId = "T1", attribution = Attribution.unknown()),
        )

        val overs = ThreadListMapper.detailState("T1", details)!!.overs.associateBy { it.transmissionId }

        assertEquals("callsign confirmed in this transmission", overs.getValue("TX1").reasoning)
        assertEquals("more than one candidate; the system will not choose", overs.getValue("TX2").reasoning)
        assertEquals("no callsign resolved", overs.getValue("TX3").reasoning)
    }

    @Test
    fun `R_332_copy an INFERRED voice match names the method and the source's time, never the raw ULID`() {
        val sourceId = TransmissionId(Ulid.generate())
        val details = listOf(
            detail(sourceId.toString(), 0L, threadId = "T1", attribution = Attribution.confirmed("W7NPC", 0.9)),
            detail(
                "TX2",
                1_000L,
                threadId = "T1",
                attribution = Attribution.inferred("K7LWH", 0.82, sourceTransmissionId = sourceId),
            ),
        )

        val over = ThreadListMapper.detailState("T1", details)!!.overs.single { it.transmissionId == "TX2" }

        assertEquals("inherited by voice from ${over.sourceTimeLabel}", over.reasoning)
        assertTrue("expected a real time link, not the raw ULID", over.sourceTimeLabel != null)
        assertTrue(!over.reasoning.contains(sourceId.toString()))
    }

    @Test
    fun `R_332_copy an unresolvable source id names the method honestly, with nothing to link`() {
        val sourceId = TransmissionId(Ulid.generate())
        val details = listOf(
            detail(
                "TX2",
                1_000L,
                threadId = "T1",
                attribution = Attribution.inferred("K7LWH", 0.82, sourceTransmissionId = sourceId),
            ),
        )

        val over = ThreadListMapper.detailState("T1", details)!!.overs.single()

        assertEquals("inherited by voice (source transmission not recorded)", over.reasoning)
        assertNull(over.sourceTimeLabel)
        assertTrue(!over.reasoning.contains(sourceId.toString()))
    }

    @Test
    fun `R_332_copy a human-typed correction reads inherited by callsign, with nothing to link`() {
        val details = listOf(
            detail("TX1", 0L, threadId = "T1", attribution = Attribution.unknown().withCorrection("K7LWH")),
        )

        val over = ThreadListMapper.detailState("T1", details)!!.overs.single()

        assertEquals("inherited by callsign", over.reasoning)
        assertNull(over.sourceTransmissionId)
        assertNull(over.sourceTimeLabel)
    }

    @Test
    fun `R_332_copy no source recorded at all and no correction stays the honest fallback`() {
        val details = listOf(
            detail("TX1", 0L, threadId = "T1", attribution = Attribution.inferred("K7LWH", 0.82)),
        )

        val over = ThreadListMapper.detailState("T1", details)!!.overs.single()

        assertEquals("inherited (source transmission not recorded)", over.reasoning)
        assertNull(over.sourceTimeLabel)
    }
}
