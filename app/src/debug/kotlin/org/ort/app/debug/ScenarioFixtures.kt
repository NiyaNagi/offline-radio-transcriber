package org.ort.app.debug

import android.content.Context
import org.ort.app.ui.data.ChecksumState
import org.ort.app.ui.data.ModelCatalog
import org.ort.app.ui.data.ModelCatalogEntry
import org.ort.app.ui.data.ModelId
import org.ort.capture.android.codec.DeflatePredictiveCodec
import org.ort.capture.android.heartbeat.FileHeartbeatStore
import org.ort.capture.android.heartbeat.HeartbeatRecord
import org.ort.core.AttributionState
import org.ort.core.SystemClock
import org.ort.core.TransmissionState
import org.ort.data.entity.CallsignCandidateEntity
import org.ort.data.entity.LatticeSlotEntity
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

    /**
     * `captureMode`/`audioRouteKind`/`audioRouteLabel`/`bluetoothProfile`/`rigTransport` are the
     * v7 columns FR-CAP-13 added (`SessionEntity`'s own kdoc) — stored as `:core`/`:rig` enum
     * `.name` strings, exactly as `RealCaptureService` itself writes them (never a shortcut schema
     * for scenario data). All five default `null` ("not tracked"), matching a pre-v7 row honestly,
     * so every scenario written before P19/WPI keeps compiling and rendering unchanged.
     *
     * `rigDescriptorId`/`audioRouteVerified`/`audioNativeRateHz` are E2-A07's v10 columns
     * (`SessionEntity`'s own kdoc) — also default `null` (a pre-v10 row's own honest state), so a
     * scenario that never names them keeps rendering exactly as it did before this round.
     */
    @Suppress("LongParameterList")
    fun session(
        id: String,
        startedAt: Long,
        endedAt: Long?,
        deviceTier: String? = null,
        terminationReason: TerminationReason? = null,
        gapCount: Int = 0,
        captureMode: String? = null,
        audioRouteKind: String? = null,
        audioRouteLabel: String? = null,
        bluetoothProfile: String? = null,
        rigTransport: String? = null,
        rigDescriptorId: String? = null,
        audioRouteVerified: Boolean? = null,
        audioNativeRateHz: Int? = null,
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
        captureMode = captureMode,
        audioRouteKind = audioRouteKind,
        audioRouteLabel = audioRouteLabel,
        bluetoothProfile = bluetoothProfile,
        rigTransport = rigTransport,
        rigDescriptorId = rigDescriptorId,
        audioRouteVerified = audioRouteVerified,
        audioNativeRateHz = audioNativeRateHz,
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
     * R-421 (schema v5, register): the ordered [LatticeSlotEntity] rows for one **text-anchored**
     * candidate lattice (its own [lattice] row must be seeded with `source =
     * `[org.ort.data.entity.LatticeSource.TEXT_DERIVED]` — an acoustic lattice's slots carry no char
     * span at all, per [LatticeSlotEntity]'s own doc comment, so pairing these with an ACOUSTIC
     * lattice would itself be a fixture inconsistency). One row per (unit, spoken word) in
     * [unitsAndWords], in order — [org.ort.app.ui.screens.TransmissionDetailScreen]'s D01/D03
     * transcript highlight ([org.ort.app.ui.data.CorrectionPolling.winningCharSpan]) had nothing to
     * exercise before this: every scenario's transmissions spelled their callsign phonetically
     * ("kilo echo seven quebec romeo sierra"), which the screen's own literal-substring fallback can
     * never match, and no scenario wrote a single [LatticeSlotEntity] row.
     *
     * Each [charStart]/[charEnd][LatticeSlotEntity] pair is *found*, never hand-computed: this
     * locates [word] inside [transcriptText] starting just past the previous slot's own end, so a
     * transcript copy edited later can never silently desync from the char spans seeded for it — the
     * `check` below fails the fixture load loudly (constitution I) rather than seeding a wrong span.
     */
    fun latticeSlots(
        transmissionId: String,
        candidateId: String,
        transcriptText: String,
        unitsAndWords: List<Pair<String, String>>,
    ): List<LatticeSlotEntity> {
        var searchFrom = 0
        return unitsAndWords.mapIndexed { index, (unit, word) ->
            val start = transcriptText.indexOf(word, startIndex = searchFrom, ignoreCase = true)
            check(start >= 0) {
                "R-421 fixture bug: '$word' not found in \"$transcriptText\" from index $searchFrom"
            }
            val end = start + word.length
            searchFrom = end
            LatticeSlotEntity(
                id = "$candidateId-slot$index",
                transmissionId = transmissionId,
                candidateId = candidateId,
                index = index,
                unit = unit,
                score = (0.98 - index * 0.02).coerceAtLeast(0.5),
                keptAlternate = null,
                charStart = start,
                charEnd = end,
            )
        }
    }

    /**
     * R-440 (register, WP12's own tour finding): every [ModelCatalog] entry "installed" — the real
     * signal `Settings-Assets.dc.html`'s rows read
     * ([org.ort.app.ui.data.ModelsController.rowFor], `:app`'s own file, not read here) is a
     * destination file plus a `.sha256` marker on disk, never a database row (no scenario wrote
     * either before this — a clean install genuinely had nothing, matching the tour's own finding).
     * A [ChecksumState.Known] entry's marker carries that entry's own real, published checksum text
     * verbatim (`rowFor`'s own `marker.readText() == spec.checksum.value` check — a string compare
     * against [ModelCatalog]'s own constant, not a real digest of [destination]'s placeholder bytes,
     * so a plain constant marker string is honestly what "installed" already means here); an
     * [ChecksumState.UnknownSideloadOnly] entry (`tiny.en-tokens.txt`) only needs both files to
     * exist for its own `INSTALLED_UNVERIFIED` status.
     */
    fun installEveryModelFixture(context: Context) {
        ModelCatalog.entries.forEach { entry -> installModelFixture(context, entry) }
    }

    /**
     * The single-entry body [installEveryModelFixture] loops over every catalog entry with — split
     * out (R-865) so `tier0-llm-stored` can call it for exactly [ModelId.LLM_GEMMA3_1B] alone,
     * leaving its other four entries to the real installer instead (see
     * [Scenarios.tier0LlmStored]'s own doc comment for why: this build's own escape hatch (no
     * `HF_TOKEN`) leaves the gated LLM genuinely absent from `bundled/manifest.json`, so it is the
     * one entry the real installer can never mark `Installed` here regardless of any fix — a
     * placeholder plus the *real*, pinned [ModelCatalog] checksum as the marker is real enough for
     * that one entry's own purpose, the tier distinction, same as this function always did for
     * every entry before R-865).
     */
    fun installModelFixture(context: Context, id: ModelId) {
        installModelFixture(context, ModelCatalog.entry(id))
    }

    private fun installModelFixture(context: Context, entry: ModelCatalogEntry) {
        val filesDir = context.filesDir
        val destination = entry.destination(filesDir)
        destination.parentFile?.mkdirs()
        destination.writeBytes(ByteArray(64))
        val markerText = when (val state = entry.checksumState) {
            is ChecksumState.Known -> state.checksum.value
            is ChecksumState.UnknownSideloadOnly -> "sideloaded"
        }
        File(destination.parentFile, destination.name + ".sha256").writeText(markerText)
    }

    /** The other half of [installEveryModelFixture] — `model-missing`'s own contract ("keeps
     * nothing installed") depends on this running even when an earlier scenario in the same
     * process installed every model, since [installEveryModelFixture] writes real files on disk,
     * outside `:data`'s own per-scenario row-clearing (`Scenarios.clearPriorScenarioData`). */
    fun uninstallEveryModelFixture(context: Context) {
        val filesDir = context.filesDir
        ModelCatalog.entries.forEach { entry ->
            val destination = entry.destination(filesDir)
            File(destination.parentFile, destination.name + ".sha256").delete()
            destination.delete()
        }
    }

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
