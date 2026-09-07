package org.ort.lexicon

import org.ort.core.AssetRef

/** One ITU international call-sign prefix allocation: which block belongs to whom. */
public data class ItuAllocation(val prefix: String, val entity: String, val iso: String)

/**
 * The ITU prefix allocation table as a trie (technical design §9.2). It answers two questions
 * and only two:
 *
 *  - [allocationFor] — does this callsign begin with an allocated prefix, and whose is it?
 *    This is the **only hard filter** in resolution (FR-LEX-8). A structurally valid callsign
 *    with an allocated prefix is a candidate even with zero database hits (AC-10); a callsign
 *    whose prefix is unallocated is not a candidate and can never reach `CONFIRMED` (AC-11).
 *  - [isLivePrefixPath] — could this partial string still grow into an allocated prefix? The
 *    grammar beam search uses it to prune dead branches early.
 *
 * The table is a bundled, independently versioned asset ([version], FR-LEX-2, FR-LEX-29).
 */
public class ItuPrefixTable internal constructor(entries: List<ItuAllocation>, public val version: AssetRef) {
    private class Node {
        val children = HashMap<Char, Node>()
        var allocation: ItuAllocation? = null
    }

    private val root = Node()
    public val allocations: List<ItuAllocation> = entries.sortedBy { it.prefix }

    init {
        for (a in allocations) {
            var node = root
            for (c in a.prefix) node = node.children.getOrPut(c) { Node() }
            // keep the first (shortest wins on a tie is irrelevant — prefixes are distinct)
            if (node.allocation == null) node.allocation = a
        }
    }

    /** The allocation for the longest table prefix that [callsign] starts with, or null. */
    public fun allocationFor(callsign: String): ItuAllocation? {
        var node = root
        var best: ItuAllocation? = null
        for (c in callsign.uppercase()) {
            node = node.children[c] ?: break
            if (node.allocation != null) best = node.allocation
        }
        return best
    }

    /** True if [prefix] is exactly an allocated prefix. */
    public fun isAllocatedPrefix(prefix: String): Boolean = allocations.any { it.prefix == prefix.uppercase() }

    /** True if some allocated prefix begins with [partial] (or equals it) — a live trie node. */
    public fun isLivePrefixPath(partial: String): Boolean {
        var node = root
        for (c in partial.uppercase()) node = node.children[c] ?: return false
        return true
    }

    public companion object {
        private const val RESOURCE = "/org/ort/lexicon/itu-prefixes.tsv"

        /** Load the bundled ITU table. */
        public fun bundled(): ItuPrefixTable {
            val tsv = readResource(RESOURCE)
            val entries = tsvRows(tsv).map { cols ->
                require(cols.size >= 3) { "ITU row needs 3 columns: ${cols.joinToString("|")}" }
                ItuAllocation(cols[0].uppercase(), cols[1], cols[2].uppercase())
            }
            require(entries.isNotEmpty()) { "empty ITU prefix table" }
            return ItuPrefixTable(entries, AssetRef("lexicon-itu-prefixes", readVersion(tsv)))
        }
    }
}
