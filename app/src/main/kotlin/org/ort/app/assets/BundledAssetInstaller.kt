package org.ort.app.assets

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * WPG (`spec/e2e-capture-modes-plan.md`, FR-AST-3/3a/3b, AC-136/AC-137, D35/D36): copies every
 * asset `buildSrc`'s `FetchBundledAssetsTask` packaged into `app/src/main/assets/bundled/` to the
 * exact private-storage path the existing locators read
 * ([org.ort.pipeline.passb.AsrModelLocator], [org.ort.pipeline.capture.SileroVadLocator]), and
 * verifies each one's sha256 **again after copying** — FR-AST-3b: shipping inside the APK
 * establishes *provenance*, never *integrity*, so this runs the identical check
 * [org.ort.net.ModelAcquisition] runs for a downloaded or side-loaded file. It writes the same
 * `<destination>.sha256` marker `ModelAcquisition` writes, so
 * [org.ort.app.ui.data.ModelsController]'s `rowFor` reports `INSTALLED` through the one existing
 * code path — a bundled asset is not a second kind of "installed".
 *
 * **Never activates a corrupt file (AC-137).** A copy is written to a `.part` file first,
 * hashed, and only renamed over the real destination on a match; on a mismatch the `.part` file is
 * discarded and whatever was previously at the destination — including nothing, on a fresh
 * install — is left exactly as it was. No marker is ever written for a failed copy.
 *
 * **No network object of any kind is constructed anywhere in this file** (AC-136) — every byte
 * comes from [BundledAssetSource], which reads the APK's own assets.
 */
public sealed interface BundledAssetState {
    public val id: String

    /** [destination] is the real, private-storage file the existing locators now find. */
    public data class Installed(override val id: String, public val destination: File) : BundledAssetState

    /** A stated, recoverable failure (constitution I) — never silently retried, never activated.
     * [reinstall] is the real recovery path a caller (Settings, a debug scenario) can call. */
    public data class Failed(override val id: String, public val reason: String) : BundledAssetState

    /** This exact build's `fetchBundledAssets` ran with the local-development escape hatch and
     * could not fetch [id] — an honest absence, distinct from [Failed] (nothing was corrupt; this
     * build simply does not carry the asset). Never produced by a CI-built or release artifact. */
    public data class NotBundledInThisBuild(override val id: String, public val reason: String) : BundledAssetState
}

/**
 * Where [BundledAssetInstaller] reads bytes from — real Android assets in production
 * ([AndroidBundledAssetSource]), an in-memory map in tests. The seam constitution II asks every
 * model-bearing interface to carry: a fake here can simulate a missing manifest, a missing asset
 * file, or bytes that do not match their recorded digest (AC-137's corruption case) with no
 * `AssetManager`/`Context` at all.
 */
public interface BundledAssetSource {
    /** @throws IOException when [assetPath] does not exist in this source. */
    public fun open(assetPath: String): InputStream
}

/** The real source — `app/src/main/assets/<assetPath>` via [Context.getAssets]. */
public class AndroidBundledAssetSource(private val context: Context) : BundledAssetSource {
    override fun open(assetPath: String): InputStream = context.applicationContext.assets.open(assetPath)
}

/** The behavioural fake (constitution II) — [files] maps an asset path to its exact bytes; an
 * absent entry throws the same [IOException] shape [AndroidBundledAssetSource] would for a
 * missing asset, so a test can simulate "this build has no manifest" or "this build never
 * packaged this one file" without touching Android at all. */
public class FakeBundledAssetSource(private val files: Map<String, ByteArray>) : BundledAssetSource {
    override fun open(assetPath: String): InputStream =
        files[assetPath]?.inputStream() ?: throw IOException("no such bundled asset: $assetPath")
}

public object BundledAssetInstaller {

    private const val MANIFEST_ASSET_PATH = "bundled/manifest.json"

    /**
     * Installs (or re-verifies) every asset named in this build's `bundled/manifest.json` into
     * [filesDir]. Idempotent: an asset already installed with a marker matching its manifest
     * sha256 is reported [BundledAssetState.Installed] without being re-copied — the ordinary
     * "every launch, not just the first" case this object's own call site
     * ([org.ort.app.OrtApplication]) relies on so a reinstalled app or a cleared destination is
     * repaired without needing a special "first launch" flag.
     */
    public fun installAll(filesDir: File, source: BundledAssetSource): List<BundledAssetState> {
        val manifestText = try {
            source.open(MANIFEST_ASSET_PATH).use { it.readBytes().decodeToString() }
        } catch (e: IOException) {
            return listOf(
                BundledAssetState.Failed(
                    id = "*",
                    reason = "no bundled asset manifest found in this build — nothing was bundled: ${e.message}",
                ),
            )
        }
        val results = parseManifest(manifestText).map { entry -> installOne(entry, filesDir, source) }
        val installedDestinations = results.filterIsInstance<BundledAssetState.Installed>().map { it.destination }
        recordBundledDestinations(filesDir, installedDestinations)
        return results
    }

