package org.ort.app.ui.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.ort.core.Outcome
import org.ort.core.SystemClock
import org.ort.data.OrtDatabase
import org.ort.data.WorkQueue
import org.ort.data.entity.LexiconVersionEntity
import org.ort.lexicon.import.ActiveLexiconRecord
import org.ort.lexicon.import.ActiveLexiconStore
import org.ort.lexicon.import.CheckStatus
import org.ort.lexicon.import.LexiconImportInstaller
import org.ort.lexicon.import.LexiconImportResult
import org.ort.net.AcquiredModel
import org.ort.net.Checksum
import org.ort.net.HttpRangeClient
import org.ort.net.HttpRangeResult
import org.ort.net.ModelAcquisition
import org.ort.net.ModelFetchSpec
import org.ort.net.NetCapability
import org.ort.net.real.RealHttpRangeClient
import org.ort.net.sha256Of
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
 * **Audit F-008 follow-up (constitution V/VII, FR-AST-1): checksums are now pinned to real,
 * cited published digests — never computed from a download this change performed.** Each
 * [ChecksumState.Known] value below carries a KDoc line naming exactly where its sha256 was read:
 *
 * - The Whisper `tiny.en` encoder and decoder are Git-LFS-tracked files on HuggingFace. Fetching
 *   `https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/raw/main/<file>` for an
 *   LFS-tracked path returns the small, plain-text LFS pointer itself (`version ...`,
 *   `oid sha256:<hex>`, `size <bytes>`) rather than the binary — read 2026-09-07.
 * - `tiny.en-tokens.txt` is a small, *non*-LFS file: HuggingFace's own file-listing API reports
 *   only a git blob id for it (40 hex chars — a SHA-1, per git's blob hashing, not a SHA-256), so
 *   there is no sha256 to read for this specific file. This is [ChecksumState.UnknownSideloadOnly],
 *   not a guess.
 * - Silero VAD's `silero_vad.onnx` is a plain GitHub release asset (`k2-fsa/sherpa-onnx`, tag
 *   `asr-models`) with no per-asset `digest` field from the Releases API (that field is null even
 *   for the differently-named `silero_vad_v5.onnx` asset in the same release). The release does,
 *   however, publish its own `checksum.txt` manifest asset — a tab-separated
 *   `<asset filename>\tsha256` line per file — which lists `silero_vad.onnx` by its exact name.
 *   Read from that manifest 2026-09-07.
 *
 * No on-device install or real download was performed to produce or check these values in this
 * change; they were read from the sources above, not hashed from bytes fetched here.
 */
public enum class ModelId(public val label: String) {
    ASR_ENCODER("Whisper tiny.en — encoder"),
    ASR_DECODER("Whisper tiny.en — decoder"),
    ASR_TOKENS("Whisper tiny.en — tokens"),
    VAD("Silero VAD"),
}

/**
 * Whether a catalog entry's checksum is a genuine, sourced digest ([Known]) or explicitly absent
 * ([UnknownSideloadOnly]) — a closed set so a caller cannot accidentally treat "we haven't looked"
 * the same as "we looked and there is nothing to pin" (constitution I's discipline applied to
 * asset integrity rather than attribution). Never a third, placeholder-shaped value.
 */
public sealed interface ChecksumState {
    public data class Known(public val checksum: Checksum) : ChecksumState
    public data class UnknownSideloadOnly(public val reason: String) : ChecksumState
}

public data class ModelCatalogEntry(
    val id: ModelId,
    val url: String,
    val destination: (filesDir: File) -> File,
    val checksumState: ChecksumState,
)

public object ModelCatalog {

