package org.ort.pipeline.capture

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ort.core.Clock
import org.ort.core.SystemClock
import org.ort.data.OrtDatabase
import java.io.File

/**
 * FR-STO-5 (register R-133): `Settings-Storage.dc.html`'s four-segment usage bar (Audio / Models /
 * Records / Lexicon) and its "Next deletion" row, sourced honestly. Before this existed, WP10 had
 * no way to render either without inventing numbers: `:app` cannot see the retained-audio directory,
 * the models directory or the Room database file the way `:pipeline`/`RealCaptureService` already
 * can (the same reason [StorageForecast]'s `audioDirectoryBytes` measurement lives here and not in
 * `:app`).
 *
 * **A pure, on-demand measurement, not a holder.** Unlike [StorageForecast]/[ShedStatus] this file
 * deliberately does not publish a process-wide `state` — nothing in this package ticks it today
 * (no caller was asked for; see this package's report). [measure] is meant to be called by whoever
 * renders `Settings-Storage` (WP10), on whatever cadence that screen needs — "cached with the same
 * cadence as the forecast" describes how a caller should use the result, not a mandate that this
 * object cache one itself, matching [StorageForecast.update]'s own caller-driven pattern.
 *
 * **`lexiconBytes` reads 0 today, honestly, not as a placeholder bug.** `LexiconImportInstaller`
 * (`:lexicon`) only ever persists an *[org.ort.lexicon.import.ActiveLexiconRecord]* — assetId,
 * version, record count, checksum — through `:app`'s `RoomActiveLexiconStore`; the validated TSV
 * file itself is never copied anywhere durable (it is read once from `:app`'s cache and discarded).
 * There is today no on-disk "lexicon asset" directory this measurement could honestly report a
 * non-zero figure for — see this package's report for the disclosure and what would need to change
 * for `lexiconBytes` to mean something. [measureDirectoryBytes] on a directory that does not exist
 * yet correctly answers `0L`, the same honest-absence answer [StorageForecast]'s own
 * `audioDirectoryBytes()` already gives before any audio has been captured.
 */
public data class StorageAccounting(
    public val audioBytes: Long,
    public val modelBytes: Long,
    public val recordBytes: Long,
    public val lexiconBytes: Long,
    /**
     * WPG (FR-AST-3a, AC-139): the real, on-disk size of every asset
     * `org.ort.app.assets.BundledAssetInstaller` verified as shipped inside the artifact — deliberately
     * **excluded** from [totalBytes] and reported as its own figure, per FR-AST-3a ("storage
     * pressure from bundled assets SHALL NOT count against the operator's retention budget — it is
     * not their recording, and presenting it as if it were would make the budget lie"). Before
     * WPG, every file under `models/` was something the operator explicitly downloaded or
     * side-loaded, so [modelBytes] counting it against the budget was correct; after WPG most of
     * that directory is bundled, so this figure is subtracted out of [modelBytes] rather than left
     * double-reported.
     */
    public val bundledBytes: Long,
    public val measuredAtMillis: Long,
    /**
     * WPARC (FR-STO-3, D39): the continuous archive's own real, on-disk size (`archive/`, a
     * sibling of `audio/` — never counted inside it, so the archive can never silently consume
     * the over-audio budget or vice versa). Deliberately **excluded** from [totalBytes], the same
     * "reported on its own, never folded in" treatment [bundledBytes] gets (AC-139) — the archive
     * is governed by its own independent budget (FR-STO-3d), not the gated-audio one.
     */
    public val archiveBytes: Long = 0L,
) {
    /** The operator's own retention budget figure — deliberately excludes [bundledBytes] and
     * [archiveBytes] (AC-139, FR-STO-3). */
    public val totalBytes: Long get() = audioBytes + modelBytes + recordBytes + lexiconBytes
}

/**
 * FR-STO-3e, D40, AC-157 (register, coordinator amendment): whether the **over-audio** budget is
 * currently exceeded — a plain, derivable fact recomputed from real measured usage and the
 * persisted budget every time it is read, **never a one-time event**. AC-157: "the warning
 * persists for as long as the budget stays exceeded, and survives app restarts" — this is true by
 * construction here, because nothing about [exceeded] is cached or fired once; a caller polling
 * this at [org.ort.app.ui.data.LiveBarPolling]'s own cadence always sees the current truth,
 * process restart included. There is deliberately no pruning tied to this state at all: D40
 * forbids deleting over audio by any means (register R-1037) — reaching this budget only ever
 * warns.
 */
