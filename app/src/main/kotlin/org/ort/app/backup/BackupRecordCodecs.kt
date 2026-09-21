package org.ort.app.backup

import org.json.JSONArray
import org.json.JSONObject
import org.ort.core.AttributionState
import org.ort.core.Tier
import org.ort.core.TransmissionState
import org.ort.core.capture.VadDetectorKind
import org.ort.data.entity.CorrectionEntity
import org.ort.data.entity.OverCountsByAttributionState
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TerminationReason
import org.ort.data.entity.ThreadEntity
import org.ort.data.entity.ThreadKind
import org.ort.data.entity.ThreadKindSource
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
import org.ort.data.entity.VoiceprintBindingSource
import org.ort.data.entity.VoiceprintEntity
import java.util.Base64

/**
 * FR-STO-6/FR-STO-9. Every field of [SessionEntity], [TransmissionEntity], [TranscriptEntity],
 * [CorrectionEntity], [StationEntity], [VoiceprintEntity] and [ThreadEntity] the backup bundle
 * carries, read back exactly — no library is on `:app`'s classpath for this (`org.json`, already
 * used by this package's own [org.ort.app.export.DebugDumpBuilder] for the identical reason: it
 * ships in the Android platform, so no new dependency, and Robolectric shadows it under test).
 *
 * **Register R-1094 widened this from four tables to seven.** FR-STO-9's own text names "sessions,
 * corrections and audio" as the round-trip floor; transmissions are the structural join between
 * the two ([TransmissionEntity.audioPath] is *how* an audio file is found again). What R-1094
 * added:
 *
 * - **Every transcript version, not only the current one** ([TranscriptCodec], via
 *   [org.ort.data.dao.TranscriptDao.getAllVersions]) — constitution III: "nothing is deleted
 *   quietly," and a restore that returned only current transcripts would quietly have deleted the
 *   superseded history the source device still had reachable.
 * - **The station catalog** ([StationCodec]) and **threads** ([ThreadCodec]) — the operator's own
 *   accumulated log of their own stations and QSOs/nets, lost otherwise on a reinstall or a new
 *   phone.
 * - **Voiceprints** ([VoiceprintCodec]) — biometric data, carried only because FR-SPK-20's own
 *   text already states the exception this bundle is: "Export (FR-STO-6) MAY include them only
 *   for the user's own device-to-device transfer, and SHALL say so." See [VoiceprintCodec]'s own
 *   kdoc for where "and SHALL say so" is discharged.
 *
 * Still **not** in this bundle: lattice/candidate detail, the work queue, calibration, assets,
 * prior adjustments, prose summaries (digests), transmission labels, station-identity/voiceprint-
 * binding history — a stated limitation (`SettingsBackupScreen`'s own "what this backup does not
 * yet carry" list), not a silent gap: broadening it further is real, separate future work.
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

private fun JSONObject.nullableStringList(key: String): List<String>? = if (isNull(key)) {
    null
} else {
    getJSONArray(key).let { array -> (0 until array.length()).map { array.getString(it) } }
}

private fun JSONObject.nullableLongList(key: String): List<Long>? = if (isNull(key)) {
    null
} else {
    getJSONArray(key).let { array -> (0 until array.length()).map { array.getLong(it) } }
}

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

/** [TranscriptEntity] <-> JSON — **every version**, current and superseded (register R-1094;
 * this file's own top-of-file kdoc). [TranscriptEntity.isCurrent] rides along unchanged, so a
 * restored transmission ends up with exactly the same current/superseded shape it had on the
 * source device — never two rows both claiming `isCurrent = true` for one transmission, since the
 * source database's own partial unique index already guaranteed that before this was ever read. */
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

