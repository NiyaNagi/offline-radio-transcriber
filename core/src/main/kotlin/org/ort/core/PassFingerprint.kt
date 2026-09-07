package org.ort.core

/**
 * Everything that can change a pass's output, captured at the moment the pass ran and stored
 * as a canonical string on every row the pass produced (technical design §3.4).
 *
 * This one mechanism implements FR-REP-2, FR-TIER-5, FR-ACC-5, FR-LEX-18 and the `STALE`
 * state. There is no separate "is stale" flag to keep in sync: a row is a reprocessing
 * candidate exactly when its stored fingerprint [materiallyDiffersFrom] the fingerprint the
 * same pass would produce under the current environment.
 */
public data class PassFingerprint(
    val passId: PassId,
    /** Bumped by hand when a pass's algorithm changes. */
    val codeVersion: Int,
    val modelIds: List<AssetRef>,
    val lexiconVersion: AssetRef?,
    val calibrationVersion: AssetRef?,
    /** Stable hash over the pass-relevant config subset (see [ResolvedConfig.configHash]). */
    val configHash: String,
    val provider: String,
    val tier: Tier,
) {
    /**
     * The canonical string persisted on a derived row. Order is fixed and every field is
     * present so two fingerprints are equal iff their canonical forms are equal.
     */
    public fun canonical(): String = buildString {
        append("pass=").append(passId.name)
        append(";code=").append(codeVersion)
        append(";models=").append(modelIds.sorted().joinToString(",") { it.canonical })
        append(";lex=").append(lexiconVersion?.canonical ?: "-")
        append(";cal=").append(calibrationVersion?.canonical ?: "-")
        append(";cfg=").append(configHash)
        append(";prov=").append(provider)
        append(";tier=").append(tier.name)
    }

    /**
     * True if the difference between `this` (stored) and [current] involves a field that
     * matters *for this pass* (technical design §3.4: a lexicon bump makes Pass D stale but
     * not Pass B). Comparing fingerprints for different passes is a programming error.
     */
    public fun materiallyDiffersFrom(current: PassFingerprint): Boolean = changedMaterialFields(current).isNotEmpty()

    /** The material fields that differ — used to explain *why* a record is a candidate (constitution I). */
    public fun changedMaterialFields(current: PassFingerprint): Set<FingerprintField> {
        require(passId == current.passId) {
            "cannot compare a $passId fingerprint with a ${current.passId} one"
        }
        val material = Materiality.fieldsFor(passId)
        return FingerprintField.entries
            .filter { it in material && !it.equalIn(this, current) }
            .toSet()
    }

    public companion object {
        /** Convenience: is [stored] a reprocessing candidate given [current]? */
        public fun isReprocessCandidate(stored: PassFingerprint, current: PassFingerprint): Boolean =
            stored.materiallyDiffersFrom(current)
    }
}

/** The fields of a [PassFingerprint], so materiality can be expressed as a set. */
public enum class FingerprintField {
    CODE_VERSION,
    MODEL_IDS,
    LEXICON_VERSION,
    CALIBRATION_VERSION,
    CONFIG_HASH,
    PROVIDER,
    TIER,
    ;

    internal fun equalIn(a: PassFingerprint, b: PassFingerprint): Boolean = when (this) {
        CODE_VERSION -> a.codeVersion == b.codeVersion
        MODEL_IDS -> a.modelIds.sorted() == b.modelIds.sorted()
        LEXICON_VERSION -> a.lexiconVersion == b.lexiconVersion
        CALIBRATION_VERSION -> a.calibrationVersion == b.calibrationVersion
        CONFIG_HASH -> a.configHash == b.configHash
        PROVIDER -> a.provider == b.provider
        TIER -> a.tier == b.tier
    }
}

/**
 * Which fingerprint fields count as "materially better" per pass (FR-REP-6). The cheap,
 * high-value reprocess — re-running Pass D after a lexicon update without re-running Pass B —
 * is exactly this table saying `LEXICON_VERSION` matters to `D_RESOLVE` and not to `B_OFFLINE`.
 */
public object Materiality {

    private val common = setOf(FingerprintField.CODE_VERSION, FingerprintField.CONFIG_HASH, FingerprintField.TIER)

    private val table: Map<PassId, Set<FingerprintField>> = mapOf(
        PassId.ENH to common + setOf(FingerprintField.MODEL_IDS, FingerprintField.PROVIDER),
        PassId.A_STREAM to common + setOf(FingerprintField.MODEL_IDS, FingerprintField.PROVIDER),
        PassId.B_OFFLINE to common + setOf(FingerprintField.MODEL_IDS, FingerprintField.PROVIDER),
        PassId.FUSE to common + setOf(FingerprintField.MODEL_IDS),
        PassId.C_SPOT to common + setOf(FingerprintField.MODEL_IDS, FingerprintField.PROVIDER),
        PassId.D_RESOLVE to common + setOf(
            FingerprintField.MODEL_IDS,
            FingerprintField.LEXICON_VERSION,
            FingerprintField.CALIBRATION_VERSION,
        ),
        PassId.E_IDENTITY to common + setOf(FingerprintField.MODEL_IDS),
        PassId.F_DIGEST to common + setOf(FingerprintField.MODEL_IDS),
    )

    public fun fieldsFor(passId: PassId): Set<FingerprintField> =
        table[passId] ?: error("no materiality entry for $passId")
}