    private const val ASR_TOKENS_UNKNOWN_REASON =
        "tiny.en-tokens.txt is not Git-LFS-tracked on HuggingFace: the repository's file-listing " +
            "API reports only a 40-hex-character git blob id for it (a SHA-1 from git's own blob " +
            "hashing, not a SHA-256), and no sha256 for this individual file is published anywhere " +
            "else found (checked 2026-09-07: HuggingFace's raw/API endpoints for this path, and " +
            "sherpa-onnx's own `checksum.txt` release manifest, which covers whole .tar.bz2 archives " +
            "only, not files extracted from them). Side-load this file yourself; it cannot be " +
            "checksum-verified against a known-good value."

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
            checksumState = ChecksumState.Known(
                Checksum(value = "0ce578b827c94a961aacb8fa14b02f096504b337e5c94be37c36238cbe3e8bc6"),
            ),
        ),
        ModelCatalogEntry(
            id = ModelId.ASR_DECODER,
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/" +
                "tiny.en-decoder.int8.onnx",
            destination = { filesDir -> File(AsrModelLocator.modelsDir(filesDir), "tiny.en-decoder.int8.onnx") },
            checksumState = ChecksumState.Known(
                Checksum(value = "06c0e6ff6348d427e51839219d1c886c18cfdf411e629e33f5e1679bff9c1527"),
            ),
        ),
        ModelCatalogEntry(
            id = ModelId.ASR_TOKENS,
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-tokens.txt",
            destination = { filesDir -> File(AsrModelLocator.modelsDir(filesDir), "tiny.en-tokens.txt") },
            checksumState = ChecksumState.UnknownSideloadOnly(ASR_TOKENS_UNKNOWN_REASON),
        ),
        ModelCatalogEntry(
            id = ModelId.VAD,
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            destination = { filesDir -> SileroVadLocator.modelFile(filesDir) },
            checksumState = ChecksumState.Known(
                Checksum(value = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
            ),
        ),
    )

    public fun entry(id: ModelId): ModelCatalogEntry = entries.first { it.id == id }

    /** Null exactly when [ModelCatalogEntry.checksumState] is [ChecksumState.UnknownSideloadOnly] — see there. */
    public fun specFor(id: ModelId, filesDir: File): ModelFetchSpec? {
        val catalogEntry = entry(id)
        val checksum = (catalogEntry.checksumState as? ChecksumState.Known)?.checksum ?: return null
        return ModelFetchSpec(
            url = catalogEntry.url,
            destination = catalogEntry.destination(filesDir),
            checksum = checksum,
        )
    }
}

public enum class ModelRowStatus { NOT_INSTALLED, INSTALLED, INSTALLED_UNVERIFIED }

/**
 * [detail] carries the honest reason when [status] is [ModelRowStatus.NOT_INSTALLED] and the last
 * action attempted actually failed (a checksum mismatch, a download error) — distinct from simply
 * never having been attempted, which reports "not installed" with no failure text (constitution I:
 * an absent conclusion is not the same fact as a failed one). It is also where a
 * [ModelRowStatus.INSTALLED_UNVERIFIED] row explains *why* no published digest exists to verify
 * against — see [ModelsController.currentState].
 *
 * [checksumKnown] is false exactly when [ModelCatalog]'s entry for [id] is
 * [ChecksumState.UnknownSideloadOnly]: Download is never offered for such a row (there is nothing
 * to verify a fetched file against), and an install that does happen via Side-load is
 * [ModelRowStatus.INSTALLED_UNVERIFIED] rather than [ModelRowStatus.INSTALLED] — trust-on-first-use
 * of the exact bytes the user supplied, never conflated with a checksum verified against a
 * published value.
 */
public data class ModelRowViewState(
    val id: ModelId,
    val label: String,
    val status: ModelRowStatus,
    val detail: String?,
    val checksumKnown: Boolean = true,
    /** R-093 (`Settings-Assets.dc.html`): the installed file's size, when it is on disk — `null`
     * when [status] is [ModelRowStatus.NOT_INSTALLED] (there is nothing on disk to size). */
    val sizeBytes: Long? = null,
    /** R-093: the first 8 hex characters of the checksum this install was verified (or, for an
     * unverified trust-on-first-use install, computed) against — `null` exactly when [sizeBytes]
     * is, for the same reason. Never the full digest: the board shows a prefix, not the whole
     * value, and a prefix is enough to recognise a row without wrapping. */
    val checksumPrefix: String? = null,
)

public data class ModelsViewState(val rows: List<ModelRowViewState>, val requeuedMessage: String? = null)

public sealed interface ModelActionResult {
    public data class Success(public val requeuedCount: Int) : ModelActionResult
    public data class Failure(public val reason: String) : ModelActionResult
}

