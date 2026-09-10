package org.ort.data

import androidx.room.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * Evidence-gathering only (CI cross-platform diagnostic task; see `data/build.gradle.kts`'s own
 * comment and CHANGELOG.md for the two rounds this backs). `TranscriptVersioningTest` and
 * `WorkQueueTest` each pass on Windows and fail on Linux CI (runs 34439250807, 34440826468)
 * asserting a duplicate write violates a partial unique index (`idx_transcript_one_current`,
 * `idx_wq_active`) — on Linux the insert silently succeeds instead. A prior round's
 * dependency-substitution fix in `data/build.gradle.kts` did not resolve it: the very next CI run
 * failed identically. This file changes no production behaviour and weakens no assertion — it only
 * reports, for the *exact* [OrtDatabase] instance each failing test's own `@Before` builds
 * (see [TranscriptVersioningTest.openDatabase] and [WorkQueueTest.openDatabase]):
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
}
