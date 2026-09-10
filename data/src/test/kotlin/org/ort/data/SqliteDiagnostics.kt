package org.ort.data

import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * Evidence-gathering only (CI cross-platform diagnostic task; see `data/build.gradle.kts`'s own
 * comment and CHANGELOG.md for the rounds this backs). `TranscriptVersioningTest` and
 * `WorkQueueTest` each pass on Windows and fail on Linux CI (runs 34439250807, 34440826468,
 * 34442248761) asserting a duplicate write violates a partial unique index
 * (`idx_transcript_one_current`, `idx_wq_active`) — on Linux the insert silently succeeds
 * instead. Two prior rounds' fixes (a dependency-substitution in `data/build.gradle.kts`, then a
 * first instrumentation pass) did not resolve it and did not explain it either: run 34442248761
 * showed identical `sqlite_version()`, an identical index listing with the right `WHERE` clause,
 * and exactly one driver jar on the classpath — every theory the first round could form was
 * eliminated by its own evidence. This file changes no production behaviour and weakens no
 * assertion — it only reports, for the *exact* [OrtDatabase] instance each failing test's own
 * `@Before` builds (see [TranscriptVersioningTest.openDatabase] and [WorkQueueTest.openDatabase]):
 *
 * - `SELECT sqlite_version()` — which SQLite build actually answered the connection;
 * - the full `sqlite_master` index listing — whether `idx_transcript_one_current` and
 *   `idx_wq_active` exist at all on this instance, and with what `WHERE` clause, rather than
 *   assuming [OrtDatabase.create]'s `CREATE UNIQUE INDEX` statements ran and succeeded;
 * - which jar [BundledSQLiteDriver] — the class [OrtDatabase.create] hardcodes via
 *   `.setDriver(BundledSQLiteDriver())` — actually loaded from, and every `sqlite`-named jar on
 *   this JVM's classpath, so a second driver-providing artifact sneaking back onto a CI-only
 *   classpath (a stale lock file, a differently-resolved graph, anything the local
 *   `:data:dependencies` check in this task's report can't see) would show up here even though it
 *   didn't show up in a local, already-warm dependency resolution;
 * - `os.name`/`os.arch`, so a report can be matched to the runner that produced it.
 *
 * This round adds two more probes, both aimed at the one question the prior evidence left open —
 * "the index is there and matches, so what is actually *in* the table after the second write?":
 *
 * - [dumpRows] — the real row content, including `typeof(...)` on the column the partial index
 *   predicates on, so a binding/affinity difference (`isCurrent` stored as `'1'` text rather than
 *   integer `1`, say) would show up directly instead of being inferred;
 * - [rawDuplicateInsertProbe] — runs the identical duplicate `INSERT` as raw SQL straight through
 *   the driver, bypassing the Room-generated DAO adapter entirely, inside its own
 *   `SAVEPOINT`/`ROLLBACK TO` so it never leaves a lasting change behind regardless of outcome —
 *   to tell a Room-layer behaviour difference from a SQLite-layer one.
 */
internal object SqliteDiagnostics {

    /**
     * Prints a report to stdout (plain `println`, so it lands in the Gradle/CI test-output log
     * without needing a custom listener) and also returns it as a string so a failing assertion
     * can fold the same evidence into its own message (see the two failing tests' own diffs).
     */
    suspend fun report(db: OrtDatabase, label: String): String {
        val text = runCatching { buildReport(db, label) }
            .getOrElse { failure ->
                "=== SQLITE DIAGNOSTICS ($label) ===\n" +
                    "report generation itself threw: ${failure::class.qualifiedName}: ${failure.message}\n" +
                    "=== END SQLITE DIAGNOSTICS ($label) ===\n"
            }
        println(text)
        return text
    }

    private suspend fun buildReport(db: OrtDatabase, label: String): String {
        data class IndexRow(val name: String, val sql: String?)

        val sqliteVersion = runCatching {
            db.useReaderConnection { connection ->
                connection.usePrepared("SELECT sqlite_version()") {
                    it.step()
                    it.getText(0)
                }
            }
        }
        val indexRows = runCatching {
            db.useReaderConnection { connection ->
                connection.usePrepared("SELECT name, sql FROM sqlite_master WHERE type = 'index' ORDER BY name") {
                    val rows = mutableListOf<IndexRow>()
                    while (it.step()) {
                        rows += IndexRow(it.getText(0), if (it.isNull(1)) null else it.getText(1))
                    }
                    rows
                }
            }
        }

        // Observed locally (Windows): under RobolectricTestRunner, BundledSQLiteDriver loads via
        // org.robolectric.internal.AndroidSandbox$SdkSandboxClassLoader, whose classes report a
        // null CodeSource — this line is very likely to print "unknown (null)" on CI too, same as
        // it does here, which is itself evidence (Robolectric's sandbox classloader, not a build
        // difference) rather than a dead end; the classpath listing below is the real fallback.
        val driverCodeSource = runCatching {
            BundledSQLiteDriver::class.java.protectionDomain?.codeSource?.location
        }
        val driverClassLoader = runCatching { BundledSQLiteDriver::class.java.classLoader?.toString() }

        val sqliteJarsOnClasspath = runCatching {
            System.getProperty("java.class.path")
                .orEmpty()
                .split(System.getProperty("path.separator") ?: ";")
                .filter { it.contains("sqlite", ignoreCase = true) }
        }

        return buildString {
            appendLine("=== SQLITE DIAGNOSTICS ($label) ===")
            appendLine("sqlite_version() = ${sqliteVersion.getOrElse { "THREW: ${it.message}" }}")
            appendLine("os.name = ${System.getProperty("os.name")}, os.arch = ${System.getProperty("os.arch")}")
            appendLine(
                "native library loaded = " +
                    if (sqliteVersion.isSuccess) {
                        "yes (inferred: sqlite_version() answered)"
                    } else {
                        "UNKNOWN/NO (sqlite_version() threw — see above)"
                    },
            )
            appendLine(
                "BundledSQLiteDriver class loaded from = " +
                    (driverCodeSource.getOrNull()?.toString() ?: "unknown (${driverCodeSource.exceptionOrNull()})"),
            )
            appendLine(
                "BundledSQLiteDriver classloader = " +
                    (driverClassLoader.getOrNull() ?: "unknown (${driverClassLoader.exceptionOrNull()})"),
            )
            val jars = sqliteJarsOnClasspath.getOrElse { listOf("THREW: ${it.message}") }
            appendLine("sqlite-named entries on java.class.path (${jars.size}):")
            jars.forEach { appendLine("  - $it") }
            val rows = indexRows.getOrElse { emptyList() }
            if (indexRows.isFailure) {
                appendLine("sqlite_master index query THREW: ${indexRows.exceptionOrNull()?.message}")
            }
            appendLine("indexes in sqlite_master, type = 'index' (${rows.size}):")
            rows.forEach { appendLine("  - ${it.name} -> ${it.sql}") }
            appendLine("=== END SQLITE DIAGNOSTICS ($label) ===")
        }
    }

