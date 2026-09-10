package org.ort.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * WPG (`spec/e2e-capture-modes-plan.md`, FR-AST-3/3a/3b, D35/D36): fetches every asset named in
 * the root `bundled-assets.json` manifest **once**, into a cache shared across worktrees
 * (`$GRADLE_USER_HOME/ort-bundled-assets/`, never `build/` — these are hundreds of megabytes and
 * a fresh worktree must not re-download them), verifies each against its pinned sha256, and
 * copies the verified bytes into `app/src/main/assets/bundled/` (gitignored — the manifest, not
 * the binaries, is the source-controlled fact) alongside a generated `bundled/manifest.json` the
 * app's own `org.ort.app.assets.BundledAssetInstaller` reads at first launch.
 *
 * This task is a thin Gradle wrapper — every real decision lives in [BundledAssetFetcher], a plain
 * class with no Gradle types in its signature, so it can be unit-tested directly (matching this
 * package's own precedent: [PlatformGuardsTask]/[DependencyRulesTask] wrap [PlatformGuards]/
 * [ModuleGraph], and only those pure objects carry tests). See [BundledAssetFetcher]'s own KDoc for
 * the failure/escape-hatch/trust-on-first-fetch behaviour this task exposes.
 */
abstract class FetchBundledAssetsTask : DefaultTask() {

    /** The root `bundled-assets.json` — also this task's own output when a `trust-on-first-fetch`
     * entry gets pinned for the first time, see [BundledAssetFetcher]'s own KDoc. */
    @get:InputFile
    abstract val manifestFile: RegularFileProperty

    /** `app/src/main/assets/bundled/` — gitignored; recreated by every run of this task. */
    @get:OutputDirectory
    abstract val assetsOutputDir: DirectoryProperty

    /** `$GRADLE_USER_HOME/ort-bundled-assets/` — shared across worktrees, never under `build/`.
     * [Internal] rather than a declared input: its *contents* are a cache this task manages
     * itself — the manifest's own sha256 values are what verification actually checks against. */
    @get:Internal
    abstract val cacheRoot: DirectoryProperty

    /** `HF_TOKEN` from the environment — absent (blank/unset) fails the build for any gated entry
     * unless [allowMissingBundledAssets] is set. */
    @get:Input
    @get:Optional
    abstract val hfToken: Property<String>

    /** The one local-development escape hatch (`-PortAllowMissingBundledAssets=true` or
     * `ORT_ALLOW_MISSING_BUNDLED_ASSETS=1`) — see [BundledAssetFetcher]'s own KDoc. Never set by
     * CI: the workflow files under `.github/workflows` set `HF_TOKEN` instead and let a missing
     * token fail loudly. */
    @get:Input
    abstract val allowMissingBundledAssets: Property<Boolean>

    @TaskAction
    fun fetch() {
        val manifestOnDisk = manifestFile.get().asFile
        val entries = BundledAssetManifest.parse(manifestOnDisk.readText())
        val outDir = assetsOutputDir.get().asFile

        val resolved = BundledAssetFetcher.fetchAll(
            entries = entries,
            cacheDir = cacheRoot.get().asFile,
            outDir = outDir,
            manifestFile = manifestOnDisk,
            hfToken = hfToken.orNull?.takeIf { it.isNotBlank() },
            allowMissing = allowMissingBundledAssets.get(),
            onInfo = { logger.lifecycle(it) },
            onWarn = { logger.warn(it) },
        )

        File(outDir, "manifest.json").writeText(BundledAssetManifest.renderResolved(resolved))
        val missingCount = resolved.count { it.missing }
        logger.lifecycle(
            "fetchBundledAssets: ${resolved.size - missingCount}/${resolved.size} assets verified and packaged " +
                "under ${outDir.path}" + if (missingCount > 0) " ($missingCount marked missing)" else "",
        )
    }
}

