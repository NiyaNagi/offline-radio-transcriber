package org.ort.pipeline.threading

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.data.entity.ThreadKind
import org.ort.data.entity.ThreadKindSource

/** FR-SPK-27/28: pure, DB-free, like [ThreadGrouper] itself. */
class ThreadKindClassifierTest {

    private fun closure(stationId: String? = null, voiceprintId: String? = null) = TransmissionClosure(
        transmissionId = "TX",
        sessionId = "S1",
        samplePosition = 0L,
        startedAtUtc = 0L,
        endedAtUtc = 1_000L,
        frequencyHz = 146_520_000L,
        channelName = null,
        stationId = stationId,
        voiceprintId = voiceprintId,
    )

    @Test
    fun `AC_163 exactly two distinct voices classifies as a QSO`() {
        val kind = ThreadKindClassifier.classify(
            previousKind = ThreadKind.UNKNOWN,
            previousKindSource = ThreadKindSource.DETECTED,
            voiceKeyCountsBeforeCurrent = mapOf("K7ABC" to 1),
            current = closure(stationId = "W7XYZ"),
            transmissionCountBeforeCurrent = 1,
        )
        assertEquals(ThreadKind.QSO, kind)
    }

    @Test
    fun `a single voice so far, with no callsign yet on the current over, stays unknown`() {
        val kind = ThreadKindClassifier.classify(
            previousKind = ThreadKind.UNKNOWN,
            previousKindSource = ThreadKindSource.DETECTED,
            voiceKeyCountsBeforeCurrent = emptyMap(),
            current = closure(stationId = null, voiceprintId = null),
            transmissionCountBeforeCurrent = 0,
        )
        assertEquals(ThreadKind.UNKNOWN, kind)
    }

    @Test
    fun `AC_165 three or more distinct voices on the channel classifies as scanner activity`() {
        val kind = ThreadKindClassifier.classify(
            previousKind = ThreadKind.QSO,
            previousKindSource = ThreadKindSource.DETECTED,
            voiceKeyCountsBeforeCurrent = mapOf("A" to 1, "B" to 1),
            current = closure(stationId = "C"),
            transmissionCountBeforeCurrent = 2,
        )
        assertEquals(ThreadKind.SCANNER, kind)
    }

    @Test
    fun `AC_164 a dominant voice alternating with several others over enough overs is a net`() {
        // 6 transmissions, dominant voice "NET-CONTROL" heard 3 of them (50% share), 4 distinct
        // voices total (3 "others" plus the dominant one) -- both FR-SPK-27 thresholds this
        // classifier uses are met.
        val kind = ThreadKindClassifier.classify(
            previousKind = ThreadKind.UNKNOWN,
            previousKindSource = ThreadKindSource.DETECTED,
            voiceKeyCountsBeforeCurrent = mapOf("NET-CONTROL" to 2, "A" to 1, "B" to 1, "C" to 1),
            current = closure(voiceprintId = "NET-CONTROL"),
            transmissionCountBeforeCurrent = 5,
        )
        assertEquals(ThreadKind.NET, kind)
    }

    @Test
    fun `enough transmissions but too few distinct other voices is not yet a net`() {
        // 5 total, dominant voice present 4 of them (80% share -- comfortably over the dominance
        // bar) but only one other voice ever heard -- FR-SPK-27's "many others" is not satisfied,
        // so this stays whatever a plain two-voice count would be (QSO), never NET.
        val kind = ThreadKindClassifier.classify(
            previousKind = ThreadKind.UNKNOWN,
            previousKindSource = ThreadKindSource.DETECTED,
            voiceKeyCountsBeforeCurrent = mapOf("NET-CONTROL" to 3, "A" to 1),
            current = closure(voiceprintId = "NET-CONTROL"),
            transmissionCountBeforeCurrent = 4,
        )
        assertEquals(ThreadKind.QSO, kind)
    }

    @Test
    fun `FR_SPK_28 once a thread is marked net it stays net regardless of the next voice`() {
        val kind = ThreadKindClassifier.classify(
            previousKind = ThreadKind.NET,
            previousKindSource = ThreadKindSource.DETECTED,
            voiceKeyCountsBeforeCurrent = mapOf("NET-CONTROL" to 5, "A" to 1, "B" to 1, "C" to 1),
            current = closure(voiceprintId = "brand-new-unrelated-voice"),
            transmissionCountBeforeCurrent = 8,
        )
        assertEquals(ThreadKind.NET, kind)
    }

    @Test
    fun `FR_SPK_28 a user-cleared marking is never overwritten by automatic classification`() {
        val kind = ThreadKindClassifier.classify(
            previousKind = ThreadKind.QSO, // the user cleared the NET marking by hand
            previousKindSource = ThreadKindSource.USER,
            voiceKeyCountsBeforeCurrent = mapOf("NET-CONTROL" to 2, "A" to 1, "B" to 1),
            current = closure(voiceprintId = "NET-CONTROL"),
            transmissionCountBeforeCurrent = 4,
        )
        assertEquals(ThreadKind.QSO, kind)
    }
}
