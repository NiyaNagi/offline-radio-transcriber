package org.ort.net

import java.io.File
import java.security.MessageDigest

/** A pinned expected digest an acquired file must match before it is usable (FR-AST-2). */
public data class Checksum(val algo: String = "sha256", val value: String) {
    init {
        require(algo == "sha256") { "only sha256 is supported, got '$algo'" }
        require(value.isNotBlank()) { "checksum value must not be blank" }
    }
}

/** Streaming SHA-256 over [file] — never loads the whole (potentially ~100 MB) file into memory. */
public fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