/**
 * The real fetch/verify/cache/pin logic (WPG), deliberately free of every Gradle type so it is
 * directly unit-testable (see this file's own test class).
 *
 * **Failure is loud by default (FR-AST-2).** A digest mismatch or a download failure fails —
 * throws [GradleException] naming the exact file — unless [allowMissing] is set, in which case
 * that one entry is packaged as absent (`missing = true` in the returned [BundledAssetManifest.ResolvedEntry]
 * and in the generated manifest) with a loud [onWarn] call, and every other entry still resolves
 * normally. A gated entry ([BundledAssetManifest.Entry.gated]) with no [hfToken] fails with exactly
 * one line telling the developer what to do — never silently skipped, with or without the escape
 * hatch's usual leniency for network/verification failures (a missing token is a configuration
 * problem the escape hatch still surfaces as a `missing` entry, never a build that pretends the
 * asset arrived).
 *
 * **`trust-on-first-fetch`** (see `tiny.en-tokens.txt`'s own history in the committed manifest): an
 * entry whose `sha256` field is the literal [BundledAssetManifest.TRUST_ON_FIRST_FETCH] has no
 * published digest anywhere to verify against. On such an entry this fetches the file, computes
 * its real sha256, and **rewrites [manifestFile] in place**, replacing the sentinel with that real
 * value — every later build then verifies against a real, pinned digest. The manifest is a source
 * file the developer commits; the fetched bytes are not.
 */
object BundledAssetFetcher {

    fun fetchAll(
        entries: List<BundledAssetManifest.Entry>,
        cacheDir: File,
        outDir: File,
        manifestFile: File,
        hfToken: String?,
        allowMissing: Boolean,
        onInfo: (String) -> Unit = {},
        onWarn: (String) -> Unit = {},
    ): List<BundledAssetManifest.ResolvedEntry> {
        outDir.mkdirs()
        return entries.map { entry ->
            try {
                fetchOne(entry, cacheDir, outDir, manifestFile, hfToken, onInfo)
            } catch (failure: GradleException) {
                if (!allowMissing) throw failure
                onWarn(
                    "fetchBundledAssets: WARNING — ${entry.id} could not be fetched (${failure.message}); " +
                        "packaging WITHOUT it because the local-only allow-missing escape hatch is set. " +
                        "This is not a complete offline install — never do this for a release build or in CI " +
                        "(FR-AST-3).",
                )
                BundledAssetManifest.ResolvedEntry(entry, sha256 = entry.sha256, missing = true)
            }
        }
    }

    private fun fetchOne(
        entry: BundledAssetManifest.Entry,
        cacheDir: File,
        outDir: File,
        manifestFile: File,
        hfToken: String?,
        onInfo: (String) -> Unit,
    ): BundledAssetManifest.ResolvedEntry {
        if (entry.gated && hfToken.isNullOrBlank()) {
            throw GradleException(
                "fetchBundledAssets: HF_TOKEN is required to fetch ${entry.id} (a gated model) — accept the " +
                    "licence for ${entry.url} on HuggingFace, then set the HF_TOKEN environment variable and " +
                    "rerun the build.",
            )
        }

        val pinned = entry.sha256.takeIf { it != BundledAssetManifest.TRUST_ON_FIRST_FETCH }
        val cacheKey = pinned ?: entry.id
        val entryCacheDir = File(cacheDir, cacheKey).apply { mkdirs() }
        val cachedFile = File(entryCacheDir, fileNameFromUrl(entry.url))

        val alreadyCached = cachedFile.isFile && (pinned == null || sha256Of(cachedFile) == pinned)
        if (!alreadyCached) {
            downloadTo(entry.url, cachedFile, if (entry.gated) hfToken else null)
        }

        val gotSha256 = sha256Of(cachedFile)
        if (pinned != null && gotSha256 != pinned) {
            cachedFile.delete()
            throw GradleException(
                "fetchBundledAssets: checksum mismatch for ${entry.id} (${cachedFile.name}, fetched from " +
                    "${entry.url}) — expected $pinned, got $gotSha256. The corrupt cached file has been removed.",
            )
        }

        if (pinned == null) {
            BundledAssetManifest.rewriteSha256(manifestFile, entry.id, gotSha256)
            onInfo(
                "fetchBundledAssets: ${entry.id} has no published digest — pinned its first-fetch sha256 " +
                    "($gotSha256) into ${manifestFile.name}. Commit this file.",
            )
        }

        val destFile = File(outDir, entry.destination)
        destFile.parentFile?.mkdirs()
        cachedFile.copyTo(destFile, overwrite = true)

        return BundledAssetManifest.ResolvedEntry(entry, sha256 = gotSha256, missing = false)
    }

