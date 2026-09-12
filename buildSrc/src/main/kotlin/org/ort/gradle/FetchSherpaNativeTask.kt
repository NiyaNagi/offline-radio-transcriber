package org.ort.gradle

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * R-1001 (register — `ASR unavailable: ... dlopen failed: library "libsherpa-onnx-jni.so" not
 * found` on every real transmission): the `sherpa-onnx-jvm` artifact `:asr-sherpa` depends on
 * (`gradle/libs.versions.toml`) deliberately supports Android — its own `LibraryUtils.load()`
 * skips `loadFromResourceInJar()` on Android and calls `System.loadLibrary("sherpa-onnx-jni")`
 * directly (confirmed by disassembling it — see this task's own build report) — but nothing in
 * this project ever shipped the `.so` files that call resolves to. This task fetches upstream's
 * own published Android release archive (`sherpa-native.json`'s `archiveUrl`, a public GitHub
 * release asset — no token, no auth) and extracts exactly the two files each declared ABI's JNI
 * binding actually needs (`libsherpa-onnx-jni.so`, `libonnxruntime.so` — confirmed the *only* two
 * by inspecting the JNI library's own dynamic symbol table; see `sherpa-native.json`'s note) into
 * `app/src/main/jniLibs/<abi>/` (gitignored, like `app/src/main/assets/bundled/` — never
 * committed).
 *
 * Deliberately **not** wired to `-PortAllowMissingBundledAssets`/`ORT_ALLOW_MISSING_BUNDLED_ASSETS`
 * the way [FetchBundledAssetsTask] is: that escape hatch exists so a no-token local iteration can
 * skip hundreds of megabytes of gated model weights. This archive needs no token, costs tens of
 * megabytes, and a build that silently shipped without these libraries **is R-1001** — the exact
 * defect this task exists to close. There is no escape hatch here; a fetch failure always fails
 * the build.
 *
 * Thin Gradle wrapper over [SherpaNativeFetcher], a plain class free of Gradle types (same
 * precedent as [FetchBundledAssetsTask]/[BundledAssetFetcher]) so the real fetch/verify/extract
 * logic is directly unit-testable.
 */
abstract class FetchSherpaNativeTask : DefaultTask() {

    /** The root `sherpa-native.json` manifest. */
    @get:InputFile
    abstract val manifestFile: RegularFileProperty

    /** `app/src/main/jniLibs/` — gitignored; recreated by every run of this task. */
    @get:OutputDirectory
    abstract val jniLibsOutputDir: DirectoryProperty

    /** `$GRADLE_USER_HOME/ort-sherpa-native/` — shared across worktrees, never under `build/`. */
    @get:org.gradle.api.tasks.Internal
    abstract val cacheRoot: DirectoryProperty

    @TaskAction
    fun fetch() {
        val manifest = SherpaNativeManifest.parse(manifestFile.get().asFile.readText())
        val outDir = jniLibsOutputDir.get().asFile
        val resolved = SherpaNativeFetcher.fetchAll(
            libraries = manifest.libraries,
            archiveUrl = manifest.archiveUrl,
            cacheDir = cacheRoot.get().asFile,
            outDir = outDir,
            onInfo = { logger.lifecycle(it) },
        )
        logger.lifecycle(
            "fetchSherpaNativeLibraries: ${resolved.size} native librar${if (resolved.size == 1) "y" else "ies"} " +
                "verified and packaged under ${outDir.path} (R-1001).",
        )
    }
}

/** One `sherpa-native.json` `libraries[]` entry. */
data class SherpaNativeLibraryEntry(
    val abi: String,
    val file: String,
    val archivePath: String,
    val sha256: String,
    val sizeBytes: Long,
)

/** The parsed `sherpa-native.json` shape [SherpaNativeManifest.parse] reads. */
object SherpaNativeManifest {

    data class Manifest(val version: String, val archiveUrl: String, val libraries: List<SherpaNativeLibraryEntry>)

    fun parse(json: String): Manifest = with(SherpaNativeJson) {
        val root = SherpaNativeJson.parse(json) as? SherpaNativeJson.JObject
            ?: throw GradleException("sherpa-native.json: expected a top-level JSON object")
        val version = root.string("version")
        val archiveUrl = root.string("archiveUrl")
        val librariesArray = root.fields["libraries"] as? SherpaNativeJson.JArray
            ?: throw GradleException("sherpa-native.json: expected a \"libraries\" array")
        val libraries = librariesArray.items.map { item ->
            val obj = item as? SherpaNativeJson.JObject
                ?: throw GradleException("sherpa-native.json: every item in \"libraries\" must be an object")
            SherpaNativeLibraryEntry(
                abi = obj.string("abi"),
                file = obj.string("file"),
                archivePath = obj.string("archivePath"),
                sha256 = obj.string("sha256"),
                sizeBytes = obj.number("sizeBytes").toLong(),
            )
        }
        Manifest(version = version, archiveUrl = archiveUrl, libraries = libraries)
    }
}

/**
 * The real fetch/verify/extract logic (R-1001), free of every Gradle type so it is directly
 * unit-testable (see this file's own test class). Mirrors [BundledAssetFetcher]'s shape: cache
 * the download once (keyed by URL, under [cacheDir]), verify every extracted file's sha256 against
 * [libraries], and fail loudly — naming the abi and file, or the missing archive path — on any
 * mismatch. There is no allow-missing escape hatch (see [FetchSherpaNativeTask]'s own KDoc for
 * why): every failure here throws [GradleException] unconditionally.
 */
object SherpaNativeFetcher {

    data class ResolvedLibrary(val entry: SherpaNativeLibraryEntry, val destination: File)

    fun fetchAll(
        libraries: List<SherpaNativeLibraryEntry>,
        archiveUrl: String,
        cacheDir: File,
        outDir: File,
        onInfo: (String) -> Unit = {},
    ): List<ResolvedLibrary> {
        outDir.mkdirs()
        val archiveFile = cachedArchive(archiveUrl, cacheDir, onInfo)
        val remaining = libraries.associateBy { it.archivePath }.toMutableMap()
        val resolved = mutableListOf<ResolvedLibrary>()

        BZip2CompressorInputStream(archiveFile.inputStream().buffered()).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                while (true) {
                    val tarEntry = tar.nextEntry ?: break
                    if (tarEntry.isDirectory) continue
                    // Upstream's own archive stores entries as "./jniLibs/<abi>/<file>" (a leading
                    // "./" from however they created the tarball) — normalize it away so
                    // sherpa-native.json's archivePath stays the clean, portable path a human
                    // would actually write, rather than baking in one tool's own tar-creation
                    // artifact.
                    val normalizedName = tarEntry.name.removePrefix("./")
                    val declared = remaining.remove(normalizedName) ?: continue
                    val bytes = tar.readBytes()
                    val actualSha256 = sha256Of(bytes)
                    if (actualSha256 != declared.sha256) {
                        throw GradleException(
                            "fetchSherpaNativeLibraries: checksum mismatch for ${declared.abi}/${declared.file} " +
                                "(archive entry ${tarEntry.name}, from $archiveUrl) — expected ${declared.sha256}, " +
                                "got $actualSha256.",
                        )
                    }
                    val destFile = File(outDir, "${declared.abi}/${declared.file}")
                    destFile.parentFile?.mkdirs()
                    destFile.writeBytes(bytes)
                    resolved += ResolvedLibrary(declared, destFile)
                }
            }
        }

        if (remaining.isNotEmpty()) {
            val missingPaths = remaining.values.joinToString(", ") { "${it.abi}/${it.file} (${it.archivePath})" }
            throw GradleException(
                "fetchSherpaNativeLibraries: archive $archiveUrl did not contain: $missingPaths",
            )
        }
        return resolved
    }

    private fun cachedArchive(archiveUrl: String, cacheDir: File, onInfo: (String) -> Unit): File {
        cacheDir.mkdirs()
        val cachedFile = File(cacheDir, fileNameFromUrl(archiveUrl))
        if (!cachedFile.isFile) {
            downloadTo(archiveUrl, cachedFile)
            onInfo("fetchSherpaNativeLibraries: downloaded $archiveUrl to ${cachedFile.path}")
        }
        return cachedFile
    }

    private fun fileNameFromUrl(url: String): String = url.substringAfterLast('/').substringBefore('?')

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun downloadTo(url: String, dest: File) {
        dest.parentFile?.mkdirs()
        try {
            val connection = URI(url).toURL().openConnection()
            if (connection is HttpURLConnection) {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                val code = connection.responseCode
                if (code !in HTTP_OK_RANGE) {
                    throw GradleException("fetchSherpaNativeLibraries: download failed for $url — HTTP $code")
                }
            }
            connection.getInputStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
        } catch (e: java.io.IOException) {
            dest.delete()
            throw GradleException("fetchSherpaNativeLibraries: download failed for $url — ${e.message}", e)
        }
    }

    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 180_000
    private val HTTP_OK_RANGE = 200..299
}

