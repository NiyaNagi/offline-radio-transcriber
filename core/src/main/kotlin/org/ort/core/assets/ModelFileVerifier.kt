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
 * code, wherever that file came from — a real download, a side-loaded file, a bundled asset, a
 * debug fixture, or anything else that can land bytes at that path. It reads two sidecars a
 * verified install writes next to the asset, via [recordVerifiedInstall]:
 * - `<name>.sha256` — the asset's expected digest. Predates this class
 *   (`org.ort.app.assets.BundledAssetInstaller`, `org.ort.net.ModelAcquisition`), also read by
 *   `org.ort.app.ui.data.ModelsController` for the Settings-Assets row; its meaning is unchanged.
 * - `<name>.size` — the asset's expected size in bytes, new alongside this class. When present,
 *   it is checked first, for free (no hashing) — the shape that catches a 64-byte debug stub
 *   instantly. **It is an optimization, not a requirement**: a model installed by an older build
 *   (`ModelAcquisition.fetch`/`sideload`, before this sidecar existed) carries only `.sha256`, and
 *   [verify] falls back to hashing the file directly in that case — see "the upgrade case" below.
 *   Checking size alone would never be enough regardless: a scenario fixture's marker deliberately
 *   carries the asset's *real* checksum text (so the Settings screen still reads "installed"
 *   without a real model on disk), which only a real hash comparison catches.
 *
 * **The upgrade case.** A file with `.sha256` but no `.size` is not treated as "no record" —
 * [verify] hashes it once, and on a match backfills `.size` (and the cache stamp below) so every
 * later call takes the cheap, size-first path. This is deliberate: refusing every model a real
 * operator already downloaded or side-loaded before this class existed would be a regression, not
 * a fix (a downloaded/side-loaded model is real, verified, user-authorized input — the exact
 * opposite of what R-1052 is about). The one-time hash cost lands on the first capture-session
 * start after an upgrade, once per asset, ever.
 *
 * Neither sidecar is trusted forever once read: a third file ([verifiedStampFile], never read by
 * anything else) caches the asset's own `(length, lastModified)` at the moment it last verified,
 * purely so a repeat call — every capture session start — does not re-hash a large model on every
 * launch. The moment that pair no longer matches the file on disk, the digest is recomputed; the
 * stamp is never itself the source of truth for whether the file is *right*, only for whether it
 * has *changed* since it was last shown to be right.
 *
 * A missing `.sha256` is not "trust it anyway" — a file with no verified-install record at all
 * (never having gone through [recordVerifiedInstall]) is reported [ModelVerification.Failed]
 * exactly like a checksum mismatch. Fail closed, always: this function never has a code path that
 * hands back [ModelVerification.Verified] without having checked a real digest against a real
 * record.
 */
public object ModelFileVerifier {

    /** The asset's own expected sha256, written once by a verified install
     * ([recordVerifiedInstall]). Also read by `ModelsController` — the filename contract predates
     * this class and is unchanged by it. */
    public fun sha256MarkerFile(destination: File): File = File(destination.parentFile, destination.name + ".sha256")

    /** The asset's own expected size in bytes, written by [recordVerifiedInstall] alongside
     * [sha256MarkerFile] — optional at read time (see this class's own KDoc, "the upgrade case"),
     * always written for a *new* verified install from this point on. */
    public fun sizeMarkerFile(destination: File): File = File(destination.parentFile, destination.name + ".size")

    /** Purely a cache key for [verify] itself — never written or read by anything else. */
    private fun verifiedStampFile(destination: File): File =
        File(destination.parentFile, destination.name + ".verified")

    /**
     * The one place every verified install — bundled, downloaded or side-loaded — records what it
     * just verified, so there is one rule for what "verified" means instead of each writer
     * inventing its own marker shape. Called by `org.ort.app.assets.BundledAssetInstaller` and
     * `org.ort.net.ModelAcquisition` immediately after each has independently confirmed
     * [sha256] against the real bytes now sitting at [destination].
     */
    public fun recordVerifiedInstall(destination: File, sha256: String, sizeBytes: Long) {
        sha256MarkerFile(destination).writeText(sha256)
        sizeMarkerFile(destination).writeText(sizeBytes.toString())
    }

    public fun verify(destination: File): ModelVerification {
        if (!destination.isFile) {
            return ModelVerification.Failed("no file at ${destination.path}")
        }

        val sha256Marker = sha256MarkerFile(destination)
        if (!sha256Marker.isFile) {
            return ModelVerification.Failed(
                "${destination.name} has no verified-install record (${sha256Marker.name} is missing) — " +
                    "never loaded without one",
            )
        }
        val expectedSha256 = sha256Marker.readText().trim()

        val actualSize = destination.length()
        val sizeMarker = sizeMarkerFile(destination)
        if (sizeMarker.isFile) {
            val expectedSize = sizeMarker.readText().trim().toLongOrNull()
                ?: return ModelVerification.Failed("${sizeMarker.name} is unreadable — not loaded")
            if (actualSize != expectedSize) {
                return ModelVerification.Failed(
                    "${destination.name} is $actualSize bytes, expected $expectedSize — not loaded",
                )
            }
        }
        // No size sidecar: an upgrade-era install (see this class's own KDoc, "the upgrade case")
        // — fall through to the real hash rather than refusing it outright.

        return verifyHash(destination, actualSize, expectedSha256)
    }

    /** Split from [verify] purely to keep each function's own return count under detekt's
     * threshold. Also the one place [sizeMarkerFile] is backfilled on a successful hash match —
     * a no-op rewrite of the same value on the ordinary path, the one-time upgrade fix on the
     * sha256-only path (see [verify]'s own KDoc). */
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
        sizeMarkerFile(destination).writeText(actualSize.toString())
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
