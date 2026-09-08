package org.ort.app.ui.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.core.Attribution

/**
 * The "Now" home's header counts (`design/canvas/Main.dc.html`: "412 overs · 19 stations · 2
 * bands") — build-plan P14. Only the two counts this prompt can honestly compute from real data
 * are built here (band counting needs rig/frequency-table data no prompt has wired to the reader
 * yet); station counting must never count an unattributed over as a station.
 */
class NowSummaryMapperTest {

    private fun detail(attribution: Attribution) = TransmissionDetail(
        id = "id",
        startedAtUtcMillis = 0L,
        frequencyHz = null,
        durationMs = 0L,
        signalStrength = null,
        attribution = attribution,
        currentTranscriptText = null,
        supersededTranscriptTexts = emptyList(),
        hasAudio = false,
    )

    @Test
    fun `overCount is the number of transmissions, regardless of attribution`() {
        val summary = NowSummaryMapper.from(
            listOf(detail(Attribution.unknown()), detail(Attribution.unknown()), detail(Attribution.ambiguous())),
        )

        assertEquals(3, summary.overCount)
    }

    @Test
    fun `stationCount counts distinct attributed stations, never an unattributed over as a station`() {
        val summary = NowSummaryMapper.from(
            listOf(
                detail(Attribution.confirmed("W7NPC", 0.9)),
                detail(Attribution.confirmed("W7NPC", 0.9)),
                detail(Attribution.inferred("K7LWH", 0.8)),
                detail(Attribution.unknown()),
                detail(Attribution.ambiguous()),
            ),
        )

        assertEquals(2, summary.stationCount)
    }

    @Test
    fun `no transmissions yields a zero, honest summary`() {
        val summary = NowSummaryMapper.from(emptyList())

        assertEquals(0, summary.overCount)
        assertEquals(0, summary.stationCount)
    }
}
