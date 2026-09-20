package org.ort.app.backup

import org.json.JSONArray
import org.json.JSONObject
import org.ort.core.AttributionState
import org.ort.core.Tier
import org.ort.core.TransmissionState
import org.ort.core.capture.VadDetectorKind
import org.ort.data.entity.CorrectionEntity
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TerminationReason
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity

/**
 * FR-STO-6/FR-STO-9. Every field of [SessionEntity], [TransmissionEntity], [TranscriptEntity] and
 * [CorrectionEntity] the backup bundle carries, read back exactly — no library is on `:app`'s
 * classpath for this (`org.json`, already used by this package's own [org.ort.app.export
 * .DebugDumpBuilder] for the identical reason: it ships in the Android platform, so no new
 * dependency, and Robolectric shadows it under test).
 *
 * **Deliberately scoped to four tables**, not the whole schema: FR-STO-9's own text names exactly
 * "sessions, corrections and audio" as what a restore must round-trip; transmissions are the
 * structural join between the two ([TransmissionEntity.audioPath] is *how* an audio file is found
 * again), and a transmission with no transcript is not a usable log record, so the current
 * transcript rides along too. Everything else the schema carries — the station catalog,
 * voiceprints, threads, lattice/candidate detail, the work queue, calibration, assets, prior
 * adjustments, prose summaries, transmission labels — is **not yet in this bundle**, a stated
 * limitation (`SettingsBackupScreen`'s own "what this backup does not yet carry" list), not a
 * silent gap: broadening it is real, separate future work, not a shortcut taken here to look done.
 *
 * Superseded transcript versions are also not carried — only the row a transmission's own
 * `isCurrent = true` names — the same "current version" scoping `Settings-Export.dc.html`'s own
 * "Transcripts, current version" checkbox already uses for the flat export formats.
 *
 * A nullable field is always written, explicitly, as `JSONObject.NULL` (`putNullable` below) —
 * `org.json.JSONObject.put(String, Any?)` silently *removes* the key for a plain Kotlin `null`
 * rather than storing one, which would make a restored row indistinguishable from one written by
 * an older codec that never had the column at all. Every reader here uses `isNull`, which is
 * `true` for both an explicit JSON `null` and a genuinely absent key, so a bundle from a
 * hypothetical future codec that drops a field it no longer needs still restores this key as
 * honestly `null` rather than throwing.
 */
private fun JSONObject.putNullable(key: String, value: Any?) {
    put(key, value ?: JSONObject.NULL)
}

private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
private fun JSONObject.nullableLong(key: String): Long? = if (isNull(key)) null else getLong(key)
private fun JSONObject.nullableInt(key: String): Int? = if (isNull(key)) null else getInt(key)
private fun JSONObject.nullableDouble(key: String): Double? = if (isNull(key)) null else getDouble(key)
private fun JSONObject.nullableBoolean(key: String): Boolean? = if (isNull(key)) null else getBoolean(key)

/** [SessionEntity] <-> JSON, every constructor field, in schema-v14 shape. */
public object SessionCodec {

    public fun toJson(entity: SessionEntity): JSONObject = JSONObject().apply {
        put("id", entity.id)
        put("startedAt", entity.startedAt)
        putNullable("endedAt", entity.endedAt)
        putNullable("profileId", entity.profileId)
        putNullable("deviceTier", entity.deviceTier)
        putNullable("appVersion", entity.appVersion)
        putNullable("terminationReason", entity.terminationReason?.name)
        putNullable("sourceId", entity.sourceId)
        put("schemaVersion", entity.schemaVersion)
        put("gapCount", entity.gapCount)
        put("shedEvents", entity.shedEvents)
        putNullable("captureMode", entity.captureMode)
        putNullable("audioRouteKind", entity.audioRouteKind)
        putNullable("audioRouteLabel", entity.audioRouteLabel)
        putNullable("bluetoothProfile", entity.bluetoothProfile)
        putNullable("rigTransport", entity.rigTransport)
        putNullable("rigDescriptorId", entity.rigDescriptorId)
        putNullable("audioRouteVerified", entity.audioRouteVerified)
        putNullable("audioNativeRateHz", entity.audioNativeRateHz)
        putNullable("archiveState", entity.archiveState)
        putNullable("archiveRemovedAtMillis", entity.archiveRemovedAtMillis)
        putNullable("overAudioRemovedAtMillis", entity.overAudioRemovedAtMillis)
        put("vadDetector", entity.vadDetector.name)
        putNullable("vadDetectorVersion", entity.vadDetectorVersion)
    }