/** R-140 (register, round 4 System validator): a failed *download* specifically — split out of the
 * generic `lastMessage` string so [org.ort.app.ui.screens.ModelsScreen] can render it as the amber
 * `FailedState` guide §9 gives every other operator-facing failure, with a real `Retry`, instead of
 * `:net`'s raw exception text ([reason]) sitting in plain body copy. [reason] is still the real,
 * unedited failure text — never replaced with a fabricated one — just no longer the *only* thing
 * shown. */
public data class ModelDownloadFailureViewState(public val id: ModelId, public val reason: String)

/**
 * R-154 (`Fail-Lexicon.dc.html`, FR-LEX-30, FR-AST-2): one row of the board's "What was checked"
 * list, pre-formatted for [org.ort.app.ui.screens.ModelsScreen] (WP10's file — not edited here) to
 * render directly. [status] is the same closed three-state set
 * [org.ort.lexicon.import.CheckStatus] uses; [detail] is never blank (constitution I — carried
 * over from [org.ort.lexicon.import.LexiconCheck]'s own non-blank invariant).
 */
public data class LexiconCheckViewRow(val name: String, val status: CheckStatus, val detail: String)

/**
 * R-154: the view-facing shape of a [LexiconImportResult] the Models screen's "Install a lexicon
 * from a file" action needs — everything `Fail-Lexicon.dc.html` draws: the file name, every check
 * with its outcome in order, and (on refusal) the reason and what remains active, already formatted
 * as the one-line label the board shows ("Callsign lexicon 2026.08 · 1,104,208 records") rather than
 * a raw [ActiveLexiconRecord] the screen would otherwise have to format itself.
 */
public sealed interface LexiconImportViewState {
    public val fileName: String
    public val checks: List<LexiconCheckViewRow>

    /** Every check passed; the file is now the active lexicon. */
    public data class Accepted(
        override val fileName: String,
        override val checks: List<LexiconCheckViewRow>,
        val version: String,
        val recordCount: Int,
    ) : LexiconImportViewState

    /**
     * At least one check failed; nothing was replaced. [stillActiveLabel] is `null` only when there
     * was genuinely no lexicon active before this import attempt (a first-ever install) — never
     * omitted for any other reason.
     */
    public data class Rejected(
        override val fileName: String,
        override val checks: List<LexiconCheckViewRow>,
        val reason: String,
        val stillActiveLabel: String?,
    ) : LexiconImportViewState
}

/**
 * Reads and drives model install state for the Models screen. Every write goes through
 * [ModelAcquisition] — this object never writes a model file itself — so "installed" always means
 * what [ModelAcquisition] itself verified, never a file this code merely observed to exist.
 */
public object ModelsController {

    /** The one lexicon asset id this build imports (technical design §12.1's `LexiconVersion.assetId`). */
    public const val CALLSIGN_LEXICON_ASSET_ID: String = "callsign-lexicon"

    /**
     * R-154: validates [source] and, only when every check passes, installs it as the new callsign
     * lexicon (FR-LEX-30, FR-AST-2) — the "Install from a file" action's real call site. Makes no
     * network call, ever (constitution V — [org.ort.lexicon.import.LexiconImportValidator] reads only
     * [source]), and runs off whatever dispatcher the caller (a Compose coroutine scope) is on.
     */
    public suspend fun installLexicon(
        context: Context,
        source: File,
        store: ActiveLexiconStore = RoomActiveLexiconStore(context),
    ): LexiconImportViewState = withContext(Dispatchers.IO) {
        toViewState(LexiconImportInstaller.installValidated(source, CALLSIGN_LEXICON_ASSET_ID, store))
    }

