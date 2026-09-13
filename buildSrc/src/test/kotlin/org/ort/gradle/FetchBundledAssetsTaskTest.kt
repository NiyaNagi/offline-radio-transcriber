package org.ort.gradle

import com.sun.net.httpserver.HttpServer
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * WPG (`spec/e2e-capture-modes-plan.md`, FR-AST-2/3/3b, D35/D36). Every test here uses a `file://`
 * URL or a tiny in-process [HttpServer] (JDK-built-in, no new dependency) — never the real
 * network, per constitution II's "a fake that cannot be told to fail... tests nothing" applied to
 * a fetch task: [BundledAssetFetcher] is exercised against sources this suite fully controls.
 */
class FetchBundledAssetsTaskTest {

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun entry(
        id: String = "A",
        url: String,
        sha256: String,
        gated: Boolean = false,
        destination: String = "models/a.bin",
    ) = BundledAssetManifest.Entry(
        id = id,
        url = url,
        sha256 = sha256,
        sizeBytes = 0L,
        destination = destination,
        tiers = listOf("T0"),
        licence = "MIT",
        gated = gated,
    )

    // ---- BundledAssetManifest.parse -----------------------------------------------------------

    @Test
    fun `parse reads every field of a well-formed manifest`() {
        val json = """
            {
              "note": ["ignored — arbitrary nested JSON must not break parsing"],
              "assets": [
                {
                  "id": "ASR_ENCODER",
                  "url": "https://example.invalid/encoder.onnx",
                  "sha256": "${"a".repeat(64)}",
                  "sizeBytes": 12937772,
                  "destination": "models/whisper-tiny-en-int8/tiny.en-encoder.int8.onnx",
                  "tiers": ["T0", "T1", "T2", "T3"],
                  "licence": "MIT",
                  "gated": false
                },
                {
                  "id": "LLM_GEMMA3_1B",
                  "url": "https://example.invalid/gemma.task",
                  "sha256": "${"b".repeat(64)}",
                  "sizeBytes": 554661243,
                  "destination": "models/llm/gemma3-1b-it-int4.task",
                  "tiers": ["T3"],
                  "licence": "gemma",
                  "gated": true
                }
              ]
            }
        """.trimIndent()

        val entries = BundledAssetManifest.parse(json)

        assertEquals(2, entries.size)
        val encoder = entries.first { it.id == "ASR_ENCODER" }
        assertEquals(12_937_772L, encoder.sizeBytes)
        assertEquals(listOf("T0", "T1", "T2", "T3"), encoder.tiers)
        assertFalse(encoder.gated)
        val gemma = entries.first { it.id == "LLM_GEMMA3_1B" }
        assertTrue(gemma.gated)
        assertEquals(listOf("T3"), gemma.tiers)
    }

