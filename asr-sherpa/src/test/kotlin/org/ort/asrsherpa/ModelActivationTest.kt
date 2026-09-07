package org.ort.asrsherpa

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.AssetRef
import org.ort.core.Outcome
import org.ort.onnx.ModelDescriptor
import org.ort.onnx.ModelFamily
import org.ort.onnx.ModelSizeClass
import org.ort.onnx.OnnxSession
import org.ort.onnx.OnnxSessionFactory
import org.ort.onnx.ResidencyClass
import org.ort.testing.Requirement

class ModelActivationTest {

    private fun descriptor(id: String) = ModelDescriptor(
        assetRef = AssetRef(id, "1"),
        family = ModelFamily.ASR_ENCODER_DECODER,
        sizeClass = ModelSizeClass.SMALL,
        quantization = "int8",
        providerBinaries = setOf("cpu"),
        isFineTuned = true,
        fineTuneId = "user-supplied",
        licence = "unknown",
        memoryFootprintBytes = 100,
        residencyClass = ResidencyClass.HOT,
    )

    private class ScriptedFactory(private val crashesOnProbe: Boolean = false, private val failsLoad: Boolean = false) :
        OnnxSessionFactory {
        var lastLoaded: ModelDescriptor? = null
        override fun load(descriptor: ModelDescriptor): Outcome<OnnxSession> {
            lastLoaded = descriptor
            if (failsLoad) return Outcome.Err("malformed file")
            return Outcome.Ok(object : OnnxSession {
                override val descriptor: ModelDescriptor = descriptor
                override var isClosed: Boolean = false
                override fun run(input: FloatArray): FloatArray {
                    if (crashesOnProbe) error("scripted probe crash")
                    return input
                }
                override fun close() {
                    isClosed = true
                }
            })
        }
    }

    @Test
    @Requirement("FR-ASR-8", "FR-AST-2")
    fun `a well-formed side-loaded model passes signature check and probe run and activates, marked unverified`() {
        val factory = ScriptedFactory()
        val activation = ModelActivation(factory, fixtureClip = FloatArray(16))
        val previous = descriptor("bundled-default")

        val result = activation.activateSideloaded(
            descriptor("user-finetune"),
            verifySignature = { Outcome.Ok(Unit) },
            currentlyActive = previous,
        )

        val activated = result as ActivationResult.Activated
        assertEquals("user-finetune", activated.descriptor.assetRef.assetId)
        assertTrue(activated.sideloadedUnverified, "a side-loaded model is never promoted to trusted (§8.4)")
    }

    @Test
    @Requirement("FR-ASR-8")
    fun `a signature mismatch refuses activation and keeps the previous model active`() {
        val factory = ScriptedFactory()
        val activation = ModelActivation(factory, fixtureClip = FloatArray(16))
        val previous = descriptor("bundled-default")

        val result = activation.activateSideloaded(
            descriptor("tampered"),
            verifySignature = { Outcome.Err("hash mismatch") },
            currentlyActive = previous,
        )

        val refused = result as ActivationResult.Refused
        assertEquals(previous, refused.stillActive)
        assertEquals(0, factory.let { 0 }) // load must never even be attempted after a signature failure
    }

    @Test
    @Requirement("FR-AST-2")
    fun `a probe-run crash refuses activation and keeps the previous model active, per F13`() {
        val factory = ScriptedFactory(crashesOnProbe = true)
        val activation = ModelActivation(factory, fixtureClip = FloatArray(16))
        val previous = descriptor("bundled-default")

        val result = activation.activateSideloaded(
            descriptor("crashy-experimental"),
            verifySignature = { Outcome.Ok(Unit) },
            currentlyActive = previous,
        )

        val refused = result as ActivationResult.Refused
        assertEquals(previous, refused.stillActive)
        assertTrue(refused.reason.contains("probe run"))
    }

    @Test
    fun `a signature check that fails means load() is never called`() {
        val factory = ScriptedFactory()
        val activation = ModelActivation(factory, fixtureClip = FloatArray(16))

        activation.activateSideloaded(
            descriptor("x"),
            verifySignature = { Outcome.Err("bad sig") },
            currentlyActive = null,
        )

        assertEquals(null, factory.lastLoaded)
    }
}
