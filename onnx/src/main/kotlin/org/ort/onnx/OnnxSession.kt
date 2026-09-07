package org.ort.onnx

import org.ort.core.Outcome

/**
 * One loaded graph, opaque to callers above `:onnx` (technical design §8.1: `:asr-sherpa`
 * consumes this, never a raw native handle). Deliberately narrow — everything this project's
 * model-bearing interfaces need is `run` plus lifecycle.
 */
public interface OnnxSession : AutoCloseable {
    public val descriptor: ModelDescriptor

    /** True once [close] has been called or the session has been torn down by eviction. */
    public val isClosed: Boolean

    /**
     * Runs the graph over one input, opaque at this layer — `:asr-sherpa` knows the shape.
     * Implementations MUST NOT catch and swallow a native crash; the caller (the residency
     * manager, or [OnnxSessionFactory.probeRun]) is responsible for treating a thrown
     * exception as "this model must not be trusted".
     */
    public fun run(input: FloatArray): FloatArray
}

/**
 * Loads and verifies [ModelDescriptor]s into [OnnxSession]s. The real implementation wraps
 * sherpa-onnx; `:onnx` itself only defines the contract so `:segment` and the residency manager
 * can be built and tested without a native runtime (mirrors the `VadModel` narrow-interface
 * pattern `:segment` already uses).
 */
public interface OnnxSessionFactory {

    /**
     * Loads [descriptor]. Fails as [Outcome.Err] — never throws — on a malformed file, a
     * signature mismatch, or an input/output shape that does not match [descriptor] (FR-ASR-8).
     */
    public fun load(descriptor: ModelDescriptor): Outcome<OnnxSession>

    /**
     * Runs [session] once over a bundled fixture clip before the model is trusted with real
     * audio (FR-AST-2). A crash here — the exception is caught internally and reported as
     * [Outcome.Err] — means the model must not be activated; the caller keeps the previous
     * model active.
     */
    public fun probeRun(session: OnnxSession, fixture: FloatArray): Outcome<Unit> = Outcome.catching(
        "probe run over fixture clip failed for ${session.descriptor.assetRef}",
    ) {
        session.run(fixture)
        Unit
    }
}
