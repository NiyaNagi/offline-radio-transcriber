package org.ort.app.ui.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.ort.app.assets.BundledAssetInstaller
import org.ort.app.assets.BundledAssetRejection
import org.ort.app.assets.GeneratedBundledAssetManifest
import org.ort.core.Outcome
import org.ort.core.SystemClock
import org.ort.data.OrtDatabase
import org.ort.data.WorkQueue
import org.ort.data.entity.LexiconVersionEntity
import org.ort.lexicon.import.ActiveLexiconRecord
import org.ort.lexicon.import.ActiveLexiconStore
import org.ort.lexicon.import.CheckStatus
import org.ort.lexicon.import.LexiconCheck
import org.ort.lexicon.import.LexiconImportInstaller
import org.ort.lexicon.import.LexiconImportResult
import org.ort.lexicon.import.LexiconImportValidator
import org.ort.net.AcquiredModel
import org.ort.net.Checksum
import org.ort.net.HttpRangeClient
import org.ort.net.HttpRangeResult
import org.ort.net.ModelAcquisition
import org.ort.net.ModelFetchSpec
import org.ort.net.NetCapability
import org.ort.net.real.RealHttpRangeClient
import org.ort.net.sha256Of
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.ShedStatus
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
 *
 * **WPG follow-up (D35/D36, FR-AST-3): every value above now lives in the root `bundled-assets.json`
 * manifest, not in this file.** `buildSrc`'s `generateBundledAssetCatalog` task
 * (`ort.android-app.gradle.kts`) reads that manifest and emits [GeneratedBundledAssetManifest] —
 * [ModelCatalog.entries] below is built from it, so the app and the build agree by construction
 * (a manifest entry cannot silently drift from what `ModelCatalog` reports, because there is only
 * one typed place either of them reads). `ASR_TOKENS`'s checksum, `UnknownSideloadOnly` above
 * (there is no *externally* published digest to verify a *download* against), is exactly the case
 * `bundled-assets.json` calls `trust-on-first-fetch`: since D35 means the app never downloads this
 * file at all, `buildSrc`'s `FetchBundledAssetsTask` fetched it once, computed its real sha256, and
 * pinned that value into the manifest (recorded there, with the date and method) — so this entry's
 * [ChecksumState] is now [ChecksumState.Known], not [ChecksumState.UnknownSideloadOnly]. This is a
 * real, upgraded guarantee, not a relaxation: provenance (this exact file shipped inside the
 * verified artifact) plus a digest now internally pinned and checked on every subsequent build and
 * install (FR-AST-3b) is strictly more than "no way to verify a download at all". Adding
 * [ModelId.LLM_GEMMA3_1B] is the same WPG change (D36).
 */
public enum class ModelId(public val label: String) {
    ASR_ENCODER("Whisper tiny.en — encoder"),
    ASR_DECODER("Whisper tiny.en — decoder"),
    ASR_TOKENS("Whisper tiny.en — tokens"),
    VAD("Silero VAD"),

    /** D36: the bundled, gated, tier-3-only language model behind the prose digest (FR-DIG-3a) —
     * never in the callsign path (D5, untouched). [ModelCatalog]'s generated entry for this id
     * carries `tiers = ["T3"]` and `gated = true`; [ModelsController.currentState]'s row for it
     * reports [ModelRowViewState.tierEligible] `false` below tier 3 (AC-138: stored, never loaded). */
    LLM_GEMMA3_1B("Gemma 3 1B int4 — prose digest"),
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
    /** R18/FR-AST-3a — the real, manifest-declared size of this asset, for `Settings-Assets`'
     * per-asset size line and [org.ort.pipeline.capture.StorageAccounting]'s bundled-storage
     * figure. Never measured from a downloaded file here: [ModelRowViewState.sizeBytes] still
     * reports the real on-disk size once installed; this is the manifest's own declared figure,
     * used before anything is on disk at all. */
    val sizeBytes: Long = 0L,
    /** The device tiers ("T0".."T3", `:core`'s `Tier`) that may ever *load* this asset — every
     * asset still ships on every install (FR-AST-3: one build variant); this only governs
     * [ModelRowViewState.tierEligible] so `Settings-Assets` can say "stored, not loaded" honestly
     * for a tier-ineligible model (FR-AST-3a, AC-138) rather than implying every row is usable. */
    val tiers: Set<String> = emptySet(),
    val licence: String = "",
    val gated: Boolean = false,
    /** FR-AST-3 (D35): every catalog entry ships inside the installed artifact — there is no
     * "download this first" state left. [ModelsController.download] refuses unconditionally for a
     * bundled entry (side-load remains the real replacement path, FR-AST-1). Defaults `true`
     * because every entry [ModelCatalog] generates today is bundled; the field exists (rather than
     * being hardcoded at the call site) so a future non-bundled entry — the TODO at FR-AST-3a about
     * split delivery — has somewhere honest to say so. */
    val bundled: Boolean = true,
)

public object ModelCatalog {

