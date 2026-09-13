package org.ort.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.Outcome
import org.ort.core.assets.ModelFileVerifier
import org.ort.core.assets.ModelVerification
import org.ort.net.fake.FakeHttpRangeClient
import org.ort.testing.Requirement
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Drives [ModelAcquisition] purely against [FakeHttpRangeClient] — no test in this module makes
 * a real network call (constitution V; the whole point of this module existing is that
 * `:capture-*`/`:pipeline` never get the chance to).
 */
class ModelAcquisitionFetchTest {

    private val body = ByteArray(4096) { (it % 251).toByte() }
    private val goodChecksum = Checksum(value = sha256(body))

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    @Requirement("FR-AST-2", "FR-AST-3")
    fun `a full successful download verifies and lands the model at the destination`() {
        val dest = Files.createTempDirectory("net-fetch").resolve("model.onnx").toFile()
        val client = FakeHttpRangeClient(body)
        val acquisition = ModelAcquisition(client)

        val result = acquisition.fetch(
            ModelFetchSpec("https://example.invalid/model.onnx", dest, goodChecksum),
            NetCapability.UserInitiated,
        )

        val ok = result as Outcome.Ok
        assertEquals(dest, ok.value.path)
        assertTrue(dest.exists())
        assertEquals(body.toList(), dest.readBytes().toList())
        assertFalse(File(dest.parentFile, dest.name + ".part").exists())
    }

    @Test
    @Requirement("FR-AST-2")
    fun `a checksum mismatch refuses the file and leaves nothing usable at the destination`() {
        val dest = Files.createTempDirectory("net-fetch").resolve("model.onnx").toFile()
        val corruptBody = body.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val client = FakeHttpRangeClient(corruptBody)
        val acquisition = ModelAcquisition(client)

        val result = acquisition.fetch(
            ModelFetchSpec("https://example.invalid/model.onnx", dest, goodChecksum),
            NetCapability.UserInitiated,
        )

        assertTrue(result is Outcome.Err, "a mismatched checksum must not be accepted as a usable model")
        assertFalse(dest.exists(), "no file must land at the destination on a checksum failure")
        assertFalse(
            File(dest.parentFile, dest.name + ".part").exists(),
            "the corrupt partial must not survive to be mistaken for resumable progress by a later run",
        )
    }

    @Test
    @Requirement("FR-AST-3")
    fun `a dropped connection resumes from the partial offset rather than restarting`() {
        val dest = Files.createTempDirectory("net-fetch").resolve("model.onnx").toFile()
        val client = FakeHttpRangeClient(body)
        client.dropAfterBytes = 1000
        val acquisition = ModelAcquisition(client)

        val firstAttempt = acquisition.fetch(
            ModelFetchSpec("https://example.invalid/model.onnx", dest, goodChecksum),
            NetCapability.UserInitiated,
        )
        assertTrue(firstAttempt is Outcome.Err, "the scripted drop must actually fail the first attempt")
        val part = File(dest.parentFile, dest.name + ".part")
        assertEquals(1000, part.length().toInt(), "the partial bytes served before the drop must be kept")

        client.dropAfterBytes = null // the connection resumes cleanly on retry
        val secondAttempt = acquisition.fetch(
            ModelFetchSpec("https://example.invalid/model.onnx", dest, goodChecksum),
            NetCapability.UserInitiated,
        )

        assertTrue(secondAttempt is Outcome.Ok, "resume must complete the download")
        assertEquals(body.toList(), dest.readBytes().toList())
        assertEquals(
            listOf(0L, 1000L),
            client.requests.map { it.second },
            "the second request must ask for bytes from the partial offset, not restart at zero",
        )
    }

    @Test
    @Requirement("FR-AST-3")
    fun `an already-verified model is not re-downloaded`() {
        val dest = Files.createTempDirectory("net-fetch").resolve("model.onnx").toFile()
        val client = FakeHttpRangeClient(body)
        val acquisition = ModelAcquisition(client)
        val spec = ModelFetchSpec("https://example.invalid/model.onnx", dest, goodChecksum)

        val first = acquisition.fetch(spec, NetCapability.UserInitiated) as Outcome.Ok
        assertFalse(first.value.fromCache)
        val requestsAfterFirst = client.requests.size

        val second = acquisition.fetch(spec, NetCapability.UserInitiated) as Outcome.Ok

        assertTrue(second.value.fromCache, "a second fetch of an already-verified model must be idempotent")
        assertEquals(requestsAfterFirst, client.requests.size, "idempotent fetch must make no HTTP call at all")
    }

    // ---- Coordinator follow-up, register R-1052: ModelFileVerifier requires a `.size` sidecar
    // this class never wrote, so it refused every model a real operator downloaded -- the
    // regression this section's own tests pin shut. -----------------------------------------

    @Test
    @Requirement("R-1052")
    fun `R_1052 a fully downloaded model passes ModelFileVerifier, not just this class's own cache check`() {
        val dest = Files.createTempDirectory("net-fetch").resolve("model.onnx").toFile()
        val acquisition = ModelAcquisition(FakeHttpRangeClient(body))

        val result = acquisition.fetch(
            ModelFetchSpec("https://example.invalid/model.onnx", dest, goodChecksum),
            NetCapability.UserInitiated,
        )

        assertTrue(result is Outcome.Ok, "expected Ok, got $result")
        assertEquals(
            ModelVerification.Verified,
            ModelFileVerifier.verify(dest),
            "a model this class just verified and installed must itself pass the loader's own check",
        )
    }

    @Test
    @Requirement("R-1052")
    fun `R_1052 an upgrade install with only the sha256 marker ModelAcquisition used to write still loads`() {
        // Exactly what a pre-fix ModelAcquisition.fetch/sideload left behind: the `.sha256` marker
        // alone, no `.size` sidecar -- a real operator's already-downloaded model must not regress
        // into "no verified-install record" once ModelFileVerifier starts gating native loads.
        val dest = Files.createTempDirectory("net-fetch-upgrade").resolve("model.onnx").toFile()
        dest.writeBytes(body)
        ModelFileVerifier.sha256MarkerFile(dest).writeText(goodChecksum.value)
        assertFalse(ModelFileVerifier.sizeMarkerFile(dest).isFile, "precondition: no size sidecar yet")

        val result = ModelFileVerifier.verify(dest)

        assertEquals(ModelVerification.Verified, result)
        assertTrue(ModelFileVerifier.sizeMarkerFile(dest).isFile, "must be backfilled for the next call")
    }
}
