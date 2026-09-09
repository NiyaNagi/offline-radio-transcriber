package org.ort.app.ui.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.ort.core.Attribution
import org.ort.data.entity.StationEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
        // R-207 (register, design, V5 @f8430b8): the list row's last-heard is a bare local mono
        // "HH:mm" — never a raw ISO date with a literal "UTC" suffix (that bug lived here). Computed
        // via the device's own zone, like the mapper itself, rather than a hardcoded machine-local value.
        val expected = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(1_700_000_000_000L))
        assertEquals(expected, entry.lastHeardLabel)
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

    // --- R-070: the honest count-context text, never a fabricated "net control" ---

    @Test
    fun `R_070 countContext reads the confirmed-inferred split honestly`() {
        val mixed = StationViewMapper.countContext(confirmedCount = 3, inferredCount = 12)
        assertEquals("12 by voice match · 3 heard", mixed)
        assertEquals("1 over", StationViewMapper.countContext(confirmedCount = 1, inferredCount = 0))
        assertEquals("5 by voice match", StationViewMapper.countContext(confirmedCount = 0, inferredCount = 5))
        assertEquals("0 overs", StationViewMapper.countContext(confirmedCount = 0, inferredCount = 0))
    }

    // --- R-071: the frequency-usage summary ("145.230 almost always · 146.960 twice") ---

    @Test
    fun `R_071 frequencySummary reads the real split, most-used first`() {
        val summary = StationViewMapper.frequencySummary(listOf(146_960_000L to 2, 145_230_000L to 96))

        assertEquals("145.230 almost always · 146.960 twice", summary)
    }

    @Test
    fun `R_071 frequencySummary is empty with no frequency data, never a fabricated one`() {
        assertEquals("", StationViewMapper.frequencySummary(emptyList()))
    }

    // --- R-074: band/mode "what it is", never a guessed repeater/simplex claim ---

    @Test
    fun `R_074 whatItIs reads the real band and the most common mode`() {
        val label = FrequencyViewMapper.whatItIs(146_960_000L, listOf("FM", "FM", "FM", "USB"))

        assertEquals("2 m · FM", label)
    }

    @Test
    fun `R_074 whatItIs never claims a mode this frequency has no data for`() {
        val label = FrequencyViewMapper.whatItIs(146_960_000L, emptyList())

        assertEquals("2 m", label)
    }

    @Test
    fun `R_074 whatItIs is empty for a frequency outside every known amateur band`() {
        val label = FrequencyViewMapper.whatItIs(99_999_999_999L, emptyList())

        assertEquals("", label)
    }

    // --- R-591: FQ03's closing paragraph narrates a cause from its kind, never the raw list text ---

    @Test
    fun `R_591 an activation cause narrates as a real sentence`() {
        val cause = FrequencyChangeCause(
            label = "K-4412 activation",
            kind = FrequencyChangeCauseKind.ACTIVATION,
        )

        assertEquals("an activation pulled the regulars over", narrateFrequencyChangeCause(cause))
    }

    @Test
    fun `R_591 a new-station cause narrates the real callsign, never a fragment of the list label`() {
        val cause = FrequencyChangeCause(
            label = "KE7QRS · 1 over · first time heard",
            kind = FrequencyChangeCauseKind.NEW_STATION,
            subjectId = "KE7QRS",
        )

        assertEquals(
            "a station heard for the first time, KE7QRS, brought the regulars out",
            narrateFrequencyChangeCause(cause),
        )
    }

    @Test
    fun `R_591 a net cause narrates as a real sentence`() {
        val cause = FrequencyChangeCause(label = "Tuesday net", kind = FrequencyChangeCauseKind.NET)

        assertEquals("the weekly net ran here", narrateFrequencyChangeCause(cause))
    }

    @Test
    fun `R_591 an unknown-kind cause narrates to null, never a spliced fragment`() {
        val unidentified = FrequencyChangeCause(label = "4 unidentified voices, 4 overs", isUnidentified = true)
        val newStationWithNoSubject = FrequencyChangeCause(
            label = "KE7QRS · 1 over · first time heard",
            kind = FrequencyChangeCauseKind.NEW_STATION,
            // subjectId omitted — the one fact the sentence needs is honestly absent.
        )

        assertNull(narrateFrequencyChangeCause(unidentified))
        assertNull(narrateFrequencyChangeCause(newStationWithNoSubject))
    }
}
