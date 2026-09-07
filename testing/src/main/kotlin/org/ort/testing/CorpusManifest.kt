package org.ort.testing

import java.io.File

/** The three folds (constitution VI). `EVAL` is sealed until M11. */
public enum class Fold { TRAIN, DEV, EVAL }

public data class CorpusEntry(
    val id: String,
    val fold: Fold,
    val source: String,
    val path: String,
    val synthetic: Boolean,
)

/**
 * A read-only view of the corpus manifest for JVM tests and the `:eval` harness. The
 * authoritative manifest, validator and acquisition live in the Python `corpus/` subproject
 * (build-plan P2); this loader enforces the one rule that must hold everywhere:
 *
 * **The `eval` fold is not readable without an explicit opt-in** (FR-TST-7 -> AC-100).
 * Sealing by discipline alone does not survive a debugging session at 2 a.m.
 *
 * Scaffold format is tab-separated: `id \t fold \t source \t path \t synthetic(true|false)`.
 * Lines starting with `#` and blank lines are ignored.
 */
public class CorpusManifest private constructor(private val entries: List<CorpusEntry>) {

    public fun entries(fold: Fold, allowEval: Boolean = false): List<CorpusEntry> {
        if (fold == Fold.EVAL) {
            check(allowEval) {
                "the eval fold is sealed (constitution VI / FR-TST-7). Pass allowEval = true only with a stated reason."
            }
        }
        return entries.filter { it.fold == fold }
    }

    public fun all(includeEval: Boolean = false): List<CorpusEntry> =
        if (includeEval) entries else entries.filter { it.fold != Fold.EVAL }

    public companion object {
        public fun parse(text: String): CorpusManifest {
            val parsed = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapIndexed { i, line ->
                    val cols = line.split('\t')
                    require(cols.size >= 4) {
                        "manifest line ${i + 1}: expected >= 4 tab-separated columns, got '$line'"
                    }
                    val fold = Fold.valueOf(cols[1].trim().uppercase())
                    val synthetic = cols.getOrNull(4)?.trim()?.toBooleanStrictOrNull() ?: false
                    require(!(synthetic && fold == Fold.EVAL)) {
                        "manifest line ${i + 1}: synthetic data must never enter the eval fold (FR-TST-9)"
                    }
                    CorpusEntry(cols[0].trim(), fold, cols[2].trim(), cols[3].trim(), synthetic)
                }
                .toList()
            val dupes = parsed.groupBy { it.id }.filterValues { it.size > 1 }.keys
            require(dupes.isEmpty()) { "duplicate manifest ids: $dupes" }
            return CorpusManifest(parsed)
        }

        public fun load(file: File): CorpusManifest = parse(file.readText())
    }
}