    /**
     * The decisive question this round adds: after the write that was supposed to be rejected,
     * what is actually in [table] for [transmissionId]? Runs [selectSql] (already scoped to
     * [transmissionId] by its caller — see the two call sites in [TranscriptVersioningTest] and
     * [WorkQueueTest] for the exact `SELECT ... , typeof(...)` text) against the *exact* [db]
     * instance the failing assertion just used, with [transmissionId] bound positionally as the
     * query's one `?` parameter, and prints every returned row rendered under [columnLabels] (one
     * label per selected column, in order) plus the row count — the row count alone already
     * distinguishes "two rows, index not enforcing" from "one row, this was a replace/update not
     * a second insert".
     */
    suspend fun dumpRows(
        db: OrtDatabase,
        label: String,
        table: String,
        selectSql: String,
        transmissionId: String,
        columnLabels: List<String>,
    ): String {
        val rowsResult = runCatching {
            db.useReaderConnection { connection ->
                connection.usePrepared(selectSql) { statement ->
                    statement.bindText(1, transmissionId)
                    val rows = mutableListOf<List<String?>>()
                    while (statement.step()) {
                        rows += columnLabels.indices.map { i ->
                            if (statement.isNull(i)) null else statement.getText(i)
                        }
                    }
                    rows
                }
            }
        }
        return buildString {
            appendLine("=== ROW DUMP ($label): $table WHERE transmissionId = '$transmissionId' ===")
            rowsResult.fold(
                onSuccess = { rows ->
                    appendLine("row count = ${rows.size}")
                    rows.forEachIndexed { index, row ->
                        val rendered = columnLabels.zip(row).joinToString(", ") { (col, value) -> "$col=$value" }
                        appendLine("  row[$index]: $rendered")
                    }
                },
                onFailure = { e -> appendLine("row dump THREW: ${e::class.qualifiedName}: ${e.message}") },
            )
            appendLine("=== END ROW DUMP ($label) ===")
        }
    }

    /**
     * Runs [insertSql] — the identical duplicate insert a failing test's DAO call above it already
     * attempted, restated as raw SQL with literal values so it goes straight through
     * [BundledSQLiteDriver], never through Room's generated DAO adapter — and reports whether the
     * driver itself throws a constraint violation. Wrapped in its own named `SAVEPOINT`, always
     * `ROLLBACK TO`'d afterward (success or failure alike), so this probe never leaves a row behind
     * for a later assertion in the same test to trip over.
     *
     * Distinguishes the two remaining explanations directly: if this throws but the DAO-level
     * insert above it did not, the difference is in Room's generated insert (conflict strategy,
     * bound value, or code path); if this *also* fails to throw, the constraint genuinely is not
     * enforced at the SQLite/driver layer on this platform, independent of Room entirely.
     */
    suspend fun rawDuplicateInsertProbe(db: OrtDatabase, label: String, insertSql: String): String {
        val outcome = runCatching {
            db.useWriterConnection { connection ->
                connection.usePrepared("SAVEPOINT sqlite_diag_probe") { it.step() }
                val insertOutcome = runCatching { connection.usePrepared(insertSql) { it.step() } }
                connection.usePrepared("ROLLBACK TO sqlite_diag_probe") { it.step() }
                connection.usePrepared("RELEASE sqlite_diag_probe") { it.step() }
                insertOutcome
            }
        }
        val verdict = outcome.fold(
            onSuccess = { insertOutcome ->
                insertOutcome.fold(
                    onSuccess = {
                        "SUCCEEDED with no exception -- the raw SQLite/driver layer did not reject the " +
                            "duplicate either (rolled back afterward; no lasting change)"
                    },
                    onFailure = { e ->
                        "THREW ${e::class.qualifiedName}: ${e.message} (rolled back afterward regardless)"
                    },
                )
            },
            onFailure = { e ->
                "PROBE HARNESS ITSELF THREW (not the insert): ${e::class.qualifiedName}: ${e.message}"
            },
        )
        return "=== RAW SQL DUPLICATE INSERT PROBE ($label) ===\n" +
            "$verdict\n" +
            "=== END RAW SQL DUPLICATE INSERT PROBE ($label) ===\n"
    }
}