    /**
     * R-267 (register, round 6/7 System validator): [ASR_TOKENS_UNKNOWN_REASON] would be
     * [ModelRowViewState.detail] for a manifest entry whose digest is still the
     * `trust-on-first-fetch` sentinel (see [checksumStateFor]) — an operator-facing sub-line
     * fragment (`Settings-Assets.dc.html`'s "size · checksum prefix · tier" shape, guide §9),
     * never a maintainer's research trail. No entry in the committed manifest is in that state
     * today (WPG pinned `ASR_TOKENS`'s real digest — see this file's top KDoc), so this reason is
     * currently unused in practice; it stays wired for the day a newly added manifest entry is
     * committed before its first fetch pins one.
     */
    private const val ASR_TOKENS_UNKNOWN_REASON = "not yet pinned — first fetch pins it"

    /**
     * Built from [GeneratedBundledAssetManifest] — `buildSrc`'s `generateBundledAssetCatalog` task
     * reads the root `bundled-assets.json` and emits that object at build time (see this file's
     * top KDoc for why a generated Kotlin object was chosen over a runtime resource read). Every
     * [ModelCatalogEntry] here is therefore a straight re-shape of one generated entry — this
     * function invents no data of its own — so a change to the manifest is the only way to change
     * what this catalog reports.
     */
    public val entries: List<ModelCatalogEntry> = GeneratedBundledAssetManifest.entries.map { generated ->
        ModelCatalogEntry(
            id = ModelId.valueOf(generated.id),
            url = generated.url,
            destination = { filesDir -> File(filesDir, generated.destination) },
            checksumState = checksumStateFor(generated.sha256),
            sizeBytes = generated.sizeBytes,
            tiers = generated.tiers.toSet(),
            licence = generated.licence,
            gated = generated.gated,
        )
    }

    /**
     * Matches buildSrc's `BundledAssetManifest.TRUST_ON_FIRST_FETCH` sentinel by literal value —
     * duplicated, not shared: buildSrc is a separate Gradle build with no classpath in common with
     * this module's runtime code (the same reason [org.ort.app.assets.BundledAssetInstaller]
     * duplicates buildSrc's manifest JSON parser rather than importing it).
     */
    private const val TRUST_ON_FIRST_FETCH_SENTINEL = "trust-on-first-fetch"

    /** `internal`, not `private`: [ModelCatalogTest] exercises this pure mapping directly for the
     * `trust-on-first-fetch` sentinel path, since no entry in the *committed* manifest is in that
     * state today (see this file's top KDoc) — there is no real [ModelId] left to exercise
     * [entry]/[specFor]'s own `UnknownSideloadOnly` branch through the public API alone. */
    internal fun checksumStateFor(sha256: String): ChecksumState = if (sha256 == TRUST_ON_FIRST_FETCH_SENTINEL) {
        ChecksumState.UnknownSideloadOnly(ASR_TOKENS_UNKNOWN_REASON)
    } else {
        ChecksumState.Known(Checksum(value = sha256))
    }

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
    /** WPG (D35, FR-AST-3): `true` for every row today — every asset ships inside the installed
     * artifact, so there is no "download this" state left; `Settings-Assets` reads this to say
     * "bundled · verified" rather than offering `Download` (FR-AST-1: `Side-load` still does, for
     * a deliberate replacement). Carried as a real field, not inferred from [status], because
     * [ModelRowStatus.NOT_INSTALLED] is reachable both for a bundled asset the installer has not
     * copied yet and (in principle, [ModelCatalogEntry.bundled] `false`) for one that genuinely
     * has to be downloaded — the two must not read the same on the board. */
    val bundled: Boolean = true,
    /** FR-AST-3a/AC-138: `false` when this device's current tier does not include this asset in
     * [ModelCatalogEntry.tiers] — the asset is still on disk (it shipped bundled regardless,
     * FR-AST-3) but MUST NEVER be loaded, only stored. `Settings-Assets` reads this to say
     * "stored, not loaded" for such a row rather than implying it is usable at the current tier. */
    val tierEligible: Boolean = true,
    /** R-934 (register, WPE round 4 — "an owed installer fact"): the last checksum-mismatch
     * rejection [org.ort.app.assets.BundledAssetInstaller] recorded for this part, or `null` when
     * there is none (never installed at all, or a later install verified and cleared it). Distinct
     * from [detail]: a rejected part is still [ModelRowStatus.NOT_INSTALLED] (it genuinely is not
     * on disk), but *why* — "the file was rejected and removed" versus "never attempted" — is a
     * fact `Settings-Assets` could not previously tell apart under `asset-corrupt` (exactly R-934's
     * own finding). WPE renders this; this field only carries it. */
    val lastRejection: BundledAssetRejection? = null,
)

public data class ModelsViewState(val rows: List<ModelRowViewState>, val requeuedMessage: String? = null)

public sealed interface ModelActionResult {
    public data class Success(public val requeuedCount: Int) : ModelActionResult
    public data class Failure(public val reason: String) : ModelActionResult
}

