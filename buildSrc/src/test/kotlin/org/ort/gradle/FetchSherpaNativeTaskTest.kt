package org.ort.gradle

import com.sun.net.httpserver.HttpServer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * R-1001 (WPJ, FR-ASR-8): [SherpaNativeFetcher] is the same shape as [BundledAssetFetcher] —
 * fetch, cache, verify, package — for the two native libraries `LibraryUtils.load()` needs on
 * Android (see this package's own build report for the disassembly proving that). Every test uses
 * a `file://` URL or a tiny in-process [HttpServer], never the real network — same discipline
 * [FetchBundledAssetsTaskTest] already documents.
 *
 * Unlike [BundledAssetFetcher], there is deliberately no allow-missing escape hatch here: the
 * archive is public (no token, no auth), a few tens of megabytes, and a build that silently
 * shipped without these libraries is the exact defect (register R-1001) this fetcher exists to
 * close — see `sherpa-native.json`'s own note and this session's CHANGELOG entry.
 */
class FetchSherpaNativeTaskTest {

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Builds a real tar.bz2 archive in memory containing exactly [entries] (archivePath -> bytes). */
    private fun buildArchive(entries: Map<String, ByteArray>): ByteArray {
        val buffer = ByteArrayOutputStream()
        BZip2CompressorOutputStream(buffer).use { bz ->
            TarArchiveOutputStream(bz).use { tar ->
                entries.forEach { (path, bytes) ->
                    val e = TarArchiveEntry(path)
                    e.size = bytes.size.toLong()
                    tar.putArchiveEntry(e)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        return buffer.toByteArray()
    }

    private fun entry(
        abi: String = "arm64-v8a",
        file: String = "libsherpa-onnx-jni.so",
        archivePath: String = "jniLibs/arm64-v8a/libsherpa-onnx-jni.so",
        sha256: String,
        sizeBytes: Long = 0L,
    ) = SherpaNativeLibraryEntry(abi = abi, file = file, archivePath = archivePath, sha256 = sha256, sizeBytes = sizeBytes)

    // ---- SherpaNativeManifest.parse -------------------------------------------------------------

    @Test
    fun `parse reads version, archiveUrl and every library entry`() {
        val json = """
            {
              "note": ["ignored — arbitrary nested JSON must not break parsing"],
              "version": "1.13.7",
              "archiveUrl": "https://example.invalid/sherpa-onnx-v1.13.7-android.tar.bz2",
              "libraries": [
                {
                  "abi": "arm64-v8a",
                  "file": "libsherpa-onnx-jni.so",
                  "archivePath": "jniLibs/arm64-v8a/libsherpa-onnx-jni.so",
                  "sha256": "${"a".repeat(64)}",
                  "sizeBytes": 4761536
                },
                {
                  "abi": "x86_64",
                  "file": "libonnxruntime.so",
                  "archivePath": "jniLibs/x86_64/libonnxruntime.so",
                  "sha256": "${"b".repeat(64)}",
                  "sizeBytes": 25000416
                }
              ]
            }
        """.trimIndent()

        val manifest = SherpaNativeManifest.parse(json)

        assertEquals("1.13.7", manifest.version)
        assertEquals("https://example.invalid/sherpa-onnx-v1.13.7-android.tar.bz2", manifest.archiveUrl)
        assertEquals(2, manifest.libraries.size)
        val arm = manifest.libraries.first { it.abi == "arm64-v8a" }
        assertEquals("libsherpa-onnx-jni.so", arm.file)
        assertEquals(4_761_536L, arm.sizeBytes)
    }

    @Test
    fun `parse of the real committed sherpa-native json round-trips exactly the two ABIs WPJ owns`() {
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "sherpa-native.json").isFile }
        val manifest = SherpaNativeManifest.parse(File(root, "sherpa-native.json").readText())

        assertEquals(setOf("arm64-v8a", "x86_64"), manifest.libraries.map { it.abi }.toSet())
        assertEquals(
            setOf("libsherpa-onnx-jni.so", "libonnxruntime.so"),
            manifest.libraries.map { it.file }.toSet(),
        )
        assertEquals(4, manifest.libraries.size, "exactly two files for exactly two ABIs, no more")
        manifest.libraries.forEach {
            assertEquals(64, it.sha256.length, "${it.abi}/${it.file}: sha256 must be a real 64-hex digest")
        }
    }

    // ---- SherpaNativeFetcher.fetchAll — success -------------------------------------------------

    @Test
    fun `every declared library is extracted from the archive, verified and written to abi-out`(
        @TempDir dir: File,
    ) {
        val jniBytes = "fake jni .so bytes".toByteArray()
        val onnxBytes = "fake onnxruntime .so bytes".toByteArray()
        val archiveFile = File(dir, "archive.tar.bz2").apply {
            writeBytes(
                buildArchive(
                    mapOf(
                        "jniLibs/arm64-v8a/libsherpa-onnx-jni.so" to jniBytes,
                        "jniLibs/arm64-v8a/libonnxruntime.so" to onnxBytes,
                    ),
                ),
            )
        }
        val outDir = File(dir, "out")

        val resolved = SherpaNativeFetcher.fetchAll(
            libraries = listOf(
                entry(archivePath = "jniLibs/arm64-v8a/libsherpa-onnx-jni.so", sha256 = sha256(jniBytes)),
                entry(
                    file = "libonnxruntime.so",
                    archivePath = "jniLibs/arm64-v8a/libonnxruntime.so",
                    sha256 = sha256(onnxBytes),
                ),
            ),
            archiveUrl = archiveFile.toURI().toString(),
            cacheDir = File(dir, "cache"),
            outDir = outDir,
        )

        assertEquals(2, resolved.size)
        val jniOut = File(outDir, "arm64-v8a/libsherpa-onnx-jni.so")
        val onnxOut = File(outDir, "arm64-v8a/libonnxruntime.so")
        assertTrue(jniOut.isFile)
        assertTrue(onnxOut.isFile)
        assertEquals(jniBytes.toList(), jniOut.readBytes().toList())
        assertEquals(onnxBytes.toList(), onnxOut.readBytes().toList())
    }

    // ---- real-archive shape — upstream's own tarball prefixes every entry with "./" -------------

    @Test
    fun `AC discrimination — a leading dot-slash in the real archive's own entry names is normalized away`(
        @TempDir dir: File,
    ) {
        // R-1001 build report: confirmed directly against a real download of upstream's own
        // published archive — every entry is stored as "./jniLibs/<abi>/<file>", not
        // "jniLibs/<abi>/<file>" (Python's tarfile.getnames() on the real file shows this; extractall
        // silently normalizes it away, which is why a first pass at this fetcher — matching
        // tarEntry.name verbatim against sherpa-native.json's clean archivePath — found nothing and
        // failed the build naming every declared library as missing, even though the real archive
        // plainly contained all of them).
        val jniBytes = "fake jni bytes".toByteArray()
        val archiveFile = File(dir, "archive.tar.bz2").apply {
            writeBytes(buildArchive(mapOf("./jniLibs/arm64-v8a/libsherpa-onnx-jni.so" to jniBytes)))
        }

        val resolved = SherpaNativeFetcher.fetchAll(
            libraries = listOf(entry(sha256 = sha256(jniBytes))),
            archiveUrl = archiveFile.toURI().toString(),
            cacheDir = File(dir, "cache"),
            outDir = File(dir, "out"),
        )

        assertEquals(1, resolved.size)
        assertTrue(File(dir, "out/arm64-v8a/libsherpa-onnx-jni.so").isFile)
    }

    // ---- caching — the archive is downloaded once, even for multiple libraries/fetches ----------

    @Test
    fun `the archive is downloaded exactly once even though it is fetched twice`(@TempDir dir: File) {
        val jniBytes = "fake jni bytes".toByteArray()
        val archiveBytes = buildArchive(mapOf("jniLibs/arm64-v8a/libsherpa-onnx-jni.so" to jniBytes))
        val requestCount = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/sherpa-onnx-v1.13.7-android.tar.bz2") { exchange ->
            requestCount.incrementAndGet()
            exchange.sendResponseHeaders(200, archiveBytes.size.toLong())
            exchange.responseBody.use { it.write(archiveBytes) }
        }
        server.start()
        try {
            val archiveUrl = "http://127.0.0.1:${server.address.port}/sherpa-onnx-v1.13.7-android.tar.bz2"
            val libs = listOf(entry(sha256 = sha256(jniBytes)))
            val cacheDir = File(dir, "cache")

            SherpaNativeFetcher.fetchAll(libs, archiveUrl, cacheDir, File(dir, "out1"))
            SherpaNativeFetcher.fetchAll(libs, archiveUrl, cacheDir, File(dir, "out2"))

            assertEquals(1, requestCount.get(), "the cached archive must not be re-downloaded")
            assertTrue(File(dir, "out2/arm64-v8a/libsherpa-onnx-jni.so").isFile)
        } finally {
            server.stop(0)
        }
    }

    // ---- failure paths — loud, named, never a silent partial install -----------------------------

    @Test
    fun `AC discrimination — a checksum mismatch fails the build and names the abi and file`(
        @TempDir dir: File,
    ) {
        val jniBytes = "real bytes".toByteArray()
        val archiveFile = File(dir, "archive.tar.bz2").apply {
            writeBytes(buildArchive(mapOf("jniLibs/arm64-v8a/libsherpa-onnx-jni.so" to jniBytes)))
        }

        val thrown = assertThrows(GradleException::class.java) {
            SherpaNativeFetcher.fetchAll(
                libraries = listOf(entry(sha256 = "0".repeat(64))),
                archiveUrl = archiveFile.toURI().toString(),
                cacheDir = File(dir, "cache"),
                outDir = File(dir, "out"),
            )
        }

        assertTrue(thrown.message!!.contains("arm64-v8a"), "must name the abi, got: ${thrown.message}")
        assertTrue(
            thrown.message!!.contains("libsherpa-onnx-jni.so"),
            "must name the file, got: ${thrown.message}",
        )
        assertFalse(File(dir, "out/arm64-v8a/libsherpa-onnx-jni.so").exists())
    }

    @Test
    fun `AC discrimination — an archive missing a declared entry fails the build and names its path`(
        @TempDir dir: File,
    ) {
        val archiveFile = File(dir, "archive.tar.bz2").apply {
            writeBytes(buildArchive(mapOf("jniLibs/arm64-v8a/some-other-file.so" to "x".toByteArray())))
        }

        val thrown = assertThrows(GradleException::class.java) {
            SherpaNativeFetcher.fetchAll(
                libraries = listOf(entry(sha256 = "a".repeat(64))),
                archiveUrl = archiveFile.toURI().toString(),
                cacheDir = File(dir, "cache"),
                outDir = File(dir, "out"),
            )
        }

        assertTrue(
            thrown.message!!.contains("jniLibs/arm64-v8a/libsherpa-onnx-jni.so"),
            "must name the missing archive path, got: ${thrown.message}",
        )
    }

    @Test
    fun `a download failure fails the build naming the url`(@TempDir dir: File) {
        val thrown = assertThrows(GradleException::class.java) {
            SherpaNativeFetcher.fetchAll(
                libraries = listOf(entry(sha256 = "a".repeat(64))),
                archiveUrl = "http://ort-sherpa-native-must-not-resolve.invalid/x.tar.bz2",
                cacheDir = File(dir, "cache"),
                outDir = File(dir, "out"),
            )
        }
        assertTrue(thrown.message!!.contains("ort-sherpa-native-must-not-resolve.invalid"))
    }
}