public data class OverAudioBudgetState(
    public val usedBytes: Long,
    /** `null` = no budget set (FR-STO-3's own distinct third state — never confused with `0` or
     * "unlimited"). */
    public val budgetBytes: Long?,
    public val exceeded: Boolean,
)

/** FR-STO-3e, D40, AC-157: [usedBytes] is [StorageAccounting.audioBytes] (already measured);
 * [budgetGb] is `SettingsStore.audioBudgetGb`, read fresh by the caller every poll — this function
 * itself holds no state at all, so "restart" changes nothing about what it reports. */
public fun overAudioBudgetState(usedBytes: Long, budgetGb: Int?): OverAudioBudgetState {
    val budgetBytes = budgetGb?.let { it * BYTES_PER_GB }
    return OverAudioBudgetState(usedBytes, budgetBytes, exceeded = budgetBytes != null && usedBytes > budgetBytes)
}

private const val BYTES_PER_GB: Long = 1_000_000_000L

/**
 * One session's contribution to retained storage, oldest-first-ordered by [collectSessionStorageSummaries]
 * — the unit [computeNextDeletion] and `Settings-Storage`'s "Next deletion" row name (D26: retention
 * prunes whole sessions, not individual transmissions).
 */
public data class SessionStorageSummary(
    public val sessionId: String,
    public val startedAtMillis: Long,
    public val overCount: Int,
    public val bytes: Long,
)

/**
 * FR-STO-3a/D26 (register R-133): the session automatic oldest-first pruning would delete *first*,
 * right now, under [budgetBytes] and [floorBytes] — computed whether or not automatic pruning
 * (`SettingsStore.autoPruneEnabled`, `:app`, unreachable from `:pipeline`) is actually turned on, so
 * `Settings-Storage` can honestly preview what pruning *would* do the moment a budget is reached,
 * not only report after the fact.
 */
public data class NextDeletion(
    public val sessionId: String,
    public val startedAtMillis: Long,
    public val overCount: Int,
    public val bytes: Long,
    public val predictedAtMillis: Long,
)

/**
 * FR-STO-5: measures [StorageAccounting]'s four segments from the real filesystem/database, on
 * [Dispatchers.IO] so a caller on the main thread (a Compose `LaunchedEffect`, WP10's polling) never
 * blocks on it. [databaseFiles] is a list, not one path, because WAL journal mode (the real mode
 * [OrtDatabase.create] uses outside tests) splits one logical database across `ort.db`/`ort.db-wal`/
 * `ort.db-shm` — "Records" must count all three or silently understate real usage; a caller passes
 * whichever of them currently exist (a fresh WAL checkpoint can remove `-wal`/`-shm` between reads).
 */
public suspend fun measureStorageAccounting(
    filesDir: File,
    databaseFiles: List<File>,
    clock: Clock = SystemClock,
): StorageAccounting = withContext(Dispatchers.IO) {
    val modelsDir = File(filesDir, "models")
    val bundled = bundledDestinations(filesDir).filter { it.isFile }
    val bundledBytes = bundled.sumOf { it.length() }
    val bundledUnderModels = bundled
        .filter { it.toPath().normalize().startsWith(modelsDir.toPath().normalize()) }
        .sumOf { it.length() }
    StorageAccounting(
        audioBytes = measureDirectoryBytes(File(filesDir, "audio")),
        modelBytes = measureDirectoryBytes(modelsDir) - bundledUnderModels,
        recordBytes = databaseFiles.filter { it.isFile }.sumOf { it.length() },
        lexiconBytes = measureDirectoryBytes(File(filesDir, "lexicon")),
        bundledBytes = bundledBytes,
        measuredAtMillis = clock.wallMillis(),
        archiveBytes = measureDirectoryBytes(File(filesDir, "archive")),
    )
}

