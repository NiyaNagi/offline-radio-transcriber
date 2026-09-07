package org.ort.asrapi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.AssetRef
import org.ort.core.Outcome
import org.ort.core.Tier
import org.ort.onnx.ModelDescriptor
import org.ort.onnx.ModelFamily
import org.ort.onnx.ModelSizeClass
import org.ort.onnx.ResidencyClass
import org.ort.testing.Requirement

class ModelRegistryTest {

    private fun descriptor(id: String) = ModelDescriptor(
        assetRef = AssetRef(id, "1"),
        family = ModelFamily.ASR_ENCODER_DECODER,
        sizeClass = ModelSizeClass.SMALL,
        quantization = "int8",
        providerBinaries = setOf("cpu"),
        isFineTuned = false,
        licence = "Apache-2.0",
        memoryFootprintBytes = 100,
        residencyClass = ResidencyClass.HOT,
    )

    @Test
    @Requirement("F13")
    fun `F13 an invalid preferred model falls back to the next-lower candidate and surfaces the fallback`() {
        val preferred = descriptor("distil-medium")
        val fallback = descriptor("distil-small")
        val registry = ModelRegistry(
            modelSets = mapOf(Tier.T2 to ModelSet(Tier.T2, listOf(preferred, fallback))),
            validate = { d ->
                if (d.assetRef.assetId == "distil-medium") Outcome.Err("corrupt file") else Outcome.Ok(Unit)
            },
        )

        val resolution = (registry.resolve(Tier.T2) as Outcome.Ok).value

        assertEquals(fallback.assetRef, resolution.descriptor.assetRef)
        assertTrue(resolution.fallback != null, "the fallback must be surfaced, not silent (F13)")
        assertEquals(preferred.assetRef, resolution.fallback!!.requested.assetRef)
        assertEquals(fallback.assetRef, resolution.fallback!!.activated.assetRef)
    }

    @Test
    @Requirement("F13")
    fun `a valid preferred model activates with no fallback reported`() {
        val preferred = descriptor("distil-medium")
        val registry = ModelRegistry(
            modelSets = mapOf(Tier.T2 to ModelSet(Tier.T2, listOf(preferred))),
            validate = { Outcome.Ok(Unit) },
        )

        val resolution = (registry.resolve(Tier.T2) as Outcome.Ok).value

        assertEquals(preferred.assetRef, resolution.descriptor.assetRef)
        assertNull(resolution.fallback)
    }

    @Test
    @Requirement("F13")
    fun `every candidate failing validation is an Err, not a crash`() {
        val a = descriptor("a")
        val b = descriptor("b")
        val registry = ModelRegistry(
            modelSets = mapOf(Tier.T0 to ModelSet(Tier.T0, listOf(a, b))),
            validate = { Outcome.Err("no model available at this tier") },
        )

        val result = registry.resolve(Tier.T0)

        assertTrue(result is Outcome.Err)
    }
}
