package org.ort.pipeline.capture

import org.ort.asrsherpa.real.RealSileroVad
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
    public fun provide(filesDir: File): VadProvisionResult {
        val file = SileroVadLocator.modelFile(filesDir)
        if (!file.isFile) {
            return VadProvisionResult.Unavailable(
                "no Silero VAD model installed at ${file.path} -- falling back to the RMS-energy " +
                    "stand-in (EnergyVadModel), not the specified Silero VAD; see asr-sherpa/README.md " +
                    "for the fetch URL, this build does not fetch it automatically",
            )
        }
        return try {
            val real = RealSileroVad(file.path)
            VadProvisionResult.Available(VadModel { frame -> real.speechProbability(frame) }, real::close)
        } catch (t: Throwable) {
            VadProvisionResult.Unavailable("Silero VAD model present but failed to load: ${t.message}")
        }
    }
}