    public fun fromJson(json: JSONObject): SessionEntity = SessionEntity(
        id = json.getString("id"),
        startedAt = json.getLong("startedAt"),
        endedAt = json.nullableLong("endedAt"),
        profileId = json.nullableString("profileId"),
        deviceTier = json.nullableString("deviceTier"),
        appVersion = json.nullableString("appVersion"),
        terminationReason = json.nullableString("terminationReason")?.let(TerminationReason::valueOf),
        sourceId = json.nullableString("sourceId"),
        schemaVersion = json.getInt("schemaVersion"),
        gapCount = json.getInt("gapCount"),
        shedEvents = json.getInt("shedEvents"),
        captureMode = json.nullableString("captureMode"),
        audioRouteKind = json.nullableString("audioRouteKind"),
        audioRouteLabel = json.nullableString("audioRouteLabel"),
        bluetoothProfile = json.nullableString("bluetoothProfile"),
        rigTransport = json.nullableString("rigTransport"),
        rigDescriptorId = json.nullableString("rigDescriptorId"),
        audioRouteVerified = json.nullableBoolean("audioRouteVerified"),
        audioNativeRateHz = json.nullableInt("audioNativeRateHz"),
        archiveState = json.nullableString("archiveState"),
        archiveRemovedAtMillis = json.nullableLong("archiveRemovedAtMillis"),
        overAudioRemovedAtMillis = json.nullableLong("overAudioRemovedAtMillis"),
        vadDetector = VadDetectorKind.valueOf(json.getString("vadDetector")),
        vadDetectorVersion = json.nullableString("vadDetectorVersion"),
    )
}

/** [TransmissionEntity] <-> JSON, every constructor field, in schema-v14 shape. */
public object TransmissionCodec {

    public fun toJson(entity: TransmissionEntity): JSONObject = JSONObject().apply {
        put("id", entity.id)
        put("sessionId", entity.sessionId)
        putNullable("threadId", entity.threadId)
        put("startedAtUtc", entity.startedAtUtc)
        putNullable("endedAtUtc", entity.endedAtUtc)
        put("durationMs", entity.durationMs)
        put("audioFormat", entity.audioFormat)
        put("preRollMs", entity.preRollMs)
        put("postRollMs", entity.postRollMs)
        putNullable("frequencyHz", entity.frequencyHz)
        put("frequencyProvenance", entity.frequencyProvenance)
        putNullable("mode", entity.mode)
        putNullable("signalStrength", entity.signalStrength)
        putNullable("channelName", entity.channelName)
        putNullable("voiceprintId", entity.voiceprintId)
        put("attributionState", entity.attributionState.name)
        putNullable("stationId", entity.stationId)
        putNullable("attributionConfidence", entity.attributionConfidence)
        putNullable("attributionSourceTransmissionId", entity.attributionSourceTransmissionId)
        put("corrected", entity.corrected)
        put("processingState", entity.processingState.name)
        putNullable("rejectionReason", entity.rejectionReason)
        put("samplePosition", entity.samplePosition)
        put("monotonicStartNanos", entity.monotonicStartNanos)
        put("utcOffsetMinutes", entity.utcOffsetMinutes)
        putNullable("calibrationId", entity.calibrationId)
        put("enhancementApplied", JSONArray(entity.enhancementApplied))
        putNullable("executionProvider", entity.executionProvider)
        put("isReprocessCandidate", entity.isReprocessCandidate)
        putNullable("processedTier", entity.processedTier?.name)
        put("rigStateChangedMidTransmission", entity.rigStateChangedMidTransmission)
        put("vadDetector", entity.vadDetector.name)
        putNullable("vadDetectorVersion", entity.vadDetectorVersion)
        put("rigSquelchFusionApplied", entity.rigSquelchFusionApplied)
    }