    /** Retries exactly one asset from the bundle — the real recovery action behind a `Retry`
     * control on whatever surfaces [BundledAssetState.Failed] (WPE/WPF's concern, not this file's;
     * this is the call those screens make). */
    public fun reinstall(id: String, filesDir: File, source: BundledAssetSource): BundledAssetState {
        val manifestText = try {
            source.open(MANIFEST_ASSET_PATH).use { it.readBytes().decodeToString() }
        } catch (e: IOException) {
            return BundledAssetState.Failed(id, "no bundled asset manifest found in this build: ${e.message}")
        }
        val entry = parseManifest(manifestText).firstOrNull { it.id == id }
            ?: return BundledAssetState.Failed(id, "no such bundled asset in this build's manifest")
        val result = installOne(entry, filesDir, source)
        if (result is BundledAssetState.Installed) recordBundledDestinations(filesDir, listOf(result.destination))
        return result
    }

    /**
     * WPG (FR-AST-3a, AC-139): records every verified bundled destination, relative to [filesDir],
     * in [BUNDLED_ASSETS_MANIFEST_FILENAME] — the filesystem contract
     * [org.ort.pipeline.capture.measureStorageAccounting] reads (that function's own KDoc explains
     * why a plain file rather than a shared type: `:pipeline` has no compile dependency on
     * `:app`). Unions with whatever the file already lists rather than overwriting, so a later
     * [reinstall] of one asset never drops the rest; sorted for a byte-identical file across runs
     * (constitution: "generated files MUST be byte-identical wherever they are generated").
     */
    private fun recordBundledDestinations(filesDir: File, newlyInstalled: List<File>) {
        if (newlyInstalled.isEmpty()) return
        val manifestFile = File(filesDir, BUNDLED_ASSETS_MANIFEST_FILENAME)
        val existing = if (manifestFile.isFile) {
            manifestFile.readLines().map { it.trim() }.filterTo(mutableSetOf()) { it.isNotEmpty() }
        } else {
            mutableSetOf()
        }
        newlyInstalled.forEach { existing += it.relativeTo(filesDir).invariantSeparatorsPath }
        manifestFile.writeText(existing.sorted().joinToString("\n") + "\n")
    }

    private const val BUNDLED_ASSETS_MANIFEST_FILENAME = "bundled_assets.manifest"

    private fun installOne(entry: ManifestEntry, filesDir: File, source: BundledAssetSource): BundledAssetState {
        if (entry.missing) {
            return BundledAssetState.NotBundledInThisBuild(
                entry.id,
                "this build was packaged with the local-only allow-missing escape hatch and does not carry " +
                    "${entry.id} — never expected from a release or CI-built artifact",
            )
        }

        val destination = File(filesDir, entry.destination)
        val marker = markerFile(destination)
        if (destination.isFile && marker.isFile && marker.readText() == entry.sha256) {
            return BundledAssetState.Installed(entry.id, destination)
        }

        val part = File(destination.parentFile, destination.name + ".part")
        return try {
            destination.parentFile?.mkdirs()
            source.open("bundled/${entry.destination}").use { input ->
                part.outputStream().use { output -> input.copyTo(output) }
            }
            val gotSha256 = sha256Of(part)
            if (gotSha256 != entry.sha256) {
                part.delete()
                BundledAssetState.Failed(
                    entry.id,
                    "bundled copy of ${entry.id} failed integrity verification after copying " +
                        "(expected ${entry.sha256.take(CHECKSUM_PREFIX_LENGTH)}..., got " +
                        "${gotSha256.take(CHECKSUM_PREFIX_LENGTH)}...) — not activated (AC-137)",
                )
            } else {
                if (!part.renameTo(destination)) {
                    part.copyTo(destination, overwrite = true)
                    part.delete()
                }
                marker.writeText(entry.sha256)
                BundledAssetState.Installed(entry.id, destination)
            }
        } catch (e: IOException) {
            part.delete()
            BundledAssetState.Failed(entry.id, "could not read the bundled copy of ${entry.id}: ${e.message}")
        }
    }

