package org.ort.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `docs/reference/labelling-protocol.md`'s own closed `outcome` vocabulary (its "Output format"
 * section): `speech | no_speech | doubled_unresolvable | non_speech`.
 */
public enum class LabelOutcome { SPEECH, NO_SPEECH, DOUBLED_UNRESOLVABLE, NON_SPEECH }

/**
 * `docs/reference/labelling-protocol.md`'s own closed callsign-certainty vocabulary ("Partial
 * audibility — never omit, always mark certainty"): `certain | uncertain | partial`.
 */
public enum class LabelCertainty { CERTAIN, UNCERTAIN, PARTIAL }

/**
 * FR-OBS-4, Q16 (`docs/reference/labelling-protocol.md`), constitution VI (provenance): ground
 * truth an *operator* recorded about one transmission for the evaluation/training corpus —
 * never a machine attribution, and never capable of becoming one. Looked up by [transmissionId]
 * directly (one row per transmission, `PRIMARY KEY`), matching the protocol's own grain: "one
 * row per transmission ... matching the grain `:segment`'s `Segmenter` already emits."
 *
 * The columns mirror the protocol's TSV columns as far as `Recording-Session.dc.html`'s (RC02)
 * per-over surface actually needs — [outcome], [doubled], [truthCallsign], [callsignCertainty],
 * [tacticalCallsign], [note] — plus [markedForTraining] and [rating], the two facts RC02's own
 * "training · good" per-over badge shows. This is deliberately **not** a full implementation of
 * the protocol's `thread_id` column or the desktop corpus tooling's `LabeledOccurrence` shape
 * (`eval/src/main/kotlin/org/ort/eval/LabeledOccurrence.kt`) — the protocol's own "Output format"
 * section already names the TSV-to-`LabeledOccurrence` conversion as a separate, not-yet-built
 * follow-up, and thread continuity is a cross-transmission fact ([ThreadEntity]) this per-over
 * table has no reason to duplicate.
 *
 * **Never an attribution, and never able to become one.** No column here is read by
 * [org.ort.core.Attribution], by `TransmissionDao.updateAttribution`, or by
 * [org.ort.data.dao.CorrectionDao] — a label is what an operator *asserts is true*, independent
 * of, and never overwriting, what the pipeline resolved (AGENTS.md: "a label is operator ground
 * truth, never an attribution ... it must never promote anything to CONFIRMED"). See
 * `org.ort.pipeline.label.TransmissionLabelRepository`, the only writer, for the enforced half of
 * that rule.
 *
 * [labelledAtMillis] is this row's provenance (constitution VI, "who/when"). There is
 * deliberately no separate "who" column: this is a single-operator, offline device with no
 * account/identity concept anywhere else in this schema — [CorrectionEntity.correctedAt] is the
 * same precedent (one operator, one clock, no identity column) — so "who" is always, and only
 * ever, the person holding this device; a future multi-operator build would need its own
 * identity work this table does not attempt to anticipate.
 */
@Entity(tableName = "transmission_label", indices = [Index("markedForTraining")])
public data class TransmissionLabelEntity(
    @PrimaryKey val transmissionId: String,
    /** RC02's "mark for training" action, independent of every other field below — an over can be
     * marked with nothing else recorded yet, and fully labelled without being marked. */
    val markedForTraining: Boolean,
    /** protocol "Transmission boundaries"/"Output format"; `null` = not yet labelled, distinct
     * from any of the four real outcomes. */
    val outcome: LabelOutcome? = null,
    /** protocol "Doubling": `true` when two stations were separably heard together in this over. */
    val doubled: Boolean = false,
    /** protocol "Partial audibility": the ground-truth callsign text, written exactly as heard
     * (including a doubtful-character marker or a heard fragment) — `null`/blank is the
     * protocol's own distinct negative case ("clearly no callsign"), never conflated with "not
     * yet labelled". */
    val truthCallsign: String? = null,
    /** Set exactly when [truthCallsign] is present (protocol rule) — enforced by
     * `org.ort.pipeline.label.TransmissionLabelRepository`, not by a Room constraint. */
    val callsignCertainty: LabelCertainty? = null,
    /** protocol "Phonetic form, tactical callsigns, club stations": the literal tactical text
     * (e.g. `"Net Control"`), never a real callsign. */
    val tacticalCallsign: String? = null,
    /** protocol "Output format" free-text `note` (non-speech kind, doubling detail, etc.). */
    val note: String? = null,
    /**
     * `Recording-Session.dc.html`'s quality-rating badge (drawn as `"good"`) — an open-ended
     * string, not a closed enum: the protocol never defines a fixed rating vocabulary (see its
     * own "Open items for the pilot round" §2), so this column does not invent one either.
     */
    val rating: String? = null,
    val labelledAtMillis: Long,
)
