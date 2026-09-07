package org.ort.asrapi

import org.ort.core.TransmissionId

/** Which pass produced a transcript candidate (mirrors `:data`'s `TranscriptPass`, technical design §8.3). */
public enum class TranscriptPass { A, B, REPROCESS }

/**
 * The domain-level shape of one transcript version, produced here from a [PassBOutcome] and
 * handed to `:pipeline` to persist (the DB-level append-only/partial-unique-index enforcement
 * is `:data`'s, built in P5 — this type is what a Pass B run yields *before* that write).
 *
 * [TranscriptSeries] enforces the append-only / exactly-one-current invariant (§8.3, FR-REP-3)
 * at this domain layer too, independent of Room: superseding never deletes, and each call to
 * [TranscriptSeries.supersede] flips the flag exactly once.
 */
public data class TranscriptCandidate(
    val transmissionId: TransmissionId,
    val pass: TranscriptPass,
    val version: Int,
    val isCurrent: Boolean,
    val text: String,
    val modelRef: org.ort.core.AssetRef,
)

/**
 * An append-only, in-memory sequence of transcript versions for one transmission, used to prove
 * the "exactly one current, nothing deleted" invariant at the domain level before `:data`'s
 * migration-level enforcement ever runs (§8.3).
 */
public class TranscriptSeries(private val transmissionId: TransmissionId) {
    private val versions = mutableListOf<TranscriptCandidate>()

    public val all: List<TranscriptCandidate> get() = versions.toList()

    public val current: TranscriptCandidate? get() = versions.singleOrNull { it.isCurrent }

    /** Writes a new current version, flipping the previous current's flag off — never removing it. */
    public fun supersede(pass: TranscriptPass, text: String, modelRef: org.ort.core.AssetRef): TranscriptCandidate {
        val previousIndex = versions.indexOfFirst { it.isCurrent }
        if (previousIndex >= 0) versions[previousIndex] = versions[previousIndex].copy(isCurrent = false)
        val next = TranscriptCandidate(
            transmissionId = transmissionId,
            pass = pass,
            version = versions.size + 1,
            isCurrent = true,
            text = text,
            modelRef = modelRef,
        )
        versions += next
        check(versions.count { it.isCurrent } == 1) { "exactly one current version must hold after supersede" }
        return next
    }
}