    @Test
    fun `parse of the real committed manifest round-trips every id with its destination`() {
        // The single source of truth this whole package builds from — a parse failure here means
        // the committed file itself is malformed, not a test fixture.
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "bundled-assets.json").isFile }
        val entries = BundledAssetManifest.parse(File(root, "bundled-assets.json").readText())

        val byId = entries.associateBy { it.id }
        assertEquals(
            "models/whisper-tiny-en-int8/tiny.en-encoder.int8.onnx",
            byId.getValue("ASR_ENCODER").destination,
        )
        assertEquals(
            "models/whisper-tiny-en-int8/tiny.en-decoder.int8.onnx",
            byId.getValue("ASR_DECODER").destination,
        )
        assertEquals("models/whisper-tiny-en-int8/tiny.en-tokens.txt", byId.getValue("ASR_TOKENS").destination)
        assertEquals("models/silero-vad/silero_vad.onnx", byId.getValue("VAD").destination)
        assertEquals("models/llm/gemma3-1b-it-int4.task", byId.getValue("LLM_GEMMA3_1B").destination)
        assertTrue(byId.getValue("LLM_GEMMA3_1B").gated)
        assertEquals(listOf("T3"), byId.getValue("LLM_GEMMA3_1B").tiers)
    }

    // ---- rewriteSha256 -------------------------------------------------------------------------

    @Test
    fun `rewriteSha256 replaces only the named entry's sentinel, leaving the rest of the file untouched`(
        @TempDir dir: File,
    ) {
        val file = File(dir, "manifest.json")
        file.writeText(
            """
            {
              "assets": [
                { "id": "A", "sha256": "${BundledAssetManifest.TRUST_ON_FIRST_FETCH}", "other": "kept" },
                { "id": "B", "sha256": "${"c".repeat(64)}", "other": "kept-too" }
              ]
            }
            """.trimIndent(),
        )

        BundledAssetManifest.rewriteSha256(file, "A", "d".repeat(64))

        val text = file.readText()
        assertTrue(text.contains("\"sha256\": \"${"d".repeat(64)}\""))
        assertFalse(text.contains(BundledAssetManifest.TRUST_ON_FIRST_FETCH))
        assertTrue(
            text.contains("\"sha256\": \"${"c".repeat(64)}\""),
            "entry B's own real digest must survive untouched",
        )
        assertTrue(text.contains("\"other\": \"kept\""), "unrelated fields must survive untouched")
    }

    @Test
    fun `rewriteSha256 fails loudly for an id that does not exist`(@TempDir dir: File) {
        val file = File(dir, "manifest.json")
        file.writeText("""{"assets": []}""")

        assertThrows(GradleException::class.java) {
            BundledAssetManifest.rewriteSha256(file, "NOPE", "d".repeat(64))
        }
    }

    // ---- BundledAssetFetcher.fetchAll — success, cache and trust-on-first-fetch ----------------

    @Test
    fun `a well-formed entry is fetched, verified and copied to its destination`(@TempDir dir: File) {
        val source = File(dir, "source.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val manifestFile = File(dir, "bundled-assets.json").apply { writeText("""{"assets": []}""") }
        val outDir = File(dir, "out")
        val cacheDir = File(dir, "cache")

        val resolved = BundledAssetFetcher.fetchAll(
            entries = listOf(entry(url = source.toURI().toString(), sha256 = sha256(source.readBytes()))),
            cacheDir = cacheDir,
            outDir = outDir,
            manifestFile = manifestFile,
            hfToken = null,
            allowMissing = false,
        )

        assertEquals(1, resolved.size)
        assertFalse(resolved.single().missing)
        val dest = File(outDir, "models/a.bin")
        assertTrue(dest.isFile, "expected the verified file at $dest")
        assertEquals(listOf<Byte>(1, 2, 3, 4), dest.readBytes().toList())
    }

    @Test
    fun `a trust-on-first-fetch entry pins the real digest into the manifest file and verifies against it`(
        @TempDir dir: File,
    ) {
        val source = File(dir, "tokens.txt").apply { writeText("hello world") }
        val realSha = sha256(source.readBytes())
        val manifestFile = File(dir, "bundled-assets.json").apply {
            writeText(
                """{"assets": [{"id": "ASR_TOKENS", "sha256": "${BundledAssetManifest.TRUST_ON_FIRST_FETCH}"}]}""",
            )
        }

        val resolved = BundledAssetFetcher.fetchAll(
            entries = listOf(
                entry(
                    id = "ASR_TOKENS",
                    url = source.toURI().toString(),
                    sha256 = BundledAssetManifest.TRUST_ON_FIRST_FETCH,
                    destination = "models/tokens.txt",
                ),
            ),
            cacheDir = File(dir, "cache"),
            outDir = File(dir, "out"),
            manifestFile = manifestFile,
            hfToken = null,
            allowMissing = false,
        )

        assertEquals(realSha, resolved.single().sha256)
        assertTrue(
            manifestFile.readText().contains("\"sha256\": \"$realSha\""),
            "the manifest file on disk must now carry the real digest, not the sentinel",
        )
        assertFalse(manifestFile.readText().contains(BundledAssetManifest.TRUST_ON_FIRST_FETCH))
    }

    // ---- digest mismatch — FR-AST-2: fails the build naming the file ---------------------------

    @Test
    fun `AC discrimination — a digest mismatch fails the build and names the file, never silently installs`(
        @TempDir dir: File,
    ) {
        val source = File(dir, "corrupt.bin").apply { writeBytes(byteArrayOf(9, 9, 9)) }
        val manifestFile = File(dir, "bundled-assets.json").apply { writeText("""{"assets": []}""") }
        val wrongSha = "0".repeat(64)

        val thrown = assertThrows(GradleException::class.java) {
            BundledAssetFetcher.fetchAll(
                entries = listOf(entry(id = "BAD", url = source.toURI().toString(), sha256 = wrongSha)),
                cacheDir = File(dir, "cache"),
                outDir = File(dir, "out"),
                manifestFile = manifestFile,
                hfToken = null,
                allowMissing = false,
            )
        }

        assertTrue(thrown.message!!.contains("BAD"), "failure must name the entry, got: ${thrown.message}")
        assertTrue(
            thrown.message!!.contains("corrupt.bin"),
            "failure must name the file, got: ${thrown.message}",
        )
        assertFalse(
            File(dir, "out/models/a.bin").exists(),
            "a mismatched file must never land at its destination",
        )
    }

    // ---- gated entry with no HF_TOKEN — one-line failure, never a silent skip ------------------

    @Test
    fun `a gated entry with no HF_TOKEN fails with a one-line instruction and makes no network call`(
        @TempDir dir: File,
    ) {
        val manifestFile = File(dir, "bundled-assets.json").apply { writeText("""{"assets": []}""") }
        // A URL that would fail this test if it were ever actually contacted (no such host).
        val neverReachedUrl = "http://ort-bundled-assets-must-not-be-contacted.invalid/gemma.task"

        val thrown = assertThrows(GradleException::class.java) {
            BundledAssetFetcher.fetchAll(
                entries = listOf(
                    entry(id = "LLM_GEMMA3_1B", url = neverReachedUrl, sha256 = "a".repeat(64), gated = true),
                ),
                cacheDir = File(dir, "cache"),
                outDir = File(dir, "out"),
                manifestFile = manifestFile,
                hfToken = null,
                allowMissing = false,
            )
        }

        assertFalse(thrown.message!!.contains("\n"), "must be exactly one line")
        assertTrue(thrown.message!!.contains("HF_TOKEN"))
        assertTrue(thrown.message!!.contains("LLM_GEMMA3_1B"))
    }

    @Test
    fun `a gated entry with HF_TOKEN present sends it as a bearer token and succeeds`(@TempDir dir: File) {
        val body = "gated bytes".toByteArray()
        var sawAuthHeader: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/gemma.task") { exchange ->
            sawAuthHeader = exchange.requestHeaders.getFirst("Authorization")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val manifestFile = File(dir, "bundled-assets.json").apply { writeText("""{"assets": []}""") }
            val resolved = BundledAssetFetcher.fetchAll(
                entries = listOf(
                    entry(
                        id = "LLM_GEMMA3_1B",
                        url = "http://127.0.0.1:${server.address.port}/gemma.task",
                        sha256 = sha256(body),
                        gated = true,
                        destination = "models/llm/gemma.task",
                    ),
                ),
                cacheDir = File(dir, "cache"),
                outDir = File(dir, "out"),
                manifestFile = manifestFile,
                hfToken = "secret-token-value",
                allowMissing = false,
            )

            assertFalse(resolved.single().missing)
            assertEquals("Bearer secret-token-value", sawAuthHeader)
            assertEquals(body.toList(), File(dir, "out/models/llm/gemma.task").readBytes().toList())
        } finally {
            server.stop(0)
        }
    }

    // ---- R-1075 — retry on a transient server failure, never the token in a log line -----------

    /**
     * Register R-1075: CI run 34769228303 failed 17s in on a bare HTTP 500 from GitHub's
     * release-asset CDN with no retry. This exercises the gated (`HF_TOKEN`) path specifically —
     * the token must survive a retry (sent again on the successful second attempt) and must never
     * appear in any logged line, including the retry log.
     */
    @Test
    fun `R_1075 a 500 then a 200 succeeds after one retry and the token never appears in a log line`(
        @TempDir dir: File,
    ) {
        val body = "gated bytes".toByteArray()
        val requestCount = AtomicInteger(0)
        val authHeadersSeen = mutableListOf<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/gemma.task") { exchange ->
            authHeadersSeen += exchange.requestHeaders.getFirst("Authorization")
            if (requestCount.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        server.start()
        try {
            val manifestFile = File(dir, "bundled-assets.json").apply { writeText("""{"assets": []}""") }
            val loggedLines = mutableListOf<String>()
            val token = "secret-token-value"

            val resolved = BundledAssetFetcher.fetchAll(
                entries = listOf(
                    entry(
                        id = "LLM_GEMMA3_1B",
                        url = "http://127.0.0.1:${server.address.port}/gemma.task",
                        sha256 = sha256(body),
                        gated = true,
                        destination = "models/llm/gemma.task",
                    ),
                ),
                cacheDir = File(dir, "cache"),
                outDir = File(dir, "out"),
                manifestFile = manifestFile,
                hfToken = token,
                allowMissing = false,
                onInfo = { loggedLines += it },
                onWarn = { loggedLines += it },
                sleeper = { },
            )

            assertEquals(2, requestCount.get(), "must have retried exactly once")
            assertFalse(resolved.single().missing)
            assertEquals(listOf("Bearer $token", "Bearer $token"), authHeadersSeen, "the token must survive the retry")
            assertEquals(body.toList(), File(dir, "out/models/llm/gemma.task").readBytes().toList())

            val allLogText = loggedLines.joinToString("\n")
            assertFalse(allLogText.contains(token), "the token must never appear in a log line: $allLogText")
            assertFalse(allLogText.contains("Authorization"), "no log line should even mention the header: $allLogText")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `R_1075 four consecutive 503s fail and the message lists all four attempts`(@TempDir dir: File) {
        val requestCount = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/a.bin") { exchange ->
            requestCount.incrementAndGet()
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        try {
            val manifestFile = File(dir, "bundled-assets.json").apply { writeText("""{"assets": []}""") }

            val url = "http://127.0.0.1:${server.address.port}/a.bin"
            val thrown = assertThrows(GradleException::class.java) {
                BundledAssetFetcher.fetchAll(
                    entries = listOf(entry(url = url, sha256 = "a".repeat(64))),
                    cacheDir = File(dir, "cache"),
                    outDir = File(dir, "out"),
                    manifestFile = manifestFile,
                    hfToken = null,
                    allowMissing = false,
                    sleeper = { },
                )
            }

            assertEquals(4, requestCount.get())
            assertTrue(thrown.message!!.contains("after 4 attempts"), "got: ${thrown.message}")
            assertEquals(4, Regex("HTTP 503").findAll(thrown.message!!).count(), "got: ${thrown.message}")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `R_1075 a 404 fails on the first attempt with no retry`(@TempDir dir: File) {
        val requestCount = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/a.bin") { exchange ->
            requestCount.incrementAndGet()
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        try {
            val manifestFile = File(dir, "bundled-assets.json").apply { writeText("""{"assets": []}""") }
            val url = "http://127.0.0.1:${server.address.port}/a.bin"

            assertThrows(GradleException::class.java) {
                BundledAssetFetcher.fetchAll(
                    entries = listOf(entry(url = url, sha256 = "a".repeat(64))),
                    cacheDir = File(dir, "cache"),
                    outDir = File(dir, "out"),
                    manifestFile = manifestFile,
                    hfToken = null,
                    allowMissing = false,
                    sleeper = { throw AssertionError("must not sleep/retry on a 404") },
                )
            }

            assertEquals(1, requestCount.get(), "a 404 must never be retried")
        } finally {
            server.stop(0)
        }
    }

    /**
     * A digest mismatch is never retried (Constitution I — a wrong asset is never quietly retried
     * into acceptance) and, per R-1075's own atomicity requirement, no file is ever left at the
     * final destination.
     */
    @Test
    fun `R_1075 a 200 whose sha256 mismatches is never retried and leaves no file at the final path`(
        @TempDir dir: File,
    ) {
        val body = "wrong bytes".toByteArray()
        val requestCount = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/a.bin") { exchange ->
            requestCount.incrementAndGet()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val manifestFile = File(dir, "bundled-assets.json").apply { writeText("""{"assets": []}""") }

            val thrown = assertThrows(GradleException::class.java) {
                BundledAssetFetcher.fetchAll(
                    entries = listOf(
                        entry(
                            id = "BAD",
                            url = "http://127.0.0.1:${server.address.port}/a.bin",
                            sha256 = "0".repeat(64),
                        ),
                    ),
                    cacheDir = File(dir, "cache"),
                    outDir = File(dir, "out"),
                    manifestFile = manifestFile,
                    hfToken = null,
                    allowMissing = false,
                    sleeper = { throw AssertionError("a checksum mismatch must never be retried") },
                )
            }

            assertEquals(1, requestCount.get(), "the download itself succeeded — never re-fetched")
            assertTrue(thrown.message!!.contains("checksum mismatch"))
            assertFalse(File(dir, "out/models/a.bin").exists())
        } finally {
            server.stop(0)
        }
    }

    // ---- escape hatch — local dev only: packages what it can, marks the rest missing, warns ----

    @Test
    fun `the allow-missing escape hatch marks an unfetchable entry missing instead of failing the build`(
        @TempDir dir: File,
    ) {
        val manifestFile = File(dir, "bundled-assets.json").apply { writeText("""{"assets": []}""") }
        val goodSource = File(dir, "good.bin").apply { writeBytes(byteArrayOf(5, 6, 7)) }
        var warned = false

        val resolved = BundledAssetFetcher.fetchAll(
            entries = listOf(
                entry(id = "GOOD", url = goodSource.toURI().toString(), sha256 = sha256(goodSource.readBytes())),
                entry(
                    id = "LLM_GEMMA3_1B",
                    url = "http://ort.invalid/gemma.task",
                    sha256 = "a".repeat(64),
                    gated = true,
                ),
            ),
            cacheDir = File(dir, "cache"),
            outDir = File(dir, "out"),
            manifestFile = manifestFile,
            hfToken = null,
            allowMissing = true,
            onWarn = { warned = true },
        )

        assertEquals(2, resolved.size)
        assertFalse(resolved.first { it.entry.id == "GOOD" }.missing)
        assertTrue(resolved.first { it.entry.id == "LLM_GEMMA3_1B" }.missing)
        assertTrue(warned, "the escape hatch must warn loudly, never silently")

        val generated = BundledAssetManifest.renderResolved(resolved)
        assertTrue(generated.contains("\"missing\": true"))
        assertTrue(generated.contains("\"missing\": false"))
    }

    @Test
    fun `AC discrimination — without the escape hatch the same missing entry fails the whole build`(
        @TempDir dir: File,
    ) {
        val manifestFile = File(dir, "bundled-assets.json").apply { writeText("""{"assets": []}""") }

        assertThrows(GradleException::class.java) {
            BundledAssetFetcher.fetchAll(
                entries = listOf(
                    entry(
                        id = "LLM_GEMMA3_1B",
                        url = "http://ort.invalid/gemma.task",
                        sha256 = "a".repeat(64),
                        gated = true,
                    ),
                ),
                cacheDir = File(dir, "cache"),
                outDir = File(dir, "out"),
                manifestFile = manifestFile,
                hfToken = null,
                allowMissing = false,
            )
        }
    }

    /**
     * Register R-1060 (process): the guarantee a release build (which never sets the escape
     * hatch — `ort.android-app.gradle.kts` defaults `allowMissingBundledAssets` to `false`, and
     * `.github/workflows` never sets the property/env var) can never mark a real asset a
     * placeholder. A mixed list — one entry that would resolve fine, one that cannot be fetched —
     * must fail the WHOLE `fetchAll` call with `allowMissing = false`, never return a list with the
     * bad one silently marked `missing` alongside the good one's real result. The Gradle task
     * itself (`FetchBundledAssetsTask.fetch`) only writes the generated `bundled/manifest.json`
     * — the one file `BundledAssetInstaller` reads at runtime — after `fetchAll` returns
     * successfully, so a thrown exception here means no `missing` entry, and no manifest naming
     * one, ever reaches a packaged build.
     */
    @Test
    fun `R_1060 a release build never marks any entry missing -- a mixed list fails the whole call`(
        @TempDir dir: File,
    ) {
        val goodSource = File(dir, "good.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val manifestFile = File(dir, "bundled-assets.json").apply { writeText("""{"assets": []}""") }

        val thrown = assertThrows(GradleException::class.java) {
            BundledAssetFetcher.fetchAll(
                entries = listOf(
                    entry(id = "GOOD", url = goodSource.toURI().toString(), sha256 = sha256(goodSource.readBytes())),
                    entry(
                        id = "LLM_GEMMA3_1B",
                        url = "http://ort-r1060-must-not-be-contacted.invalid/gemma.task",
                        sha256 = "a".repeat(64),
                        gated = true,
                    ),
                ),
                cacheDir = File(dir, "cache"),
                outDir = File(dir, "out"),
                manifestFile = manifestFile,
                hfToken = null,
                allowMissing = false,
            )
        }

        assertTrue(thrown.message!!.contains("LLM_GEMMA3_1B"))
    }
}
