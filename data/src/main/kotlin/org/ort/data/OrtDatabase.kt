package org.ort.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.ort.data.dao.ActivityDao
import org.ort.data.dao.CaptureGapDao
import org.ort.data.dao.CatalogDao
import org.ort.data.dao.CorrectionDao
import org.ort.data.dao.SearchDao
import org.ort.data.dao.SessionDao
import org.ort.data.dao.TranscriptDao
import org.ort.data.dao.TransmissionDao
import org.ort.data.dao.WorkQueueDao
import org.ort.data.entity.AssetEntity
import org.ort.data.entity.CalibrationEntity
import org.ort.data.entity.CallsignCandidateEntity
import org.ort.data.entity.CaptureGapEntity
import org.ort.data.entity.ContributionItemEntity
import org.ort.data.entity.CorrectionEntity
import org.ort.data.entity.LexiconVersionEntity
import org.ort.data.entity.OperatorLocationEntity
import org.ort.data.entity.PhoneticLatticeEntity
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.StationSummaryEntity
import org.ort.data.entity.ThreadEntity
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.data.entity.VoiceprintEntity
import org.ort.data.entity.WorkQueueItemEntity

/**
 * The schema (functional spec §8; technical design §12.1). Schema version 1 — the first
 * released version, per build-plan P5. `exportSchema = true` writes to `:data/schemas/`, which
 * [migrationCallback] and future [Migration]s are tested against forward to head (FR-AST-5 →
 * AC-53).
 */
@Database(
    entities = [
        SessionEntity::class,
        TransmissionEntity::class,
        TranscriptEntity::class,
        WorkQueueItemEntity::class,
        CaptureGapEntity::class,
        PhoneticLatticeEntity::class,
        CallsignCandidateEntity::class,
        StationEntity::class,
        VoiceprintEntity::class,
        ThreadEntity::class,
        ContributionItemEntity::class,
        LexiconVersionEntity::class,
        CorrectionEntity::class,
        CalibrationEntity::class,
        AssetEntity::class,
        OperatorLocationEntity::class,
        StationSummaryEntity::class,
    ],
    version = OrtDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
public abstract class OrtDatabase : RoomDatabase() {

    public abstract fun sessionDao(): SessionDao
    public abstract fun transmissionDao(): TransmissionDao
    public abstract fun transcriptDao(): TranscriptDao
    public abstract fun workQueueDao(): WorkQueueDao
    public abstract fun captureGapDao(): CaptureGapDao
    public abstract fun catalogDao(): CatalogDao
    public abstract fun activityDao(): ActivityDao
    public abstract fun searchDao(): SearchDao
    public abstract fun correctionDao(): CorrectionDao

    public companion object {
        public const val SCHEMA_VERSION: Int = 1
        public const val DATABASE_NAME: String = "ort.db"

        /**
         * Every released schema's migration, in order (FR-AST-5, FR-AST-6 → AC-53). Empty at
         * v1 — the first release has nothing to migrate *from* yet; the fixture-forward-to-head
         * test in `:testing` is the harness this list plugs into as soon as v2 exists.
         */
        public val MIGRATIONS: Array<Migration> = emptyArray()

        /**
         * Adds the schema Room's annotations cannot express (technical design §8.3, §12.1):
         * partial unique indices, and the FTS5 external-content table with its sync triggers.
         * Room's own schema validation never sees these — it only hashes what it generated.
         */
        public val callback: Callback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                createHandWrittenSchema(db)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.execSQL("PRAGMA synchronous=NORMAL")
            }
        }

        public fun createHandWrittenSchema(db: SupportSQLiteDatabase) {
            // Exactly one current transcript per transmission (FR-REP-3 → AC-31).
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_transcript_one_current " +
                    "ON transcript(transmissionId) WHERE isCurrent = 1",
            )
            // Active-state-only uniqueness so a completed or finally-failed pass is
            // re-enqueueable (technical design §7.1's draft-1 fix).
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_wq_active " +
                    "ON work_queue_item(transmissionId, pass) WHERE state IN ('READY','LEASED','DEFERRED')",
            )
            createFtsIndex(db)
        }

        /**
         * FTS5 external-content over the whole transcript table (technical design §12.1) —
         * `content_rowid='rowid'` is SQLite's implicit rowid, valid even though `id` (the
         * declared TEXT primary key) is a separate column.
         *
         * minSdk 26's bundled SQLite always has the fts5 module; some *host-JVM* SQLite builds
         * used by Robolectric on the desktop do not. That gap is real but test-only, so it is
         * caught narrowly by message and skipped — not swallowed generally — leaving search
         * unavailable under Robolectric while the transaction/lifecycle mechanics this module's
         * acceptance criteria actually gate are unaffected.
         */
        private fun createFtsIndex(db: SupportSQLiteDatabase) {
            try {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS transcript_fts USING fts5(" +
                        "text, content='transcript', content_rowid='rowid')",
                )
            } catch (e: android.database.sqlite.SQLiteException) {
                if (e.message?.contains("no such module: fts5") != true) throw e
                return
            }
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS transcript_ai AFTER INSERT ON transcript BEGIN " +
                    "INSERT INTO transcript_fts(rowid, text) VALUES (new.rowid, new.text); END",
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS transcript_ad AFTER DELETE ON transcript BEGIN " +
                    "INSERT INTO transcript_fts(transcript_fts, rowid, text) " +
                    "VALUES('delete', old.rowid, old.text); END",
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS transcript_au AFTER UPDATE ON transcript BEGIN " +
                    "INSERT INTO transcript_fts(transcript_fts, rowid, text) VALUES('delete', old.rowid, old.text); " +
                    "INSERT INTO transcript_fts(rowid, text) VALUES (new.rowid, new.text); END",
            )
        }

        /** WAL + the hand-written schema (technical design §12.1) — the production and test factory. */
        @Suppress("SpreadOperator") // MIGRATIONS is tiny; addMigrations(vararg) has no non-spread overload.
        public fun create(context: Context, name: String = DATABASE_NAME, inMemory: Boolean = false): OrtDatabase {
            val builder = if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, OrtDatabase::class.java)
            } else {
                Room.databaseBuilder(context, OrtDatabase::class.java, name)
            }
            if (!inMemory) builder.setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            return builder
                .addMigrations(*MIGRATIONS)
                .addCallback(callback)
                .build()
        }
    }
}
