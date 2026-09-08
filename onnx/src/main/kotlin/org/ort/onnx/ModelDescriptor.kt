package org.ort.onnx

import org.ort.core.AssetRef

/**
 * Everything a model-bearing pass needs to know about one installed graph, independent of the
 * runtime that executes it (technical design §8.4). Lives in `:onnx` rather than `:asr-api`
 * because [org.ort.onnx.ModelResidencyManager] needs [memoryFootprintBytes] for every resident
 * graph, not only ASR ones (Silero VAD is a [ModelDescriptor] too).
 */
public data class ModelDescriptor(
    val assetRef: AssetRef,
    val family: ModelFamily,
    val sizeClass: ModelSizeClass,
    val quantization: String,
    val providerBinaries: Set<String>,
    val isFineTuned: Boolean,
    val fineTuneId: String? = null,
    val trainingDataDescription: String? = null,
    val licence: String,
    /** Declared, not measured — summed against the tier budget at tier entry (§4.3). */
    val memoryFootprintBytes: Long,
    val residencyClass: ResidencyClass,
) {
    init {
        require(memoryFootprintBytes > 0) { "declared memory footprint must be positive" }
        require(providerBinaries.isNotEmpty()) { "a model must declare at least one provider binary" }
        // FR-ACC-2 / NFR-5b: a CPU path must exist for every model the product ships, so the app
        // is fully functional with no accelerator present. Type-level, not a review convention
        // (constitution VII) — a descriptor that only ever declares an accelerator binary cannot
        // be constructed.
        require("cpu" in providerBinaries) { "a model must declare a \"cpu\" provider binary (FR-ACC-2, NFR-5b)" }
        if (isFineTuned) {
            require(!fineTuneId.isNullOrBlank()) { "a fine-tuned model must declare its fine-tune id" }
        }
    }
}

public enum class ModelFamily { VAD, ASR_TRANSDUCER, ASR_ENCODER_DECODER, KWS_SPOTTER, SPEAKER_EMBEDDER, ENHANCER, LLM }

public enum class ModelSizeClass { TINY, SMALL, MEDIUM, LARGE }
