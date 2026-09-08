package org.ort.pipeline.capture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Build-plan P12: [org.ort.pipeline.capture.RealCaptureService]'s `EnergyVadModel` is an
 * RMS-energy stand-in, explicitly not Silero. A real Silero VAD binding does exist in the
 * already-resolved `sherpa-onnx-jvm` jar (`asr-sherpa/README.md`), but it needs a real `.onnx`
 * model file this repo does not commit -- [RealVadProvider] must report that honestly (constitution
 * I) rather than silently falling back.
 */
class RealVadProviderTest {

    @Test
    fun `no model file present reports Unavailable, naming the expected path`(@TempDir tmp: File) {
        val result = RealVadProvider.provide(tmp)
        assertTrue(result is VadProvisionResult.Unavailable)
        val reason = (result as VadProvisionResult.Unavailable).reason
        assertTrue(reason.contains(SileroVadLocator.modelFile(tmp).path))
    }

    @Test
    fun `modelFile is a fixed, documented app-private path`(@TempDir tmp: File) {
        val expected = File(tmp, "models/silero-vad/silero_vad.onnx")
        assertTrue(SileroVadLocator.modelFile(tmp) == expected)
        assertFalse(expected.exists())
    }
}
