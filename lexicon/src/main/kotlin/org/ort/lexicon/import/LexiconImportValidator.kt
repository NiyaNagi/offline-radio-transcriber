package org.ort.lexicon.import

import org.ort.lexicon.ItuPrefixTable
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * FR-LEX-12 (register R-154, `Fail-Lexicon.dc.html`), FR-LEX-30, FR-AST-2: validates a user-supplied
 * lexicon import file before it can ever become the active lexicon. Every check the board lists runs
 * in a fixed order, and a file that fails validation is **rejected outright** — [validate] never
 * mutates anything; [LexiconImportInstaller.installValidated] is the only place that may swap the
 * active lexicon, and only when this function returns [LexiconImportResult.Accepted].
 *
 * **File format.** A lexicon import is one UTF-8 text file: a small `#`-prefixed manifest header
 * (`# version <v>`, `# records <n>`, `# sha256 <hex>` — the same `# version` convention
 * [org.ort.lexicon.readVersion] already uses for bundled assets, extended with the two fields a
 * *user-supplied* import additionally needs to be self-describing) followed by tab-separated data
 * rows, one callsign record per line, first column the callsign. The manifest's `sha256` is the
 * digest of the data section alone (the header can never be part of what it describes); its
 * `records` is the exact row count the data section must contain.
 *
 * **Check order and short-circuiting.** [org.ort.lexicon.import.CheckStatus.NOT_REACHED] appears
 * exactly where the board draws it: once the data section is known untrustworthy (checksum mismatch
 * or a record-count/shape problem), the two content checks that would have to *trust* that data —
 * the callsign-grammar sample and the duplicate-key scan — do not run at all, rather than running
 * against data already known to be corrupt and reporting a second, derivative failure.
 */
public object LexiconImportValidator {

    public const val CHECK_MANIFEST: String = "Manifest readable"
    public const val CHECK_CHECKSUM: String = "Checksum"
    public const val CHECK_RECORD_SHAPE: String = "Record count and shape"
    public const val CHECK_GRAMMAR_SAMPLE: String = "Callsign grammar sample"
    public const val CHECK_DUPLICATE_KEYS: String = "No duplicate keys"

    /** How many data rows [validate] samples for [CHECK_GRAMMAR_SAMPLE] — enough to be meaningful, cheap on 1M+ rows. */
    private const val GRAMMAR_SAMPLE_SIZE = 500

    private val VERSION_LINE = Regex("""^#\s*version\s+(\S+)""")
    private val RECORDS_LINE = Regex("""^#\s*records\s+(\d+)""")
    private val SHA256_LINE = Regex("""^#\s*sha256\s+([0-9a-fA-F]{64})""")
    private val CALLSIGN_SHAPE = Regex("^[A-Z0-9]{1,3}[0-9][A-Z]{1,4}$")

