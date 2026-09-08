package org.ort.net

import org.ort.core.Outcome
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Fetch, resume, checksum-verify and land a model asset on disk (build-plan P18; FR-ASR-8,
 * FR-AST-2, FR-AST-3, technical design §8.4/§13). This is where `:net`'s responsibility ends —
 * activation (signature check, probe-run, keep-previous-on-failure) is `:asr-sherpa`'s
 * `ModelActivation`, deliberately not duplicated here.
 *
 * Mirrors `corpus/src/corpus/acquire.py`'s `_download`/`acquire_source` semantics: a `.part` file
 * carries partial progress and resumes from its own size, a completed download is checksummed
 * before it is renamed into place, and a marker records that the destination is verified so a
 * later call is idempotent. One deliberate divergence: on a checksum mismatch this class deletes
 * the `.part` file rather than keeping it, because resuming a *corrupt* partial cannot fix it —
 * FR-AST-2 requires that nothing half-written survive for a later run to mistake for progress.
 */
public class ModelAcquisition(private val client: HttpRangeClient) {

    /** Fetches [spec] over the network, resuming an interrupted attempt from its partial offset. */
    public fun fetch(spec: ModelFetchSpec, capability: NetCapability): Outcome<AcquiredModel> {
        requireCapability(capability)

        cachedIfVerified(spec)?.let { return Outcome.Ok(it) }
        if (spec.destination.exists()) {
            return Outcome.Err(
                "refusing to overwrite an existing, unverified file at ${spec.destination} — " +
                    "remove it first, or use sideload() to verify and replace it deliberately",
            )
        }

        spec.destination.parentFile?.mkdirs()
        val part = partFile(spec.destination)
        val resumeFrom = if (part.exists()) part.length() else 0L

        val response = when (val result = client.get(spec.url, resumeFrom)) {
            is HttpRangeResult.Failure -> return Outcome.Err(
                describeFailure(result, part),
                result.cause,
            )
            is HttpRangeResult.Success -> result
        }

        val append = resumeFrom > 0 && !response.servedFromStart
        if (!append && part.exists()) part.delete()

        try {
            FileOutputStream(part, append).use { out -> response.body.use { it.copyTo(out) } }
        } catch (e: IOException) {
            return Outcome.Err(
                "connection interrupted after ${part.length()} bytes for ${spec.url} " +
                    "(partial file kept at ${part.name} for resume): ${e.message}",
                e,
            )
        }

        return verifyAndInstall(part, spec)
    }

    /**
     * Verifies a user-supplied file against [spec]'s checksum and, only on a match, copies it
     * into place (FR-ASR-8). Never makes a network call. A mismatch leaves whatever was
     * previously at [spec]'s destination untouched (FR-AST-2).
     */
    public fun sideload(source: File, spec: ModelFetchSpec, capability: NetCapability): Outcome<AcquiredModel> {
        requireCapability(capability)

        val got = sha256Of(source)
        if (got != spec.checksum.value) {
            return Outcome.Err(
                "checksum mismatch for side-loaded file $source: expected ${spec.checksum.value}, got $got",
            )
        }

        spec.destination.parentFile?.mkdirs()
        source.copyTo(spec.destination, overwrite = true)
        markerFile(spec.destination).writeText(spec.checksum.value)
        return Outcome.Ok(AcquiredModel(spec.destination, spec.checksum, fromCache = false))
    }

    @Suppress("UNUSED_PARAMETER")
    private fun requireCapability(capability: NetCapability) {
        // No further gating is possible from inside :net — see NetCapability's doc comment. The
        // parameter exists so every entry point's *signature* requires a capability even though
        // this module cannot itself distinguish a real user gesture from a hand-constructed one.
    }

    private fun cachedIfVerified(spec: ModelFetchSpec): AcquiredModel? {
        val marker = markerFile(spec.destination)
        if (!spec.destination.exists() || !marker.exists()) return null
        if (marker.readText() != spec.checksum.value) return null
        return AcquiredModel(spec.destination, spec.checksum, fromCache = true)
    }

    private fun verifyAndInstall(part: File, spec: ModelFetchSpec): Outcome<AcquiredModel> {
        val got = sha256Of(part)
        if (got != spec.checksum.value) {
            part.delete()
            return Outcome.Err("checksum mismatch for ${spec.url}: expected ${spec.checksum.value}, got $got")
        }

        if (!part.renameTo(spec.destination)) {
            part.copyTo(spec.destination, overwrite = true)
            part.delete()
        }
        markerFile(spec.destination).writeText(spec.checksum.value)
        return Outcome.Ok(AcquiredModel(spec.destination, spec.checksum, fromCache = false))
    }

    private fun describeFailure(failure: HttpRangeResult.Failure, part: File): String {
        val resumeNote = if (part.exists()) {
            " (partial file kept at ${part.name}, ${part.length()} bytes, for resume)"
        } else {
            ""
        }
        return "download failed: ${failure.reason}$resumeNote"
    }

    private fun partFile(destination: File) = File(destination.parentFile, destination.name + ".part")

    private fun markerFile(destination: File) = File(destination.parentFile, destination.name + ".sha256")
}
