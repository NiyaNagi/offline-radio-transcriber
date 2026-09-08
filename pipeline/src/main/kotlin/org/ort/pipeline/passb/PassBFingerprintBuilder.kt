package org.ort.pipeline.passb

import org.ort.asrapi.DecodeOptions
import org.ort.core.AssetRef
import java.security.MessageDigest

/**
 * audit F-013: [PassBFactory] used to stamp every [org.ort.core.PassFingerprint.configHash] with
 * the literal `"v0-smoke"`, so no two Pass B configurations were ever distinguishable and no
 * threshold or lexicon change was ever detectable as a reprocessing candidate (constitution III:
 * "every pass is a pure function of (audio, lexicon snapshot, model set, config) and records the
 * fingerprint of what produced it"; FR-REP-1). This builds that hash for real, from every input
 * fixed at [PassBFactory.create] time that can change what a Pass B run produces:
 *
 * - [confirmThreshold] / [separationThreshold] — [CallsignResolver]'s own thresholds.
 * - [decodeOptions] — passed to [org.ort.asrapi.AsrEngine.transcribe] on every run of this pass.
 * - the bundled lexicon snapshot's three independently-versioned tables ([variantsVersion],
 *   [ituVersion], [confusionVersion]). Unlike Pass D's separate `lexiconVersion` fingerprint
 *   field, this matters here because Pass B resolves a callsign from the lexicon itself
 *   ([PassBResolutionChain]) rather than deferring resolution to a later pass — a lexicon bump
 *   really does change what this specific Pass B run would produce.
 *
 * Deterministic and independent of any hash-set iteration order: fields are joined in a fixed,
 * documented order into a canonical `key=value;...` string, then SHA-256 hex — the same technique
 * [org.ort.core.ResolvedConfig.configHash] already uses for the same purpose in `:core`. There is
 * no public SHA-256 helper exposed from `:core` to reuse (its `sha256Hex` is private), so this
 * repeats the four-line implementation rather than exporting a new cross-module API for it.
 *
 * Segmentation is deliberately excluded: `:core`'s own `ConfigRelevance` table scopes `B_OFFLINE`
 * to `asr`/`pipeline` config prefixes, never `segment` — the segmenter never accepts a tier and
 * its parameters do not vary Pass B's decode (constitution III).
 */
public object PassBFingerprintBuilder {

    public fun configHash(
        confirmThreshold: Float,
        separationThreshold: Float,
        decodeOptions: DecodeOptions,
        variantsVersion: AssetRef,
        ituVersion: AssetRef,
        confusionVersion: AssetRef,
    ): String {
        val canonical = buildString {
            append("confirmThreshold=").append(confirmThreshold)
            append(";separationThreshold=").append(separationThreshold)
            append(";decode.language=").append(decodeOptions.language ?: "-")
            append(";decode.hotwords=").append(decodeOptions.hotwords.phrases.sorted().joinToString(","))
            append(";decode.beamSize=").append(decodeOptions.beamSize)
            append(";decode.nBest=").append(decodeOptions.nBest)
            append(";lexicon.variants=").append(variantsVersion.canonical)
            append(";lexicon.itu=").append(ituVersion.canonical)
            append(";lexicon.confusion=").append(confusionVersion.canonical)
        }
        return sha256Hex(canonical)
    }

    private fun sha256Hex(input: String): String = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
