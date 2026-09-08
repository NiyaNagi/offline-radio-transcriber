package org.ort.lexicon.import

import java.io.File
import java.security.MessageDigest

/** Test-only: the same digest [LexiconImportValidator] computes, so fixtures can declare a correct one. */
internal fun sha256HexOf(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** A handful of real, ITU-allocated callsigns (matches [org.ort.lexicon.ItuPrefixTableTest]'s own fixtures). */
internal val VALID_CALLSIGNS = listOf("K7ABC", "W7NPC", "VE7CAB", "ZL4AA", "KJ7ABC")

internal fun dataRow(callsign: String, name: String = "Test Operator", state: String = "WA"): String =
    "$callsign\t$name\t$state"

/**
 * Writes a lexicon import file into [dir]. Every knob defaults to "correct" so a test only overrides
 * the one thing it means to corrupt (mirrors the rest of this module's fixture style).
 */
internal fun writeLexiconFile(
    dir: File,
    name: String = "lexicon-import.tsv",
    rows: List<String> = VALID_CALLSIGNS.map { dataRow(it) },
    version: String = "2026.09",
    declaredRecords: Int? = null,
    declaredChecksum: String? = null,
    truncateDataAfterLines: Int? = null,
): File {
    val fullData = rows.joinToString("\n")
    val checksum = declaredChecksum ?: sha256HexOf(fullData)
    val records = declaredRecords ?: rows.size
    val writtenRows = if (truncateDataAfterLines != null) rows.take(truncateDataAfterLines) else rows
    val header = "# version $version\n# records $records\n# sha256 $checksum\n"
    val file = File(dir, name)
    file.writeText(header + writtenRows.joinToString("\n") + "\n")
    return file
}

/**
 * An in-memory [ActiveLexiconStore] — no [org.ort.lexicon.import.LexiconImportInstaller] test needs
 * a real database.
 */
internal class FakeActiveLexiconStore(initial: ActiveLexiconRecord? = null) : ActiveLexiconStore {
    var activated: MutableList<ActiveLexiconRecord> = mutableListOf()
        private set
    private var currentRecord: ActiveLexiconRecord? = initial

    override fun current(): ActiveLexiconRecord? = currentRecord

    override fun activate(record: ActiveLexiconRecord) {
        activated += record
        currentRecord = record
    }
}
