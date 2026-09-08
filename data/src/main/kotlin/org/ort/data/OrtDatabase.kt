package org.ort.data

import android.content.Context
import androidx.room.Database
import androidx.room.PooledConnection
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transactor
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.room.useWriterConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import org.ort.data.dao.ActivityDao
import org.ort.data.dao.CaptureGapDao
import org.ort.data.dao.CatalogDao
import org.ort.data.dao.CorrectionDao
import org.ort.data.dao.SearchDao
import org.ort.data.dao.SessionDao
import org.ort.data.dao.ShedEventDao
import org.ort.data.dao.StationIdentityDao
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
import org.ort.data.entity.PriorAdjustmentEntity
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.ShedEventEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.StationIdentityHistoryEntity
import org.ort.data.entity.StationSummaryEntity
import org.ort.data.entity.ThreadEntity
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.data.entity.VoiceprintBindingHistoryEntity
import org.ort.data.entity.VoiceprintEntity
import org.ort.data.entity.WorkQueueItemEntity

/**
 * The schema (functional spec §8; technical design §12.1). Schema version 3 — v1 was the first
 * released version (build-plan P5); v2 added [ShedEventEntity] (F-021, FR-RUN-3/4/5); v3 adds
 * [StationIdentityHistoryEntity], [VoiceprintBindingHistoryEntity] and [PriorAdjustmentEntity]
 * (register R-052, R-073; FR-SPK-10, FR-UI-6) so a station rename, a voiceprint rebinding and a
 * prior weight change each leave what they replaced reachable, not overwritten in place.
 * `exportSchema = true` writes to `:data/schemas/`, which [migrationCallback] and future
 * [Migration]s are tested against forward to head (FR-AST-5 → AC-53).
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
        ShedEventEntity::class,
        StationIdentityHistoryEntity::class,
        VoiceprintBindingHistoryEntity::class,
        PriorAdjustmentEntity::class,
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
    public abstract fun shedEventDao(): ShedEventDao
    public abstract fun stationIdentityDao(): StationIdentityDao

    public companion object {
        public const val SCHEMA_VERSION: Int = 3
        public const val DATABASE_NAME: String = "ort.db"

        /**
         * v1 → v2 (F-021): adds the `shed_event` table so [ShedEventEntity] rows can be
         * persisted. No existing table is touched — every v1 row survives untouched
         * (FR-AST-5/6 → AC-53), verified by `MigrationTest`.
         */
        public val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shed_event` (" +
                        "`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `levelBefore` INTEGER NOT NULL, " +
                        "`levelAfter` INTEGER NOT NULL, `trigger` TEXT NOT NULL, `reason` TEXT NOT NULL, " +
                        "`atWallMillis` INTEGER NOT NULL, `atMonotonicNanos` INTEGER NOT NULL, " +
                        "`samplePosition` INTEGER, PRIMARY KEY(`id`))",
                )
            }
        }

        /**
         * v2 → v3 (register R-052, R-073): adds `station_identity_history`,
         * `voiceprint_binding_history` and `prior_adjustment` — see [StationIdentityDao]. No
         * existing table or column is touched — every v2 row survives untouched (FR-AST-5/6 →
         * AC-53), verified by `MigrationTest`.
         *
         * Deliberately does **not** create `idx_prior_adjustment_one_current` here — that partial
         * unique index lives only in [applyHandWrittenSchema], the same choice already made for
         * `idx_transcript_one_current` (see that function's comment): Room's schema validation
         * compares a migrated database only against what the `@Entity`/`@Index` annotations
         * declare, so a hand-written index created *by a `Migration`* would make every future
         * migration test fail a spurious "unexpected index" check. The accepted consequence,
         * carried over unchanged from `idx_transcript_one_current`, is that the constraint is
         * enforced on a fresh install but not (yet) on a database upgraded from v2.
         */
        public val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `station_identity_history` (" +
                        "`id` TEXT NOT NULL, `stationId` TEXT NOT NULL, `field` TEXT NOT NULL, " +
                        "`previousValue` TEXT, `newValue` TEXT, `changedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_station_identity_history_stationId` " +
                        "ON `station_identity_history` (`stationId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `voiceprint_binding_history` (" +
                        "`id` TEXT NOT NULL, `voiceprintId` TEXT NOT NULL, `previousStationId` TEXT, " +
                        "`previousBindingConfidence` REAL, `previousBindingSource` TEXT, " +
                        "`newStationId` TEXT, `newBindingConfidence` REAL, `newBindingSource` TEXT, " +
                        "`changedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_voiceprint_binding_history_voiceprintId` " +
                        "ON `voiceprint_binding_history` (`voiceprintId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `prior_adjustment` (" +
                        "`id` TEXT NOT NULL, `stationId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`weight` REAL NOT NULL, `reason` TEXT, `isCurrent` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_prior_adjustment_stationId_name_isCurrent` " +
                        "ON `prior_adjustment` (`stationId`, `name`, `isCurrent`)",
                )
            }
        }

        /**
         * Every released schema's migration, in order (FR-AST-5, FR-AST-6 → AC-53).
         */
        public val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        private suspend fun PooledConnection.exec(sql: String) {
            usePrepared(sql) { it.step() }
        }

        /**
         * Adds the schema Room's annotations cannot express (technical design §8.3, §12.1):
         * partial unique indices, and the FTS5 external-content table with its sync triggers.
         * Room's own schema validation never sees these — it only hashes what it generated.
         *
         * **Run explicitly from [create], every time, not from a `RoomDatabase.Callback`.**
         * Register R-204: `RoomDatabase.Callback.onCreate`/`.onOpen` were the original home for
         * this (and, before that, the only place `createFtsIndex` ran at all) — confirmed
         * empirically, by instrumenting `onOpen` to throw unconditionally and observing that nothing
         * in this module's test suite ever caught it, that **neither callback runs at all** once
         * `RoomDatabase.Builder.setDriver(...)` is used (this project's Room version, 2.7.2): every
         * hand-written index silently never existed, and `transcript_fts` silently never got built,
         * with no error anywhere — exactly the kind of silent failure the constitution's "uncertainty
         * is content" principle exists to prevent, just one layer down in the stack instead of in the
         * product itself. Every statement below is idempotent (`IF NOT EXISTS`, or FTS5's own
         * `'rebuild'` command), so calling this on every [create] — fresh install or the ten-thousandth
         * launch alike — is deliberately cheap-and-safe rather than conditional.
         */
        private suspend fun applyHandWrittenSchema(connection: PooledConnection) {
            // Exactly one current transcript per transmission (FR-REP-3 → AC-31).
            connection.exec(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_transcript_one_current " +
                    "ON transcript(transmissionId) WHERE isCurrent = 1",
            )
            // Active-state-only uniqueness so a completed or finally-failed pass is
            // re-enqueueable (technical design §7.1's draft-1 fix).
            connection.exec(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_wq_active " +
                    "ON work_queue_item(transmissionId, pass) WHERE state IN ('READY','LEASED','DEFERRED')",
            )
            // Exactly one current weight per (station, named prior) — register R-052.
            connection.exec(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_prior_adjustment_one_current " +
                    "ON prior_adjustment(stationId, name) WHERE isCurrent = 1",
            )
            ensureFtsIndex(connection)
        }

        /**
         * R-204: an install whose database predates this change (or, before it, one opened on a
         * platform whose SQLite lacked fts5 entirely) never got `transcript_fts` built. Repaired
         * every time [applyHandWrittenSchema] runs (every [create] call — see its doc comment)
         * rather than as a one-shot `Migration`, because the failure being recovered from — "the
         * index was never built" — is not tied to a schema version.
         *
         * [createFtsIndex]'s `CREATE VIRTUAL TABLE`/`CREATE TRIGGER` statements are already
         * `IF NOT EXISTS`, so calling it unconditionally is safe; the FTS5 `'rebuild'` command is
         * fts5's own built-in "recompute the whole index from the external content table"
         * operation — also idempotent by definition — used here instead of a conditional "does the
         * index already have rows" probe so this needs no extra raw-query round trip.
         */
        private suspend fun ensureFtsIndex(connection: PooledConnection) {
            if (!createFtsIndex(connection)) return
            connection.exec("INSERT INTO transcript_fts(transcript_fts) VALUES('rebuild')")
        }

        /**
         * FTS5 external-content over the whole transcript table (technical design §12.1) —
         * `content_rowid='rowid'` is SQLite's implicit rowid, valid even though `id` (the
         * declared TEXT primary key) is a separate column.
         *
         * Returns whether the index was actually built. Before R-204 this module ran on whatever
         * SQLite the platform (or Robolectric's host-JVM shadow) happened to ship, and some of
         * those builds have no fts5 module at all — the API 34 reference emulator's among them —
         * so this used to be a real, silently-accepted fallback. [create] now always installs
         * [BundledSQLiteDriver], which bundles a SQLite built with fts5, so in practice this catch
         * is unreachable; it is kept because "assume the platform SQLite has fts5" is exactly the
         * assumption that was false, and a caller ([ensureFtsIndex]) still needs to know whether it
         * can safely run the rebuild that depends on the table existing. The driver throws
         * `android.database.SQLException` (its base Android-compatible type, not always the
         * `SQLiteException` subclass) for a failed prepare, confirmed from this exact call site.
         */
        private suspend fun createFtsIndex(connection: PooledConnection): Boolean {
            try {
                connection.exec(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS transcript_fts USING fts5(" +
                        "text, content='transcript', content_rowid='rowid')",
                )
            } catch (e: android.database.SQLException) {
                if (e.message?.contains("no such module: fts5") != true) throw e
                return false
            }
            connection.exec(
                "CREATE TRIGGER IF NOT EXISTS transcript_ai AFTER INSERT ON transcript BEGIN " +
                    "INSERT INTO transcript_fts(rowid, text) VALUES (new.rowid, new.text); END",
            )
            connection.exec(
                "CREATE TRIGGER IF NOT EXISTS transcript_ad AFTER DELETE ON transcript BEGIN " +
                    "INSERT INTO transcript_fts(transcript_fts, rowid, text) " +
                    "VALUES('delete', old.rowid, old.text); END",
            )
            connection.exec(
                "CREATE TRIGGER IF NOT EXISTS transcript_au AFTER UPDATE ON transcript BEGIN " +
                    "INSERT INTO transcript_fts(transcript_fts, rowid, text) VALUES('delete', old.rowid, old.text); " +
                    "INSERT INTO transcript_fts(rowid, text) VALUES (new.rowid, new.text); END",
            )
            return true
        }

        /**
         * WAL + the hand-written schema (technical design §12.1) + [BundledSQLiteDriver]
         * (register R-204, FR-UI-3) — the production and test factory, and the **only** place a
         * connection is opened, so every caller — the shipped app and every Robolectric/JVM test —
         * gets the same SQLite build. Room's default driver defers to whatever SQLite the platform
         * ships; the API 34 reference emulator's platform SQLite has no `fts5` module ("no such
         * module: fts5"), and Robolectric's host-JVM shadow SQLite has the same gap.
         * [BundledSQLiteDriver] bypasses the platform SQLite entirely and talks to a SQLite binary
         * this app ships (`androidx.sqlite:sqlite-bundled`), built with fts5.
         *
         * [applyHandWrittenSchema] runs here, synchronously (`runBlocking`), rather than through
         * `RoomDatabase.Builder.addCallback(...)` — see that function's doc comment for why the
         * callback path silently does not run at all under `.setDriver(...)`. Blocking the calling
         * thread until the writer connection's setup completes is the same shape opening a database
         * has always had (the classic `SupportSQLiteOpenHelper` path is synchronous too); it keeps
         * `create()` non-suspend, which every existing caller across the app depends on.
         */
        @Suppress("SpreadOperator") // MIGRATIONS is tiny; addMigrations(vararg) has no non-spread overload.
        public fun create(context: Context, name: String = DATABASE_NAME, inMemory: Boolean = false): OrtDatabase {
            val builder = if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, OrtDatabase::class.java)
            } else {
                Room.databaseBuilder(context, OrtDatabase::class.java, name)
            }
            if (!inMemory) builder.setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            val db = builder
                .setDriver(BundledSQLiteDriver())
                .addMigrations(*MIGRATIONS)
                .build()
            runBlocking { db.useWriterConnection { connection -> applyHandWrittenSchema(connection) } }
            return db
        }
    }
}