/**
 * The minimal, dependency-free JSON reader `sherpa-native.json` needs — the identical grammar
 * [BundledAssetManifest]'s own private `Json` object implements, duplicated rather than shared
 * because that one is private to its file (see this project's own precedent: two small,
 * independent manifests, two small, independent parsers, no new shared internal API surface for
 * one another to accidentally depend on).
 */
private object SherpaNativeJson {
    sealed interface JValue
    data class JObject(val fields: Map<String, JValue>) : JValue
    data class JArray(val items: List<JValue>) : JValue
    data class JString(val value: String) : JValue
    data class JNumber(val value: Double) : JValue
    data class JBool(val value: Boolean) : JValue
    object JNull : JValue

    fun JObject.string(key: String): String =
        (fields[key] as? JString)?.value
            ?: throw GradleException("sherpa-native.json: missing or non-string field \"$key\"")

    fun JObject.number(key: String): Double =
        (fields[key] as? JNumber)?.value
            ?: throw GradleException("sherpa-native.json: missing or non-numeric field \"$key\"")

    fun parse(text: String): JValue = Parser(text).parseValue()

    private class Parser(private val text: String) {
        var pos = 0

        fun parseValue(): JValue {
            skipWs()
            return when (text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JString(parseRawString())
                't' -> { expect("true"); JBool(true) }
                'f' -> { expect("false"); JBool(false) }
                'n' -> { expect("null"); JNull }
                else -> parseNumber()
            }
        }

