package org.ort.app.ui.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.ort.core.Attribution
import org.ort.data.entity.StationEntity

/** FR-UI-9 (station view) and FR-UI-10 (frequency view) — the pure view-state mapping (build-plan P17). */
class StationsAndFrequenciesTest {

    private fun station(
        id: String = "W7NPC",
        callsign: String? = "W7NPC",
        transmissionCount: Int = 4,
        lastHeardAt: Long? = 1_700_000_000_000L,
    ) = StationEntity(
        id = id,
        callsign = callsign,
        firstHeardAt = 0L,
        lastHeardAt = lastHeardAt,
        transmissionCount = transmissionCount,
        isUserPinned = false,
        notes = null,
        userName = null,
        frequenciesHeard = null,
        activityByHourDow = null,
        potaRefs = null,
        spokenGrids = null,
        ituRegionFromPrefix = null,
        overCountsByAttributionState = null,
    )

    private fun detail(id: String) = TransmissionDetail(
        id = id,
        startedAtUtcMillis = 0L,
        frequencyHz = 146_960_000L,
        durationMs = 1_000L,
        signalStrength = null,
        attribution = Attribution.confirmed("W7NPC", 0.9),
        currentTranscriptText = "hello",
        supersededTranscriptTexts = emptyList(),
        hasAudio = false,
    )

    @Test
    fun `a station list row shows the callsign, transmission count and last-heard time`() {
        val entry = StationViewMapper.listEntry(station())

        assertEquals("W7NPC", entry.label)
        assertEquals(4, entry.transmissionCount)
        assertEquals("2023-11-14 22:13 UTC", entry.lastHeardLabel)
    }

    @Test
    fun `a station never heard shows an honest null last-heard label, not a fabricated date`() {
        val entry = StationViewMapper.listEntry(station(lastHeardAt = null))

        assertNull(entry.lastHeardLabel)
    }

    @Test
    fun `station detail counts real transmissions and carries the activity pattern through unchanged`() {
        val pattern = ActivityPatternMapper.buildPattern(emptyList(), emptyList(), 0L)

        val view = StationViewMapper.detail(
            stationId = "W7NPC",
            label = "W7NPC",
            transmissions = listOf(detail("TX1"), detail("TX2")),
            activityPattern = pattern,
        )

        assertEquals(2, view.transmissionCount)
        assertEquals(pattern, view.activityPattern)
        assertEquals(listOf("TX1", "TX2"), view.transmissions.map { it.id })
    }

    @Test
    fun `a frequency list row is labelled in MHz`() {
        val entry = FrequencyViewMapper.listEntry(146_960_000L, transmissionCount = 3)

        assertEquals("146.960 MHz", entry.label)
        assertEquals(3, entry.transmissionCount)
    }

    @Test
    fun `frequency detail counts real transmissions and carries the activity pattern through unchanged`() {
        val pattern = ActivityPatternMapper.buildPattern(emptyList(), emptyList(), 0L)

        val view = FrequencyViewMapper.detail(
            frequencyHz = 146_960_000L,
            transmissions = listOf(detail("TX1")),
            activityPattern = pattern,
        )

        assertEquals("146.960 MHz", view.label)
        assertEquals(1, view.transmissionCount)
        assertEquals(pattern, view.activityPattern)
    }
}
