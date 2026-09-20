package org.ort.net

/**
 * Every `:net` entry point requires one of these (technical design §16.3). This is the
 * type-level half of "the capture and processing paths make no network call": `:capture-*` and
 * `:pipeline`'s pass-execution code cannot construct either variant, because neither module may
 * depend on `:net` at all (`ModuleGraph`, rule 5 — enforced by `dependencyRules`, not by review).
 *
 * [UserInitiated] is the only kind this module mints today — a model download or side-load,
 * triggered from a UI action in `:app`. [ContributionGrant] is scaffolded for the corpus
 * contribution channel (FR-CON-2) and is deliberately not exercised by anything in this prompt.
 * [AnalyticsUpload] is P28's third kind (D42, D48): analytics is a declared outbound channel like
 * the other two, and its own upload/purge calls
 * ([org.ort.net.analytics.real.RealAnalyticsUploadClient]) are gated by the same discipline —
 * `:app`'s composition root passes one only when it has already established capture is not
 * active (FR-ANL-7), the same way [org.ort.core.analytics.AnalyticsUploadRequest.captureActive]
 * carries that fact across the module boundary as plain data.
 *
 * Note on what this does and does not prove: nothing in the Kotlin type system stops `:app`
 * itself from calling `NetCapability.UserInitiated` outside of a real user gesture — "mintable
 * only from a UI action" is an architectural intent that a later `:app` wiring session is
 * responsible for honouring at the call site, the same way `ModelActivation.sideloadedUnverified`
 * documents a guarantee its own type cannot fully enforce either.
 */
public sealed interface NetCapability {
    public object UserInitiated : NetCapability

    public data class ContributionGrant(val activatedAtEpochMillis: Long) : NetCapability

    /** P28 (D42, D48) — the analytics channel's own capability token. [mintedAtEpochMillis] is
     * carried for the same reason [ContributionGrant.activatedAtEpochMillis] is: a record of when
     * `:app` decided to mint one, not a value this module itself interprets. */
    public data class AnalyticsUpload(val mintedAtEpochMillis: Long) : NetCapability
}