        private fun parseObject(): JObject {
            expectChar('{')
            val fields = LinkedHashMap<String, JValue>()
            skipWs()
            if (peek() == '}') { pos++; return JObject(fields) }
            while (true) {
                skipWs()
                val key = parseRawString()
                skipWs()
                expectChar(':')
                val value = parseValue()
                fields[key] = value
                skipWs()
                when (peek()) {
                    ',' -> { pos++; continue }
                    '}' -> { pos++; break }
                    else -> throw GradleException("sherpa-native.json: malformed object at offset $pos")
                }
            }
            return JObject(fields)
        }

        private fun parseArray(): JArray {
            expectChar('[')
            val items = mutableListOf<JValue>()
            skipWs()
            if (peek() == ']') { pos++; return JArray(items) }
            while (true) {
                items += parseValue()
                skipWs()
                when (peek()) {
                    ',' -> { pos++; continue }
                    ']' -> { pos++; break }
                    else -> throw GradleException("sherpa-native.json: malformed array at offset $pos")
                }
            }
            return JArray(items)
        }

        private fun parseRawString(): String {
            expectChar('"')
            val sb = StringBuilder()
            while (true) {
                val c = text[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val esc = text[pos++]
                        sb.append(
                            when (esc) {
                                '"' -> '"'
                                '\\' -> '\\'
                                '/' -> '/'
                                'n' -> '\n'
                                't' -> '\t'
                                'r' -> '\r'
                                'b' -> '\b'
                                'u' -> {
                                    val hex = text.substring(pos, pos + 4)
                                    pos += 4
                                    hex.toInt(16).toChar()
                                }
                                else -> throw GradleException("sherpa-native.json: bad escape \\$esc")
                            },
                        )
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): JNumber {
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] in "+-.eE")) pos++
            return JNumber(text.substring(start, pos).toDouble())
        }

        private fun expect(literal: String) {
            if (!text.startsWith(literal, pos)) {
                throw GradleException("sherpa-native.json: expected \"$literal\" at offset $pos")
            }
            pos += literal.length
        }

        private fun expectChar(expected: Char) {
            skipWs()
            if (peek() != expected) {
                throw GradleException("sherpa-native.json: expected '$expected' at offset $pos")
            }
            pos++
        }

        private fun peek(): Char = text[pos]

        fun skipWs() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }
}