/**
 * [StationEntity] <-> JSON (register R-1094, FR-STO-9, D56, constitution III). Every column is
 * carried **except** [StationEntity.overCountsByAttributionState] — R-1134/D56 already made that
 * column *derived*, recomputed by [org.ort.data.dao.CatalogDao.getStation] from the transmission
 * rows it is a fact about, never trusted from storage. Carrying the bundle's own stale copy of a
 * derived value into a restore would create exactly the second source of truth D56 removed, so
 * [toJson] never writes the field at all, and [fromJson] always returns
 * [OverCountsByAttributionState.EMPTY]'s own serialized form — the identical honest placeholder
 * [org.ort.data.dao.CatalogDao.recordStationObservation] already writes at a station's real birth.
 * The next real [org.ort.data.dao.CatalogDao.getStation] read on the restored row recomputes the
 * true counts from the transmissions this same restore also carries.
 *
 * [StationEntity.userName]/`.notes` and the station-knowledge facts (`frequenciesHeard` etc.) ARE
 * carried, in full: FR-SPK-20's own text — echoed by FR-DIG-13's "on the same reasoning as
 * voiceprints" — permits a station's user-supplied name and knowledge to travel through FR-STO-6's
 * export path specifically because it is "the user's own device-to-device transfer," never a
 * third-party channel. What FR-SPK-25/FR-DIG-13 actually forbid is the *contribution* payload and
 * the *diagnostic* bundle — neither of which this object, or anything in this package, writes.
 */
public object StationCodec {

    public fun toJson(entity: StationEntity): JSONObject = JSONObject().apply {
        put("id", entity.id)
        putNullable("callsign", entity.callsign)
        putNullable("firstHeardAt", entity.firstHeardAt)
        putNullable("lastHeardAt", entity.lastHeardAt)
        put("transmissionCount", entity.transmissionCount)
        put("isUserPinned", entity.isUserPinned)
        putNullable("notes", entity.notes)
        putNullable("userName", entity.userName)
        putNullable("frequenciesHeard", entity.frequenciesHeard?.let { JSONArray(it) })
        putNullable("activityByHourDow", entity.activityByHourDow)
        putNullable("potaRefs", entity.potaRefs?.let { JSONArray(it) })
        putNullable("spokenGrids", entity.spokenGrids?.let { JSONArray(it) })
        putNullable("ituRegionFromPrefix", entity.ituRegionFromPrefix)
        // overCountsByAttributionState deliberately absent -- see this object's own kdoc.
    }

    public fun fromJson(json: JSONObject): StationEntity = StationEntity(
        id = json.getString("id"),
        callsign = json.nullableString("callsign"),
        firstHeardAt = json.nullableLong("firstHeardAt"),
        lastHeardAt = json.nullableLong("lastHeardAt"),
        transmissionCount = json.getInt("transmissionCount"),
        isUserPinned = json.getBoolean("isUserPinned"),
        notes = json.nullableString("notes"),
        userName = json.nullableString("userName"),
        frequenciesHeard = json.nullableLongList("frequenciesHeard"),
        activityByHourDow = json.nullableString("activityByHourDow"),
        potaRefs = json.nullableStringList("potaRefs"),
        spokenGrids = json.nullableStringList("spokenGrids"),
        ituRegionFromPrefix = json.nullableString("ituRegionFromPrefix"),
        overCountsByAttributionState = OverCountsByAttributionState.EMPTY.serialize(),
    )
}

/**
 * [VoiceprintEntity] <-> JSON (register R-1094, FR-SPK-20, D38, constitution V). [VoiceprintEntity
 * .embedding] is biometric data — the constitution treats voiceprints as leaving the device
 * **almost never**; this object's own existence is the stated exception FR-SPK-20 already carries
 * in its own text: "Export (FR-STO-6) MAY include them only for the user's own device-to-device
 * transfer, and SHALL say so." This *is* that device-to-device transfer, never the corpus
 * contribution channel, the field-report channel, or any analytics tier — none of those call this
 * codec, and `SettingsBackupScreen`'s own "In the backup" copy says so in words, not only in a
 * code comment, discharging FR-SPK-20's "and SHALL say so" at the one surface the operator
 * actually decides from, so this bundle is never mistaken for a contribution or a field report.
 *
 * [VoiceprintEntity.embedding]'s raw bytes are Base64-encoded ([Base64], available since minSdk
 * 26 — no new dependency) since `org.json` has no binary type.
 */
