package org.ort.pipeline.passb

import org.ort.asrapi.PassBOutcome
import org.ort.core.SystemClock
import org.ort.core.Ulid
import org.ort.data.OrtDatabase
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass

/**
 * The real [PassBResultSink] (build-plan P12, defect 3): `PassB`'s own doc comment names
 * "`:data` gaining a write path for attribution fields" as a follow-up left open by P11 — this is
 * that follow-up.
 *
 * A transcript row is written **only** for a [PassBOutcome.Accepted] decode (AC-31: exactly one
 * current version, via [org.ort.data.dao.TranscriptDao.supersede]'s single-transaction
 * clear-then-insert) — nothing here invents transcript text for a segment the rejection pipeline
 * refused. A [PassBOutcome.Rejected] instead records its rule/detail on the transmission's own
 * `rejectionReason` column, so AC-8's "reachable, not hidden" holds at the persisted layer too,
 * not only in the in-memory [org.ort.asrapi.RejectedSegmentLog] build-plan P10 built. A
 * [PassBOutcome.Failed] writes nothing here — it is retryable, and `WorkQueue.failPass` already
 * records the error against the queue row itself (constitution VI: don't duplicate provenance).
 */
public class DataPassBResultSink(private val db: OrtDatabase) : PassBResultSink {

    override suspend fun record(result: PassBResult) {
        when (val outcome = result.outcome) {
            is PassBOutcome.Accepted -> {
                db.transcriptDao().supersede(
                    TranscriptEntity(
                        id = Ulid.generate().value,
                        transmissionId = result.transmissionId,
                        pass = TranscriptPass.B,
                        text = outcome.result.text,
                        modelId = outcome.result.modelRef.assetId,
                        modelVersion = outcome.result.modelRef.version,
                        quantization = null,
                        decodeParams = null,
                        noSpeechProb = outcome.result.noSpeechProb,
                        confidence = result.attribution.confidence,
                        isCurrent = true,
                        createdAt = SystemClock.wallMillis(),
                    ),
                )
                db.transmissionDao().updateAttribution(
                    id = result.transmissionId,
                    state = result.attribution.state,
                    stationId = result.attribution.stationId,
                    confidence = result.attribution.confidence,
                    sourceTransmissionId = result.attribution.sourceTransmissionId?.ulid?.value,
                )
            }
            is PassBOutcome.Rejected -> {
                db.transmissionDao().setRejectionReason(result.transmissionId, "${outcome.rule}: ${outcome.detail}")
            }
            is PassBOutcome.Failed -> Unit
        }
    }
}
