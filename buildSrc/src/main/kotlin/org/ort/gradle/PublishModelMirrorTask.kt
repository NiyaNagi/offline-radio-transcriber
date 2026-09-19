package org.ort.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * D44 (FR-AST-14): uploads every bundled asset [FetchBundledAssetsTask] has already fetched and
 * verified to a GitHub Release tagged [releaseTag] (`models-v1`, the default) on this project's
 * own repository — the mirror the `play` variant's setup MODELS step downloads from
 * (FR-AST-10..13). Wired only from `.github/workflows/release.yml`, on a release tag, never from
 * the plain build/check gate: publishing a release asset is a release-time action, not something
 * `./gradlew build` should ever do as a side effect.
 *
 * **Idempotent (D44):** an asset already present at the release under the same name and byte size
 * is skipped, never re-uploaded — see [ModelMirrorPublisher]'s own KDoc for exactly what
 * "matching" means and why that is the right (and only affordable) definition. Every decision —
 * what to skip, upload or report missing — lives there, pure and unit-tested; this class only
 * shells out to the real `gh` CLI (already a dependency of `release.yml`'s own steps) for the two
 * operations that genuinely need a live GitHub Release to talk to: listing what is already there,
 * and uploading a file that is not. **Never reads or prints `HF_TOKEN`** — this task does not
 * touch it at all; `gh` authenticates however the workflow already has it authenticate
 * (`GH_TOKEN`/`GITHUB_TOKEN`, an ordinary repository token, never the HuggingFace one
 * [FetchBundledAssetsTask] reads for the gated Gemma model).
 */
abstract class PublishModelMirrorTask : DefaultTask() {

    /** The root `bundled-assets.json` — read-only here; this task never rewrites it (unlike
     * [FetchBundledAssetsTask]'s own trust-on-first-fetch pinning). */
    @get:InputFile
    abstract val manifestFile: RegularFileProperty

    /** `$GRADLE_USER_HOME/ort-bundled-assets/` — the same cache [FetchBundledAssetsTask] already
     * populated in the same build invocation. [Internal], not a declared input: this task reacts
     * to whatever is already verified there: it is not itself invalidated by every cache write. */
    @get:Internal
    abstract val cacheRoot: DirectoryProperty

    @get:Input
    abstract val releaseTag: Property<String>

    @get:Inject
    abstract val execOperations: ExecOperations

    init {
        releaseTag.convention("models-v1")
    }

    @TaskAction
    fun publish() {
        val entries = BundledAssetManifest.parse(manifestFile.get().asFile.readText())
        val tag = releaseTag.get()
        val existing = listExistingAssets(tag)
        val plan = ModelMirrorPublisher.plan(entries, cacheRoot.get().asFile, existing)

        plan.skipped.forEach {
            logger.lifecycle("publishModelMirror: ${it.id} already present at $tag with a matching size — skipped.")
        }
        plan.missingLocally.forEach {
            logger.warn(
                "publishModelMirror: ${it.id} has no verified cached file to upload — fetchBundledAssets must " +
                    "fetch this exact entry before the mirror can carry it.",
            )
        }
        plan.toUpload.forEach { action ->
            val assetName = ModelMirrorPublisher.mirrorAssetName(action.entry)
            logger.lifecycle(
                "publishModelMirror: uploading ${action.entry.id} ($assetName, ${action.file.length()} bytes) " +
                    "to $tag...",
            )
            uploadFile(tag, action.file, assetName)
        }

        logger.lifecycle(
            "publishModelMirror: $tag — ${plan.toUpload.size} uploaded, ${plan.skipped.size} already present, " +
                "${plan.missingLocally.size} missing locally.",
        )
        if (plan.missingLocally.isNotEmpty()) {
            throw GradleException(
                "publishModelMirror: ${plan.missingLocally.size} " +
                    (if (plan.missingLocally.size == 1) "entry is" else "entries are") +
                    " missing from the local fetch cache — run fetchBundledAssets before this task: " +
                    plan.missingLocally.joinToString(", ") { it.id },
            )
        }
    }

    private fun listExistingAssets(tag: String): Map<String, Long> {
        val output = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine("gh", "release", "view", tag, "--json", "assets")
            standardOutput = output
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) return emptyMap()
        return ModelMirrorPublisher.parseExistingAssets(output.toString(Charsets.UTF_8))
    }

    /** `gh release upload` names the uploaded asset after the local file's own basename — the
     * cache's on-disk name already matches [assetName] for every entry the committed manifest
     * carries ([BundledAssetManifestMirrorFieldsTest]), but this copies to a correctly-named
     * temp-adjacent file first regardless, so a future manifest edit where they diverge fails
     * loudly at upload time rather than silently publishing an asset under the wrong name. */
    private fun uploadFile(tag: String, file: File, assetName: String) {
        val uploadable = if (file.name == assetName) {
            file
        } else {
            File(file.parentFile, assetName).also { file.copyTo(it, overwrite = true) }
        }
        execOperations.exec {
            commandLine("gh", "release", "upload", tag, uploadable.absolutePath, "--clobber")
        }
    }
}
