package org.ort.app.debug

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.ModelCatalog
import org.ort.app.ui.data.ModelId
import org.ort.core.assets.ModelFileVerifier
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest

/**
 * Register R-1052 (halt): [ScenarioFixtures.installModelFixture] used to overwrite whatever sat at
 * a model's destination -- including a genuinely installed, verified real model -- with a 64-byte
 * stub, unconditionally. On a device with real bundled models this fed sherpa-onnx JNI a corrupt
 * file and the process died with `SIGABRT`. These tests pin the fix at this package's own layer,
 * independent of the load-time refusal `RealVadProvider`/`RealAsrEngineProvider` now also apply.
 */
@RunWith(RobolectricTestRunner::class)
class ScenarioFixturesTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `R_1052 a genuinely verified real model is left untouched, not overwritten with a stub`() {
        val entry = ModelCatalog.entry(ModelId.VAD)
        val destination = entry.destination(context.filesDir)
        destination.parentFile?.mkdirs()
        val realBytes = "a real model, much bigger than 64 bytes in spirit".toByteArray()
        destination.writeBytes(realBytes)
        ModelFileVerifier.sizeMarkerFile(destination).writeText(realBytes.size.toString())
        ModelFileVerifier.sha256MarkerFile(destination).writeText(sha256(realBytes))

        ScenarioFixtures.installModelFixture(context, ModelId.VAD)

        assertEquals(
            "a verified real model must never be replaced by a fixture stub",
            realBytes.toList(),
            destination.readBytes().toList(),
        )
    }

    @Test
    fun `an absent model still gets the fixture stub, exactly as before this fix`() {
        val entry = ModelCatalog.entry(ModelId.VAD)
        val destination = entry.destination(context.filesDir)
        assertTrue("precondition: nothing installed yet", !destination.exists())

        ScenarioFixtures.installModelFixture(context, ModelId.VAD)

        assertEquals(64L, destination.length())
    }

    @Test
    fun `skipIfAlreadyVerified false forces the placeholder even over a verified file`() {
        val entry = ModelCatalog.entry(ModelId.VAD)
        val destination = entry.destination(context.filesDir)
        destination.parentFile?.mkdirs()
        val realBytes = "a real model, verified".toByteArray()
        destination.writeBytes(realBytes)
        ModelFileVerifier.sizeMarkerFile(destination).writeText(realBytes.size.toString())
        ModelFileVerifier.sha256MarkerFile(destination).writeText(sha256(realBytes))

        ScenarioFixtures.installModelFixture(context, ModelId.VAD, skipIfAlreadyVerified = false)

        assertEquals(64L, destination.length())
    }

    @Test
    fun `a corrupt same-size file that fails real verification is still replaced by the fixture`() {
        val entry = ModelCatalog.entry(ModelId.VAD)
        val destination = entry.destination(context.filesDir)
        destination.parentFile?.mkdirs()
        destination.writeBytes(ByteArray(64))
        // A marker that does not match this content -- not a verified install, so the fixture must
        // still be free to write its own stub (and its own honest marker) over it.
        ModelFileVerifier.sizeMarkerFile(destination).writeText("64")
        ModelFileVerifier.sha256MarkerFile(destination).writeText("deadbeef")

        ScenarioFixtures.installModelFixture(context, ModelId.VAD)

        val expectedMarker = (entry.checksumState as org.ort.app.ui.data.ChecksumState.Known).checksum.value
        assertEquals(expectedMarker, java.io.File(destination.parentFile, destination.name + ".sha256").readText())
    }
}
