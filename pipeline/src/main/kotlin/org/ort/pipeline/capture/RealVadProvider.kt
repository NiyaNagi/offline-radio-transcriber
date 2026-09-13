package org.ort.pipeline.capture

import org.ort.asrsherpa.real.RealSileroVad
import org.ort.core.assets.ModelFileVerifier
import org.ort.core.assets.ModelVerification
import org.ort.pipeline.diagnostics.DiagnosticsLog
import org.ort.pipeline.diagnostics.ModelAssetId
import org.ort.segment.VadModel
import java.io.File

/**
 * Where the real Silero VAD `.onnx` model is expected once fetched — never committed
 * (`asr-sherpa/README.md`). A fixed, documented location, mirroring `AsrModelLocator`'s pattern
 * for the ASR model exactly.
 */
public object SileroVadLocator {
    public fun modelFile(filesDir: File): File = File(filesDir, "models/silero-vad/silero_vad.onnx")
}

/** What [RealVadProvider.provide] found. [Available.close] releases the native VAD when done. */
public sealed interface VadProvisionResult {
    public data class Available(val vad: VadModel, val close: () -> Unit) : VadProvisionResult
    public data class Unavailable(val reason: String) : VadProvisionResult
}

/**
 * Resolves a real Silero [VadModel] from app-private storage, or reports honestly why none is
 * available (build-plan P12: "if you cannot [resolve a usable Silero build], say so plainly and
 * leave the stand-in clearly labelled" — never quietly ship an energy threshold as if it were the
 * specified VAD). A real binding *is* resolvable here (`asr-sherpa/README.md`); what is missing in
 * this environment is the model file itself, exactly the same shape of gap
 * [org.ort.pipeline.passb.RealAsrEngineProvider] reports for the ASR model.
 */
public object RealVadProvider {

    /**
     * Register R-1052 (halt): the seam standing in for [RealSileroVad]'s constructor — the exact
     * call whose native `Vad(...)` load threw an uncaught C++ exception, past `std::terminate`,
     * when handed a corrupt file (`std::terminate` cannot be caught by [provide]'s own
     * `catch (t: Throwable)` below, which only ever saw ordinary JVM/JNI-bridge exceptions such as
     * `UnsatisfiedLinkError`). Never called until [ModelFileVerifier.verify] has already said the
     * file is trustworthy — see [provide]. Returns a probability function plus its `close`
     * callback rather than a bare [RealSileroVad], so a test can fake the entire native boundary
     * without depending on that concrete type or its own native library at all.
     */
    internal var nativeLoader: (path: String) -> Pair<(FloatArray) -> Float, () -> Unit> = { path ->
        val real = RealSileroVad(path)
        ({ frame: FloatArray -> real.speechProbability(frame) }) to real::close
    }

    public fun provide(filesDir: File): VadProvisionResult {
        val file = SileroVadLocator.modelFile(filesDir)
        if (!file.isFile) {
            return VadProvisionResult.Unavailable(
                "no Silero VAD model installed at ${file.path} -- falling back to the RMS-energy " +
                    "stand-in (EnergyVadModel), not the specified Silero VAD; see asr-sherpa/README.md " +
                    "for the fetch URL, this build does not fetch it automatically",
            )
        }
        // R-1052: never hand a path to native code without checking it first — a Kotlin
        // catch (t: Throwable) around the constructor call below cannot stop a native abort.
        when (val verification = ModelFileVerifier.verify(file)) {
            is ModelVerification.Failed -> {
                DiagnosticsLog.logModelVerificationFailed(ModelAssetId.VAD, verification.kind)
                return VadProvisionResult.Unavailable(
                    "Silero VAD model at ${file.path} failed verification and was not loaded: ${verification.reason}",
                )
            }
            ModelVerification.Verified -> Unit
        }
        return try {
            val (probability, close) = nativeLoader(file.path)
            VadProvisionResult.Available(VadModel { frame -> probability(frame) }, close)
        } catch (t: Throwable) {
            VadProvisionResult.Unavailable("Silero VAD model present but failed to load: ${t.message}")
        }
    }
}
