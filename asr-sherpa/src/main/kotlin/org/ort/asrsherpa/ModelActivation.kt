package org.ort.asrsherpa

import org.ort.core.Outcome
import org.ort.onnx.ModelDescriptor
import org.ort.onnx.OnnxSessionFactory

/** What activating a (possibly side-loaded) model produced (technical design §8.4, FR-ASR-8, FR-AST-2). */
public sealed interface ActivationResult {
    /**
     * [sideloadedUnverified] is true for any user-supplied model: passing the signature check
     * and the probe run makes it *safe to execute*, not *trusted* — the design is explicit that
     * "this does not make executing a third-party graph safe; it makes it deliberate" (§8.4).
     * The user is told their accuracy figures are their own.
     */
    public data class Activated(val descriptor: ModelDescriptor, val sideloadedUnverified: Boolean) : ActivationResult

    /** The previous model, if any, remains active — this activation attempt changed nothing. */
    public data class Refused(val reason: String, val stillActive: ModelDescriptor?) : ActivationResult
}

/**
 * The side-loaded model install path (FR-ASR-8): a user-supplied ONNX file is untrusted input.
 * It is validated — well-formed, input/output signature matches [descriptor] — *before*
 * activation, then probe-run over [fixtureClip] before it is trusted with real audio. A crash or
 * signature mismatch at any step refuses activation and leaves [currentlyActive] in place
 * (FR-AST-2): the previous model is never torn down speculatively.
 */
public class ModelActivation(private val sessionFactory: OnnxSessionFactory, private val fixtureClip: FloatArray) {
    public fun activateSideloaded(
        descriptor: ModelDescriptor,
        verifySignature: () -> Outcome<Unit>,
        currentlyActive: ModelDescriptor?,
    ): ActivationResult {
        val signature = verifySignature()
        if (signature is Outcome.Err) {
            return ActivationResult.Refused("signature check failed: ${signature.reason}", currentlyActive)
        }

        val loaded = sessionFactory.load(descriptor)
        val session = loaded.getOrNull()
            ?: return ActivationResult.Refused(
                "failed to load model: ${(loaded as Outcome.Err).reason}",
                currentlyActive,
            )

        val probe = sessionFactory.probeRun(session, fixtureClip)
        session.close() // this activation call only verifies; the caller re-acquires via ModelResidencyManager
        if (probe is Outcome.Err) {
            return ActivationResult.Refused("probe run over the fixture clip failed: ${probe.reason}", currentlyActive)
        }

        return ActivationResult.Activated(descriptor, sideloadedUnverified = true)
    }
}
