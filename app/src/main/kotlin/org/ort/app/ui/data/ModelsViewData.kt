package org.ort.app.ui.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ort.core.Outcome
import org.ort.core.SystemClock
import org.ort.data.OrtDatabase
import org.ort.data.WorkQueue
import org.ort.net.AcquiredModel
import org.ort.net.Checksum
import org.ort.net.HttpRangeClient
import org.ort.net.HttpRangeResult
import org.ort.net.ModelAcquisition
import org.ort.net.ModelFetchSpec
import org.ort.net.NetCapability
import org.ort.net.real.RealHttpRangeClient
import org.ort.pipeline.capture.SileroVadLocator
import org.ort.pipeline.passb.AsrModelLocator
import java.io.File

/**
 * Audit F-008 (FR-ASR-1, constitution V): `:net`'s `ModelAcquisition` had no `:app` call site, so
 * no ASR or VAD model could ever reach the device — the debug build could capture but never
 * transcribe. This is that call site: the "Models" screen reachable from the drawer's `Settings`
 * destination (chosen over `Improve records`, which build-plan P16 already gave a per-transmission
 * correction/labelling meaning — asset management reads more naturally as a `Settings` concern).
 *
 * [ModelCatalog] is the single place that says where each required file comes from and where it
 * must land — the exact paths [AsrModelLocator]/[org.ort.pipeline.capture.SileroVadLocator] read,
 * so a successful install is picked up by the real provisioning code with no second, drifting path
 * definition.
 *
 * **Checksums are not yet pinned to the real published bytes.** No network egress was available to
 * this change to actually fetch either model and compute its real SHA-256 (constitution VI: a
 * number without provenance is not evidence, and the same discipline applies to a checksum this
 * change did not compute itself). [ModelCatalog.UNPINNED_CHECKSUM] is a human-readable placeholder,
 * deliberately *not* a hex string, so nobody mistakes it for a genuinely pinned value: a real fetch
 * or side-load against these specs today will correctly and loudly fail checksum verification
 * (see [ModelAcquisition]) rather than ever silently install unverified bytes. Replacing it with the
 * real digest — computed once from a genuinely downloaded copy of each file — is left open (see
 * CHANGELOG, audit F-008).
 */
public enum class ModelId(public val label: String) {
    ASR_ENCODER("Whisper tiny.en — encoder"),
    ASR_DECODER("Whisper tiny.en — decoder"),
    ASR_TOKENS("Whisper tiny.en — tokens"),
    VAD("Silero VAD"),
}

public data class ModelCatalogEntry(val id: ModelId, val url: String, val destination: (filesDir: File) -> File)

public object ModelCatalog {
    /**
     * Not a real digest — see this file's doc comment. Any 64-hex-looking value here would risk
     * being mistaken for one that was actually verified against the published artifact; this is
     * not that.
     */
    public const val UNPINNED_CHECKSUM: String =
        "UNPINNED-see-CHANGELOG-audit-F-008-compute-real-sha256-before-device-use"

    /**
     * Individual, flat file URLs — confirmed to exist as of this change (HuggingFace mirrors the
     * same sherpa-onnx release contents as separate files, not only the `.tar.bz2` archive
     * `asr-sherpa/README.md` documents for the desktop-JVM test cache), so each of the three files
     * [org.ort.pipeline.passb.AsrModelLocator] looks for can be fetched directly with no archive
     * extraction step — deliberately avoided as a new, untested piece of infrastructure this fix
     * does not need.
     */
    public val entries: List<ModelCatalogEntry> = listOf(
        ModelCatalogEntry(
            id = ModelId.ASR_ENCODER,
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/" +
                "tiny.en-encoder.int8.onnx",
            destination = { filesDir -> File(AsrModelLocator.modelsDir(filesDir), "tiny.en-encoder.int8.onnx") },
        ),
        ModelCatalogEntry(
            id = ModelId.ASR_DECODER,
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/" +
                "tiny.en-decoder.int8.onnx",
            destination = { filesDir -> File(AsrModelLocator.modelsDir(filesDir), "tiny.en-decoder.int8.onnx") },
        ),
        ModelCatalogEntry(
            id = ModelId.ASR_TOKENS,
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-tokens.txt",
            destination = { filesDir -> File(AsrModelLocator.modelsDir(filesDir), "tiny.en-tokens.txt") },
        ),
        ModelCatalogEntry(
            id = ModelId.VAD,
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            destination = { filesDir -> SileroVadLocator.modelFile(filesDir) },
        ),
    )

    public fun entry(id: ModelId): ModelCatalogEntry = entries.first { it.id == id }

    public fun specFor(id: ModelId, filesDir: File): ModelFetchSpec {
        val catalogEntry = entry(id)
        return ModelFetchSpec(
            url = catalogEntry.url,
            destination = catalogEntry.destination(filesDir),
            checksum = Checksum(value = UNPINNED_CHECKSUM),
        )
    }
}

public enum class ModelRowStatus { NOT_INSTALLED, INSTALLED }

/**
 * [detail] carries the honest reason when [status] is [ModelRowStatus.NOT_INSTALLED] and the last
 * action attempted actually failed (a checksum mismatch, a download error) — distinct from simply
 * never having been attempted, which reports "not installed" with no failure text (constitution I:
 * an absent conclusion is not the same fact as a failed one).
 */