    /**
     * Runs every check against [file] in order. [currentActive] — read by
     * [LexiconImportInstaller.installValidated] from its [ActiveLexiconStore] before this call, or
     * passed directly by a caller/test that wants a [LexiconImportResult.Rejected] to report it — is
     * never written here; [validate] performs no I/O other than reading [file].
     */
    public fun validate(
        file: File,
        assetId: String,
        currentActive: ActiveLexiconRecord? = null,
        itu: ItuPrefixTable = ItuPrefixTable.bundled(),
    ): LexiconImportResult {
        val checks = mutableListOf<LexiconCheck>()

        val text = readFileOrNull(file)
        if (text == null) {
            checks += LexiconCheck(CHECK_MANIFEST, CheckStatus.FAILED, "could not read '${file.name}' — it may not exist or is not a plain file")
            checks += notReached(CHECK_CHECKSUM, CHECK_RECORD_SHAPE, CHECK_GRAMMAR_SAMPLE, CHECK_DUPLICATE_KEYS)
            return reject(file, checks, "The file could not be read.", currentActive)
        }

        val lines = text.lines()
        val headerLines = lines.takeWhile { it.isBlank() || it.trimStart().startsWith("#") }
        val version = headerLines.firstNotNullOfOrNull { VERSION_LINE.find(it.trim())?.groupValues?.get(1) }
        val declaredRecords = headerLines.firstNotNullOfOrNull { RECORDS_LINE.find(it.trim())?.groupValues?.get(1)?.toIntOrNull() }
        val declaredChecksum = headerLines.firstNotNullOfOrNull { SHA256_LINE.find(it.trim())?.groupValues?.get(1)?.lowercase(Locale.ROOT) }

        if (version == null || declaredRecords == null || declaredChecksum == null) {
            val missing = listOfNotNull(
                "`# version`".takeIf { version == null },
                "`# records`".takeIf { declaredRecords == null },
                "`# sha256`".takeIf { declaredChecksum == null },
            ).joinToString(", ")
            checks += LexiconCheck(CHECK_MANIFEST, CheckStatus.FAILED, "manifest header is missing or malformed: $missing not found")
            checks += notReached(CHECK_CHECKSUM, CHECK_RECORD_SHAPE, CHECK_GRAMMAR_SAMPLE, CHECK_DUPLICATE_KEYS)
            return reject(file, checks, "The file is not a recognisable lexicon import — its manifest header could not be read.", currentActive)
        }
        checks += LexiconCheck(
            CHECK_MANIFEST,
            CheckStatus.PASSED,
            "version $version · declares ${declaredRecords.grouped()} records · sha256 ${declaredChecksum.take(SHA_PREFIX_LEN)}…",
        )

        val dataLines = lines.drop(headerLines.size).filter { it.isNotBlank() }
        val dataSection = dataLines.joinToString("\n")
        val computedChecksum = sha256Hex(dataSection)
        val checksumOk = computedChecksum == declaredChecksum
        checks += if (checksumOk) {
            LexiconCheck(CHECK_CHECKSUM, CheckStatus.PASSED, "computed sha256 ${computedChecksum.take(SHA_PREFIX_LEN)}… · matches")
        } else {
            LexiconCheck(CHECK_CHECKSUM, CheckStatus.FAILED, "computed sha256 ${computedChecksum.take(SHA_PREFIX_LEN)}… · does not match")
        }

        val rows = dataLines.map { it.split('\t') }
        val expectedWidth = rows.firstOrNull()?.size ?: 0
        val shapeMismatches = rows.count { it.size != expectedWidth }
        val countMatches = rows.size == declaredRecords
        val recordShapeOk = countMatches && shapeMismatches == 0
        checks += if (recordShapeOk) {
            LexiconCheck(
                CHECK_RECORD_SHAPE,
                CheckStatus.PASSED,
                "${rows.size.grouped()} records, each with $expectedWidth fields, matches the manifest",
            )
        } else if (!countMatches) {
            LexiconCheck(
                CHECK_RECORD_SHAPE,
                CheckStatus.FAILED,
                "read ${rows.size.grouped()} · declared ${declaredRecords.grouped()} · file ends mid-record",
            )
        } else {
            LexiconCheck(
                CHECK_RECORD_SHAPE,
                CheckStatus.FAILED,
                "$shapeMismatches of ${rows.size.grouped()} records have a different number of fields than the first",
            )
        }

        val dataTrustworthy = checksumOk && recordShapeOk
        if (!dataTrustworthy) {
            checks += notReached(CHECK_GRAMMAR_SAMPLE, CHECK_DUPLICATE_KEYS)
            return reject(file, checks, rejectionReason(checksumOk, recordShapeOk), currentActive)
        }

        val sample = rows.take(GRAMMAR_SAMPLE_SIZE)
        val invalid = sample.count { row -> row.firstOrNull()?.let { !isStructurallyValidCallsign(it, itu) } ?: true }
        checks += if (invalid == 0) {
            LexiconCheck(CHECK_GRAMMAR_SAMPLE, CheckStatus.PASSED, "${sample.size.grouped()} sampled, all structurally valid against the ITU table")
        } else {
            LexiconCheck(
                CHECK_GRAMMAR_SAMPLE,
                CheckStatus.FAILED,
                "$invalid of ${sample.size.grouped()} sampled do not parse as a callsign against the ITU table",
            )
        }

        val callsigns = rows.mapNotNull { it.firstOrNull()?.trim()?.uppercase(Locale.ROOT) }
        val duplicates = callsigns.groupingBy { it }.eachCount().filterValues { it > 1 }
        checks += if (duplicates.isEmpty()) {
            LexiconCheck(CHECK_DUPLICATE_KEYS, CheckStatus.PASSED, "no duplicate callsigns among ${callsigns.size.grouped()} records")
        } else {
            LexiconCheck(
                CHECK_DUPLICATE_KEYS,
                CheckStatus.FAILED,
                "${duplicates.size} callsign(s) repeated, e.g. '${duplicates.keys.first()}' (${duplicates.values.first()}×)",
            )
        }

        return if (checks.all { it.status == CheckStatus.PASSED }) {
            LexiconImportResult.Accepted(file.name, checks, assetId, version, rows.size, computedChecksum)
        } else {
            reject(file, checks, "The callsign content itself did not pass every check; see the checks below.", currentActive)
        }
    }

    private fun readFileOrNull(file: File): String? = try {
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    } catch (e: java.io.IOException) {
        null
    }

    /** [names], each marked [CheckStatus.NOT_REACHED] — the checks an earlier failure made unreachable. */
    private fun notReached(vararg names: String): List<LexiconCheck> =
        names.map { LexiconCheck(it, CheckStatus.NOT_REACHED, "not reached") }

    private fun reject(
        file: File,
        checks: List<LexiconCheck>,
        reason: String,
        currentActive: ActiveLexiconRecord?,
    ): LexiconImportResult.Rejected = LexiconImportResult.Rejected(file.name, checks, reason, currentActive)

    private fun rejectionReason(checksumOk: Boolean, recordShapeOk: Boolean): String = when {
        !checksumOk && !recordShapeOk ->
            "Checksum mismatched and the record count did not match the manifest — a partial or corrupted " +
                "download, most likely. Nothing was replaced."
        !checksumOk ->
            "Checksum mismatched — the file is not byte-identical to what its manifest describes. Nothing was replaced."
        else ->
            "The record count did not match the manifest. Nothing was replaced."
    }

    private fun isStructurallyValidCallsign(raw: String, itu: ItuPrefixTable): Boolean {
        val callsign = raw.trim().uppercase(Locale.ROOT).substringBefore('/')
        if (!CALLSIGN_SHAPE.matches(callsign)) return false
        return itu.allocationFor(callsign) != null
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun Int.grouped(): String = String.format(Locale.ROOT, "%,d", this)

    private const val SHA_PREFIX_LEN = 4
}
