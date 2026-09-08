package org.ort.asrsherpa.real

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * A genuine sherpa-onnx Silero VAD (build-plan P12): the same JNI binding / JVM `sherpa-onnx-jvm`
 * artifact [RealSherpaDecoder] uses (see `asr-sherpa/README.md`), replacing the RMS-energy
 * stand-in `RealCaptureService.EnergyVadModel` explicitly names as "not Silero".
 *
 * Exposes [speechProbability] structurally rather than implementing `org.ort.segment.VadModel`
 * directly: per ModuleGraph, `:asr-sherpa` may depend on `:core`, `:onnx` and `:asr-api` only,
 * never `:segment`. `:pipeline` — which depends on both — adapts this to `VadModel` (a Kotlin
 * `fun interface`) with a one-line lambda, exactly the pattern `SherpaAsrEngine`/`RealSherpaDecoder`
 * already establishes for ASR.
 *
 * Requires a real Silero VAD `.onnx` file on disk at [modelPath] — never bundled or fabricated
 * (constitution I). See `asr-sherpa/README.md` for where to fetch one from and
 * `RealSileroVadRealModelTest` for the gated real-model proof.
 */
public class RealSileroVad(modelPath: String, sampleRate: Int = SAMPLE_RATE_HZ, numThreads: Int = 1) : AutoCloseable {

    private val vad: Vad = Vad(
        VadModelConfig.builder()
            .setSileroVadModelConfig(SileroVadModelConfig.builder().setModel(modelPath).build())
            .setSampleRate(sampleRate)
            .setNumThreads(numThreads)
            .setProvider("cpu")
            .build(),
    )

    /** One frame's speech probability in `[0,1]` — the exact shape `org.ort.segment.VadModel` needs. */
    public fun speechProbability(frame: FloatArray): Float = vad.compute(frame)

    /** Releases the native VAD. Callers own this lifecycle. */
    override fun close() {
        vad.release()
    }

    public companion object {
        /** The pipeline's one fixed sample rate (`SampleClock.DEFAULT_SAMPLE_RATE`, FR-RUN-16). */
        public const val SAMPLE_RATE_HZ: Int = 16_000
    }
}