public data class ModelRowViewState(val id: ModelId, val label: String, val status: ModelRowStatus, val detail: String?)

public data class ModelsViewState(val rows: List<ModelRowViewState>, val requeuedMessage: String? = null)

public sealed interface ModelActionResult {
    public data class Success(public val requeuedCount: Int) : ModelActionResult
    public data class Failure(public val reason: String) : ModelActionResult
}

/**
 * Reads and drives model install state for the Models screen. Every write goes through
 * [ModelAcquisition] — this object never writes a model file itself — so "installed" always means
 * what [ModelAcquisition] itself verified, never a file this code merely observed to exist.
 */
public object ModelsController {

    /**
     * [specFor] defaults to the real [ModelCatalog] and exists as a seam purely for this object's
     * own tests: [ModelCatalog]'s checksums are not pinned yet (see this file's top doc comment),
     * so a test proving the *mechanism* — a successful, checksum-verified install flips a row to
     * [ModelRowStatus.INSTALLED] — must supply a spec with a checksum it can actually satisfy,
     * without pretending [ModelCatalog] itself has a real one. Production call sites never pass it.
     */
    public fun currentState(
        context: Context,
        requeuedMessage: String? = null,
        specFor: (ModelId, File) -> ModelFetchSpec = ModelCatalog::specFor,
    ): ModelsViewState {
        val filesDir = context.filesDir
        val rows = ModelId.entries.map { id -> rowFor(id, filesDir, specFor) }
        return ModelsViewState(rows = rows, requeuedMessage = requeuedMessage)
    }

    /**
     * Mints [NetCapability.UserInitiated] on the caller's behalf — this function exists to be
     * called from exactly one place, a tap on this screen's "Download" button — and runs the fetch
     * on [Dispatchers.IO], off whatever dispatcher the caller (a Compose coroutine scope) is on.
     * Never called from `:pipeline`: the capture/processing path makes no network call, ever
     * (constitution V), and `:pipeline` cannot depend on `:net` at all (`dependencyRules`).
     */
    public suspend fun download(
        context: Context,
        id: ModelId,
        client: HttpRangeClient = RealHttpRangeClient(),
        specFor: (ModelId, File) -> ModelFetchSpec = ModelCatalog::specFor,
    ): ModelActionResult = withContext(Dispatchers.IO) {
        val spec = specFor(id, context.filesDir)
        finish(context, ModelAcquisition(client).fetch(spec, NetCapability.UserInitiated))
    }

    /** Verifies and installs a user-picked local file — no network call, ever (see [download]'s doc comment). */
    public suspend fun sideload(
        context: Context,
        id: ModelId,
        source: File,
        specFor: (ModelId, File) -> ModelFetchSpec = ModelCatalog::specFor,
    ): ModelActionResult = withContext(Dispatchers.IO) {
        val spec = specFor(id, context.filesDir)
        val acquisition = ModelAcquisition(NeverCalledHttpRangeClient)
        finish(context, acquisition.sideload(source, spec, NetCapability.UserInitiated))
    }

    private suspend fun finish(context: Context, outcome: Outcome<AcquiredModel>): ModelActionResult = when (outcome) {
        is Outcome.Ok -> ModelActionResult.Success(requeuedCount = requeueFailed(context))
        is Outcome.Err -> ModelActionResult.Failure(outcome.reason)
    }

    /**
     * F-016's requeue, called on every successful install regardless of which model just landed:
     * a transmission stuck `FAILED` for lack of *a* model deserves a fresh run once *any* model
     * install succeeds, and narrowing to a specific error-message prefix here would silently
     * depend on `:pipeline`'s exact wording for `UnavailableAsrEngine`'s failure text, which this
     * module does not own and must not assume (`:app` owns this file, not `:pipeline`).
     */
    private suspend fun requeueFailed(context: Context): Int {
        val db = OrtDatabase.create(context.applicationContext)
        return WorkQueue(db, SystemClock).requeueFailed()
    }

    /**
     * Verified installed means [ModelAcquisition] itself wrote the `.sha256` marker recording a
     * match against [ModelCatalog]'s pinned checksum for this exact destination — never inferred
     * from the destination file merely existing (constitution I: never claim "installed" without
     * the checksum having verified).
     */
    private fun rowFor(id: ModelId, filesDir: File, specFor: (ModelId, File) -> ModelFetchSpec): ModelRowViewState {
        val spec = specFor(id, filesDir)
        val marker = File(spec.destination.parentFile, spec.destination.name + ".sha256")
        val verified = spec.destination.isFile && marker.isFile && marker.readText() == spec.checksum.value
        return if (verified) {
            ModelRowViewState(id, id.label, ModelRowStatus.INSTALLED, detail = null)
        } else {
            ModelRowViewState(id, id.label, ModelRowStatus.NOT_INSTALLED, detail = null)
        }
    }

    /** [ModelAcquisition.sideload] never calls its client — this exists only to satisfy the constructor. */
    private object NeverCalledHttpRangeClient : HttpRangeClient {
        override fun get(url: String, rangeStart: Long): HttpRangeResult =
            error("sideload() must never make a network call")
    }
}
