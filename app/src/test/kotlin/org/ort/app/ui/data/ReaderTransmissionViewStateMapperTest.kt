package org.ort.app.ui.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.Attribution

/**
 * `TransmissionDetail` -> `TransmissionListEntryViewState`, compose-agnostic (build-plan P14).
 *
 * FR-UI-1: a transmission appears in the live view as it is captured, newest first, and a
 * superseded partial is *visibly* superseded rather than silently replaced. The ordering half of
 * FR-UI-1 is established by `ReaderPollingTest` (real DB read order); this class tests the other
 * half — that the mapping itself never hides a supersession or fabricates a transcript.
 */
class ReaderTransmissionViewStateMapperTest {

    private fun detail(
        id: String = "01ARZ3NDEKTSV4RRFFQ69G5FAV",
        currentTranscriptText: String? = null,
        supersededTranscriptTexts: List<String> = emptyList(),
        attribution: Attribution = Attribution.unknown(),
        hasAudio: Boolean = false,
    ) = TransmissionDetail(
        id = id,
        startedAtUtcMillis = 1_000L,
        frequencyHz = 146_960_000L,
        durationMs = 4_200L,
        signalStrength = 7.0,
        attribution = attribution,
        currentTranscriptText = currentTranscriptText,
        supersededTranscriptTexts = supersededTranscriptTexts,
        hasAudio = hasAudio,
    )

    @Test
    fun `FR_UI_1 a transmission with no transcript row yet shows the honest not-yet-transcribed label`() {
        val view = ReaderTransmissionViewStateMapper.listEntry(detail(currentTranscriptText = null))

        assertEquals("(captured, not yet transcribed)", view.transcriptText)
    }

    @Test
    fun `FR_UI_1 a transmission with a current transcript and no earlier versions carries no revision note`() {
        val view = ReaderTransmissionViewStateMapper.listEntry(
            detail(
                currentTranscriptText = "this is whiskey seven november papa charlie",
                supersededTranscriptTexts = emptyList(),
            ),
        )

        assertEquals("this is whiskey seven november papa charlie", view.transcriptText)
        assertNull(view.revisionNote)
    }

    @Test
    fun `FR_UI_1 a superseded partial is visibly marked, never silently replaced`() {
        val view = ReaderTransmissionViewStateMapper.listEntry(
            detail(
                currentTranscriptText = "roger that, good copy on the repeater this morning",
                supersededTranscriptTexts = listOf("roger that good copy on the repeat this morning"),
            ),
        )

        assertNotNull(view.revisionNote)
        assertTrue(view.revisionNote!!.contains("1"), "expected the revision count in the note: ${view.revisionNote}")
    }

    @Test
    fun `FR_UI_1 multiple superseded versions are counted, not collapsed to a boolean`() {
        val view = ReaderTransmissionViewStateMapper.listEntry(
            detail(
                currentTranscriptText = "final text",
                supersededTranscriptTexts = listOf("first partial", "second partial"),
            ),
        )

        assertTrue(view.revisionNote!!.contains("2"), "expected 2 earlier versions in the note: ${view.revisionNote}")
    }

    @Test
    fun `frequency renders in MHz and the attribution is carried through unchanged`() {
        val attribution = Attribution.confirmed("W7NPC", 0.95)
        val view = ReaderTransmissionViewStateMapper.listEntry(detail(attribution = attribution))

        assertEquals("146.960", view.frequencyLabel)
        assertEquals(attribution, view.attribution)
    }

    @Test
    fun `a null frequency renders honestly rather than as zero`() {
        val view = ReaderTransmissionViewStateMapper.listEntry(detail().copy(frequencyHz = null))

        assertEquals("—", view.frequencyLabel)
    }
}