    private fun fileNameFromUrl(url: String): String = url.substringAfterLast('/').substringBefore('?')

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

    private fun downloadTo(url: String, dest: File, bearerToken: String?) {
        dest.parentFile?.mkdirs()
        val connection = URI(url).toURL().openConnection()
        if (connection is HttpURLConnection) {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            if (!bearerToken.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            }
            val code = connection.responseCode
            if (code !in HTTP_OK_RANGE) {
                throw GradleException("fetchBundledAssets: download failed for $url — HTTP $code")
            }
        }
        try {
            connection.getInputStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
        } catch (e: java.io.IOException) {
            dest.delete()
            throw GradleException("fetchBundledAssets: download failed for $url — ${e.message}", e)
        }
    }

    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 180_000
    private val HTTP_OK_RANGE = 200..299
}

/**
 * The manifest model and its (hand-rolled, dependency-free — buildSrc carries no JSON library on
 * its own classpath) parser/renderer. [Json] below is a minimal, recursive-descent parser for
 * exactly the JSON grammar, nothing more — enough to read `bundled-assets.json`'s flat,
 * single-level-nested shape without pulling in a new dependency for one build-time file read.
 */
object BundledAssetManifest {

    const val TRUST_ON_FIRST_FETCH: String = "trust-on-first-fetch"

    data class Entry(
        val id: String,
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
        val destination: String,
        val tiers: List<String>,
        val licence: String,
        val gated: Boolean,
    )

    /** One entry as [BundledAssetFetcher] actually resolved it — [sha256] is the real, verified
     * digest (never the [TRUST_ON_FIRST_FETCH] sentinel, even when [entry]'s own field still was,
     * since a successful fetch always pins a real value); [missing] is true only under the escape
     * hatch. */
    data class ResolvedEntry(val entry: Entry, val sha256: String, val missing: Boolean)

    fun parse(manifestJson: String): List<Entry> {
        val root = Json.parse(manifestJson) as? Json.JObject
            ?: throw GradleException("bundled-assets.json: expected a top-level JSON object")
        val assets = root.fields["assets"] as? Json.JArray
            ?: throw GradleException("bundled-assets.json: expected an \"assets\" array")
        return assets.items.map { item ->
            val obj = item as? Json.JObject
                ?: throw GradleException("bundled-assets.json: every item in \"assets\" must be an object")
            with(Json) {
                Entry(
                    id = obj.string("id"),
                    url = obj.string("url"),
                    sha256 = obj.string("sha256"),
                    sizeBytes = obj.number("sizeBytes").toLong(),
                    destination = obj.string("destination"),
                    tiers = obj.stringArray("tiers"),
                    licence = obj.string("licence"),
                    gated = obj.bool("gated"),
                )
            }
        }
    }