/** R-154 (round 5): [org.ort.app.ui.screens.ModelsScreen]'s last-action-message params, bundled to
 * keep that composable's own parameter list under detekt's threshold once the lexicon row added a
 * ninth. [lastMessage] and [downloadFailure] were already mutually exclusive in practice
 * (`ModelsContent` clears one when it sets the other) — this makes that structural, not just a
 * convention two separate optional params relied on.
 *
 * FR-AST-4 (WP11b's F21 audit finding): [stagedActivation] joined this bundle for the identical
 * reason — [ModelsController.stagedActivation] would otherwise have been this composable's own
 * tenth bare parameter. */
public data class ModelsScreenStatus(
    val lastMessage: String? = null,
    val downloadFailure: ModelDownloadFailureViewState? = null,
    val stagedActivation: StagedActivation? = null,
)

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
 * R-154 (round 5, System validator addendum): makes the `lexicon-corrupt` debug scenario's
 * [LexiconImportViewState] reachable from the real `Settings-Assets` screen for a screenshot,
 * following [org.ort.app.ui.failures.DebugFailureOverride]'s exact pattern — that object's own
 * class kdoc explains why this shape (a plain object in `:app`'s **main** source set, read gated on
 * [isDebugBuild], written only by debug-sourceset callers) rather than reading the scenario's own
 * `internal object` directly: `app/src/debug/kotlin/org/ort/app/debug/LexiconCorruptScenario.kt`
 * cannot be imported from `app/src/main` (the main source set does not depend on the debug one),
 * so this main-sourceset holder is the bridge — the scenario (debug sourceset, which *does* depend
 * on main) writes to it via [show] in addition to setting its own `lastResult`
 * (`LexiconCorruptScenarioTest.kt` already asserts against that field directly, so it stays).
 */
public object DebugLexiconImportOverride {

    @Volatile
    public var current: LexiconImportViewState? = null
        private set

    /** Test seam (see class kdoc) — production code never assigns this. */
    @Volatile
    internal var isDebugBuild: () -> Boolean = { org.ort.app.BuildConfig.DEBUG }

    /** The scenario simulator's own entry point (debug-sourceset-only caller — see class kdoc). */
    public fun show(result: LexiconImportViewState) {
        current = result
    }

    public fun clear() {
        current = null
    }

    /** The Models screen's own read — gated on [isDebugBuild], `null` in any non-debug build no
     * matter what [current] holds. */
    public val activeOverride: LexiconImportViewState?
        get() = if (isDebugBuild()) current else null
}

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
     * At least one check failed; nothing was replaced. [stillActiveLabel]/[stillActiveRecordCount]
     * are `null` only when there was genuinely no lexicon active before this import attempt (a
     * first-ever install) — never omitted for any other reason, and always both null or both real
     * together (both come from the same [org.ort.lexicon.import.ActiveLexiconRecord]).
     *
     * R-492 (register, Reviewer D): the board's own STILL ACTIVE row is two real lines — the
     * lexicon's name, then "N records · verified · in use by the running session" — [stillActiveLabel]
     * carries the name alone now (was the whole combined string) so the screen can draw both without
     * re-parsing it. "verified" and "in use by the running session" are both structurally true for
     * *any* currently active lexicon, never fields to source separately: [RoomActiveLexiconStore.activate]
     * is the only writer of "the active lexicon" and only ever runs after [LexiconImportValidator.validate]
     * returns [Accepted], and this exact record is read from that same store.
     */
    public data class Rejected(
        override val fileName: String,
        override val checks: List<LexiconCheckViewRow>,
        val reason: String,
        val stillActiveLabel: String?,
        val stillActiveRecordCount: Int? = null,
    ) : LexiconImportViewState
}

/** R-154 (register): the Assets screen's own lexicon row — [installed] false and [label]
 * "not installed" for a fresh device, never a fabricated version. */
public data class LexiconAssetRowViewState(val installed: Boolean, val label: String)

/** R-154 (round 5): [org.ort.app.ui.screens.ModelsScreen]'s lexicon-specific params, bundled to
 * keep that composable's own parameter list under detekt's threshold — the same reason
 * `SettingsCaptureToggleActions`/`ui/navigation`'s `NavHostCallbacks` bundles exist. [row] is
 * `null` while the real row has not loaded yet (`ModelsContent`'s own first composition, before
 * its `LaunchedEffect` resolves); [importResult] is the live-or-debug-override
 * [LexiconImportViewState] to render as `Fail-Lexicon.dc.html` (a [LexiconImportViewState.Rejected])
 * or fold back into the assets row in place (a [LexiconImportViewState.Accepted]) — `null` means
 * "no import in flight or shown", the ordinary assets-list state. */
public data class LexiconAssetActions(
    val row: LexiconAssetRowViewState?,
    val importResult: LexiconImportViewState?,
    val onInstall: () -> Unit,
    val onDismissResult: () -> Unit,
)