/** `0L` for a directory that does not exist — never a fabricated non-zero figure (constitution I). */
public fun measureDirectoryBytes(dir: File): Long =
    if (dir.isDirectory) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L

/**
 * WPG (FR-AST-3a, AC-139): the relative paths `org.ort.app.assets.BundledAssetInstaller` recorded
 * (in [BUNDLED_ASSETS_MANIFEST_FILENAME], under [filesDir]) as bundled-asset destinations it
 * actually verified and installed. Duplicated filename constant, not a shared import: `:pipeline`
 * has no compile dependency on `:app` (technical design §2, `dependencyRules`) — the filesystem
 * itself is the contract, the same way [org.ort.pipeline.passb.AsrModelLocator]'s fixed path is
 * the contract between `:net`'s fetch and `:pipeline`'s own model lookup. A missing manifest (a
 * build before WPG landed, or a fresh device where nothing has installed yet) reads as "no bundled
 * bytes to exclude", never a crash — constitution I's honest-absence, not a fabricated zero hiding
 * a real problem, since an absent manifest genuinely means no bundled asset has verified yet.
 */
private fun bundledDestinations(filesDir: File): List<File> {
    val manifest = File(filesDir, BUNDLED_ASSETS_MANIFEST_FILENAME)
    if (!manifest.isFile) return emptyList()
    return manifest.readLines().map { it.trim() }.filter { it.isNotEmpty() }.map { File(filesDir, it) }
}

private const val BUNDLED_ASSETS_MANIFEST_FILENAME = "bundled_assets.manifest"

/**
 * FR-STO-5/D26: every session with retained audio, oldest-first (`SessionEntity.startedAt`
 * ascending — [org.ort.data.dao.SessionDao.listAll] itself returns newest-first, for the Sessions
 * list; this reverses it, deliberately, for the opposite reason retention needs). [overCount] is a
 * real [org.ort.data.dao.TransmissionDao.listBySession] count, not the session's own denormalised
 * counters (`RealCaptureService` does not maintain one). No pinning exclusion: FR-STO-7 ("support
 * pinning a thread or transmission so retention never deletes it") has no schema field yet — see
 * this package's report.
 */
public suspend fun collectSessionStorageSummaries(db: OrtDatabase, filesDir: File): List<SessionStorageSummary> =
    withContext(Dispatchers.IO) {
        db.sessionDao().listAll()
            .sortedBy { it.startedAt }
            .map { session ->
                SessionStorageSummary(
                    sessionId = session.id,
                    startedAtMillis = session.startedAt,
                    overCount = db.transmissionDao().listBySession(session.id).size,
                    bytes = measureDirectoryBytes(File(filesDir, "audio/${session.id}")),
                )
            }
    }

/**
 * FR-STO-3a/D26 (register R-133): the first (oldest) entry of [sessionsOldestFirst] would be
 * automatic pruning's first deletion right now — but only when there is actually a reason to prune:
 * [audioBytesUsed] over an explicitly-set [budgetBytes] (`null` = "no budget set", never treated as
 * either 0 or unlimited — see `SettingsStore.audioBudgetGb`'s own kdoc), or [freeBytes] already at
 * or below [floorBytes] (the same hard floor [storageFloorBreached] uses — an emergency even a
 * caller with no budget configured needs to see coming). Neither condition true returns `null`,
 * honestly — "nothing would be deleted", not an empty placeholder.
 */
public fun computeNextDeletion(
    sessionsOldestFirst: List<SessionStorageSummary>,
    audioBytesUsed: Long,
    budgetBytes: Long?,
    floorBytes: Long,
    freeBytes: Long,
    nowMillis: Long,
): NextDeletion? {
    val overBudget = budgetBytes != null && audioBytesUsed > budgetBytes
    val atOrBelowFloor = freeBytes <= floorBytes
    if (!overBudget && !atOrBelowFloor) return null
    val oldest = sessionsOldestFirst.firstOrNull() ?: return null
    return NextDeletion(
        sessionId = oldest.sessionId,
        startedAtMillis = oldest.startedAtMillis,
        overCount = oldest.overCount,
        bytes = oldest.bytes,
        predictedAtMillis = nowMillis,
    )
}
