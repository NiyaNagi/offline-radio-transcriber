package org.ort.data

import android.content.Context
import androidx.room.Database
import androidx.room.PooledConnection
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transactor
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.room.useReaderConnection
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
import org.ort.data.entity.LatticeSlotEntity
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
import org.ort.data.entity.WorkAttemptEntity
import org.ort.data.entity.WorkQueueItemEntity
import java.util.concurrent.Executors

/**
 * The schema (functional spec §8; technical design §12.1). Schema version 5 — v1 was the first
 * released version (build-plan P5); v2 added [ShedEventEntity] (F-021, FR-RUN-3/4/5); v3 adds
 * [StationIdentityHistoryEntity], [VoiceprintBindingHistoryEntity] and [PriorAdjustmentEntity]
 * (register R-052, R-073; FR-SPK-10, FR-UI-6) so a station rename, a voiceprint rebinding and a
 * prior weight change each leave what they replaced reachable, not overwritten in place; v4 adds
 * [TransmissionEntity.processedTier] (register R-204 follow-up, FR-REP-2/9) so a reprocess run's
 * outcome tier is queryable per record, not just per session; v5 also adds
 * [CorrectionEntity.previousAttributionState] and its three siblings (register R-321) so
 * `Undo all` can restore a correction's exact prior attribution, not just its prior callsign,
 * and [LatticeSlotEntity] (register R-320, R-182) so each candidate's per-slot lattice detail
 * and transcript char span are queryable per candidate, not just opaque inside
 * [PhoneticLatticeEntity.unitsBlob]; v6 adds [WorkAttemptEntity] (register R-426) so
 * `Fail-Pass.dc.html` can list every failed attempt at a queue item with its own timestamp and
 * reason, not just the queue item's own `attemptCount`/`lastError` (the latest attempt only);
 * v7 adds [SessionEntity.captureMode] and its four siblings (FR-CAP-13, AC-129) so a session
 * records which capture mode, audio route and rig transport produced it — see that entity's own
 * doc comment for why all five are nullable.
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
        LatticeSlotEntity::class,
        WorkAttemptEntity::class,
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
        public const val SCHEMA_VERSION: Int = 7
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
         * v3 → v4 (register R-204 follow-up, FR-REP-2/9): adds `transmission.processedTier` —
         * see [org.ort.data.entity.TransmissionEntity]'s own doc comment. No existing column is
         * touched or dropped; every v3 row survives with `processedTier = NULL` (FR-AST-5/6 →
         * AC-53), verified by `MigrationTest`. A nullable `ADD COLUMN` needs no `DEFAULT` clause —
         * SQLite's own default for an added nullable column is `NULL`.
         */
        public val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transmission` ADD COLUMN `processedTier` TEXT")
            }
        }

        /**
         * v4 → v5, shared by two independent register items (the lead's own instruction: one
         * version bump, not a v5→v6 for the second):
         *
         * - **R-321**: adds `correction.previousAttributionState`, `.previousAttributionConfidence`,
         *   `.previousAttributionSourceTransmissionId` and `.previousCorrected` — see
         *   [org.ort.data.entity.CorrectionEntity]'s own doc comment.
         * - **R-320, R-182**: adds the `lattice_slot` table for [org.ort.data.entity.LatticeSlotEntity]
         *   (one row per [org.ort.lexicon.SlotDetail] per candidate) — see that entity's own doc
         *   comment.
         *
         * No existing table or column is touched or dropped; every v4 row survives, the four new
         * `correction` columns `NULL` (FR-AST-5/6 → AC-53), verified by `MigrationTest`.
         */
        public val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `correction` ADD COLUMN `previousAttributionState` TEXT")
                db.execSQL("ALTER TABLE `correction` ADD COLUMN `previousAttributionConfidence` REAL")
                db.execSQL("ALTER TABLE `correction` ADD COLUMN `previousAttributionSourceTransmissionId` TEXT")
                db.execSQL("ALTER TABLE `correction` ADD COLUMN `previousCorrected` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `lattice_slot` (" +
                        "`id` TEXT NOT NULL, `transmissionId` TEXT NOT NULL, `candidateId` TEXT NOT NULL, " +
                        "`index` INTEGER NOT NULL, `unit` TEXT NOT NULL, `score` REAL NOT NULL, " +
                        "`keptAlternate` TEXT, `charStart` INTEGER, `charEnd` INTEGER, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_lattice_slot_transmissionId_candidateId_index` " +
                        "ON `lattice_slot` (`transmissionId`, `candidateId`, `index`)",
                )
            }
        }

        /**
         * v5 → v6 (register R-426): adds the `work_attempt` table for [WorkAttemptEntity] — one
         * row per failed attempt at a [WorkQueueItemEntity], written by
         * [org.ort.data.WorkQueue.failPass]. No existing table or column is touched or dropped;
         * every v5 row survives untouched (FR-AST-5/6 → AC-53), verified by `MigrationTest`.
         */
        public val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `work_attempt` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `itemId` INTEGER NOT NULL, " +
                        "`attemptNo` INTEGER NOT NULL, `startedAtMillis` INTEGER NOT NULL, " +
                        "`finishedAtMillis` INTEGER NOT NULL, `outcome` TEXT NOT NULL, `reason` TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_work_attempt_itemId_attemptNo` " +
                        "ON `work_attempt` (`itemId`, `attemptNo`)",
                )
            }
        }

        /**
         * v6 → v7 (FR-CAP-13, AC-129): adds `session.captureMode`, `.audioRouteKind`,
         * `.audioRouteLabel`, `.bluetoothProfile` and `.rigTransport` — see [SessionEntity]'s own
         * doc comment for why all five are nullable `TEXT` columns rather than the `:core` enum
         * types directly. No existing table or column is touched or dropped; every v6 row survives
         * untouched, all five new columns `NULL` (FR-AST-5/6 → AC-53), verified by `MigrationTest`.
         */
        public val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `session` ADD COLUMN `captureMode` TEXT")
                db.execSQL("ALTER TABLE `session` ADD COLUMN `audioRouteKind` TEXT")
                db.execSQL("ALTER TABLE `session` ADD COLUMN `audioRouteLabel` TEXT")
                db.execSQL("ALTER TABLE `session` ADD COLUMN `bluetoothProfile` TEXT")
                db.execSQL("ALTER TABLE `session` ADD COLUMN `rigTransport` TEXT")
            }
        }

        /**
         * Every released schema's migration, in order (FR-AST-5, FR-AST-6 → AC-53).
         */
        public val MIGRATIONS: Array<Migration> =
            arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)

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
         * Register R-204's follow-up, from WP0's `Scenarios.kt` regression (register R-110,
         * `ScenariosTest :: R_110 every declared scenario name loads without throwing`): a
         * transient single-writer lock — two `OrtDatabase` instances open against the same
         * on-disk file at once, one mid-write while the other opens, exactly the shape that
         * test's own regression case reproduced — should make the *next* writer **wait**, not
         * throw `SQLITE_BUSY`/`SQLITE_LOCKED` immediately.
         *
         * Room's own driver-mode connection pipeline already sets a `PRAGMA busy_timeout` of
         * `BaseRoomConnectionManager.BUSY_TIMEOUT_MS` (3000ms — confirmed by disassembly of the
         * shipped `room-runtime` class; there is no public API for it) on every connection it
         * opens. That call is part of the same always-run `configureDatabase` step
         * [applyHandWrittenSchema] discovered `RoomDatabase.Callback` is *not* part of, so, unlike
         * this file's hand-written schema, it was never actually broken — every connection already
         * gets a busy-timeout floor. This sets a longer, explicit value on the writer connection
         * [create] already touches once per `OrtDatabase` instance — for headroom beyond that
         * 3000ms default under exactly the kind of shared-machine/back-to-back-instance contention
         * this project's own test suite has hit (`PRAGMA busy_timeout` only ever *raises* how long
         * SQLite retries internally before giving up; it is always safe to set a larger value on
         * top of Room's own). `Scenarios.kt`'s bounded, backed-off retry (register R-110) stays in
         * place as defence in depth for whatever a 10-second wait does not itself absorb.
         */
        private suspend fun configureBusyTimeout(connection: PooledConnection) {
            connection.exec("PRAGMA busy_timeout = $BUSY_TIMEOUT_MILLIS")
        }

        private const val BUSY_TIMEOUT_MILLIS = 10_000L

        /**
         * This task (register R-204 follow-up, FR-UI-3): whether *this process's* SQLite build
         * actually has the fts5 module, decided **once** by a positive capability probe, never by
         * parsing a driver exception's message text. The prior version of [createFtsIndex] caught
         * `android.database.SQLException` from a failed `CREATE VIRTUAL TABLE ... USING fts5(...)`
         * and swallowed it only `if (e.message?.contains("no such module: fts5") == true)`. A
         * three-instrumented-run CI investigation (this repository's own `data-msg` commit,
         * 5a9f53a) proved that message text is not a stable cross-platform signal: the *same*
         * failed prepare throws with `"Error code: 19, message: UNIQUE constraint failed: ..."` on
         * Windows and bare `"Error code: "` — no numeric code, no descriptive text at all — on the
         * Linux CI runner, for a completely unrelated constraint failure. Applied to
         * `createFtsIndex`'s own substring check, the same platform gap means a genuine
         * missing-fts5 build could arrive with a message that does not contain the literal English
         * phrase "no such module: fts5" and get rethrown instead of degrading gracefully — silently
         * failing to open the database on exactly the platform this fallback exists for. This is
         * the kind of silent failure constitution I exists to catch, one layer below the product.
         *
         * `PRAGMA compile_options` lists every compile-time feature flag the running SQLite build
         * was compiled with; `ENABLE_FTS5` is fts5's own (https://sqlite.org/compile.html) — a
         * fact about the build, not a parsed failure message. Cached for the process's lifetime
         * once known: capability is a property of the driver binary in use, not of any one
         * [OrtDatabase] instance or connection, so re-probing on every [create] call would just
         * repeat the same query for the same answer. A racing double computation is harmless —
         * every racer observes the same driver and therefore computes the same result — so this is
         * deliberately not synchronized.
         */
        @Volatile
        private var fts5SupportedCache: Boolean? = null

        /**
         * Test-only seam: when non-null, [isFts5Supported] returns this instead of probing —
         * `BundledSQLiteDriver` always ships fts5 compiled in, so no build reachable from this
         * module's own test suite can otherwise exercise the fts5-absent path. Production code
         * never assigns this; see `FtsCapabilityProbeTest` for the test that does.
         */
        @Volatile
        internal var fts5SupportOverrideForTest: Boolean? = null

        internal suspend fun isFts5Supported(connection: PooledConnection): Boolean {
            fts5SupportOverrideForTest?.let { return it }
            fts5SupportedCache?.let { return it }
            val supported = connection.usePrepared("PRAGMA compile_options") { statement ->
                var found = false
                while (statement.step()) {
                    if (statement.getText(0) == "ENABLE_FTS5") {
                        found = true
                        break
                    }
                }
                found
            }
            fts5SupportedCache = supported
            return supported
        }

        /**
         * FTS5 external-content over the whole transcript table (technical design §12.1) —
         * `content_rowid='rowid'` is SQLite's implicit rowid, valid even though `id` (the
         * declared TEXT primary key) is a separate column.
         *
         * Returns whether the index was actually built, decided up front by [isFts5Supported] —
         * see that function's own doc comment for why this is no longer a try/catch around the
         * `CREATE VIRTUAL TABLE` statement. When [isFts5Supported] says fts5 is absent, this
         * returns `false` without ever attempting the statement — no exception to parse or
         * mis-parse. When it says fts5 is present, the statement is expected to succeed; if it
         * (or either trigger below) throws anyway, that is a **real, unrelated database error**
         * — corruption, a disk fault, something this function has no business hiding — and it is
         * deliberately left to propagate uncaught rather than being folded into the "no fts5"
         * fallback.
         */
        private suspend fun createFtsIndex(connection: PooledConnection): Boolean {
            if (!isFts5Supported(connection)) return false
            connection.exec(
                "CREATE VIRTUAL TABLE IF NOT EXISTS transcript_fts USING fts5(" +
                    "text, content='transcript', content_rowid='rowid')",
            )
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
         * Coordinator escalation, 2026-09-08 (blocks every merge): since the FTS5/driver merge
         * (register R-204), every Compose screen that collects a Room `Flow` took minutes instead
         * of seconds to go idle under `RobolectricIdlingStrategy.runUntilIdle` — fine alone, only
         * under many concurrent Gradle workers/test classes sharing one JVM.
         *
         * Root cause, confirmed by disassembling the shipped `room-runtime` 2.7.2 classes (there is
         * no public API to inspect this): [RoomDatabase.Builder.build] — when neither
         * `setQueryExecutor` nor `setTransactionExecutor` nor `setQueryCoroutineContext` is called,
         * which was true here both before and after the driver switch — falls back to
         * `androidx.arch.core.executor.ArchTaskExecutor.getIOThreadExecutor()`, itself
         * `Executors.newFixedThreadPool(4, ...)`: a **static, JVM-process-wide singleton**, shared
         * with every other AndroidX Architecture Components consumer in the same test JVM (LiveData,
         * WorkManager-compat, Paging), not per-`OrtDatabase`-instance. `RoomDatabase.getCoroutineScope()`
         * — the dispatcher `androidx.room.util.DBUtil.getCoroutineContext` hands to *every* suspend
         * query outside an explicit transaction, including [TriggerBasedInvalidationTracker]'s
         * `Flow` collector every `Flow`-returning DAO query subscribes to — is built directly on
         * that same 4-thread pool (`ExecutorsKt.from(internalQueryExecutor) + SupervisorJob()`).
         *
         * That pool was already the (undocumented) default before R-204. What changed at the driver
         * switch is [applyHandWrittenSchema] now running through [useWriterConnection] on *every*
         * [create] call (the `RoomDatabase.Callback` path it replaces never touched an executor at
         * all — see that function's doc comment) — so opening a database now itself round-trips
         * through this same shared 4-thread pool, on every test, on top of the [configureBusyTimeout]
         * 10s ceiling (register R-204 follow-up) a stuck writer can now hold a pool thread for. Under
         * one JVM running many test classes (each constructing its own `OrtDatabase`) at once, that
         * pool saturates; a Compose screen's `Flow` collector queued behind it can't produce its next
         * value, so `runUntilIdle` spins — at 100% CPU, since it is polling for idleness, not blocked
         * — until the queue finally drains. A single class run alone never saturates 4 threads.
         *
         * Fix: give every `OrtDatabase` its own dedicated, cached (not fixed) pool via
         * `setQueryExecutor` (Room reuses it for `transactionExecutor` too when only this is set —
         * `RoomDatabase.Builder.build`'s own fallback, confirmed the same way) so opening a database,
         * running a query, and collecting an invalidation `Flow` never again contend with whatever
         * else in the process happens to be using [androidx.arch.core.executor.ArchTaskExecutor]'s
         * pool — the actual isolation failure, not the pool being "too small" in absolute terms. One
         * `Executors.newCachedThreadPool()`, held for the process's lifetime (never shut down; Room
         * itself has no `OrtDatabase`-scoped shutdown hook, and a per-instance pool would leak threads
         * across the many short-lived `OrtDatabase.create(..., inMemory = true)` instances tests
         * already create) — idle threads in a cached pool time out after 60s on their own, so this
         * never grows unbounded the way a per-instance never-`shutdown()` pool would.
         */
        private val queryExecutor: java.util.concurrent.ExecutorService by lazy { Executors.newCachedThreadPool() }

        /**
         * Test-suite regression, 2026-09-08 (this class's own report): every real `*Polling`
         * object in `:app` (`ReaderPolling`, `LogPolling`, `DigestPolling`, `CorrectionPolling`,
         * `SearchPolling`/`ThreadPolling`, `SettingsPolling`, `ImprovePolling`/`ImproveRunner`,
         * `FailureSignalsPolling`, `LiveBarPolling`, `DrawerCounts`, `ModelsController`, the
         * diagnostics-bundle producers, `RealTransmissionAudioPlayer`) calls [create] fresh on
         * every invocation and never closes what it opens — by design, per [ReaderPolling]'s own
         * kdoc ("this stays a poll for now"), several of these run inside a screen's
         * `LaunchedEffect(key) { while (true) { ...; delay(2_000) } }` loop (`LogContent.kt`,
         * `NowContent.kt`, `CaptureStatusContent.kt`, `ThreadContent.kt`, `ImproveContent.kt`,
         * `ModelsContent.kt`, `OrtNavHost.kt`, `FailureHost.kt`) — every 2 real seconds, forever,
         * for as long as that screen stays open. `ReaderActivity.kt`'s own kdoc already documents
         * that such a loop, once started under a Robolectric-driven test, is "never [given] a
         * chance to cleanly cancel" — a stale `Handler`-posted continuation can keep firing into
         * *later, unrelated* test classes sharing the same JVM fork, each firing opening one more
         * never-closed [OrtDatabase] against the same on-disk file. Each new instance re-runs
         * [applyHandWrittenSchema] through [useWriterConnection] (register R-204) on the shared
         * [queryExecutor] above, and WAL-mode SQLite serialises writers — enough simultaneously
         * open instances against one file compound into exactly the "fine alone, catastrophic
         * combined" slowdown this file's own R-204 fix already diagnosed once for a different
         * cause. This is also a genuine device-side leak, not just a test artifact: a real capture
         * session left open for hours would accumulate one native SQLite connection (and file
         * descriptors) every 2 seconds from every polling screen the operator has visited.
         *
         * Fix, at the one place every caller already funnels through: cache a live, non-in-memory
         * [OrtDatabase] by its on-disk path (matching [Context.getDatabasePath]'s own identity —
         * every caller passes the same [DATABASE_NAME] in production, and Robolectric gives each
         * simulated app install its own `filesDir`, so this never conflates two unrelated tests'
         * data) and hand back the *same* instance instead of opening a new one — turning the
         * existing "open on every call" idiom into what every caller already assumed it was: cheap
         * to call repeatedly. [OrtDatabase.close] (only test `@After` blocks call it — no
         * production call site does, confirmed by search) evicts itself automatically: a cached
         * instance is only reused while [RoomDatabase.isOpen] is still true, and a test that
         * deletes the on-disk file first (`context.deleteDatabase(DATABASE_NAME)` — `SearchPollingTest`
         * and its siblings) is also honoured, since a cache hit is discarded when the file it names
         * no longer exists. `inMemory` instances are deliberately never cached — each of those is
         * already a short-lived, intentionally isolated instance (this file's own [queryExecutor]
         * kdoc already names that pattern as normal), and caching one under a name shared with
         * every other in-memory caller would silently leak state between unrelated tests instead
         * of fixing a leak.
         */
        private val instances = java.util.concurrent.ConcurrentHashMap<String, OrtDatabase>()

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
         *
         * `.setQueryExecutor(queryExecutor)` — see [queryExecutor]'s own doc comment for why every
         * `OrtDatabase` instance needs a pool dedicated to this module, not Room's shared default.
         */
        public fun create(context: Context, name: String = DATABASE_NAME, inMemory: Boolean = false): OrtDatabase {
            // See [instances]'s own kdoc: every non-in-memory caller shares one live instance per
            // on-disk path, reused while it is still open and the file it names still exists.
            if (!inMemory) {
                val path = context.applicationContext.getDatabasePath(name).absolutePath
                instances[path]?.let { cached ->
                    if (cached.isOpen && java.io.File(path).exists()) return cached
                    instances.remove(path, cached)
                }
                val fresh = buildAndInitialize(context, name, inMemory = false)
                // Another thread may have raced this one to the same key — `putIfAbsent` keeps
                // whichever instance wins the race as the single shared one; the loser's own
                // connection has no other reference once discarded, so nothing leaks by losing.
                val winner = instances.putIfAbsent(path, fresh) ?: fresh
                return winner
            }
            return buildAndInitialize(context, name, inMemory = true)
        }

        @Suppress("SpreadOperator") // MIGRATIONS is tiny; addMigrations(vararg) has no non-spread overload.
        private fun buildAndInitialize(context: Context, name: String, inMemory: Boolean): OrtDatabase {
            val builder = if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, OrtDatabase::class.java)
            } else {
                Room.databaseBuilder(context, OrtDatabase::class.java, name)
            }
            if (!inMemory) builder.setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            val db = builder
                .setDriver(BundledSQLiteDriver())
                .setQueryExecutor(queryExecutor)
                .addMigrations(*MIGRATIONS)
                .build()
            runBlocking {
                db.useWriterConnection { connection ->
                    configureBusyTimeout(connection)
                    applyHandWrittenSchema(connection)
                }
            }
            return db
        }
    }
}