/**
 * FR-AST-4 (functional spec §9, `Fail-Asset-Swap.dc.html`/F21; WP11b's audit finding that
 * [LexiconImportInstaller.installValidated] activated unconditionally with no runtime signal at
 * all): the fact a lexicon swap or model install could not activate immediately because a session
 * was live when it finished — "a swap mid-session changes what usual means" is F21's own board
 * rationale for why activation must defer. [assetId] is either
 * [ModelsController.CALLSIGN_LEXICON_ASSET_ID] or a [ModelId.name]; [version] is the lexicon's
 * real version string or a model's real checksum prefix — never a placeholder, and never blank.
 * This is the exact, trimmed shape [ModelsController.stagedActivation] exposes; WP11b's own
 * `FailureSignals`/`AssetSwap` mapper is expected to read it directly, so it carries nothing an
 * operator or that mapper would not need — the extra facts a real lexicon activation needs later
 * ([ActiveLexiconRecord]'s `recordCount`/`checksum`) live in [StagedActivationStore] instead, never
 * folded into this type.
 */
public data class StagedActivation(
    public val assetId: String,
    public val version: String,
    public val stagedAtMillis: Long,
    public val reason: String,
)

/**
 * Where a staged activation is persisted — app-level `SharedPreferences`, not `:data` (this round's
 * brief, verbatim: "app-level DataStore/prefs is fine; no `:data` schema"). This is a pending
 * operational fact about *this device*, not a domain record the corpus or a backup would ever need
 * to carry — the same reasoning `SettingsStore.kt`'s own doc comment gives for its own choice of
 * store. [pendingLexiconRecord] carries the one extra fact [StagedActivation] itself deliberately
 * omits — the full [ActiveLexiconRecord] a real, later activation needs — so a real activation is
 * never a lossy replay of the trimmed DTO.
 */
public interface StagedActivationStore {
    public fun current(): StagedActivation?
    public fun stageLexicon(activation: StagedActivation, record: ActiveLexiconRecord)
    public fun stageModel(activation: StagedActivation)
    public fun pendingLexiconRecord(): ActiveLexiconRecord?
    public fun clear()
}

/** The real, `SharedPreferences`-backed [StagedActivationStore] — follows `SettingsStore.kt`'s own
 * established shape for this codebase's app-level preferences stores. */
public class SharedPreferencesStagedActivationStore(context: Context) : StagedActivationStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun current(): StagedActivation? {
        val assetId = prefs.getString(KEY_ASSET_ID, null) ?: return null
        val version = prefs.getString(KEY_VERSION, null) ?: return null
        val reason = prefs.getString(KEY_REASON, null) ?: return null
        val stagedAt = prefs.getLong(KEY_STAGED_AT, -1L)
        if (stagedAt < 0L) return null
        return StagedActivation(assetId, version, stagedAt, reason)
    }

    override fun stageLexicon(activation: StagedActivation, record: ActiveLexiconRecord) {
        writeCommon(activation)
        prefs.edit {
            putString(KEY_LEXICON_VERSION, record.version)
            putInt(KEY_LEXICON_RECORD_COUNT, record.recordCount)
            putString(KEY_LEXICON_CHECKSUM, record.checksum)
        }
    }

    override fun stageModel(activation: StagedActivation) {
        writeCommon(activation)
        prefs.edit {
            remove(KEY_LEXICON_VERSION)
            remove(KEY_LEXICON_RECORD_COUNT)
            remove(KEY_LEXICON_CHECKSUM)
        }
    }

    override fun pendingLexiconRecord(): ActiveLexiconRecord? {
        val assetId = prefs.getString(KEY_ASSET_ID, null) ?: return null
        val version = prefs.getString(KEY_LEXICON_VERSION, null) ?: return null
        if (!prefs.contains(KEY_LEXICON_RECORD_COUNT)) return null
        val recordCount = prefs.getInt(KEY_LEXICON_RECORD_COUNT, 0)
        val checksum = prefs.getString(KEY_LEXICON_CHECKSUM, null)
        return ActiveLexiconRecord(assetId, version, recordCount, checksum)
    }

    override fun clear() {
        prefs.edit {
            remove(KEY_ASSET_ID)
            remove(KEY_VERSION)
            remove(KEY_STAGED_AT)
            remove(KEY_REASON)
            remove(KEY_LEXICON_VERSION)
            remove(KEY_LEXICON_RECORD_COUNT)
            remove(KEY_LEXICON_CHECKSUM)
        }
    }

    private fun writeCommon(activation: StagedActivation) {
        prefs.edit {
            putString(KEY_ASSET_ID, activation.assetId)
            putString(KEY_VERSION, activation.version)
            putLong(KEY_STAGED_AT, activation.stagedAtMillis)
            putString(KEY_REASON, activation.reason)
        }
    }

    private companion object {
        const val PREFS_NAME: String = "org.ort.app.staged_activation"
        const val KEY_ASSET_ID = "asset_id"
        const val KEY_VERSION = "version"
        const val KEY_STAGED_AT = "staged_at_millis"
        const val KEY_REASON = "reason"
        const val KEY_LEXICON_VERSION = "lexicon_version"
        const val KEY_LEXICON_RECORD_COUNT = "lexicon_record_count"
        const val KEY_LEXICON_CHECKSUM = "lexicon_checksum"
    }
}

