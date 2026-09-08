package org.ort.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.ort.core.Tier

/**
 * The remaining functional spec §8 entities — no acceptance criterion names them directly in
 * build-plan P5, but they belong to the schema this module owns. Grouped in one file since each
 * is a handful of columns with no behaviour of its own; DAOs live in [org.ort.data.dao.CatalogDao].
 */
public enum class LatticeSource { ACOUSTIC, TEXT_DERIVED }

@Entity(tableName = "phonetic_lattice")
public data class PhoneticLatticeEntity(
    @PrimaryKey val id: String,
    val transmissionId: String,
    val source: LatticeSource,
    /** Slots (unit, startMs, endMs, score, alternatives[]) — an opaque blob here; §9's types own the shape. */
    val unitsBlob: String,
    val modelId: String?,
    val createdAt: Long,
)

@Entity(tableName = "callsign_candidate", indices = [Index("transmissionId", "rank")])
public data class CallsignCandidateEntity(
    @PrimaryKey val id: String,
    val transmissionId: String,
    val callsign: String,
    val rank: Int,
    val score: Double,
    val grammarValid: Boolean,
    val ituPrefix: String?,
    val ituCountry: String?,
    val priorBreakdown: Map<String, Double>?,
    val databaseHit: Boolean,
    val selected: Boolean,
)

@Entity(tableName = "station")
public data class StationEntity(
    @PrimaryKey val id: String,
    val callsign: String?,
    val firstHeardAt: Long?,
    val lastHeardAt: Long?,
    val transmissionCount: Int = 0,
    val isUserPinned: Boolean = false,
    val notes: String?,
    /** User content — never inferred, never contributed (FR-SPK-25). */
    val userName: String?,
    // Station-knowledge facts (FR-DIG-7), all re-derivable (FR-DIG-8), all local-only (FR-DIG-13).
    val frequenciesHeard: List<Long>?,
    val activityByHourDow: String?,
    val potaRefs: List<String>?,
    val spokenGrids: List<String>?,
    val ituRegionFromPrefix: String?,
    val overCountsByAttributionState: String?,
)

public enum class VoiceprintBindingSource { AUTO, MANUAL }

@Entity(tableName = "voiceprint", indices = [Index("boundStationId")])
public data class VoiceprintEntity(
    @PrimaryKey val id: String,
    /** Never leaves the device (D28, FR-SPK-20). */
    val embedding: ByteArray,
    val memberCount: Int,
    val centroidUpdatedAt: Long?,
    val boundStationId: String?,
    val bindingConfidence: Double?,
    val lastConfirmedAt: Long?,
    val isEnrolled: Boolean = false,
    val enrolmentObservationCount: Int = 0,
    val enrolmentSessionIds: List<String>?,
    val enrolledAt: Long?,
    val lastMatchedAt: Long?,
    val bindingSource: VoiceprintBindingSource?,
    val embeddingModelId: String?,
    val embeddingModelVersion: String?,
) {
    override fun equals(other: Any?): Boolean = other is VoiceprintEntity &&
        id == other.id &&
        embedding.contentEquals(other.embedding) &&
        memberCount == other.memberCount &&
        centroidUpdatedAt == other.centroidUpdatedAt &&
        boundStationId == other.boundStationId &&
        bindingConfidence == other.bindingConfidence &&
        lastConfirmedAt == other.lastConfirmedAt &&
        isEnrolled == other.isEnrolled &&
        enrolmentObservationCount == other.enrolmentObservationCount &&
        enrolmentSessionIds == other.enrolmentSessionIds &&
        enrolledAt == other.enrolledAt &&
        lastMatchedAt == other.lastMatchedAt &&
        bindingSource == other.bindingSource &&
        embeddingModelId == other.embeddingModelId &&
        embeddingModelVersion == other.embeddingModelVersion

    override fun hashCode(): Int = id.hashCode()
}

public enum class ThreadKind { QSO, NET, SCANNER, UNKNOWN }
public enum class ThreadKindSource { DETECTED, USER }

@Entity(tableName = "thread")
public data class ThreadEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val startedAt: Long,
    val endedAt: Long?,
    val frequencyHz: Long?,
    val transmissionCount: Int = 0,
    val participantStationIds: List<String>?,
    val digestText: String?,
    val kind: ThreadKind,
    val kindSource: ThreadKindSource,
    val participantOrder: List<String>?,
)

public enum class ContributionState { PENDING, SENT, EXCLUDED }

@Entity(tableName = "contribution_item")
public data class ContributionItemEntity(
    @PrimaryKey val id: String,
    val transmissionId: String,
    val state: ContributionState,
    val sentAt: Long?,
    val installToken: String,
)

@Entity(tableName = "lexicon_version", primaryKeys = ["assetId", "version"])
public data class LexiconVersionEntity(
    val assetId: String,
    val version: String,
    val importedAt: Long,
    val recordCount: Int,
    val checksum: String,
)

