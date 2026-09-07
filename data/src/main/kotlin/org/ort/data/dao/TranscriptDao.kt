package org.ort.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.SkipQueryVerification
import androidx.room.Transaction
import org.ort.data.entity.TranscriptEntity

/**
 * `transcript` is append-only (technical design §8.3): [supersede] writes the new row and
 * flips `isCurrent` on the old one in a single transaction, nothing is ever deleted (FR-REP-3 →
 * AC-31). The FTS5 index (technical design §12.1) is a hand-written external-content virtual
 * table, not a Room entity — [org.ort.data.OrtDatabase] creates it and its sync triggers, and
 * the search methods here need [SkipQueryVerification] because Room cannot see a table it did
 * not generate.
 */
@Dao
public interface TranscriptDao {

    @Insert
    public suspend fun insert(entity: TranscriptEntity)

    @Query("SELECT * FROM transcript WHERE transmissionId = :transmissionId AND isCurrent = 1 LIMIT 1")
    public suspend fun getCurrent(transmissionId: String): TranscriptEntity?

    /** Every version, current and superseded — nothing is deleted (P9). */
    @Query("SELECT * FROM transcript WHERE transmissionId = :transmissionId ORDER BY createdAt")
    public suspend fun getAllVersions(transmissionId: String): List<TranscriptEntity>

    @Query("UPDATE transcript SET isCurrent = 0 WHERE transmissionId = :transmissionId AND isCurrent = 1")
    public suspend fun clearCurrent(transmissionId: String)

    /** Insert [transcript] as the current transcript, superseding whatever was current before it — one transaction. */
    @Transaction
    public suspend fun supersede(transcript: TranscriptEntity) {
        require(transcript.isCurrent) { "supersede(...) always installs its argument as the new current transcript" }
        clearCurrent(transcript.transmissionId)
        insert(transcript)
    }

    /** Full-text search over every transcript, current and superseded (technical design §12.1). */
    @SkipQueryVerification
    @Query(
        "SELECT transcript.* FROM transcript JOIN transcript_fts ON transcript.rowid = transcript_fts.rowid " +
            "WHERE transcript_fts MATCH :matchQuery",
    )
    public suspend fun searchAll(matchQuery: String): List<TranscriptEntity>

    /** Full-text search filtered to current transcripts only — `is_current` filtered in the join, not the index. */
    @SkipQueryVerification
    @Query(
        "SELECT transcript.* FROM transcript JOIN transcript_fts ON transcript.rowid = transcript_fts.rowid " +
            "WHERE transcript_fts MATCH :matchQuery AND transcript.isCurrent = 1",
    )
    public suspend fun searchCurrent(matchQuery: String): List<TranscriptEntity>
}
