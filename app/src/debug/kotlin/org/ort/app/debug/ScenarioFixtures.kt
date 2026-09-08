package org.ort.app.debug

import android.content.Context
import org.ort.capture.android.codec.DeflatePredictiveCodec
import org.ort.capture.android.heartbeat.FileHeartbeatStore
import org.ort.capture.android.heartbeat.HeartbeatRecord
import org.ort.core.AttributionState
import org.ort.core.SystemClock
import org.ort.core.TransmissionState
import org.ort.data.entity.CallsignCandidateEntity
import org.ort.data.entity.LatticeSource
import org.ort.data.entity.PhoneticLatticeEntity
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TerminationReason
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
import org.ort.pipeline.capture.CaptureState
import java.io.File

/**
 * Shared fixture-building helpers for [Scenarios]' individual scenario builders — no test data
 * lives here itself (that would defeat the point of splitting scenarios into their own files);
 * this is only the boilerplate every scenario needs to fill in.
 *
 * spec/ui-conformance-plan.md WP0's own instruction: "Use the callsigns the artboards use... they
 * are fictional. No real callsigns, no voiceprints, no location, nothing from `corpus/`."
 */
internal object ScenarioFixtures {

    /** Every callsign the design canvas artboards use (this package's brief, verbatim) — fictional. */
    val CALLSIGNS: List<String> = listOf(
        "W7NPC", "K7LWH", "KA7LWH", "KE7QRS", "KE7QRF", "WA7HJR", "KJ7ABC", "N7XYZ", "KA7OEI",
    )

    /** Every session/transmission/etc. id a scenario writes carries this prefix (see [Scenarios.clearPriorScenarioData]). */
    const val SESSION_PREFIX: String = "scenario-"

    fun sessionId(scenario: String, suffix: String = ""): String =
        if (suffix.isEmpty()) "$SESSION_PREFIX$scenario" else "$SESSION_PREFIX$scenario-$suffix"

    fun session(
        id: String,
        startedAt: Long,
        endedAt: Long?,
        deviceTier: String? = null,
        terminationReason: TerminationReason? = null,
        gapCount: Int = 0,
    ): SessionEntity = SessionEntity(
        id = id,
        startedAt = startedAt,
        endedAt = endedAt,
        profileId = null,
        deviceTier = deviceTier,
        appVersion = "scenario",
        terminationReason = terminationReason,
        sourceId = null,
        schemaVersion = org.ort.data.OrtDatabase.SCHEMA_VERSION,
        gapCount = gapCount,
    )

    /**
     * A [TransmissionEntity], every field given an honest default (constitution I: an attribution
     * always carries a state — [attributionState] has no default, every caller must choose one).
     */
    @Suppress("LongParameterList")
    fun transmission(
        id: String,
        sessionId: String,
        threadId: String? = null,
        startedAtUtc: Long,
        durationMs: Long = 4_200L,
        samplePosition: Long,
        frequencyHz: Long?,
        signalStrength: Double? = 7.0,
        attributionState: AttributionState,
        stationId: String? = null,
        attributionConfidence: Double? = null,
        attributionSourceTransmissionId: String? = null,
        corrected: Boolean = false,
        processingState: TransmissionState = TransmissionState.COMPLETE,
        rejectionReason: String? = null,
        isReprocessCandidate: Boolean = false,
        voiceprintId: String? = null,
    ): TransmissionEntity = TransmissionEntity(
        id = id,
        sessionId = sessionId,
        threadId = threadId,
        startedAtUtc = startedAtUtc,
        endedAtUtc = startedAtUtc + durationMs,
        durationMs = durationMs,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = frequencyHz,
        frequencyProvenance = if (frequencyHz != null) "rig" else "unknown",
        mode = "FM",
        signalStrength = signalStrength,
        channelName = null,
        voiceprintId = voiceprintId,
        attributionState = attributionState,
        stationId = stationId,
        attributionConfidence = attributionConfidence,
        attributionSourceTransmissionId = attributionSourceTransmissionId,
        corrected = corrected,
        processingState = processingState,
        rejectionReason = rejectionReason,
        samplePosition = samplePosition,
        monotonicStartNanos = samplePosition * 1_000_000L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = "cpu",
        isReprocessCandidate = isReprocessCandidate,
    )