    /**
     * Rewrites the `"sha256": "trust-on-first-fetch"` field for [id] in [manifestFile] to
     * [newSha256], in place, as a targeted textual substitution — deliberately not a full
     * parse-modify-reserialize round trip, which would reformat the whole file (including its
     * `note` commentary) on every run and make the resulting diff unreviewable. The manifest's own
     * hand-authored formatting survives; only the one field changes.
     */
    fun rewriteSha256(manifestFile: File, id: String, newSha256: String) {
        val text = manifestFile.readText()
        val idMarker = "\"id\": \"$id\""
        val idIndex = text.indexOf(idMarker)
        if (idIndex < 0) {
            throw GradleException("bundled-assets.json: no entry with id \"$id\" to pin a digest for")
        }
        val shaFieldMarker = "\"sha256\": \"$TRUST_ON_FIRST_FETCH\""
        val shaIndex = text.indexOf(shaFieldMarker, startIndex = idIndex)
        if (shaIndex < 0) {
            throw GradleException(
                "bundled-assets.json: entry \"$id\" does not carry the literal sha256 sentinel " +
                    "\"$TRUST_ON_FIRST_FETCH\" to replace — was it already pinned?",
            )
        }
        val replacement = "\"sha256\": \"$newSha256\""
        val updated = text.substring(0, shaIndex) + replacement +
            text.substring(shaIndex + shaFieldMarker.length)
        manifestFile.writeText(updated)
    }

    /** The generated `bundled/manifest.json` `org.ort.app.assets.BundledAssetInstaller` reads at
     * runtime — flat, no nested commentary, one object per line for a readable diff. */
    fun renderResolved(entries: List<ResolvedEntry>): String = buildString {
        append("{\n  \"assets\": [\n")
        entries.forEachIndexed { index, resolved ->
            val e = resolved.entry
            append("    {\n")
            append("      \"id\": ${jsonString(e.id)},\n")
            append("      \"destination\": ${jsonString(e.destination)},\n")
            append("      \"sha256\": ${jsonString(resolved.sha256)},\n")
            append("      \"sizeBytes\": ${e.sizeBytes},\n")
            append("      \"tiers\": [${e.tiers.joinToString(", ") { jsonString(it) }}],\n")
            append("      \"missing\": ${resolved.missing}\n")
            append("    }")
            append(if (index != entries.lastIndex) ",\n" else "\n")
        }
        append("  ]\n}\n")
    }

    private fun jsonString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** The minimal JSON reader — object/array/string/number/bool/null, nothing else, sufficient
     * for this manifest's own shape (see this file's class-level KDoc). */
    private object Json {
        sealed interface JValue
        data class JObject(val fields: Map<String, JValue>) : JValue
        data class JArray(val items: List<JValue>) : JValue
        data class JString(val value: String) : JValue
        data class JNumber(val value: Double) : JValue
        data class JBool(val value: Boolean) : JValue
        object JNull : JValue

        // Not private: called via `with(Json) { ... }` from BundledAssetManifest.parse, outside
        // this object's own body. Json itself stays private to this file, so these are never
        // over-exposed — see this file's class-level KDoc.
        fun JObject.string(key: String): String =
            (fields[key] as? JString)?.value
                ?: throw GradleException("bundled-assets.json: missing or non-string field \"$key\"")

        fun JObject.number(key: String): Double =
            (fields[key] as? JNumber)?.value
                ?: throw GradleException("bundled-assets.json: missing or non-numeric field \"$key\"")

        fun JObject.bool(key: String): Boolean =
            (fields[key] as? JBool)?.value
                ?: throw GradleException("bundled-assets.json: missing or non-boolean field \"$key\"")

        fun JObject.stringArray(key: String): List<String> =
            (fields[key] as? JArray)?.items?.map {
                (it as? JString)?.value ?: throw GradleException("bundled-assets.json: \"$key\" must be strings")
            } ?: throw GradleException("bundled-assets.json: missing or non-array field \"$key\"")

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
                        else -> throw GradleException("bundled-assets.json: malformed object at offset $pos")
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
                        else -> throw GradleException("bundled-assets.json: malformed array at offset $pos")
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
                                    else -> throw GradleException("bundled-assets.json: bad escape \\$esc")
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
                    throw GradleException("bundled-assets.json: expected \"$literal\" at offset $pos")
                }
                pos += literal.length
            }

            private fun expectChar(expected: Char) {
                skipWs()
                if (peek() != expected) {
                    throw GradleException("bundled-assets.json: expected '$expected' at offset $pos")
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