/**
 * Runs [block] — typically several DAO calls across more than one DAO, with ordinary Kotlin
 * control flow in between — as one write transaction. [OrtDatabase.create] always installs
 * [BundledSQLiteDriver] (register R-204), and `androidx.room.withTransaction`
 * (`androidx.room:room-ktx`'s KTX helper this module used before) still assumes the classic
 * `SupportSQLiteOpenHelper` internally — it throws `Cannot return a SupportSQLiteOpenHelper since
 * no SupportSQLiteOpenHelper.Factory was configured with Room` the moment a driver is set. This is
 * the driver-native replacement, built directly on [androidx.room.useWriterConnection] and
 * [Transactor.withTransaction]: every suspend DAO call issued from [block] transparently reuses
 * the same pooled connection and transaction (the same mechanism a `@Transaction`-annotated DAO
 * default method already relies on — see [org.ort.data.dao.CorrectionDao.recordCorrection],
 * [org.ort.data.dao.TranscriptDao.supersede] — which is why those kept working unchanged).
 * `IMMEDIATE` (not `DEFERRED`) matches `withTransaction`'s old default: the write lock is taken up
 * front, not on the first write statement, so two callers cannot both start and then discover a
 * conflict partway through.
 */
public suspend fun <R> OrtDatabase.inWriteTransaction(block: suspend () -> R): R =
    useWriterConnection { transactor -> transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { block() } }