/** The behavioural fake (constitution II) — a plain in-memory [StagedActivationStore] for tests,
 * matching this file's own `InMemory*`-free precedent set by `SettingsStore.kt`'s
 * `InMemorySettingsStore`. */
public class InMemoryStagedActivationStore(
    private var pending: StagedActivation? = null,
    private var pendingRecord: ActiveLexiconRecord? = null,
) : StagedActivationStore {
    override fun current(): StagedActivation? = pending

    override fun stageLexicon(activation: StagedActivation, record: ActiveLexiconRecord) {
        pending = activation
        pendingRecord = record
    }

    override fun stageModel(activation: StagedActivation) {
        pending = activation
        pendingRecord = null
    }

    override fun pendingLexiconRecord(): ActiveLexiconRecord? = pendingRecord

    override fun clear() {
        pending = null
        pendingRecord = null
    }
}

/**
 * Reads and drives model install state for the Models screen. Every write goes through
 * [ModelAcquisition] — this object never writes a model file itself — so "installed" always means
 * what [ModelAcquisition] itself verified, never a file this code merely observed to exist.
 */
public object ModelsController {

    /** The one lexicon asset id this build imports (technical design §12.1's `LexiconVersion.assetId`). */
    public const val CALLSIGN_LEXICON_ASSET_ID: String = "callsign-lexicon"

    private val stagedActivationFlow = MutableStateFlow<StagedActivation?>(null)

    /** FR-AST-4: the one staged lexicon swap or model install waiting for a live session to end, or
     * `null` when nothing is staged. WP11b's `FailureSignals`/`AssetSwap` mapper is expected to read
     * this directly (see [StagedActivation]'s own doc comment) — never re-derive it from
     * `SharedPreferences` a second time elsewhere; this is the one live, in-process source. Starts
     * `null` on every fresh process until [currentState] or [refreshStagedActivation] first reads
     * whatever [StagedActivationStore] persisted from an earlier run. */
    public val stagedActivation: StateFlow<StagedActivation?> = stagedActivationFlow.asStateFlow()

    /**
     * Test-only reset (see `org.ort.app.testing.ortComposeTestRule` in `app/src/test` — the one
     * place every Compose test in `:app` must route its teardown through): this object is a plain
     * Kotlin `object`, so [stagedActivationFlow] is a single, process-lifetime instance shared by
     * every test that runs in the same JVM fork, not sandboxed per test the way a fresh Robolectric
     * `Application` is. A test that stages an activation (directly, or through a real
     * [installLexicon]/[download]/[sideload] call while [org.ort.pipeline.capture.CaptureState] is
     * capturing) and never drains it left that fact readable by whichever unrelated test happens to
     * read [stagedActivation] next in the same fork — production code never calls this; a fresh
     * process already starts with [stagedActivationFlow] at `null`.
     */
    internal fun resetForTest() {
        stagedActivationFlow.value = null
    }

    /** Re-reads the persisted staged activation into [stagedActivation] — [currentState] already
     * does this as a side effect of its own read, so a caller that only needs this fact refreshed
     * (never the whole [ModelsViewState]) can reach for this instead. */
    public fun refreshStagedActivation(
        context: Context,
        stagedStore: StagedActivationStore = SharedPreferencesStagedActivationStore(context),
    ) {
        stagedActivationFlow.value = stagedStore.current()
    }

    /** [RoomActiveLexiconStore.activate] calls this instead of writing to `:data` when
     * [CaptureState.isCapturing] is true — the actual defer-and-remember step FR-AST-4 asks for.
     * Internal: the only real caller is that class, in this same file; a test drives this path
     * through [installLexicon] with a real [CaptureState.capturing] session in effect, exactly as
     * production would, rather than calling this directly. */
    internal fun stageLexiconActivation(
        context: Context,
        record: ActiveLexiconRecord,
        stagedStore: StagedActivationStore = SharedPreferencesStagedActivationStore(context),
    ) {
        val activation = StagedActivation(
            assetId = record.assetId,
            version = record.version,
            stagedAtMillis = SystemClock.wallMillis(),
            reason = "a session is live — activating a new callsign lexicon mid-session would " +
                "change which callsigns read as usual until this session ends (FR-AST-4)",
        )
        stagedStore.stageLexicon(activation, record)
        stagedActivationFlow.value = activation
    }

    /**
     * FR-AST-4: really activates whatever is staged — a real lexicon write via
     * [RoomActiveLexiconStore] (safe to call directly here since [CaptureState.isCapturing] is
     * already confirmed false below, so it takes that class's own real-write branch, never its
     * staging one), or, for a staged model, the same [requeueFailed] a normal install triggers
     * immediately. Refuses, with no effect, while [CaptureState.isCapturing] is still true — never
     * force-activates mid-session even if called incorrectly — so both the Settings-Assets
     * next-visit trigger and F21's `Reprocess`/`Install` action are safe to call unconditionally.
     * Returns the activation that was applied, or `null` when nothing happened (nothing staged, or
     * a session is still live).
     */
    public suspend fun activateStaged(
        context: Context,
        stagedStore: StagedActivationStore = SharedPreferencesStagedActivationStore(context),
    ): StagedActivation? = withContext(Dispatchers.IO) {
        if (CaptureState.isCapturing) return@withContext null
        val pending = stagedStore.current() ?: return@withContext null
        if (pending.assetId == CALLSIGN_LEXICON_ASSET_ID) {
            stagedStore.pendingLexiconRecord()?.let { record -> RoomActiveLexiconStore(context).activate(record) }
        } else {
            requeueFailed(context)
        }
        stagedStore.clear()
        stagedActivationFlow.value = null
        pending
    }