    private fun markerFile(destination: File): File = File(destination.parentFile, destination.name + ".sha256")

    private fun sha256Of(file: File): String {
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

    private const val CHECKSUM_PREFIX_LENGTH = 8

    private data class ManifestEntry(val id: String, val destination: String, val sha256: String, val missing: Boolean)

    /** The minimal, dependency-free JSON reader for `bundled/manifest.json`'s own flat shape
     * (produced by buildSrc's `BundledAssetManifest.renderResolved` — see that function's own
     * KDoc). Duplicated from buildSrc's identical parser rather than shared: buildSrc is a
     * separate Gradle build with no classpath in common with `:app`'s runtime code. */
    private fun parseManifest(text: String): List<ManifestEntry> {
        val root = requireJsonObject(Json.parse(text), "expected a top-level JSON object")
        val assets = requireJsonArray(root.fields["assets"], "expected an \"assets\" array")
        return assets.items.map { item ->
            val obj = requireJsonObject(item, "every item in \"assets\" must be an object")
            ManifestEntry(
                id = obj.requiredString("id"),
                destination = obj.requiredString("destination"),
                sha256 = obj.requiredString("sha256"),
                missing = (obj.fields["missing"] as? Json.JBool)?.value ?: false,
            )
        }
    }

    private fun requireJsonObject(value: Json.JValue?, message: String): Json.JObject =
        value as? Json.JObject ?: throw IOException("bundled/manifest.json: $message")

    private fun requireJsonArray(value: Json.JValue?, message: String): Json.JArray =
        value as? Json.JArray ?: throw IOException("bundled/manifest.json: $message")

    private fun Json.JObject.requiredString(key: String): String = (fields[key] as? Json.JString)?.value
        ?: throw IOException("bundled/manifest.json: entry missing \"$key\"")

    private object Json {
        sealed interface JValue
        data class JObject(val fields: Map<String, JValue>) : JValue
        data class JArray(val items: List<JValue>) : JValue
        data class JString(val value: String) : JValue
        data class JNumber(val value: Double) : JValue
        data class JBool(val value: Boolean) : JValue
        object JNull : JValue

        fun parse(text: String): JValue = Parser(text).parseValue()

        private class Parser(private val text: String) {
            var pos = 0

            fun parseValue(): JValue {
                skipWs()
                return when (text[pos]) {
                    '{' -> parseObject()
                    '[' -> parseArray()
                    '"' -> JString(parseRawString())
                    't' -> {
                        expect("true")
                        JBool(true)
                    }
                    'f' -> {
                        expect("false")
                        JBool(false)
                    }
                    'n' -> {
                        expect("null")
                        JNull
                    }
                    else -> parseNumber()
                }
            }

            private fun parseObject(): JObject {
                expectChar('{')
                val fields = LinkedHashMap<String, JValue>()
                skipWs()
                var more = peek() != '}'
                if (!more) pos++
                while (more) {
                    skipWs()
                    val key = parseRawString()
                    skipWs()
                    expectChar(':')
                    fields[key] = parseValue()
                    skipWs()
                    more = when (peek()) {
                        ',' -> {
                            pos++
                            true
                        }
                        '}' -> {
                            pos++
                            false
                        }
                        else -> throw IOException("bundled/manifest.json: malformed object at offset $pos")
                    }
                }
                return JObject(fields)
            }

            private fun parseArray(): JArray {
                expectChar('[')
                val items = mutableListOf<JValue>()
                skipWs()
                var more = peek() != ']'
                if (!more) pos++
                while (more) {
                    items += parseValue()
                    skipWs()
                    more = when (peek()) {
                        ',' -> {
                            pos++
                            true
                        }
                        ']' -> {
                            pos++
                            false
                        }
                        else -> throw IOException("bundled/manifest.json: malformed array at offset $pos")
                    }
                }
                return JArray(items)
            }

            private fun parseRawString(): String {
                expectChar('"')
                val sb = StringBuilder()
                while (true) {
                    when (val c = text[pos++]) {
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
                                    else -> throw IOException("bundled/manifest.json: bad escape \\$esc")
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
                    throw IOException("bundled/manifest.json: expected \"$literal\" at offset $pos")
                }
                pos += literal.length
            }

            private fun expectChar(expected: Char) {
                skipWs()
                if (peek() != expected) {
                    throw IOException("bundled/manifest.json: expected '$expected' at offset $pos")
                }
                pos++
            }

            private fun peek(): Char = text[pos]

            fun skipWs() {
                while (pos < text.length && text[pos].isWhitespace()) pos++
            }
        }
    }
}
