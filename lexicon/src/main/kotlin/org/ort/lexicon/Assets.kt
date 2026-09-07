package org.ort.lexicon

/**
 * Shared plumbing for the bundled lexicon assets (technical design §9.6). Every asset is a
 * small TSV file on the classpath with a `# version <v>` comment line; the loaders here keep
 * that convention in one place so each asset can be versioned independently (FR-LEX-2).
 */
internal object Assets

/** Read a bundled resource as UTF-8 text, or fail loudly — a missing bundled asset is a bug. */
internal fun readResource(path: String): String {
    val stream = Assets::class.java.getResourceAsStream(path)
        ?: error("bundled lexicon asset not found on the classpath: $path")
    return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
}

/** The value of the `# version <v>` line, or `"0"` if the asset does not declare one. */
internal fun readVersion(tsv: String): String = tsv.lineSequence()
    .map { it.trim() }
    .firstNotNullOfOrNull { line ->
        Regex("""^#\s*version\s+(\S+)""").find(line)?.groupValues?.get(1)
    } ?: "0"

/** The data rows of a TSV asset: `#` comments and blank lines dropped, each row split on tabs. */
internal fun tsvRows(tsv: String): List<List<String>> = tsv.lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .map { line -> line.split('\t').map { it.trim() } }
    .toList()
