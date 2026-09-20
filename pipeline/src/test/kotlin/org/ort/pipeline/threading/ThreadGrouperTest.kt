package org.ort.pipeline.threading

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.data.entity.ThreadJoinReason
import org.ort.data.entity.ThreadKind
import org.ort.data.entity.ThreadKindSource

/**
 * FR-SPK-5: [ThreadGrouper] is pure and DB-free, so every case here runs on a plain JVM with
 * hand-built [TransmissionClosure]/[PriorThreadContext] fixtures — no Robolectric, no fake wall
 * clock needed, because every timestamp involved is a value on the fixture, not a live reading.
 */
class ThreadGrouperTest {

    @Suppress("LongParameterList") // a plain test fixture builder -- every parameter is an independent fixture field
    private fun closure(
        id: String = "TX2",
        sessionId: String = "S1",
        samplePosition: Long = 1_000L,
        startedAtUtc: Long = 60_000L,
        endedAtUtc: Long = 65_000L,
        frequencyHz: Long? = 146_520_000L,
        channelName: String? = null,
        stationId: String? = null,
        voiceprintId: String? = null,
    ) = TransmissionClosure(
        transmissionId = id,
        sessionId = sessionId,
        samplePosition = samplePosition,
        startedAtUtc = startedAtUtc,
        endedAtUtc = endedAtUtc,
        frequencyHz = frequencyHz,
        channelName = channelName,
        stationId = stationId,
        voiceprintId = voiceprintId,
    )

    private fun prior(
        threadId: String = "THREAD1",
        frequencyHz: Long? = 146_520_000L,
        channelName: String? = null,
        lastEndedAtUtc: Long = 0L,
        kind: ThreadKind = ThreadKind.QSO,
        kindSource: ThreadKindSource = ThreadKindSource.DETECTED,
        voiceKeyCounts: Map<String, Int> = emptyMap(),
        transmissionCount: Int = 1,
    ) = PriorThreadContext(
        threadId = threadId,
        frequencyHz = frequencyHz,
        channelName = channelName,
        lastEndedAtUtc = lastEndedAtUtc,
        kind = kind,
        kindSource = kindSource,
        voiceKeyCounts = voiceKeyCounts,
        transmissionCount = transmissionCount,
    )

    @Test
    fun `AC_163 a second over on the same frequency within the gap joins the first over's thread`() {
        val decision = ThreadGrouper().decide(
            current = closure(startedAtUtc = 60_000L, frequencyHz = 146_520_000L),
            prior = prior(threadId = "T-QSO", frequencyHz = 146_520_000L, lastEndedAtUtc = 55_000L),
            unlistenedCaptureGapInBetween = false,
        )
        val expected = ThreadGroupingDecision.JoinExistingThread("T-QSO", ThreadJoinReason.SAME_FREQUENCY_WITHIN_GAP)
        assertEquals(expected, decision)
    }

    @Test
    fun `AC_165 scanner activity from many different resolved stations on one frequency still joins one thread`() {
        // The grouper does not look at who is speaking at all -- only frequency and gap. A third,
        // fourth, fifth distinct station on the same channel joins exactly like a second one would.
        val decision = ThreadGrouper().decide(
            current = closure(stationId = "KE7XYZ", frequencyHz = 462_562_500L),
            prior = prior(threadId = "T-SCANNER", frequencyHz = 462_562_500L, lastEndedAtUtc = 55_000L),
            unlistenedCaptureGapInBetween = false,
        )
        val expected =
            ThreadGroupingDecision.JoinExistingThread("T-SCANNER", ThreadJoinReason.SAME_FREQUENCY_WITHIN_GAP)
        assertEquals(expected, decision)
    }

    @Test
    fun `single over -- the first transmission in a session always starts its own thread`() {
        val decision = ThreadGrouper().decide(current = closure(), prior = null, unlistenedCaptureGapInBetween = false)
        assertEquals(ThreadGroupingDecision.StartNewThread(ThreadJoinReason.FIRST_TRANSMISSION_IN_SESSION), decision)
    }

    @Test
    fun `a gap beyond the configured threshold starts a new thread even on the same frequency`() {
        val config = ThreadGroupingConfig(gapThresholdMillis = 60_000L)
        val decision = ThreadGrouper(config).decide(
            current = closure(startedAtUtc = 200_000L, frequencyHz = 146_520_000L),
            prior = prior(frequencyHz = 146_520_000L, lastEndedAtUtc = 100_000L), // 100s gap > 60s threshold
            unlistenedCaptureGapInBetween = false,
        )
        assertEquals(ThreadJoinReason.NEW_THREAD_GAP_EXCEEDED, decision.reason)
        assertTrue(decision is ThreadGroupingDecision.StartNewThread)
    }

    @Test
    fun `a gap at exactly the threshold still joins -- the bound is inclusive`() {
        val config = ThreadGroupingConfig(gapThresholdMillis = 60_000L)
        val decision = ThreadGrouper(config).decide(
            current = closure(startedAtUtc = 160_000L, frequencyHz = 146_520_000L),
            prior = prior(frequencyHz = 146_520_000L, lastEndedAtUtc = 100_000L), // exactly 60s
            unlistenedCaptureGapInBetween = false,
        )
        assertTrue(decision is ThreadGroupingDecision.JoinExistingThread)
    }

    @Test
    fun `two interleaved frequencies -- a frequency change with no rig-channel match starts a new thread`() {
        val decision = ThreadGrouper().decide(
            current = closure(frequencyHz = 146_940_000L),
            prior = prior(frequencyHz = 146_520_000L, lastEndedAtUtc = 55_000L),
            unlistenedCaptureGapInBetween = false,
        )
        assertEquals(ThreadJoinReason.NEW_THREAD_FREQUENCY_CHANGED, decision.reason)
        assertTrue(decision is ThreadGroupingDecision.StartNewThread)
    }

    @Test
    fun `FR_SPK_5 a frequency change the Rig Module reports as the same channel still joins`() {
        val decision = ThreadGrouper().decide(
            current = closure(frequencyHz = 146_940_000L, channelName = "Memory 3"),
            prior = prior(frequencyHz = 146_520_000L, channelName = "Memory 3", lastEndedAtUtc = 55_000L),
            unlistenedCaptureGapInBetween = false,
        )
        assertEquals(ThreadJoinReason.SAME_RIG_CHANNEL_WITHIN_GAP, decision.reason)
        assertTrue(decision is ThreadGroupingDecision.JoinExistingThread)
    }

    @Test
    fun `local-microphone capture with no frequency on either side still joins by gap alone`() {
        val decision = ThreadGrouper().decide(
            current = closure(frequencyHz = null, channelName = null),
            prior = prior(frequencyHz = null, channelName = null, lastEndedAtUtc = 55_000L),
            unlistenedCaptureGapInBetween = false,
        )
        assertEquals(ThreadJoinReason.NO_FREQUENCY_INFO_WITHIN_GAP, decision.reason)
    }

    @Test
    fun `a gap that was never listened through breaks the thread even when arithmetic alone would allow it`() {
        val decision = ThreadGrouper().decide(
            current = closure(startedAtUtc = 60_000L, frequencyHz = 146_520_000L),
            // 5s -- comfortably within any gap threshold, yet still not to be bridged.
            prior = prior(frequencyHz = 146_520_000L, lastEndedAtUtc = 55_000L),
            unlistenedCaptureGapInBetween = true,
        )
        val expected = ThreadGroupingDecision.StartNewThread(ThreadJoinReason.NEW_THREAD_UNLISTENED_CAPTURE_GAP)
        assertEquals(expected, decision)
    }
}
