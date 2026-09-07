package org.ort.lexicon

import org.ort.core.AssetRef

/** Raised when a spoken form is not in the variant table — an unknown form is rejected, never guessed. */
public class UnknownSpokenFormException(public val form: String) :
    IllegalArgumentException("no phonetic unit for spoken form \"$form\"")

/**
 * Maps every spoken form of a phonetic unit — NATO, letter-name, and legacy (WWII/ARRL)
 * pronunciations, plus digit words and separator words — to its [PhoneticUnit] (technical
 * design §9.1). The table is **content, not localization** (FR-A11Y-6): a versioned, bundled
 * asset ([version]) extensible without a translation pass.
 *
 * Lookup normalises the query to lowercase with every non-alphanumeric character removed, so
 * `"x-ray"`, `"x ray"` and `"xray"` are one key. An unknown form resolves to null (or throws
 * from [require]); it is never mapped to a nearest guess.
 */
public class VariantTable internal constructor(
    private val byForm: Map<String, PhoneticUnit>,
    public val version: AssetRef,
) {
    /** Every normalised spoken form the table knows. */
    public val forms: Set<String> get() = byForm.keys

    /** The unit for [spoken], or null if the form is unknown. */
    public fun resolve(spoken: String): PhoneticUnit? = byForm[normalise(spoken)]

    /** The unit for [spoken], or [UnknownSpokenFormException] if the form is unknown. */
    public fun require(spoken: String): PhoneticUnit = resolve(spoken) ?: throw UnknownSpokenFormException(spoken)

    /** The known spoken forms that map to [unit]. */
    public fun formsFor(unit: PhoneticUnit): Set<String> = byForm.filterValues { it == unit }.keys

    public companion object {
        private const val RESOURCE = "/org/ort/lexicon/phonetic-variants.tsv"

        internal fun normalise(spoken: String): String = spoken.lowercase().filter { it.isLetterOrDigit() }

        /** Load the bundled table (FR-LEX-29 — always present, not an optional download). */
        public fun bundled(): VariantTable = parse(
            readResource(RESOURCE),
            AssetRef("lexicon-phonetic-variants", readVersion(readResource(RESOURCE))),
        )

        /** Parse a table from TSV text: `<spoken form>\t<PhoneticUnit>`, `#` lines ignored. */
        public fun parse(tsv: String, version: AssetRef): VariantTable {
            val map = LinkedHashMap<String, PhoneticUnit>()
            tsvRows(tsv).forEach { cols ->
                require(cols.size >= 2) { "variant row needs 2 columns: ${cols.joinToString("|")}" }
                val key = normalise(cols[0])
                val unit = PhoneticUnit.valueOf(cols[1].trim())
                val prev = map.put(key, unit)
                require(prev == null || prev == unit) {
                    "spoken form \"$key\" maps to both $prev and $unit"
                }
            }
            require(map.isNotEmpty()) { "empty variant table" }
            return VariantTable(map, version)
        }
    }
}