@Entity(tableName = "correction")
public data class CorrectionEntity(
    @PrimaryKey val id: String,
    val transmissionId: String,
    val field: String,
    val previousValue: String?,
    val newValue: String,
    val correctedAt: Long,
    val propagatedToCount: Int = 0,
)

@Entity(tableName = "calibration")
public data class CalibrationEntity(
    @PrimaryKey val id: String,
    val modelId: String,
    val tier: Tier,
    /** `{a, b, ...}` — Platt-scaling parameters (technical design §9.5), opaque here. */
    val parameters: String,
    val fittedAgainstCorpusVersion: String,
    val fittedAt: Long,
)

public enum class AssetKind { MODEL, LEXICON, CALIBRATION, RIG_DESCRIPTOR, NPU_BINARY }

@Entity(tableName = "asset")
public data class AssetEntity(
    @PrimaryKey val id: String,
    val kind: AssetKind,
    val version: String,
    val source: String,
    val licence: String,
    val checksum: String,
    val sizeBytes: Long,
    val installedAt: Long,
    val activatedAt: Long?,
    val verifiedAt: Long,
    /** `npu_binary` only. */
    val chipset: String?,
)

public enum class OperatorLocationSource { RIG, GPS, MANUAL }

@Entity(tableName = "operator_location")
public data class OperatorLocationEntity(
    @PrimaryKey val profileId: String,
    /** Grid-square precision only (FR-LEX-24). */
    val gridSquare: String,
    val source: OperatorLocationSource,
    val updatedAt: Long,
)

@Entity(tableName = "station_summary")
public data class StationSummaryEntity(
    @PrimaryKey val id: String,
    val stationId: String,
    val windowStart: Long,
    val windowEnd: Long,
    /** Optional, LLM-produced, always marked and attributed (FR-DIG-11); never asserts a characteristic of a person. */
    val text: String,
    val modelId: String,
    val sourceTransmissionIds: List<String>,
    val generatedAt: Long,
)

/**
 * R-073 (`Station-Identity.dc.html`, FR-SPK-10, constitution III): every previous value of
 * [StationEntity.userName] or [StationEntity.notes] stays reachable after a rename or a note
 * edit. The write path — [org.ort.data.dao.StationIdentityDao.renameStation] /
 * [org.ort.data.dao.StationIdentityDao.updateStationNote] — always inserts one of these before
 * overwriting the column, the same append-then-overwrite shape [org.ort.data.dao.TranscriptDao]
 * uses for transcripts. User content only (FR-SPK-25) — never contributed, same as the column
 * it shadows.
 */
@Entity(tableName = "station_identity_history", indices = [Index("stationId")])
public data class StationIdentityHistoryEntity(
    @PrimaryKey val id: String,
    val stationId: String,
    /** [org.ort.data.dao.StationIdentityDao.FIELD_NAME] or `.FIELD_NOTE`. */
    val field: String,
    val previousValue: String?,
    val newValue: String?,
    val changedAt: Long,
)

/**
 * R-052 (`Detail-Propagated.dc.html`, FR-UI-6, constitution III): the binding a [VoiceprintEntity]
 * held *before* [org.ort.data.dao.StationIdentityDao.bindVoiceprintToStation] moved it. "1
 * voiceprint now belongs to `<callsign>`" on the propagated screen is only honest if the previous
 * owner (or the absence of one) stays inspectable rather than being overwritten in place.
 */
@Entity(tableName = "voiceprint_binding_history", indices = [Index("voiceprintId")])
public data class VoiceprintBindingHistoryEntity(
    @PrimaryKey val id: String,
    val voiceprintId: String,
    val previousStationId: String?,
    val previousBindingConfidence: Double?,
    val previousBindingSource: VoiceprintBindingSource?,
    val newStationId: String?,
    val newBindingConfidence: Double?,
    val newBindingSource: VoiceprintBindingSource?,
    val changedAt: Long,
)

/**
 * R-052 (`Detail-Propagated.dc.html`, FR-LEX-9, constitution I): a named prior's current weight
 * for a station — e.g. the repeater-match or the recency/conversation-context priors FR-LEX-9
 * lists — versioned the same way [TranscriptEntity] is: [isCurrent] marks the row a resolution
 * should read, and the row it replaces is kept, never deleted, so "priors updated" on the
 * propagated screen is auditable rather than merely asserted.
 */
@Entity(
    tableName = "prior_adjustment",
    indices = [Index(value = ["stationId", "name", "isCurrent"])],
)
public data class PriorAdjustmentEntity(
    @PrimaryKey val id: String,
    val stationId: String,
    val name: String,
    val weight: Double,
    val reason: String?,
    val isCurrent: Boolean,
    val updatedAt: Long,
)
