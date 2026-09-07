package org.ort.capture.android.codec

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement
import java.io.File
import kotlin.random.Random

class FlacStoreTest {

    private fun randomPcm(n: Int, seed: Long = 42): ByteArray {
        val r = Random(seed)
        val out = ByteArray(n)
        r.nextBytes(out)
        return out
    }

    @Test
    @Requirement("FR-STO-2b")
    fun `the substitute codec is byte-for-byte lossless over random PCM`() {
        val codec = DeflatePredictiveCodec()
        val pcm = randomPcm(20_000)
        val encoded = codec.encode(pcm)
        val decoded = codec.decode(encoded)
        assertArrayEquals(pcm, decoded)
    }

    @Test
    @Requirement("FR-STO-2b")
    fun `a successful encode decodes-and-compares before deleting the staged PCM`() {
        val dir = createTempDir("flac-store-test")
        val staged = File(dir, "staged.pcm")
        val encoded = File(dir, "encoded.bin")
        val store = FlacStore(DeflatePredictiveCodec())
        val pcm = randomPcm(5_000, seed = 7)

        store.stage(pcm, staged)
        val result = store.encodeAndVerify(staged, encoded)

        assertTrue(result is FlacEncodeResult.Success)
        assertFalse(staged.exists(), "staged PCM must be deleted only after the round-trip check")
        assertTrue(encoded.exists())
    }

    @Test
    @Requirement("FR-STO-2b")
    fun `a codec that fails to be lossless never deletes the staged PCM`() {
        val dir = createTempDir("flac-store-test-fail")
        val staged = File(dir, "staged.pcm")
        val encoded = File(dir, "encoded.bin")
        val corrupting = object : LosslessCodec {
            override val name = "corrupting-fake"
            override fun encode(pcm: ByteArray): ByteArray = pcm
            override fun decode(encoded: ByteArray): ByteArray = encoded.also { it[0] = (it[0] + 1).toByte() }
        }
        val store = FlacStore(corrupting)
        val pcm = randomPcm(100, seed = 3)

        store.stage(pcm, staged)
        val result = store.encodeAndVerify(staged, encoded)

        assertTrue(result is FlacEncodeResult.VerificationFailed)
        assertTrue(staged.exists(), "nothing is deleted quietly on a verification failure (constitution III)")
        assertFalse(encoded.exists(), "the broken encoded output is discarded, not the source")
    }
}
