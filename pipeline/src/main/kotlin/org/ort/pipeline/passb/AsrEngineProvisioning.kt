package org.ort.pipeline.passb

import org.ort.asrapi.AsrEngine
import org.ort.asrapi.AsrResult
import org.ort.asrapi.DecodeOptions
import org.ort.asrsherpa.SherpaAsrEngine
import org.ort.asrsherpa.real.RealSherpaDecoder
import org.ort.core.AssetRef
import org.ort.onnx.ModelDescriptor
import org.ort.onnx.ModelFamily
import org.ort.onnx.ModelSizeClass
import org.ort.onnx.OnnxSession
import org.ort.onnx.ResidencyClass
import java.io.File

/**
 * Where the real, on-device Whisper `tiny.en` model is expected once fetched — never committed
 * (`asr-sherpa/README.md`). A fixed, documented location, so the (out-of-scope, see CHANGELOG)
 * fetch step and [RealAsrEngineProvider]'s load step agree on where to look without either side
 * inventing a path.
 */
public object AsrModelLocator {
    public const val MODEL_ID: String = "whisper-tiny-en-int8"

    public fun modelsDir(filesDir: File): File = File(filesDir, "models/$MODEL_ID")

    /**
     * The three files a `sherpa-onnx-whisper-tiny.en` export needs (`asr-sherpa/README.md`'s
     * archive layout). Null if any is missing — a partially-fetched model is treated exactly like
     * no model at all, never guessed at (constitution I).
     */
    public fun locate(filesDir: File): AsrModelFiles? {
        val dir = modelsDir(filesDir)
        val encoder = File(dir, "tiny.en-encoder.int8.onnx")
        val decoder = File(dir, "tiny.en-decoder.int8.onnx")
        val tokens = File(dir, "tiny.en-tokens.txt")
        return if (encoder.isFile && decoder.isFile && tokens.isFile) {
            AsrModelFiles(encoder, decoder, tokens)
        } else {
            null
        }
    }
}

public data class AsrModelFiles(val encoder: File, val decoder: File, val tokens: File)

/**
 * What [RealAsrEngineProvider.provide] found. A caller MUST handle [Unavailable] explicitly and
 * say so (constitution I: uncertainty is content) — never silently substitute a different engine
 * for the one that was supposed to run.
 *
 * [Available.provider] is the execution provider [engine] actually runs on (audit F-013,
 * constitution VI: "provider is part of provenance") — read from the real [SherpaOnnxSession]
 * descriptor this class builds, never a literal guessed downstream of here.
 */
public sealed interface AsrEngineAvailability {
    public data class Available(val engine: AsrEngine, val modelRef: AssetRef, val provider: String) :
        AsrEngineAvailability
    public data class Unavailable(val reason: String) : AsrEngineAvailability
}

/**
 * Resolves a real [AsrEngine] from app-private storage, or reports honestly why none is available
 * — build-plan P12's explicit requirement: "do NOT let it silently look like transcription is
 * working when no model is present." Fetching the model *to* [filesDir] is a user-initiated asset
 * download that belongs behind `:net` (constitution V — the capture/processing paths this class
 * runs in make no network call, ever); this class only ever *reads* app-private storage that some
 * other, out-of-this-session's-scope action already populated (see CHANGELOG "left open").
 */
public class RealAsrEngineProvider(private val filesDir: File) {
    public fun provide(): AsrEngineAvailability {
        val files = AsrModelLocator.locate(filesDir)
            ?: return AsrEngineAvailability.Unavailable(
                "no ASR model installed at ${AsrModelLocator.modelsDir(filesDir).path} (expected " +
                    "tiny.en-encoder.int8.onnx, tiny.en-decoder.int8.onnx, tiny.en-tokens.txt -- see " +
                    "asr-sherpa/README.md for the fetch URL; this build does not fetch it automatically)",
            )
        val modelRef = AssetRef(AsrModelLocator.MODEL_ID, "1")
        return try {
            val decoder = RealSherpaDecoder(files.encoder.path, files.decoder.path, files.tokens.path)
            val session = SherpaOnnxSession(modelRef, decoder)
            val provider = session.descriptor.providerBinaries.sorted().joinToString(",")
            AsrEngineAvailability.Available(SherpaAsrEngine(session, decoder), modelRef, provider)
        } catch (t: Throwable) {
            AsrEngineAvailability.Unavailable("ASR model present but failed to load: ${t.message}")
        }
    }
}

/**
 * Wraps [RealSherpaDecoder]'s lifecycle behind [OnnxSession] so [SherpaAsrEngine] — which only
 * needs a session for its [OnnxSession.descriptor] and [OnnxSession.isClosed] bookkeeping, per its
 * own doc comment — can be constructed for a real decoder. [run] is never called: [SherpaAsrEngine]
 * decodes via the [org.ort.asrsherpa.SherpaDecoder] directly.
 */
internal class SherpaOnnxSession(assetRef: AssetRef, private val decoder: RealSherpaDecoder) : OnnxSession {
    override val descriptor: ModelDescriptor = ModelDescriptor(
        assetRef = assetRef,
        family = ModelFamily.ASR_ENCODER_DECODER,
        sizeClass = ModelSizeClass.TINY,
        quantization = "int8",
        providerBinaries = setOf("cpu"),
        isFineTuned = false,
        licence = "MIT",
        memoryFootprintBytes = MODEL_FOOTPRINT_BYTES,
        residencyClass = ResidencyClass.HOT,
    )

    override var isClosed: Boolean = false
        private set

    override fun run(input: FloatArray): FloatArray =
        error("SherpaOnnxSession.run is never called -- SherpaAsrEngine decodes via SherpaDecoder directly")

    override fun close() {
        if (!isClosed) {
            decoder.close()
            isClosed = true
        }
    }

    private companion object {
        /** Whisper tiny.en int8, encoder+decoder (`asr-sherpa/README.md`: "≈101MB total"), rounded up. */
        const val MODEL_FOOTPRINT_BYTES: Long = 150_000_000L
    }
}

/**
 * The honest failure [RealAsrEngineProvider]'s doc comment promises: a clearly-labelled [AsrEngine]
 * that never silently returns text, wired in exactly when [RealAsrEngineProvider] reports
 * [AsrEngineAvailability.Unavailable]. [org.ort.asrapi.RejectionPipeline] catches the thrown
 * [IllegalStateException] and reports [org.ort.asrapi.PassBOutcome.Failed] with [reason] intact —
 * an honest, retryable failure, never a fabricated transcript.
 */
public class UnavailableAsrEngine(private val reason: String) : AsrEngine {
    override suspend fun transcribe(audio: FloatArray, opts: DecodeOptions): AsrResult =
        error("ASR unavailable: $reason")
}