    /**
     * R-154: validates [source] and, only when every check passes, installs it as the new callsign
     * lexicon (FR-LEX-30, FR-AST-2) — the "Install from a file" action's real call site. Makes no
     * network call, ever (constitution V — [org.ort.lexicon.import.LexiconImportValidator] reads only
     * [source]), and runs off whatever dispatcher the caller (a Compose coroutine scope) is on.
     *
     * FR-AST-4: [store] (the real [RoomActiveLexiconStore] by default) itself decides whether an
     * `Accepted` result activates for real or is staged — this function's own job is only to refresh
     * [stagedActivation] afterward so the caller's next read is never stale.
     */
    public suspend fun installLexicon(
        context: Context,
        source: File,
        store: ActiveLexiconStore = RoomActiveLexiconStore(context),
        stagedStore: StagedActivationStore = SharedPreferencesStagedActivationStore(context),
    ): LexiconImportViewState = withContext(Dispatchers.IO) {
        val result = toViewState(LexiconImportInstaller.installValidated(source, CALLSIGN_LEXICON_ASSET_ID, store))
        stagedActivationFlow.value = stagedStore.current()
        result
    }

    /**
     * R-154 (round 5): the Assets screen's own lexicon row — real, from [ActiveLexiconStore.current]
     * (no `ModelId` covers the lexicon, so it is not part of [currentState]'s rows), formatted with
     * the same one-line label a rejected import's "Still active" row shows.
     */
    public suspend fun lexiconRow(
        context: Context,
        store: ActiveLexiconStore = RoomActiveLexiconStore(context),
    ): LexiconAssetRowViewState = withContext(Dispatchers.IO) {
        val active = store.current()
        if (active != null) {
            LexiconAssetRowViewState(installed = true, label = activeLexiconLabel(active))
        } else {
            LexiconAssetRowViewState(installed = false, label = "not installed")
        }
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
        stagedStore: StagedActivationStore = SharedPreferencesStagedActivationStore(context),
        currentTierLabel: () -> String = ::realCurrentTierLabel,
    ): ModelsViewState {
        stagedActivationFlow.value = stagedStore.current()
        val filesDir = context.filesDir
        val tier = currentTierLabel()
        val rows = ModelId.entries.map { id -> rowFor(id, filesDir, specFor, tier) }
        return ModelsViewState(rows = rows, requeuedMessage = requeuedMessage)
    }

