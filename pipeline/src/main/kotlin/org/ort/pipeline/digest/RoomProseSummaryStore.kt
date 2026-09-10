package org.ort.pipeline.digest

import org.ort.data.OrtDatabase
import org.ort.data.entity.ProseSummaryEntity

/**
 * The production [ProseSummaryStore], backed by `:data`'s `prose_summary` table (schema v8,
 * `OrtDatabase.MIGRATION_7_8`). `:pipeline` already depends on `:data` (module graph) — the same
 * way `RealCaptureService` reads `OrtDatabase` directly — so this maps [ProseSummary] to and from
 * [ProseSummaryEntity] field-by-field rather than adding a new dependency edge.
 */
public class RoomProseSummaryStore(private val db: OrtDatabase) : ProseSummaryStore {

    override suspend fun store(summary: ProseSummary) {
        db.proseSummaryDao().upsert(summary.toEntity())
    }

    override suspend fun forThread(threadId: String): ProseSummary? =
        db.proseSummaryDao().getByThreadId(threadId)?.toDomain()

    override suspend fun forThreads(threadIds: Collection<String>): List<ProseSummary> =
        db.proseSummaryDao().getByThreadIds(threadIds.toList()).map { it.toDomain() }

    override suspend fun all(): List<ProseSummary> = db.proseSummaryDao().listAll().map { it.toDomain() }

    private fun ProseSummary.toEntity() = ProseSummaryEntity(
        threadId = threadId,
        text = text,
        sourceTransmissionIds = sourceTransmissionIds,
        generatedAtMillis = generatedAtMillis,
        modelId = modelId,
    )

    private fun ProseSummaryEntity.toDomain() = ProseSummary(
        threadId = threadId,
        text = text,
        sourceTransmissionIds = sourceTransmissionIds,
        generatedAtMillis = generatedAtMillis,
        modelId = modelId,
    )
}
