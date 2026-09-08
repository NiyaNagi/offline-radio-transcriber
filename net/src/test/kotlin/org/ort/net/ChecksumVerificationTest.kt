package org.ort.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement
import java.nio.file.Files

class ChecksumVerificationTest {

    @Test
    @Requirement("FR-AST-2")
    fun `sha256 of a known file matches the known digest`() {
        val file = Files.createTempFile("net-checksum", ".bin").toFile()
        file.deleteOnExit()
        file.writeBytes("hello world".toByteArray())

        // sha256("hello world") — a fixed, independently-verifiable vector.
        assertEquals(
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
            sha256Of(file),
        )
    }
}
