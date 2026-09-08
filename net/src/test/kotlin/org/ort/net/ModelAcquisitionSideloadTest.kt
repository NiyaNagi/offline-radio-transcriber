package org.ort.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.Outcome
import org.ort.net.fake.FakeHttpRangeClient
import org.ort.testing.Requirement
import java.nio.file.Files
import java.security.MessageDigest

/**
 * FR-ASR-8: a user-supplied model file never touched the network, but it is untrusted input all
 * the same and goes through the identical checksum verification as a fetched one (FR-AST-2).
 * `:net`'s job ends here — the signature check and probe-run activation belong to `:asr-sherpa`'s
 * `ModelActivation`, deliberately not duplicated in this module.
 */
class ModelAcquisitionSideloadTest {

    private val body = ByteArray(2048) { it.toByte() }
    private val goodChecksum = Checksum(
        value = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) },
    )

    @Test
    @Requirement("FR-ASR-8", "FR-AST-2")
    fun `a side-loaded file matching its checksum is verified and copied into place, untouched by the network`() {
        val source = Files.createTempFile("net-sideload-src", ".onnx").toFile()
        source.writeBytes(body)
        val dest = Files.createTempDirectory("net-sideload-dest").resolve("model.onnx").toFile()
        val client = FakeHttpRangeClient(ByteArray(0))
        val acquisition = ModelAcquisition(client)

        val result = acquisition.sideload(
            source,
            ModelFetchSpec("unused://not-fetched", dest, goodChecksum),
            NetCapability.UserInitiated,
        )

        val ok = result as Outcome.Ok
        assertTrue(dest.exists())
        assertEquals(body.toList(), dest.readBytes().toList())
        assertTrue(client.requests.isEmpty(), "a side-loaded file must never cause an HTTP request")
        assertEquals(dest, ok.value.path)
    }

    @Test
    @Requirement("FR-ASR-8", "FR-AST-2")
    fun `a side-loaded file failing its checksum is refused and never lands at the destination`() {
        val source = Files.createTempFile("net-sideload-src", ".onnx").toFile()
        source.writeBytes(body.copyOf().also { it[0] = (it[0] + 1).toByte() })
        val dest = Files.createTempDirectory("net-sideload-dest").resolve("model.onnx").toFile()
        val acquisition = ModelAcquisition(FakeHttpRangeClient(ByteArray(0)))

        val result = acquisition.sideload(
            source,
            ModelFetchSpec("unused://not-fetched", dest, goodChecksum),
            NetCapability.UserInitiated,
        )

        assertTrue(result is Outcome.Err)
        assertFalse(dest.exists(), "a tampered or wrong side-loaded file must never reach the destination path")
    }

    @Test
    @Requirement("FR-AST-2")
    fun `a side-loaded refusal leaves a previously-active model at the destination untouched`() {
        val source = Files.createTempFile("net-sideload-src", ".onnx").toFile()
        source.writeBytes(body.copyOf().also { it[0] = (it[0] + 1).toByte() }) // tampered

        val destDir = Files.createTempDirectory("net-sideload-dest")
        val dest = destDir.resolve("model.onnx").toFile()
        val previousBytes = "the previously active, good model".toByteArray()
        dest.writeBytes(previousBytes)

        val acquisition = ModelAcquisition(FakeHttpRangeClient(ByteArray(0)))
        val result = acquisition.sideload(
            source,
            ModelFetchSpec("unused://not-fetched", dest, goodChecksum),
            NetCapability.UserInitiated,
        )

        assertTrue(result is Outcome.Err)
        assertEquals(
            previousBytes.toList(),
            dest.readBytes().toList(),
            "FR-AST-2: a failed verification leaves the previous version active",
        )
    }
}
