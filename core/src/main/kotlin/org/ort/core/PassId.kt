package org.ort.core

/**
 * The processing stages, in pipeline order (technical design §3.1). Every stored derived row
 * names the pass that produced it, and staleness is computed per pass (§3.4).
 */
public enum class PassId {
    /** Enhancement (GTCRN denoise), M11, optional. */
    ENH,

    /** Streaming ASR — the live hypothesis (Pass A), M8. */
    A_STREAM,

    /** Offline ASR — the accurate transcript (Pass B), M3. */
    B_OFFLINE,

    /** Ensemble fusion of the A and B hypotheses (Pass FUSE), M11. */
    FUSE,

    /** Acoustic unit spotting against the lexicon (Pass C), M4 — may be deleted at the fork. */
    C_SPOT,

    /** Callsign resolution over the phonetic lattice (Pass D), M4/M1. */
    D_RESOLVE,

    /** Speaker embedding, clustering and threading (Pass E), M6. */
    E_IDENTITY,

    /** Digest generation over a time window (Pass F), M9. */
    F_DIGEST,
}

/** Device processing tier — a property of *processing*, never of the record (D8). */
public enum class Tier { T0, T1, T2, T3 }