    /**
     * [specFor] defaults to the real [ModelCatalog] and exists as a seam purely for this object's
     * own tests. It returns null exactly for an entry whose checksum is
     * [ChecksumState.UnknownSideloadOnly] — see [ModelCatalog.specFor]. Production call sites
     * never pass it.
     */
    public fun currentState(
        context: Context,
        requeuedMessage: String? = null,
        specFor: (ModelId, File) -> ModelFetchSpec? = ModelCatalog::specFor,
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
     *
     * Refuses, with no network call at all, when [id]'s checksum is unknown
     * ([ChecksumState.UnknownSideloadOnly]): there is nothing to verify a downloaded file against,
     * so a download can never be safely installed — only [sideload] is possible for such a file.
     */
    public suspend fun download(
        context: Context,
        id: ModelId,
        client: HttpRangeClient = RealHttpRangeClient(),
        specFor: (ModelId, File) -> ModelFetchSpec? = ModelCatalog::specFor,
    ): ModelActionResult = withContext(Dispatchers.IO) {
        val spec = specFor(id, context.filesDir) ?: return@withContext ModelActionResult.Failure(
            "no published checksum for ${id.label} — a download cannot be verified, so it is refused; " +
                "side-load a copy you trust instead",
        )
        finish(context, ModelAcquisition(client).fetch(spec, NetCapability.UserInitiated))
    }

    /**
     * Verifies and installs a user-picked local file — no network call, ever (see [download]'s doc
     * comment). When [id]'s checksum is [ChecksumState.Known], [source] must match it exactly, the
     * same as before this change. When it is [ChecksumState.UnknownSideloadOnly] there is no
     * known-good value to check [source] against; this computes `sha256Of(source)` itself and
     * installs against that value — a plain trust-on-first-use of the exact bytes the caller
     * supplied, not a claim that those bytes match any published artifact. [rowFor] reports such an
     * install as [ModelRowStatus.INSTALLED_UNVERIFIED], never [ModelRowStatus.INSTALLED].
     */
    public suspend fun sideload(
        context: Context,
        id: ModelId,
        source: File,
        specFor: (ModelId, File) -> ModelFetchSpec? = ModelCatalog::specFor,
    ): ModelActionResult = withContext(Dispatchers.IO) {
        val spec = specFor(id, context.filesDir) ?: unverifiedSpecFor(id, context.filesDir, source)
        val acquisition = ModelAcquisition(NeverCalledHttpRangeClient)
        finish(context, acquisition.sideload(source, spec, NetCapability.UserInitiated))
    }

    /**
     * Builds a spec whose checksum is [source]'s own digest, for an [id] whose catalog entry has
     * no published checksum to verify against (see [sideload]'s doc comment). Falls back to
     * [ModelCatalog.entry] directly for the URL/destination, since [specFor]'s null return carries
     * no [ModelFetchSpec] to reuse.
     */
    private fun unverifiedSpecFor(id: ModelId, filesDir: File, source: File): ModelFetchSpec {
        val catalogEntry = ModelCatalog.entry(id)
        return ModelFetchSpec(
            url = catalogEntry.url,
            destination = catalogEntry.destination(filesDir),
            checksum = Checksum(value = sha256Of(source)),
        )
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
     * match against the checksum it verified for this exact destination — never inferred from the
     * destination file merely existing (constitution I: never claim "installed" without the
     * checksum having verified). For an unknown-checksum entry there is no target value to match
     * the marker against, so "installed" there means only that both the destination and its marker
     * exist — [ModelAcquisition.sideload] wrote them together as one atomic step in [sideload]
     * above, so their mere co-presence already implies the trust-on-first-use digest was recorded,
     * never that it was checked against anything external.
     */
    private fun rowFor(id: ModelId, filesDir: File, specFor: (ModelId, File) -> ModelFetchSpec?): ModelRowViewState {
        val spec = specFor(id, filesDir)
        if (spec != null) {
            val marker = markerFile(spec.destination)
            val verified = spec.destination.isFile && marker.isFile && marker.readText() == spec.checksum.value
            val status = if (verified) ModelRowStatus.INSTALLED else ModelRowStatus.NOT_INSTALLED
            return ModelRowViewState(
                id,
                id.label,
                status,
                detail = null,
                checksumKnown = true,
                sizeBytes = if (verified) spec.destination.length() else null,
                checksumPrefix = if (verified) spec.checksum.value.take(CHECKSUM_PREFIX_LENGTH) else null,
            )
        }

        val reason = (ModelCatalog.entry(id).checksumState as ChecksumState.UnknownSideloadOnly).reason
        val destination = ModelCatalog.entry(id).destination(filesDir)
        val marker = markerFile(destination)
        val installed = destination.isFile && marker.isFile
        val status = if (installed) ModelRowStatus.INSTALLED_UNVERIFIED else ModelRowStatus.NOT_INSTALLED
        return ModelRowViewState(
            id,
            id.label,
            status,
            detail = reason,
            checksumKnown = false,
            sizeBytes = if (installed) destination.length() else null,
            checksumPrefix = if (installed) marker.readText().take(CHECKSUM_PREFIX_LENGTH) else null,
        )
    }

    private const val CHECKSUM_PREFIX_LENGTH = 8

    private fun markerFile(destination: File) = File(destination.parentFile, destination.name + ".sha256")

    /** [ModelAcquisition.sideload] never calls its client — this exists only to satisfy the constructor. */
    private object NeverCalledHttpRangeClient : HttpRangeClient {
        override fun get(url: String, rangeStart: Long): HttpRangeResult =
            error("sideload() must never make a network call")
    }

    /**
     * [LexiconImportResult] (a `:lexicon`-owned, storage-agnostic type) to [LexiconImportViewState]
     * (this file's own, screen-ready type) — a straight field-for-field re-shape, plus formatting
     * [ActiveLexiconRecord] into the one-line label the board shows.
     */
    private fun toViewState(result: LexiconImportResult): LexiconImportViewState {
        val checks = result.checks.map { LexiconCheckViewRow(it.name, it.status, it.detail) }
        return when (result) {
            is LexiconImportResult.Accepted ->
                LexiconImportViewState.Accepted(result.fileName, checks, result.version, result.recordCount)
            is LexiconImportResult.Rejected ->
                LexiconImportViewState.Rejected(
                    result.fileName,
                    checks,
                    result.reason,
                    result.stillActive?.let(::activeLexiconLabel),
                )
        }
    }

    private fun activeLexiconLabel(record: ActiveLexiconRecord): String =
        "Callsign lexicon ${record.version} · ${"%,d".format(record.recordCount)} records"
}

/**
 * R-154: the real [ActiveLexiconStore] — the "active lexicon" [org.ort.lexicon.import] itself has no
 * way to persist (`:lexicon` is a pure JVM module, constitution VII) actually lives in `:data`'s
 * `lexicon_version` table (technical design §12.1's `LexiconVersion`), read/written through
 * [org.ort.data.dao.CatalogDao] exactly the way every other catalog entity in this schema is. "The
 * active lexicon" is the most recently imported (hence [org.ort.data.dao.CatalogDao.versionsFor]'s own
 * `ORDER BY importedAt DESC`) row for [assetId] — [activate] never deletes a superseded row, matching
 * constitution III's "nothing is deleted quietly" for every other asset in this schema.
 *
 * [current]/[activate] are plain (non-suspend) — the [ActiveLexiconStore] interface [org.ort.lexicon.import.LexiconImportInstaller]
 * calls is deliberately synchronous, since `:lexicon` carries no coroutines dependency. Both call
 * sites here already run on [Dispatchers.IO] ([ModelsController.installLexicon]), so bridging to the
 * DAO's `suspend` functions with [runBlocking] blocks a thread that is already meant for blocking
 * I/O, not the caller's own dispatcher.
 */
public class RoomActiveLexiconStore(private val context: Context) : ActiveLexiconStore {

    override fun current(): ActiveLexiconRecord? = runBlocking {
        db().catalogDao().versionsFor(ModelsController.CALLSIGN_LEXICON_ASSET_ID).firstOrNull()?.let {
            ActiveLexiconRecord(it.assetId, it.version, it.recordCount, it.checksum)
        }
    }

    override fun activate(record: ActiveLexiconRecord) {
        runBlocking {
            db().catalogDao().insert(
                LexiconVersionEntity(
                    assetId = record.assetId,
                    version = record.version,
                    importedAt = SystemClock.wallMillis(),
                    recordCount = record.recordCount,
                    checksum = record.checksum ?: "",
                ),
            )
        }
    }

    private fun db(): OrtDatabase = OrtDatabase.create(context.applicationContext)
}