/**
 * FR-UI-3, this task (register R-204 follow-up): whether this database's `transcript_fts` index
 * actually exists — the positive, schema-level fact `:app`'s [org.ort.data.OrtDatabase]-backed
 * search path checks *before* running a text query, instead of inferring "fts5 is missing" from a
 * caught exception's message text (see [OrtDatabase.createFtsIndex]'s own doc comment for why that
 * text is not a stable cross-platform signal). [OrtDatabase.applyHandWrittenSchema] runs on every
 * [OrtDatabase.create] call and builds `transcript_fts` whenever [OrtDatabase.isFts5Supported]
 * says fts5 is present, so on every supported SQLite build this is already `true` by the time a
 * caller can observe it; it reads `false` only when this build's SQLite genuinely has no fts5
 * module — `:data`'s own honest "a text search cannot run here" fact, not a guess from a string.
 */
public suspend fun OrtDatabase.hasTextSearchIndex(): Boolean = useReaderConnection { connection ->
    connection.usePrepared(
        "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'transcript_fts'",
    ) { statement ->
        statement.step()
        statement.getLong(0) > 0L
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
public suspend fun <R> OrtDatabase.inWriteTransaction(block: suspend () -> R): R = useWriterConnection { transactor ->
    transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { block() }
}

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
