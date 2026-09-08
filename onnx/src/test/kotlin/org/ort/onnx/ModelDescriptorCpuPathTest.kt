package org.ort.onnx

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.ort.core.AssetRef
import org.ort.testing.Requirement

/**
 * FR-ACC-2 / NFR-5b — audit F-027.
 *
 * This establishes only the narrow, type-checkable slice of these requirements that is actually
 * built: **every [ModelDescriptor] the product ships must declare a CPU provider binary**, so a
 * CPU path is guaranteed to exist rather than merely intended. It does NOT establish the rest of
 * FR-ACC-2 ("a stable execution-provider interface") or NFR-5b's broader "no required dependency
 * on a specific SoC/NPU/vendor SDK" claim — the `ExecutionProvider` abstraction technical design
 * §3.3 describes (of which `CpuProvider` would be one implementation) is not yet built; that is
 * M11/NPU-acceleration scope (FR-ACC-1), not something a JVM unit test here can create or prove.
 * Robolectric/JVM only — no device, no real accelerator involved.
 */
class ModelDescriptorCpuPathTest {

    private fun descriptor(providerBinaries: Set<String>) = ModelDescriptor(
        assetRef = AssetRef("some-model", "1"),
        family = ModelFamily.ASR_ENCODER_DECODER,
        sizeClass = ModelSizeClass.SMALL,
        quantization = "int8",
        providerBinaries = providerBinaries,
        isFineTuned = false,
        licence = "Apache-2.0",
        memoryFootprintBytes = 100L,
        residencyClass = ResidencyClass.HOT,
    )

    @Test
    @Requirement("FR-ACC-2", "NFR-5b")
    fun `FR_ACC_2_NFR_5b a model descriptor without a cpu provider binary is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            descriptor(providerBinaries = setOf("qnn-htp"))
        }
    }

    @Test
    @Requirement("FR-ACC-2", "NFR-5b")
    fun `FR_ACC_2_NFR_5b a model descriptor declaring cpu alongside an accelerator binary is accepted`() {
        descriptor(providerBinaries = setOf("cpu", "qnn-htp"))
    }

    @Test
    @Requirement("FR-ACC-2", "NFR-5b")
    fun `FR_ACC_2_NFR_5b a cpu-only descriptor is accepted, the no-accelerator fallback path`() {
        descriptor(providerBinaries = setOf("cpu"))
    }
}