    public fun fromJson(json: JSONObject): TransmissionEntity = TransmissionEntity(
        id = json.getString("id"),
        sessionId = json.getString("sessionId"),
        threadId = json.nullableString("threadId"),
        startedAtUtc = json.getLong("startedAtUtc"),
        endedAtUtc = json.nullableLong("endedAtUtc"),
        durationMs = json.getLong("durationMs"),
        audioFormat = json.getString("audioFormat"),
        preRollMs = json.getInt("preRollMs"),
        postRollMs = json.getInt("postRollMs"),
        frequencyHz = json.nullableLong("frequencyHz"),
        frequencyProvenance = json.getString("frequencyProvenance"),
        mode = json.nullableString("mode"),
        signalStrength = json.nullableDouble("signalStrength"),
        channelName = json.nullableString("channelName"),
        voiceprintId = json.nullableString("voiceprintId"),
        attributionState = AttributionState.valueOf(json.getString("attributionState")),
        stationId = json.nullableString("stationId"),
        attributionConfidence = json.nullableDouble("attributionConfidence"),
        attributionSourceTransmissionId = json.nullableString("attributionSourceTransmissionId"),
        corrected = json.getBoolean("corrected"),
        processingState = TransmissionState.valueOf(json.getString("processingState")),
        rejectionReason = json.nullableString("rejectionReason"),
        samplePosition = json.getLong("samplePosition"),
        monotonicStartNanos = json.getLong("monotonicStartNanos"),
        utcOffsetMinutes = json.getInt("utcOffsetMinutes"),
        calibrationId = json.nullableString("calibrationId"),
        enhancementApplied = json.getJSONArray("enhancementApplied").let { array ->
            (0 until array.length()).map { array.getString(it) }
        },
        executionProvider = json.nullableString("executionProvider"),
        isReprocessCandidate = json.getBoolean("isReprocessCandidate"),
        processedTier = json.nullableString("processedTier")?.let(Tier::valueOf),
        rigStateChangedMidTransmission = json.getBoolean("rigStateChangedMidTransmission"),
        vadDetector = VadDetectorKind.valueOf(json.getString("vadDetector")),
        vadDetectorVersion = json.nullableString("vadDetectorVersion"),
        rigSquelchFusionApplied = json.getBoolean("rigSquelchFusionApplied"),
    )
}

/** [TranscriptEntity] <-> JSON — the current-version row only (this file's own top-of-file kdoc). */
public object TranscriptCodec {

    public fun toJson(entity: TranscriptEntity): JSONObject = JSONObject().apply {
        put("id", entity.id)
        put("transmissionId", entity.transmissionId)
        put("pass", entity.pass.name)
        put("text", entity.text)
        put("modelId", entity.modelId)
        put("modelVersion", entity.modelVersion)
        putNullable("quantization", entity.quantization)
        putNullable("decodeParams", entity.decodeParams)
        putNullable("noSpeechProb", entity.noSpeechProb?.toDouble())
        putNullable("confidence", entity.confidence)
        put("isCurrent", entity.isCurrent)
        put("createdAt", entity.createdAt)
    }

    public fun fromJson(json: JSONObject): TranscriptEntity = TranscriptEntity(
        id = json.getString("id"),
        transmissionId = json.getString("transmissionId"),
        pass = TranscriptPass.valueOf(json.getString("pass")),
        text = json.getString("text"),
        modelId = json.getString("modelId"),
        modelVersion = json.getString("modelVersion"),
        quantization = json.nullableString("quantization"),
        decodeParams = json.nullableString("decodeParams"),
        noSpeechProb = json.nullableDouble("noSpeechProb")?.toFloat(),
        confidence = json.nullableDouble("confidence"),
        isCurrent = json.getBoolean("isCurrent"),
        createdAt = json.getLong("createdAt"),
    )
}

/** [CorrectionEntity] <-> JSON, every constructor field (schema v5 shape). */
public object CorrectionCodec {

    public fun toJson(entity: CorrectionEntity): JSONObject = JSONObject().apply {
        put("id", entity.id)
        put("transmissionId", entity.transmissionId)
        put("field", entity.field)
        putNullable("previousValue", entity.previousValue)
        put("newValue", entity.newValue)
        put("correctedAt", entity.correctedAt)
        put("propagatedToCount", entity.propagatedToCount)
        putNullable("previousAttributionState", entity.previousAttributionState?.name)
        putNullable("previousAttributionConfidence", entity.previousAttributionConfidence)
        putNullable("previousAttributionSourceTransmissionId", entity.previousAttributionSourceTransmissionId)
        putNullable("previousCorrected", entity.previousCorrected)
    }

    public fun fromJson(json: JSONObject): CorrectionEntity = CorrectionEntity(
        id = json.getString("id"),
        transmissionId = json.getString("transmissionId"),
        field = json.getString("field"),
        previousValue = json.nullableString("previousValue"),
        newValue = json.getString("newValue"),
        correctedAt = json.getLong("correctedAt"),
        propagatedToCount = json.getInt("propagatedToCount"),
        previousAttributionState = json.nullableString("previousAttributionState")?.let(AttributionState::valueOf),
        previousAttributionConfidence = json.nullableDouble("previousAttributionConfidence"),
        previousAttributionSourceTransmissionId = json.nullableString("previousAttributionSourceTransmissionId"),
        previousCorrected = json.nullableBoolean("previousCorrected"),
    )
}
