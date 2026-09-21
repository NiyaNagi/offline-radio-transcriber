package org.ort.asrsherpa.real

import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

/**
 * Exercises [decodeTokens] directly against a real `com.k2fsa.sherpa.onnx.OfflineRecognizerResult`
 * — a plain constructor call, no native method, no model, no `RealSherpaDecoder` instance needed
 * (confirmed by `javap -p` against the resolved `sherpa-onnx-jvm-1.13.7.jar`: the class carries
 * only a public constructor and getters). This is genuinely a unit test of the real binding's
 * shape, not a fake standing in for it — the same class `OfflineRecognizer.getResult()` returns.
 *
 * Register R-1121: this is where the investigation this row asked for is pinned down as a test.
 * `OfflineRecognizerResult` carries `text`, `tokens`, `timestamps`, `lang`, `emotion`, `event` and
 * `durations` — nothing that is a log-probability of any kind. [decodeTokens] must therefore never
 * fabricate one: `logProb` is `null` on every [org.ort.asrapi.TokenScore] it produces, not `0f`
 * (constitution I — `0f` is log-probability 1.0, absolute certainty, and nothing here computed
 * that).
 */
class DecodeTokensTest {

    @Test
    @Requirement("FR-ASR-5", "FR-ASR-6")
    fun `R_1121 decodeTokens never fabricates a logProb the binding does not provide`() {
        val result = OfflineRecognizerResult(
            "kilo seven",
            arrayOf("kilo", "seven"),
            floatArrayOf(0.0f, 0.5f),
            "en",
            "",
            "",
            floatArrayOf(0.4f, 0.3f),
        )

        val tokens = decodeTokens(result)

        assertEquals(2, tokens.size)
        tokens.forEach { assertNull(it.logProb, "no real log-probability exists for ${it.token}; must not be faked") }
    }

    @Test
    fun `R_1121 decodeTokens still carries real per-token text and timing from the binding`() {
        val result = OfflineRecognizerResult(
            "kilo seven",
            arrayOf("kilo", "seven"),
            floatArrayOf(0.0f, 0.5f),
            "en",
            "",
            "",
            floatArrayOf(0.4f, 0.3f),
        )

        val tokens = decodeTokens(result)

        assertEquals("kilo", tokens[0].token)
        assertEquals(0, tokens[0].startMs)
        assertEquals(400, tokens[0].endMs)
        assertEquals("seven", tokens[1].token)
        assertEquals(500, tokens[1].startMs)
        assertEquals(800, tokens[1].endMs)
    }

    @Test
    fun `decodeTokens returns empty when the binding provides no tokens at all`() {
        val result = OfflineRecognizerResult("", null, null, "en", "", "", null)
        assertTrue(decodeTokens(result).isEmpty())
    }
}