/**
 * Runs [sql] (typically `DELETE`/`UPDATE` with no result set — this is not a `SELECT` helper) with
 * [args] bound positionally, the driver-native replacement for the removed
 * `SupportSQLiteDatabase.execSQL(sql, args)` call callers reached via `OrtDatabase.openHelper` —
 * itself unavailable once [BundledSQLiteDriver] is installed (register R-204; see
 * [inWriteTransaction]'s doc comment for the same story on `androidx.room.withTransaction`).
 * `:data` has no DAO for arbitrary/ad-hoc statements — build-plan P5 never needed one — so this is
 * for the rare caller (today, only `app/src/debug/Scenarios.kt`'s scenario-data cleanup) that
 * genuinely does need to run a raw statement Room's own generated DAOs cannot express, kept here
 * rather than reinvented per-caller so there is exactly one binding implementation to get right.
 */
public suspend fun OrtDatabase.execRaw(sql: String, vararg args: Any?) {
    useWriterConnection { transactor ->
        transactor.usePrepared(sql) { statement ->
            args.forEachIndexed { index, arg ->
                val position = index + 1
                when (arg) {
                    null -> statement.bindNull(position)
                    is String -> statement.bindText(position, arg)
                    is Long -> statement.bindLong(position, arg)
                    is Int -> statement.bindLong(position, arg.toLong())
                    is Double -> statement.bindDouble(position, arg)
                    is Boolean -> statement.bindBoolean(position, arg)
                    is ByteArray -> statement.bindBlob(position, arg)
                    else -> error("execRaw(...) cannot bind argument of type ${arg::class}: $arg")
                }
            }
            statement.step()
        }
    }
}
