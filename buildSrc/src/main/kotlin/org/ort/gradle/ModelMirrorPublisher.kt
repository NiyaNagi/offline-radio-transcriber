package org.ort.gradle

import java.io.File

/**
 * D44 (FR-AST-14): the pure decision logic behind [PublishModelMirrorTask] — which manifest
 * entries need uploading to the `models-v1` GitHub Release, which are already there with matching
 * bytes, and which have no locally cached file to upload at all. Kept free of any Gradle or
 * process-execution type so it is directly unit-testable — same split [FetchBundledAssetsTask]/
 * [BundledAssetFetcher] already established for the build-time fetch side of this same manifest.
 *
 * **Idempotent by name and size, not by re-hashing the remote asset.** `gh release view --json
 * assets` exposes a remote asset's name and byte size, never its own checksum — matching name and
 * size is treated as "already present, skip". This cannot catch a genuine collision (same name,
 * same size, different bytes at the remote end) — nothing short of re-downloading and re-hashing
 * every asset on every run could, and that would defeat the point of an idempotent, cheap re-run.
 * What it does guarantee: the *local* file this task would upload is never eligible unless
 * [FetchBundledAssetsTask] already verified it against the manifest's own pinned sha256 — a
 * corrupt local file never reaches this decision in the first place.
 */
object ModelMirrorPublisher {

    data class UploadAction(val entry: BundledAssetManifest.Entry, val file: File)

    data class Plan(
        val toUpload: List<UploadAction>,
        val skipped: List<BundledAssetManifest.Entry>,
        val missingLocally: List<BundledAssetManifest.Entry>,
    )

    /** The exact filename an entry is published under at the mirror — the basename of
     * [BundledAssetManifest.Entry.mirrorUrl], which the committed manifest always keeps equal to
     * the build-time [BundledAssetManifest.Entry.url]'s own basename
     * ([BundledAssetManifestMirrorFieldsTest] enforces this), so the file
     * [FetchBundledAssetsTask]'s cache already holds needs no rename to become the mirror's own
     * asset name. */
    fun mirrorAssetName(entry: BundledAssetManifest.Entry): String =
        entry.mirrorUrl.substringAfterLast('/').substringBefore('?')

    /**
     * Decides, for every [entries], whether it is already at the mirror ([Plan.skipped]), needs
     * uploading from a real cached file ([Plan.toUpload]), or has no cached file to upload at all
     * ([Plan.missingLocally] — [PublishModelMirrorTask] fails loudly on this rather than silently
     * publishing an incomplete mirror).
     */
    fun plan(
        entries: List<BundledAssetManifest.Entry>,
        cacheRoot: File,
        existingAssets: Map<String, Long>,
    ): Plan {
        val toUpload = mutableListOf<UploadAction>()
        val skipped = mutableListOf<BundledAssetManifest.Entry>()
        val missingLocally = mutableListOf<BundledAssetManifest.Entry>()

        for (entry in entries) {
            val existingSize = existingAssets[mirrorAssetName(entry)]
            if (existingSize != null && existingSize == entry.sizeBytes) {
                skipped += entry
                continue
            }
            val cached = cachedFileFor(entry, cacheRoot)
            if (cached == null) {
                missingLocally += entry
            } else {
                toUpload += UploadAction(entry, cached)
            }
        }
        return Plan(toUpload, skipped, missingLocally)
    }

    /** Mirrors [BundledAssetFetcher]'s own cache-key rule exactly (pinned sha256, or the entry id
     * for a `trust-on-first-fetch` entry) — this reads the same cache [FetchBundledAssetsTask]
     * already populated, never triggers a second download of its own. */
    private fun cachedFileFor(entry: BundledAssetManifest.Entry, cacheRoot: File): File? {
        val cacheKey = entry.sha256.takeIf { it != BundledAssetManifest.TRUST_ON_FIRST_FETCH } ?: entry.id
        val dir = File(cacheRoot, cacheKey)
        if (!dir.isDirectory) return null
        return dir.listFiles()?.firstOrNull { it.isFile }
    }

    /**
     * Parses the narrow shape `gh release view <tag> --json assets` prints:
     * `{"assets": [{"name": "...", "size": N, ...other fields, in no guaranteed order...}, ...]}`.
     * Any other field on each asset object is ignored; an asset object missing either field is
     * skipped rather than failing the whole parse (an unrecognised or partial `gh` schema should
     * degrade to "treat as not present, re-upload" — the safe direction — never abort the release).
     * Returns an empty map for a release that does not exist yet, or one with no assets at all —
     * [PublishModelMirrorTask] callers pass empty output for a non-zero `gh` exit code, so this
     * never has to distinguish "no release" from "empty release" itself.
     */
    fun parseExistingAssets(ghReleaseViewJson: String): Map<String, Long> {
        val root = runCatching { BundledAssetManifest.Json.parse(ghReleaseViewJson) }.getOrNull()
            as? BundledAssetManifest.Json.JObject ?: return emptyMap()
        val assets = root.fields["assets"] as? BundledAssetManifest.Json.JArray ?: return emptyMap()
        return assets.items.mapNotNull { item ->
            val obj = item as? BundledAssetManifest.Json.JObject ?: return@mapNotNull null
            val name = (obj.fields["name"] as? BundledAssetManifest.Json.JString)?.value ?: return@mapNotNull null
            val size = (obj.fields["size"] as? BundledAssetManifest.Json.JNumber)?.value?.toLong()
                ?: return@mapNotNull null
            name to size
        }.toMap()
    }
}
