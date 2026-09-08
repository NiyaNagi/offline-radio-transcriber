package org.ort.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.Outcome
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
}