public object VoiceprintCodec {

    public fun toJson(entity: VoiceprintEntity): JSONObject = JSONObject().apply {
        put("id", entity.id)
        put("embedding", Base64.getEncoder().encodeToString(entity.embedding))
        put("memberCount", entity.memberCount)
        putNullable("centroidUpdatedAt", entity.centroidUpdatedAt)
        putNullable("boundStationId", entity.boundStationId)
        putNullable("bindingConfidence", entity.bindingConfidence)
        putNullable("lastConfirmedAt", entity.lastConfirmedAt)
        put("isEnrolled", entity.isEnrolled)
        put("enrolmentObservationCount", entity.enrolmentObservationCount)
        putNullable("enrolmentSessionIds", entity.enrolmentSessionIds?.let { JSONArray(it) })
        putNullable("enrolledAt", entity.enrolledAt)
        putNullable("lastMatchedAt", entity.lastMatchedAt)
        putNullable("bindingSource", entity.bindingSource?.name)
        putNullable("embeddingModelId", entity.embeddingModelId)
        putNullable("embeddingModelVersion", entity.embeddingModelVersion)
    }

    public fun fromJson(json: JSONObject): VoiceprintEntity = VoiceprintEntity(
        id = json.getString("id"),
        embedding = Base64.getDecoder().decode(json.getString("embedding")),
        memberCount = json.getInt("memberCount"),
        centroidUpdatedAt = json.nullableLong("centroidUpdatedAt"),
        boundStationId = json.nullableString("boundStationId"),
        bindingConfidence = json.nullableDouble("bindingConfidence"),
        lastConfirmedAt = json.nullableLong("lastConfirmedAt"),
        isEnrolled = json.getBoolean("isEnrolled"),
        enrolmentObservationCount = json.getInt("enrolmentObservationCount"),
        enrolmentSessionIds = json.nullableStringList("enrolmentSessionIds"),
        enrolledAt = json.nullableLong("enrolledAt"),
        lastMatchedAt = json.nullableLong("lastMatchedAt"),
        bindingSource = json.nullableString("bindingSource")?.let(VoiceprintBindingSource::valueOf),
        embeddingModelId = json.nullableString("embeddingModelId"),
        embeddingModelVersion = json.nullableString("embeddingModelVersion"),
    )
}

/** [ThreadEntity] <-> JSON, every constructor field (register R-1094, FR-STO-9, FR-SPK-5). No FK
 * ties [org.ort.data.entity.TransmissionEntity.threadId] to this table (`TransmissionEntity`'s own
 * schema — see that file), so a restore may insert threads and transmissions in either order. */
public object ThreadCodec {

    public fun toJson(entity: ThreadEntity): JSONObject = JSONObject().apply {
        put("id", entity.id)
        put("sessionId", entity.sessionId)
        put("startedAt", entity.startedAt)
        putNullable("endedAt", entity.endedAt)
        putNullable("frequencyHz", entity.frequencyHz)
        put("transmissionCount", entity.transmissionCount)
        putNullable("participantStationIds", entity.participantStationIds?.let { JSONArray(it) })
        putNullable("digestText", entity.digestText)
        put("kind", entity.kind.name)
        put("kindSource", entity.kindSource.name)
        putNullable("participantOrder", entity.participantOrder?.let { JSONArray(it) })
    }

    public fun fromJson(json: JSONObject): ThreadEntity = ThreadEntity(
        id = json.getString("id"),
        sessionId = json.getString("sessionId"),
        startedAt = json.getLong("startedAt"),
        endedAt = json.nullableLong("endedAt"),
        frequencyHz = json.nullableLong("frequencyHz"),
        transmissionCount = json.getInt("transmissionCount"),
        participantStationIds = json.nullableStringList("participantStationIds"),
        digestText = json.nullableString("digestText"),
        kind = ThreadKind.valueOf(json.getString("kind")),
        kindSource = ThreadKindSource.valueOf(json.getString("kindSource")),
        participantOrder = json.nullableStringList("participantOrder"),
    )
}