    /**
     * `"T0"`.."T3"`, the same vocabulary [ModelCatalogEntry.tiers] and `bundled-assets.json` use.
     * `app/.../ui/settings/SettingsPolling.kt` computes the identical
     * `(MAX_TIER - ShedStatus.currentLevel).coerceIn(0, MAX_TIER)` formula for `Settings-Tier`'s
     * own row, but keeps it `private` (and outside this package's ownership for this change —
     * WPG owns only `ModelsViewData.kt`) — there is no single shared accessor today, so this
     * duplicates the formula rather than reaching across an ownership boundary for one `private`
     * function. [currentState]'s own `currentTierLabel` parameter exists precisely so a caller (or
     * a future refactor that does add a shared accessor) can override this default instead of this
     * function needing to change at every call site.
     */
    private fun realCurrentTierLabel(): String {
        val maxTier = 3
        val current = (maxTier - ShedStatus.currentLevel).coerceIn(0, maxTier)
        return "T$current"
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
     *
     * **WPG (D35, FR-AST-1): also refuses, with no network call at all, when [id]'s catalog entry
     * is [ModelCatalogEntry.bundled]** — every entry [ModelCatalog] generates today is, so this
     * refuses unconditionally in practice. [isBundled] is a seam (mirroring [specFor]'s own
     * pattern) rather than reading [ModelCatalog.entry] directly, so a test can still exercise the
     * underlying fetch-through-a-client mechanism this function has carried since before D35
     * (`ModelAcquisition`, staging, requeue) without needing a hypothetical non-bundled catalog
     * entry to do it.
     */
    public suspend fun download(
        context: Context,
        id: ModelId,
        client: HttpRangeClient = RealHttpRangeClient(),
        specFor: (ModelId, File) -> ModelFetchSpec? = ModelCatalog::specFor,
        stagedStore: StagedActivationStore = SharedPreferencesStagedActivationStore(context),
        isBundled: (ModelId) -> Boolean = { ModelCatalog.entry(it).bundled },
    ): ModelActionResult = withContext(Dispatchers.IO) {
        if (isBundled(id)) {
            return@withContext ModelActionResult.Failure(
                "${id.label} ships bundled with the app — there is nothing to download; side-load a " +
                    "replacement instead if you need a different copy (D35, FR-AST-1)",
            )
        }
        val spec = specFor(id, context.filesDir) ?: return@withContext ModelActionResult.Failure(
            "no published checksum for ${id.label} — a download cannot be verified, so it is refused; " +
                "side-load a copy you trust instead",
        )
        finish(context, id, ModelAcquisition(client).fetch(spec, NetCapability.UserInitiated), stagedStore)
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
        stagedStore: StagedActivationStore = SharedPreferencesStagedActivationStore(context),
    ): ModelActionResult = withContext(Dispatchers.IO) {
        val spec = specFor(id, context.filesDir) ?: unverifiedSpecFor(id, context.filesDir, source)
        val acquisition = ModelAcquisition(NeverCalledHttpRangeClient)
        finish(context, id, acquisition.sideload(source, spec, NetCapability.UserInitiated), stagedStore)
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

    /**
     * FR-AST-4: a successful install's [requeueFailed] retrigger is itself the mid-session-visible
     * change (freshly reprocessed transcripts appearing while a session is live) — deferred exactly
     * like a lexicon swap, when [CaptureState.isCapturing]. The file itself is still downloaded/
     * side-loaded, verified and written to disk immediately either way — real bytes on disk are not
     * the risk FR-AST-4 names; only the *reprocessing* consequence waits. `requeuedCount = 0` in the
     * staged branch is the honest, literal count (nothing was requeued yet), never a placeholder.
     */
    private suspend fun finish(
        context: Context,
        id: ModelId,
        outcome: Outcome<AcquiredModel>,
        stagedStore: StagedActivationStore,
    ): ModelActionResult = when (outcome) {
        is Outcome.Ok -> {
            if (CaptureState.isCapturing) {
                val activation = StagedActivation(
                    assetId = id.name,
                    version = outcome.value.checksum.value.take(CHECKSUM_PREFIX_LENGTH),
                    stagedAtMillis = SystemClock.wallMillis(),
                    reason = "a session is live — reprocessing previously failed overs with " +
                        "${id.label} mid-session would change what this session finds usual (FR-AST-4)",
                )
                stagedStore.stageModel(activation)
                stagedActivationFlow.value = activation
                ModelActionResult.Success(requeuedCount = 0)
            } else {
                ModelActionResult.Success(requeuedCount = requeueFailed(context))
            }
        }
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
    private fun rowFor(
        id: ModelId,
        filesDir: File,
        specFor: (ModelId, File) -> ModelFetchSpec?,
        currentTierLabel: String,
    ): ModelRowViewState {
        val catalogEntry = ModelCatalog.entry(id)
        val tierEligible = catalogEntry.tiers.isEmpty() || currentTierLabel in catalogEntry.tiers
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
                bundled = catalogEntry.bundled,
                tierEligible = tierEligible,
                lastRejection = if (verified) null else BundledAssetInstaller.lastRejectionFor(spec.destination),
            )
        }

        // A safe cast, not `as`: [specFor] is an injectable seam (tests use it to force this
        // branch — see ModelsControllerTest's own KDoc), so a null spec no longer guarantees the
        // real catalog entry is itself ChecksumState.UnknownSideloadOnly the way it did before WPG
        // (every real entry is Known today). A generic reason covers that injected case honestly
        // without crashing; a real UnknownSideloadOnly entry still gets its own specific reason.
        val reason = (catalogEntry.checksumState as? ChecksumState.UnknownSideloadOnly)?.reason
            ?: "no checksum available to verify this install against"
        val destination = catalogEntry.destination(filesDir)
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
            bundled = catalogEntry.bundled,
            tierEligible = tierEligible,
            lastRejection = if (installed) null else BundledAssetInstaller.lastRejectionFor(destination),
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
        val checks = foldChecksToBoardRows(result.checks)
        return when (result) {
            is LexiconImportResult.Accepted ->
                LexiconImportViewState.Accepted(result.fileName, checks, result.version, result.recordCount)
            is LexiconImportResult.Rejected ->
                LexiconImportViewState.Rejected(
                    result.fileName,
                    checks,
                    result.reason,
                    result.stillActive?.let { "Callsign lexicon ${it.version}" },
                    result.stillActive?.recordCount,
                )
        }
    }

    /**
     * R-490 (register, Reviewer D, `Fail-Lexicon.dc.html`'s own WHAT WAS CHECKED list): the board
     * names four checks — "Manifest readable", "Checksum", "Record count", "Prefix table
     * consistency" — but [LexiconImportValidator.validate] (real, correctly) runs five
     * ([LexiconImportValidator.CHECK_MANIFEST]/[CHECK_CHECKSUM]/[CHECK_RECORD_SHAPE]/
     * [CHECK_GRAMMAR_SAMPLE]/[CHECK_DUPLICATE_KEYS]) — a genuinely finer-grained validator than the
     * board's four-row sketch, not a defect to shrink. Per this round's own instruction ("the
     * validator may run more checks; fold them under the board's four rows"), this folds — never
     * drops a real result: [CHECK_RECORD_SHAPE] and [CHECK_DUPLICATE_KEYS] (both concern the
     * record set's own structural integrity) fold into one "Record count" row, and
     * [CHECK_GRAMMAR_SAMPLE] (validates each sampled callsign's shape against
     * [org.ort.lexicon.ItuPrefixTable]'s own allocation table) displays as "Prefix table
     * consistency" — the real fact it already checks, under the board's own name for it. A folded
     * row's status is the worse of its parts (`FAILED` over `NOT_REACHED` over `PASSED`) and its
     * detail carries both real facts, never only one.
     */
    private fun foldChecksToBoardRows(checks: List<LexiconCheck>): List<LexiconCheckViewRow> {
        fun find(name: String) = checks.firstOrNull { it.name == name }
        val rows = mutableListOf<LexiconCheckViewRow>()
        find(LexiconImportValidator.CHECK_MANIFEST)?.let { rows += LexiconCheckViewRow(it.name, it.status, it.detail) }
        find(LexiconImportValidator.CHECK_CHECKSUM)?.let { rows += LexiconCheckViewRow(it.name, it.status, it.detail) }
        val shape = find(LexiconImportValidator.CHECK_RECORD_SHAPE)
        val duplicates = find(LexiconImportValidator.CHECK_DUPLICATE_KEYS)
        foldPair("Record count", shape, duplicates)?.let { rows += it }
        find(LexiconImportValidator.CHECK_GRAMMAR_SAMPLE)?.let {
            rows += LexiconCheckViewRow("Prefix table consistency", it.status, it.detail)
        }
        return rows
    }

    /**
     * Combines two real [LexiconCheck]s that only exist as one row on the board — `null` only when
     * neither ran at all (never expected from [LexiconImportValidator.validate], which always runs
     * both, but this stays honestly absent rather than fabricating a row with nothing behind it).
     * When one part is genuinely worse than the other, that part's own real detail carries the row
     * alone — the board's own `Fail-Lexicon.dc.html` example shows exactly this: a record-shape
     * failure's own text verbatim, with no "not reached" from the duplicate-keys check it
     * short-circuited appended alongside it (real, but not what an operator needs when there is
     * already a concrete reason). Two parts at the *same* severity (both passed, or both genuinely
     * failed) both carry real, independent facts, so both join the row's own detail.
     */
    private fun foldPair(displayName: String, a: LexiconCheck?, b: LexiconCheck?): LexiconCheckViewRow? {
        val parts = listOfNotNull(a, b)
        if (parts.isEmpty()) return null
        val worstSeverity = parts.minOf { checkSeverity(it.status) }
        val atWorst = parts.filter { checkSeverity(it.status) == worstSeverity }
        val detail = atWorst.joinToString("; ") { it.detail }
        return LexiconCheckViewRow(displayName, atWorst.first().status, detail)
    }

    /** Lower sorts worse — [CheckStatus.FAILED] first, [CheckStatus.PASSED] last — so
     * [foldPair]'s `minByOrNull` picks the real worst outcome among the folded parts. */
    private fun checkSeverity(status: CheckStatus): Int = when (status) {
        CheckStatus.FAILED -> 0
        CheckStatus.NOT_REACHED -> 1
        CheckStatus.PASSED -> 2
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
 * [current]/[activate] are plain (non-suspend) — the [ActiveLexiconStore] interface
 * [org.ort.lexicon.import.LexiconImportInstaller] calls is deliberately synchronous, since
 * `:lexicon` carries no coroutines dependency. Both call
 * sites here already run on [Dispatchers.IO] ([ModelsController.installLexicon]), so bridging to the
 * DAO's `suspend` functions with [runBlocking] blocks a thread that is already meant for blocking
 * I/O, not the caller's own dispatcher.
 *
 * FR-AST-4 (WP11b's F21 `Fail-Asset-Swap` audit finding): [activate] itself is now the one real
 * gate deciding whether a validated import lands immediately or is staged for later — see its own
 * doc comment.
 */
public class RoomActiveLexiconStore(private val context: Context) : ActiveLexiconStore {

    override fun current(): ActiveLexiconRecord? = runBlocking {
        db().catalogDao().versionsFor(ModelsController.CALLSIGN_LEXICON_ASSET_ID).firstOrNull()?.let {
            ActiveLexiconRecord(it.assetId, it.version, it.recordCount, it.checksum)
        }
    }

    /**
     * FR-AST-4: [LexiconImportInstaller.installValidated] calls this unconditionally on an
     * `Accepted` result (that class's own doc comment) — this is the one real gate that decides
     * whether the write actually lands now. While [CaptureState.isCapturing] this defers to
     * [ModelsController.stageLexiconActivation] instead of writing: the previous lexicon stays
     * active/"usual" for the rest of this session, and the new one becomes real only once
     * [ModelsController.activateStaged] runs after the session ends.
     */
    override fun activate(record: ActiveLexiconRecord) {
        if (CaptureState.isCapturing) {
            ModelsController.stageLexiconActivation(context, record)
            return
        }
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
