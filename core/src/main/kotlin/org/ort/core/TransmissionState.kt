package org.ort.core

/**
 * The transmission lifecycle (functional spec §7.14 FR-RUN-7). A transmission occupies exactly
 * one of these at any time. `STALE` from the spec diagram is **not** a stored state — it is
 * derived from [PassFingerprint] staleness (technical design §7.2), which removes the class of
 * bug where a flag and its cause disagree. A "reprocess" of a `COMPLETE`/`REJECTED`/`FAILED`
 * transmission simply moves it back to `PROCESSING`.
 *
 * `processingState` in the data model takes its values only from here (functional spec §8).
 */
public enum class TransmissionState {
    /** Audio on disk, queued, no passes run. Entered on VAD close. */
    CAPTURED,

    /** A pass is leased and running. */
    PROCESSING,

    /** All required passes finished; a current transcript exists. */
    COMPLETE,

    /** A pass ran and correctly declined the segment (FR-RUN-9). A result, not an accident. */
    REJECTED,

    /** A pass errored or timed out (FR-RUN-9, FR-RUN-10a). Retryable to a bounded count. */
    FAILED,
}

/**
 * The legal-transition table, asserted exhaustively in a unit test (technical design §7.2).
 * Any `(from, to)` not in [legalTransitions] is a bug if it ever occurs at runtime.
 */
public object TransmissionLifecycle {

    public val legalTransitions: Set<Pair<TransmissionState, TransmissionState>> = setOf(
        // the happy path
        TransmissionState.CAPTURED to TransmissionState.PROCESSING,
        TransmissionState.PROCESSING to TransmissionState.COMPLETE,
        TransmissionState.PROCESSING to TransmissionState.REJECTED,
        TransmissionState.PROCESSING to TransmissionState.FAILED,
        // crash / kill recovery — any PROCESSING transmission is returned to CAPTURED and re-queued (FR-RUN-8)
        TransmissionState.PROCESSING to TransmissionState.CAPTURED,
        // bounded retry of a failed pass (FR-RUN-10)
        TransmissionState.FAILED to TransmissionState.PROCESSING,
        // reprocess requested / a higher tier is available — the derived-STALE path (FR-REP-9)
        TransmissionState.COMPLETE to TransmissionState.PROCESSING,
        TransmissionState.REJECTED to TransmissionState.PROCESSING,
    )

    public val initialState: TransmissionState = TransmissionState.CAPTURED

    public val terminalUntilReprocess: Set<TransmissionState> =
        setOf(TransmissionState.COMPLETE, TransmissionState.REJECTED, TransmissionState.FAILED)

    public fun isLegal(from: TransmissionState, to: TransmissionState): Boolean = (from to to) in legalTransitions

    public fun legalTargetsFrom(from: TransmissionState): Set<TransmissionState> =
        legalTransitions.filter { it.first == from }.map { it.second }.toSet()

    /** Assert a transition, or throw [IllegalTransitionException] naming it. */
    public fun require(from: TransmissionState, to: TransmissionState) {
        if (!isLegal(from, to)) throw IllegalTransitionException(from, to)
    }
}

public class IllegalTransitionException(public val from: TransmissionState, public val to: TransmissionState) :
    IllegalStateException("illegal transmission transition: $from -> $to (see functional spec §7.14)")
