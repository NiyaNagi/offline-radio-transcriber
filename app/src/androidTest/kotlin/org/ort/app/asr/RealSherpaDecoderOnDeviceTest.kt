package org.ort.app.asr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.asrapi.DecodeOptions
import org.ort.asrsherpa.real.RealSherpaDecoder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * R-1001's own on-device proof: constructs a genuine [RealSherpaDecoder] — the same class
 * `:pipeline`'s `AsrEngineProvisioning.kt` constructs in production — on a **real Android
 * runtime**, and asserts it produces a real, recognizable transcript. This is the test the halt
 * itself demanded: every previous proof that the JNI binding is wired correctly (Step 0's symbol
 * comparison, the packaged-APK guard) is static; this is the only one that actually calls
 * `System.loadLibrary("sherpa-onnx-jni")` on a device and decodes real audio through it.
 *
 * **Gated, deliberately, the same shape as `:asr-sherpa`'s own
 * `RealSherpaDecoderRealModelTest`** (JVM-side counterpart, `ORT_RUN_REAL_SHERPA`): a real Whisper
 * `tiny.en` model is ~103MB and constitution/AGENTS.md is explicit that **no test reads a real
 * bundled model** — that rule exists to stop a Robolectric/tour *sweep* from inflating a
 * compressed APK asset whole into a shared JVM test worker's heap (the exact `OutOfMemoryError`
 * `TinyFixtureBundledAssetSource`'s own KDoc documents). That mechanism does not apply here: this
 * is a single, hand-run instrumented test on a real device/emulator filesystem, reading a file the
 * operator pushes with `adb push` — never bundled as an app or test-APK asset, never part of the
 * `:app:testDebugUnitTest`/tour sweep, and structurally incapable of ballooning a shared test
 * worker's heap the way the constitution's rule is actually guarding against.
 *
 * A garbage-bytes fixture (`TinyFixtureBundledAssetSource`'s own shape — a handful of bytes that
 * merely hash to a checksum) **cannot** stand in for the model here: this test's whole point is
 * to assert a genuine decoded transcript, and a real ONNX graph is the only thing that produces
 * one. Where the model is absent, this test [assumeTrue]s (skips, honestly — constitution I) that
 * expectation away rather than fabricating a pass; it never falls back to a smaller fixture that
 * would let a JNI-resolution regression through unnoticed.
 *
 * **To run this test for real** — the identical corpus-push shape `HarnessInstrumentedTest`'s own
 * KDoc already documents in this same source set (`adb push` to a world-readable staging path,
 * then `run-as` to copy into the app's real **internal** private storage; app-*external* storage
 * under `/sdcard/Android/data/<pkg>/` was tried first in this session and confirmed to deny the
 * app read access to a directory `adb push` created directly as root — this codebase's own
 * established pattern avoids exactly that):
 * ```powershell
 * adb push sherpa-onnx-whisper-tiny.en /data/local/tmp/ort-sherpa-test
 * adb shell run-as org.ort.app mkdir -p files/ort-sherpa-test
 * adb shell run-as org.ort.app cp -r /data/local/tmp/ort-sherpa-test/sherpa-onnx-whisper-tiny.en files/ort-sherpa-test/
 * adb shell am instrument -w -e class org.ort.app.asr.RealSherpaDecoderOnDeviceTest \
 *     org.ort.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * (`asr-sherpa/README.md` has the download command for the model archive itself.) A different
 * on-device location can be supplied with the `ortSherpaModelDir` instrumentation argument
 * (`-e ortSherpaModelDir <path>`).
 */
@RunWith(AndroidJUnit4::class)
class RealSherpaDecoderOnDeviceTest {

    @Test
    fun a_real_sherpa_onnx_whisper_model_transcribes_a_real_clip_on_a_real_android_runtime() {
        val dir = resolveModelDir()
        val encoder = File(dir, "tiny.en-encoder.int8.onnx")
        val decoderModel = File(dir, "tiny.en-decoder.int8.onnx")
        val tokens = File(dir, "tiny.en-tokens.txt")
        val wav = File(dir, "test_wavs/0.wav")
        assumeTrue(
            "no real model pushed to $dir — see this test's own KDoc for the adb push commands " +
                "(constitution I: a missing fixture is an honest skip, never a fabricated pass)",
            encoder.isFile && decoderModel.isFile && tokens.isFile && wav.isFile,
        )

        val decoder = RealSherpaDecoder(
            encoderOnnxPath = encoder.absolutePath,
            decoderOnnxPath = decoderModel.absolutePath,
            tokensPath = tokens.absolutePath,
        )
        try {
            val audio = readPcm16MonoWav(wav)

            val hypothesis = decoder.decode(audio, DecodeOptions())

            // Real, non-degenerate text containing the substance of test_wavs/trans.txt's ground
            // truth for 0.wav: "AFTER EARLY NIGHTFALL THE YELLOW LAMPS WOULD LIGHT UP HERE AND
            // THERE THE SQUALID QUARTER OF THE BROTHELS". This is not a claim about accuracy
            // (constitution VI) — it is proof that System.loadLibrary("sherpa-onnx-jni") resolved,
            // a real OfflineRecognizer was constructed, and a real decode happened, on a real
            // Android runtime — the exact failure this whole register row (R-1001) is about.
            val normalized = hypothesis.text.lowercase()
            assertTrue("expected real transcribed text, got blank", hypothesis.text.isNotBlank())
            assertTrue("expected 'nightfall' in: ${hypothesis.text}", normalized.contains("nightfall"))
            assertTrue("expected 'lamps' in: ${hypothesis.text}", normalized.contains("lamps"))
            assertTrue("expected per-token timing to be populated", hypothesis.tokens.isNotEmpty())
        } finally {
            decoder.close()
        }
    }

    private fun resolveModelDir(): File {
        val args = InstrumentationRegistry.getArguments()
        val fromArg = args?.getString("ortSherpaModelDir")
        if (!fromArg.isNullOrBlank()) return File(fromArg)
        // Internal private storage (context.filesDir), not external — see this class's own KDoc
        // for why: an app-external directory `adb push` creates directly as root is not
        // necessarily readable by the app itself (confirmed directly in this session), whereas
        // `run-as <pkg> cp` into internal storage — the exact shape `HarnessInstrumentedTest`
        // already documents in this source set — always is, because the app process itself owns
        // every byte under its own filesDir.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.filesDir, "ort-sherpa-test/sherpa-onnx-whisper-tiny.en")
    }

    /** Minimal PCM16 mono WAV reader — identical shape to :asr-sherpa's own JVM-side
     * `RealSherpaDecoderRealModelTest`; duplicated rather than shared because that test lives in a
     * module this file does not depend on for main code, and this is a dozen lines. */
    private fun readPcm16MonoWav(file: File): FloatArray {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(12)
            raf.readFully(header)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF") { "not a RIFF file: $file" }
            require(String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "not a WAVE file: $file" }

            var dataBytes: ByteArray? = null
            while (raf.filePointer < raf.length()) {
                val chunkHeader = ByteArray(8)
                raf.readFully(chunkHeader)
                val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (id == "data") {
                    dataBytes = ByteArray(size)
                    raf.readFully(dataBytes)
                } else {
                    raf.seek(raf.filePointer + size + (size % 2))
                }
            }
            val bytes = requireNotNull(dataBytes) { "no data chunk in $file" }
            val samples = FloatArray(bytes.size / 2)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in samples.indices) {
                samples[i] = buffer.short / SHORT_MAX
            }
            return samples
        }
    }

    private companion object {
        const val SHORT_MAX = 32768f
    }
}
