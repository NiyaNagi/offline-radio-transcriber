package org.ort.core.assets

import java.io.File
import java.security.MessageDigest

/**
 * Register R-1052 (halt): a validator's fresh install, with real bundled models, died with
 * `SIGABRT` on first launch. `RealVadProvider`/`RealAsrEngineProvider` (`:pipeline`) checked only
 * that a model file *existed* before handing its path to sherpa-onnx JNI; a debug scenario
 * (`ScenarioFixtures.installModelFixture`) had overwritten the real, verified model with a 64-byte
 * stub. Native code threw an uncaught C++ exception (`Ort::Exception 'Protobuf parsing failed'`)
 * and `std::terminate` followed — no Kotlin `catch (t: Throwable)` around the constructor call can
 * stop that (constitution IV: capture must never die; constitution I: uncertainty is content, not
 * a crash).
 *
 * [verify] is the one check every loader of a model-bearing file MUST run before it reaches native
 * code, wherever that file came from — a real download, a bundled asset, a debug fixture, or
 * anything else that can land bytes at that path. It reads two sidecars a verified install writes
 * next to the asset:
 * - `<name>.sha256` — already existed (`org.ort.app.assets.BundledAssetInstaller`), also read by
 *   `org.ort.app.ui.data.ModelsController` for the Settings-Assets row; unchanged by this class.
 * - `<name>.size` — new, written by the same installer alongside it. Size is the free check a
 *   64-byte debug stub fails instantly, with no hashing at all; sha256 is the one a same-size
 *   corruption still cannot pass. Checking only one of the two is not enough: a scenario fixture's
 *   marker deliberately carries the *real* checksum text (so the Settings screen still reads
 *   "installed" without a real model on disk), which a hash-only check would never catch.
 *
 * Neither sidecar is trusted forever once read: a third file ([verifiedStampFile], never read by
 * anything else) caches the asset's own `(length, lastModified)` at the moment it last verified,
 * purely so a repeat call — every capture session start — does not re-hash a large model on every
 * launch. The moment that pair no longer matches the file on disk, the digest is recomputed; the
 * stamp is never itself the source of truth for whether the file is *right*, only for whether it
 * has *changed* since it was last shown to be right.
 *
 * Missing sidecars are not "trust it anyway" — a file with no verified-install record at all
 * (never having gone through [org.ort.app.assets.BundledAssetInstaller] or an equivalent) is
 * reported [ModelVerification.Failed] exactly like a checksum mismatch. Fail closed, always: this
 * function never has a code path that hands back [ModelVerification.Verified] without having
 * checked a real digest against a real record.
 */
public object ModelFileVerifier {

    /** The manifest's own expected sha256 for the asset at [destination], written once by a
     * verified install. Also read by `ModelsController` — the filename contract predates this
     * class and is unchanged by it. */
    public fun sha256MarkerFile(destination: File): File = File(destination.parentFile, destination.name + ".sha256")

    /** The manifest's own expected size in bytes for the asset at [destination] — new alongside
     * this class, written by [org.ort.app.assets.BundledAssetInstaller] at the same moment as
     * [sha256MarkerFile]. */
    public fun sizeMarkerFile(destination: File): File = File(destination.parentFile, destination.name + ".size")

    /** Purely a cache key for [verify] itself — never written or read by anything else. */
    private fun verifiedStampFile(destination: File): File =
        File(destination.parentFile, destination.name + ".verified")

    public fun verify(destination: File): ModelVerification {
        if (!destination.isFile) {
            return ModelVerification.Failed("no file at ${destination.path}")
        }

        val sizeMarker = sizeMarkerFile(destination)
        val sha256Marker = sha256MarkerFile(destination)
        if (!sizeMarker.isFile || !sha256Marker.isFile) {
            val missing = if (!sizeMarker.isFile) sizeMarker.name else sha256Marker.name
            return ModelVerification.Failed(
                "${destination.name} has no verified-install record ($missing is missing) — " +
                    "never loaded without one",
            )
        }

        val expectedSize = sizeMarker.readText().trim().toLongOrNull()
            ?: return ModelVerification.Failed("${sizeMarker.name} is unreadable — not loaded")
        val actualSize = destination.length()
        if (actualSize != expectedSize) {
            return ModelVerification.Failed(
                "${destination.name} is $actualSize bytes, expected $expectedSize — not loaded",
            )
        }

        return verifyHash(destination, actualSize, sha256Marker.readText().trim())
    }

    /** Split from [verify] purely to keep each function's own return count under detekt's
     * threshold — the cache-stamp check and the real hash comparison are genuinely two more
     * outcomes on top of [verify]'s own four pre-checks. */
    private fun verifyHash(destination: File, actualSize: Long, expectedSha256: String): ModelVerification {
        val stamp = verifiedStampFile(destination)
        val currentStamp = stampFor(actualSize, destination.lastModified())
        if (stamp.isFile && stamp.readText() == currentStamp) {
            return ModelVerification.Verified
        }

        val actualSha256 = sha256Of(destination)
        if (actualSha256 != expectedSha256) {
            stamp.delete()
            return ModelVerification.Failed(
                "${destination.name} failed checksum verification (expected " +
                    "${expectedSha256.take(CHECKSUM_PREFIX_LENGTH)}..., got " +
                    "${actualSha256.take(CHECKSUM_PREFIX_LENGTH)}...) — not loaded",
            )
        }
        stamp.writeText(currentStamp)
        return ModelVerification.Verified
    }

    private fun stampFor(size: Long, lastModified: Long): String = "$size:$lastModified"

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
}

/** What [ModelFileVerifier.verify] found. A caller MUST handle [Failed] explicitly and never call
 * native code when it does (constitution I — uncertainty is content, not a crash). */
public sealed interface ModelVerification {
    public object Verified : ModelVerification
    public data class Failed(public val reason: String) : ModelVerification
}
