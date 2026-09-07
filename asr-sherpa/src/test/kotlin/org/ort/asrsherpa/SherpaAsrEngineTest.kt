package org.ort.asrsherpa

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.ort.asrapi.DecodeOptions
import org.ort.asrsherpa.fake.FakeSherpaDecoder
import org.ort.core.AssetRef
import org.ort.onnx.ModelDescriptor
import org.ort.onnx.ModelFamily
import org.ort.onnx.ModelSizeClass
import org.ort.onnx.OnnxSession
import org.ort.onnx.ResidencyClass
import org.ort.testing.Requirement

class SherpaAsrEngineTest {

    private fun descriptor() = ModelDescriptor(
        assetRef = AssetRef("distil-small-en", "1"),
        family = ModelFamily.ASR_ENCODER_DECODER,
        sizeClass = ModelSizeClass.SMALL,
        quantization = "int8",
        providerBinaries = setOf("cpu"),
        isFineTuned = true,
        fineTuneId = "atc-ft-1",
        trainingDataDescription = "55 hand-transcribed clips",
        licence = "MIT",
        memoryFootprintBytes = 200_000_000,
        residencyClass = ResidencyClass.HOT,
    )

    private class StubSession(override val descriptor: ModelDescriptor) : OnnxSession {
        override var isClosed: Boolean = false
        override fun run(input: FloatArray): FloatArray = input
        override fun close() {
            isClosed = true
        }
    }

    @Test
    @Requirement("technical-design-8.1", "FR-ASR-1")
    fun `transcribe stamps the decoded hypothesis with the active session's model ref`() = runTest {
        val session = StubSession(descriptor())
        val engine = SherpaAsrEngine(
            session,
            FakeSherpaDecoder(
                FakeSherpaDecoder.Behaviour.Returns(
                    FakeSherpaDecoder.defaultHypothesis("kilo seven able baker"),
                ),
            ),
        )

        val result = engine.transcribe(FloatArray(16), DecodeOptions())

        assertEquals("kilo seven able baker", result.text)
        assertEquals(descriptor().assetRef, result.modelRef)
    }

    @Test
    fun `transcribe on a closed session fails loudly rather than returning a stale result`() = runTest {
        val session = StubSession(descriptor()).apply { close() }
        val engine = SherpaAsrEngine(session, FakeSherpaDecoder())

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { engine.transcribe(FloatArray(16), DecodeOptions()) }
        }
    }

    @Test
    fun `a decoder crash propagates rather than being silently swallowed into a fake success`() = runTest {
        val session = StubSession(descriptor())
        val crashes = FakeSherpaDecoder.Behaviour.Crashes(RuntimeException("native decode failure"))
        val engine = SherpaAsrEngine(session, FakeSherpaDecoder(crashes))

        assertThrows(RuntimeException::class.java) {
            kotlinx.coroutines.runBlocking { engine.transcribe(FloatArray(16), DecodeOptions()) }
        }
    }
}
