package org.ort.core

import java.security.MessageDigest

/** A partial set of configuration values — one tier of the merge. */
public class ConfigLayer(values: Map<String, String>) {

    public val values: Map<String, String> = values.toSortedMap()

    public operator fun get(key: String): String? = values[key]

    public companion object {
        public val EMPTY: ConfigLayer = ConfigLayer(emptyMap())

        public fun of(vararg pairs: Pair<String, Any>): ConfigLayer =
            ConfigLayer(pairs.associate { (k, v) -> k to v.toString() })
    }
}

/**
 * An immutable, fully-merged configuration snapshot (technical design §3.5). Produced by
 * merging three layers, each overriding the one before:
 *
 * ```
 * built-in defaults  <-  active capture profile (FR-CFG-1)  <-  user overrides
 * ```
 *
 * Every threshold named in the functional spec has a typed accessor with a documented default
 * (FR-CFG-2); unknown keys are still carried so the [configHash] covers them. A profile switch
 * produces a new snapshot atomically (F21) — this type has no mutators.
 */
public class ResolvedConfig private constructor(public val values: Map<String, String>) {
    // ---- capture -------------------------------------------------------------------------
    /** Pre-roll prepended from the ring buffer, >= FR-CAP-4's 1.0 s floor. */
    public val capturePreRollMs: Int get() = int("capture.preRollMs", DEF_PRE_ROLL_MS)

    // ---- segmentation (tier-invariant; deliberately absent from every pass subset) -------
    public val segmentMinSpeechMs: Int get() = int("segment.minSpeechMs", DEF_MIN_SPEECH_MS)
    public val segmentMinSilenceMs: Int get() = int("segment.minSilenceMs", DEF_MIN_SILENCE_MS)
    public val segmentMaxSegmentMs: Int get() = int("segment.maxSegmentMs", DEF_MAX_SEGMENT_MS)
    public val segmentPostRollMs: Int get() = int("segment.postRollMs", DEF_POST_ROLL_MS)

    // ---- ASR / hallucination controls (FR-ASR-5) ----------------------------------------
    public val asrNoSpeechProbMax: Double get() = double("asr.noSpeechProbMax", DEF_NO_SPEECH_PROB_MAX)
    public val asrCompressionRatioMax: Double get() = double("asr.compressionRatioMax", DEF_COMPRESSION_RATIO_MAX)

    // ---- lexicon -----------------------------------------------------------------------
    public val lexiconSeparationThreshold: Double
        get() = double("lexicon.separationThreshold", DEF_SEPARATION_THRESHOLD)

    // ---- pipeline / runtime -----------------------------------------------------------
    public val passRetryLimit: Int get() = int("pipeline.passRetryLimit", DEF_PASS_RETRY_LIMIT)
    public val passMinTimeoutMs: Int get() = int("pipeline.passMinTimeoutMs", DEF_PASS_MIN_TIMEOUT_MS)

    /** Raw typed reads for keys without a dedicated accessor yet. */
    public fun int(key: String, default: Int): Int = values[key]?.toIntOrNull() ?: default
    public fun double(key: String, default: Double): Double = values[key]?.toDoubleOrNull() ?: default
    public fun boolean(key: String, default: Boolean): Boolean = values[key]?.toBooleanStrictOrNull() ?: default
    public fun string(key: String, default: String): String = values[key] ?: default

    /**
     * A stable hash over the config subset a given pass depends on (technical design §3.4).
     * Deterministic: keys sorted, `key=value` lines, SHA-256, lowercase hex. A change to a key
     * outside the pass's subset — segmentation, say — does not move this hash.
     */
    public fun configHash(passId: PassId): String {
        val prefixes = ConfigRelevance.prefixesFor(passId)
        val subset = values.filterKeys { key -> prefixes.any { key == it || key.startsWith("$it.") } }
        return sha256Hex(subset.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    /** A hash over the entire merged snapshot — used where a pass-scoped subset is not meaningful. */
    public val configHash: String
        get() = sha256Hex(values.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" })

    override fun equals(other: Any?): Boolean = other is ResolvedConfig && other.values == values

    override fun hashCode(): Int = values.hashCode()

    public companion object {
        internal const val DEF_PRE_ROLL_MS = 1200
        internal const val DEF_MIN_SPEECH_MS = 250
        internal const val DEF_MIN_SILENCE_MS = 600
        internal const val DEF_MAX_SEGMENT_MS = 60_000
        internal const val DEF_POST_ROLL_MS = 400
        internal const val DEF_NO_SPEECH_PROB_MAX = 0.60
        internal const val DEF_COMPRESSION_RATIO_MAX = 2.4
        internal const val DEF_SEPARATION_THRESHOLD = 0.15
        internal const val DEF_PASS_RETRY_LIMIT = 5
        internal const val DEF_PASS_MIN_TIMEOUT_MS = 15_000

        /** Merge the three layers, later winning, into an immutable snapshot. */
        public fun resolve(
            defaults: ConfigLayer,
            profile: ConfigLayer = ConfigLayer.EMPTY,
            overrides: ConfigLayer = ConfigLayer.EMPTY,
        ): ResolvedConfig {
            val merged = LinkedHashMap<String, String>()
            merged.putAll(defaults.values)
            merged.putAll(profile.values)
            merged.putAll(overrides.values)
            return ResolvedConfig(merged.toSortedMap())
        }

        /** The built-in defaults layer — the documented value of every typed field above. */
        public fun builtInDefaults(): ConfigLayer = ConfigLayer.of(
            "capture.preRollMs" to DEF_PRE_ROLL_MS,
            "segment.minSpeechMs" to DEF_MIN_SPEECH_MS,
            "segment.minSilenceMs" to DEF_MIN_SILENCE_MS,
            "segment.maxSegmentMs" to DEF_MAX_SEGMENT_MS,
            "segment.postRollMs" to DEF_POST_ROLL_MS,
            "asr.noSpeechProbMax" to DEF_NO_SPEECH_PROB_MAX,
            "asr.compressionRatioMax" to DEF_COMPRESSION_RATIO_MAX,
            "lexicon.separationThreshold" to DEF_SEPARATION_THRESHOLD,
            "pipeline.passRetryLimit" to DEF_PASS_RETRY_LIMIT,
            "pipeline.passMinTimeoutMs" to DEF_PASS_MIN_TIMEOUT_MS,
        )

        private fun sha256Hex(input: String): String = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/** Which configuration areas each pass's [PassFingerprint.configHash] must cover (§3.4). */
public object ConfigRelevance {

    private val table: Map<PassId, Set<String>> = mapOf(
        PassId.ENH to setOf("asr", "pipeline"),
        PassId.A_STREAM to setOf("asr", "pipeline"),
        PassId.B_OFFLINE to setOf("asr", "pipeline"),
        PassId.FUSE to setOf("asr", "lexicon", "pipeline"),
        PassId.C_SPOT to setOf("asr", "lexicon", "pipeline"),
        PassId.D_RESOLVE to setOf("lexicon", "pipeline"),
        PassId.E_IDENTITY to setOf("identity", "pipeline"),
        PassId.F_DIGEST to setOf("digest", "pipeline"),
    )

    public fun prefixesFor(passId: PassId): Set<String> =
        table[passId] ?: error("no config-relevance entry for $passId")
}
