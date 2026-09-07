package org.ort.core

/**
 * A reference to a versioned asset — a model, a lexicon snapshot, a calibration file, a rig
 * descriptor. Assets share one lifecycle (FR-AST-1) and every score-bearing record cites the
 * exact versions that produced it (§3.4).
 */
public data class AssetRef(val assetId: String, val version: String) : Comparable<AssetRef> {

    init {
        require(assetId.isNotBlank()) { "assetId must not be blank" }
        require(version.isNotBlank()) { "version must not be blank" }
    }

    /** Canonical `assetId@version` form used inside a fingerprint string. */
    public val canonical: String get() = "$assetId@$version"

    override fun compareTo(other: AssetRef): Int = compareValuesBy(this, other, { it.assetId }, { it.version })

    override fun toString(): String = canonical

    public companion object {
        public fun parse(canonical: String): AssetRef {
            val at = canonical.lastIndexOf('@')
            require(at > 0 && at < canonical.length - 1) { "expected assetId@version, got '$canonical'" }
            return AssetRef(canonical.substring(0, at), canonical.substring(at + 1))
        }
    }
}
