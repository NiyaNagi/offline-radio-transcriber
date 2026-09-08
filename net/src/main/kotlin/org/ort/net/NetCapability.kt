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
}