    fun transcript(
        id: String,
        transmissionId: String,
        text: String,
        pass: TranscriptPass = TranscriptPass.B,
        isCurrent: Boolean,
        createdAt: Long,
        confidence: Double? = 0.9,
    ): TranscriptEntity = TranscriptEntity(
        id = id,
        transmissionId = transmissionId,
        pass = pass,
        text = text,
        modelId = if (pass == TranscriptPass.A) "streaming-tiny.en" else "distil-small.en",
        modelVersion = "1",
        quantization = null,
        decodeParams = null,
        noSpeechProb = null,
        confidence = confidence,
        isCurrent = isCurrent,
        createdAt = createdAt,
    )

    fun lattice(
        id: String,
        transmissionId: String,
        source: LatticeSource = LatticeSource.ACOUSTIC,
        modelId: String? = "phonetic-lattice-v1",
        createdAt: Long,
    ): PhoneticLatticeEntity = PhoneticLatticeEntity(
        id = id,
        transmissionId = transmissionId,
        source = source,
        unitsBlob = "whiskey|seven|november|papa|charlie",
        modelId = modelId,
        createdAt = createdAt,
    )

    fun candidate(
        id: String,
        transmissionId: String,
        callsign: String,
        rank: Int,
        score: Double,
        grammarValid: Boolean = true,
        ituPrefix: String? = "K",
        ituCountry: String? = "United States",
        priorBreakdown: Map<String, Double>? = null,
        databaseHit: Boolean = true,
        selected: Boolean,
    ): CallsignCandidateEntity = CallsignCandidateEntity(
        id = id,
        transmissionId = transmissionId,
        callsign = callsign,
        rank = rank,
        score = score,
        grammarValid = grammarValid,
        ituPrefix = ituPrefix,
        ituCountry = ituCountry,
        priorBreakdown = priorBreakdown,
        databaseHit = databaseHit,
        selected = selected,
    )

    /**
     * Writes a small, genuinely decodable "retained audio" fixture at the exact path
     * [TransmissionEntity.audioPath] computes — a low tone, encoded through the same
     * [org.ort.capture.android.codec.LosslessCodec] (`DeflatePredictiveCodec`) `RealSegmentSink`
     * encodes with and [org.ort.pipeline.passb.FlacSegmentAudioProvider]/
     * [org.ort.app.ui.audio.RealTransmissionAudioPlayer] decode with — despite the `.flac`
     * extension, this project's retained-audio codec is not container-FLAC (see
     * `FlacSegmentAudioProvider`'s own doc comment); a real, valid file for *this* codec is what
     * "the reader expects" (this package's brief, verbatim), not a bare placeholder that would
     * only satisfy `File.isFile`.
     */
    fun writeAudioFixture(context: Context, transmission: TransmissionEntity) {
        val durationMs = transmission.durationMs.coerceAtLeast(200L)
        val sampleCount = (durationMs * SAMPLES_PER_MS).toInt()
        val pcmBytes = ByteArray(sampleCount * 2)
        for (i in 0 until sampleCount) {
            val sample = (
                TONE_AMPLITUDE * kotlin.math.sin(
                    2.0 * Math.PI * TONE_HZ * i / (SAMPLES_PER_MS * 1000),
                )
                ).toInt()
            pcmBytes[i * 2] = (sample and 0xFF).toByte()
            pcmBytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        val encoded = DeflatePredictiveCodec().encode(pcmBytes)
        val file = File(context.filesDir, transmission.audioPath())
        file.parentFile?.mkdirs()
        file.writeBytes(encoded)
    }

    private const val SAMPLES_PER_MS = 16L
    private const val TONE_HZ = 440.0
    private const val TONE_AMPLITUDE = 6_000

    /**
     * Publishes [CaptureState.capturing] and a fresh heartbeat for [sessionId], so a scenario
     * meant to look like a session that is genuinely running (`Now`/`Capture-Status`'s live
     * facts, the live bar) reads that way rather than idle — constitution IV: liveness is proven
     * by heartbeat, so a "live" scenario must leave one a caller's own liveness check accepts, not
     * just flip [CaptureState].
     */
    fun markCapturing(context: Context, sessionId: String, samplePosition: Long = 0L) {
        CaptureState.capturing(sessionId)
        FileHeartbeatStore(File(context.filesDir, "heartbeat.txt")).write(
            HeartbeatRecord(
                sessionId = sessionId,
                monotonicNanos = SystemClock.monotonicNanos(),
                wallMillis = SystemClock.wallMillis(),
                samplePosition = samplePosition,
            ),
        )
    }
}
