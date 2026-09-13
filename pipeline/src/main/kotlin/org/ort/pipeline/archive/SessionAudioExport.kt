package org.ort.pipeline.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ort.core.AttributionState
import org.ort.core.Clock
import org.ort.core.SystemClock
import org.ort.data.OrtDatabase
import org.ort.data.entity.TransmissionEntity
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.export.ExportAttribution
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `Recording-Session.dc.html`'s (RC02) Export action, for the session's **audio** — over audio,
 * the raw continuous archive, or both, as one zip the operator saves or shares through the
 * Storage Access Framework (the same picker
 * [org.ort.app.ui.settings.SettingsExportScreen]'s own `Save file` flow already uses;
 * [write] takes a plain [OutputStream] so `:pipeline` never needs `Uri`/`ContentResolver` on its
 * classpath — the future RC02 caller resolves the operator's chosen `Uri` to a stream and hands it
 * here, the identical shape `org.ort.app.diagnostics.DiagnosticsBundleBuilder.write` already uses
 * for the same reason).
 *
 * **Not a second export format.** `org.ort.app.export.ExportCoordinator`'s four formats
 * (ADIF/CSV/JSON/TEXT) are text-only — none carries audio bytes — and FR-STO-6 already specifies
 * "export of the full database and audio archive" as the one audio-carrying export this product
 * commits to. No other backup/export/archive writer exists anywhere in this codebase today (this
 * package's own report enumerates the search); **this is that format's smallest slice, applied to
 * one session**, not a competing one. [SessionAudioExportManifest]'s own kdoc says how a future
 * whole-database export nests many of these without collision — same zip entry layout
 * (`audio/<sessionId>/…`, `archive/<sessionId>/…`), same manifest shape, one per session.
 *
 * **The container is a zip, FLAC entries [ZipEntry.STORED] rather than re-compressed.** FLAC is
 * already a compressed format — deflating it again wastes CPU for a container that can be
 * gigabytes (the continuous archive, D39) and would gain essentially nothing (see this package's
 * report for measurements). `manifest.json` alone uses the default (deflated) method — small text,
 * where compression is free. Zip specifically (not tar/tar.gz, which nothing else in this codebase
 * uses) because every other bundle-style writer here already standardises on
 * [java.util.zip.ZipOutputStream] (`DiagnosticsBundleBuilder`, `FieldReportBundleBuilder`) and it
 * opens natively on every platform an operator might share to.
 *
 * **Never buffers a whole file, let alone a whole archive, into memory.** [preview] reads only
 * `File.length()` and small database rows; [write] streams every audio file to the target
 * [OutputStream] in [STREAM_BUFFER_BYTES]-sized chunks, computing each [ZipEntry.STORED] entry's
 * CRC32 the same streaming way (Zip's stored method requires the CRC and size *before*
 * `putNextEntry`, so the file is read twice — still bounded memory both times, never `readBytes()`)
 * — see `SessionAudioExportTest`'s own streaming test, which fails for the right reason against a
 * `zip.write(file.readBytes())` implementation.
 *
 * **Excluded from every export, structurally** — this object never queries
 * [org.ort.data.entity.VoiceprintEntity], [org.ort.data.entity.StationEntity.userName]/`.notes`,
 * [org.ort.data.entity.OperatorLocationEntity] or [org.ort.data.dao.TransmissionLabelDao] at all
 * (Q21, `spec/open-questions.md`: training labels stay excluded from every outbound path until
 * decided) — never merely filtered out after being read. This is a **stricter** rule than FR-SPK-20
 * allows for FR-STO-6's own full-database export (which "MAY include \[voiceprints\] only for the
 * user's own device-to-device transfer, and SHALL say so"): RC02's Export is a share action to an
 * unknown destination, not a declared device-to-device restore, so this slice never carries a
 * voiceprint even in the narrow case the full export someday might.
 */
public enum class SessionAudioExportTarget { OVER_AUDIO, RAW_ARCHIVE, BOTH }

/** Typed refusal reasons RC02's Export sheet states verbatim — never a generic thrown exception
 * the UI has to parse (constitution II). */
public sealed interface SessionAudioExportRefusal {
    /** [sessionId] passed to [SessionAudioExport] does not exist. */
    public data object SessionNotFound : SessionAudioExportRefusal

    /** Nothing [SessionAudioExportTarget] asked for is actually available — every audio it named
     * has already been removed (or, for [SessionAudioExportTarget.RAW_ARCHIVE], never existed at
     * all). [reason] names which, plainly, never a bare "nothing found". */
    public data class NothingToExport(public val reason: String) : SessionAudioExportRefusal

    /**
     * [SessionAudioExportTarget.RAW_ARCHIVE]/[SessionAudioExportTarget.BOTH] against the session
     * that is capturing *right now* ([CaptureState]): the continuous archive's own trailing chunk
     * is only flushed at [org.ort.pipeline.capture.ContinuousArchiveAttachment.finishAndAwait],
     * called at session end — reading the archive directory before that could ship a truncated
     * chunk as though it were the whole recording (constitution I).
     *
     * **Over audio for overs already closed is unaffected** — each over's FLAC is written and
     * verified the moment that over ends
     * ([org.ort.capture.android.codec.FlacStore.encodeAndVerify]), independent of whether the
     * session as a whole is still running — so [SessionAudioExportTarget.OVER_AUDIO] alone is
     * never refused for this reason. A caller that wants over audio from a live session should ask
     * for [SessionAudioExportTarget.OVER_AUDIO] explicitly rather than [SessionAudioExportTarget
     * .BOTH]: **this refusal covers the whole request**, including the over-audio half of a
     * [SessionAudioExportTarget.BOTH] ask, rather than silently downgrading it to over-audio-only —
     * a caller that asked for both and receives only one, unannounced, is exactly the silent
     * scope-narrowing constitution I forbids (see `SettingsPolling.export`'s own `RANGE` handling
     * for the identical reasoning: disable/refuse rather than silently reinterpret the request).
     */
    public data object ArchiveCapturingNow : SessionAudioExportRefusal
}

/** One file [SessionAudioExport.write] will add to the zip, with the real, current byte size
 * [SessionAudioExport.preview] reports it at — never an estimate (constitution VI). */
public data class SessionAudioExportFile(
    public val entryName: String,
    public val sourceFile: File,
    public val sizeBytes: Long,
)

/** [totalBytes] is `manifestBytes + `[files]`.sumOf { it.sizeBytes }` — the exact figure
 * [SessionAudioExport.write] would produce, resolved by the same [buildExportPlan] both call, so a
 * preview can never drift from the real write (the same "read twice, write once" discipline
 * `org.ort.app.export.ExportCoordinator.previewSizeBytes`'s own kdoc already documents). */
public data class SessionAudioExportPreview(
    public val sessionId: String,
    public val target: SessionAudioExportTarget,
    public val files: List<SessionAudioExportFile>,
    public val manifestBytes: Long,
) {
    public val totalBytes: Long get() = manifestBytes + files.sumOf { it.sizeBytes }
}

public sealed interface SessionAudioExportWriteResult {
    public data class Refused(public val reason: SessionAudioExportRefusal) : SessionAudioExportWriteResult

    public data class Written(public val fileCount: Int, public val totalBytes: Long) : SessionAudioExportWriteResult
}

/** One resolved plan, shared by [SessionAudioExport.preview] and [SessionAudioExport.write] so the
 * two can never disagree about what a session's export actually contains. */
private data class ExportPlan(
    val manifest: SessionAudioExportManifest,
    val manifestJson: ByteArray,
    val files: List<SessionAudioExportFile>,
)

public object SessionAudioExport {

    /** Schema version 1 — see [SessionAudioExportManifest]'s own kdoc for what bumping it means. */
    public const val MANIFEST_FORMAT_VERSION: Int = 1

    public const val MANIFEST_ENTRY_NAME: String = "manifest.json"

    /** 64 KiB — bounded, chosen the same way [org.ort.pipeline.capture.ContinuousArchiveAttachment
     * .DEFAULT_CAPACITY]'s own kdoc derives its buffer from a real, stated constraint rather than
     * a round number: large enough that a gigabyte-scale archive does not spend its time in read()
     * syscall overhead, small enough that it is nowhere close to "the whole file". */
    private const val STREAM_BUFFER_BYTES: Int = 64 * 1024

    private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC)

    private const val OVER_AUDIO_DIR_NAME = "audio"

    /**
     * `ort-session-<sessionId>-<target word>-<UTC timestamp>.zip` — mirrors
     * `org.ort.app.export.ExportCoordinator.suggestedFileName`'s own shape (scope word, UTC
     * timestamp, real extension) but cannot literally share that function: `:pipeline` may not
     * depend on `:app` (module graph; `ExportCoordinator` lives in `org.ort.app.export`), so this
     * is the identical *pattern*, not shared code, on this side of the dependency boundary.
     */
    public fun suggestedFileName(
        sessionId: String,
        target: SessionAudioExportTarget,
        now: Instant = Instant.now(),
    ): String {
        val targetWord = when (target) {
            SessionAudioExportTarget.OVER_AUDIO -> "overs"
            SessionAudioExportTarget.RAW_ARCHIVE -> "archive"
            SessionAudioExportTarget.BOTH -> "audio"
        }
        return "ort-session-$sessionId-$targetWord-${FILE_STAMP.format(now)}.zip"
    }

    /** `null` means export is allowed; otherwise the typed reason RC02 states verbatim. Checked
     * fresh on every call — [CaptureState] is never cached (the same discipline
     * [SessionAudioDeletionService.canDelete]'s own kdoc holds itself to). */
    public suspend fun canExport(
        db: OrtDatabase,
        sessionId: String,
        target: SessionAudioExportTarget,
    ): SessionAudioExportRefusal? = withContext(Dispatchers.IO) {
        val session = db.sessionDao().getById(sessionId)
            ?: return@withContext SessionAudioExportRefusal.SessionNotFound

        val needsArchive = target != SessionAudioExportTarget.OVER_AUDIO
        if (needsArchive && CaptureState.isCapturing && CaptureState.sessionId == sessionId) {
            return@withContext SessionAudioExportRefusal.ArchiveCapturingNow
        }

        val overAudioAvailable = session.overAudioRemovedAtMillis == null
        val archiveAvailable = session.archiveState == "KEPT"
        val nothingAvailable = when (target) {
            SessionAudioExportTarget.OVER_AUDIO -> !overAudioAvailable
            SessionAudioExportTarget.RAW_ARCHIVE -> !archiveAvailable
            SessionAudioExportTarget.BOTH -> !overAudioAvailable && !archiveAvailable
        }
        if (nothingAvailable) {
            return@withContext SessionAudioExportRefusal.NothingToExport(
                nothingToExportReason(sessionId, target, session.overAudioRemovedAtMillis, session.archiveState),
            )
        }
        null
    }

    /**
     * `null` only for an unknown [sessionId] (mirrors
     * [SessionAudioDeletionService.preview]'s own null-for-unknown shape). Unlike [canExport], this
     * never refuses — it answers "what would this write, right now", even for a target
     * [canExport] would refuse, so RC02 can show real figures on a disabled control rather than
     * nothing at all.
     */
    public suspend fun preview(
        db: OrtDatabase,
        filesDir: File,
        sessionId: String,
        target: SessionAudioExportTarget,
    ): SessionAudioExportPreview? = withContext(Dispatchers.IO) {
        val session = db.sessionDao().getById(sessionId) ?: return@withContext null
        val plan = buildExportPlan(db, filesDir, sessionId, target, session)
        SessionAudioExportPreview(
            sessionId = sessionId,
            target = target,
            files = plan.files,
            manifestBytes = plan.manifestJson.size.toLong(),
        )
    }

    /**
     * Refuses exactly as [canExport] would (checked first, before anything is written to [out]),
     * else streams `manifest.json` followed by every [SessionAudioExportFile] to [out] as one zip
     * — never loading a whole file, let alone the whole archive, into memory (class kdoc).
     * [onProgress] is called after each entry (the manifest counts as one) with the cumulative
     * bytes written and the real total from the same plan — never a fabricated percentage.
     */
    public suspend fun write(
        db: OrtDatabase,
        filesDir: File,
        sessionId: String,
        target: SessionAudioExportTarget,
        out: OutputStream,
        clock: Clock = SystemClock,
        onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): SessionAudioExportWriteResult = withContext(Dispatchers.IO) {
        canExport(db, sessionId, target)?.let { return@withContext SessionAudioExportWriteResult.Refused(it) }

        val session = db.sessionDao().getById(sessionId)
            ?: return@withContext SessionAudioExportWriteResult.Refused(SessionAudioExportRefusal.SessionNotFound)
        val plan = buildExportPlan(db, filesDir, sessionId, target, session, clock)
        val totalBytes = plan.manifestJson.size.toLong() + plan.files.sumOf { it.sizeBytes }
        var written = 0L

        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY_NAME))
            zip.write(plan.manifestJson)
            zip.closeEntry()
            written += plan.manifestJson.size
            onProgress(written, totalBytes)

            for (file in plan.files) {
                putStoredFileEntry(zip, file.entryName, file.sourceFile)
                written += file.sizeBytes
                onProgress(written, totalBytes)
            }
        }

        SessionAudioExportWriteResult.Written(fileCount = plan.files.size, totalBytes = totalBytes)
    }

    // -- plan building (shared by preview and write) -------------------------------------------

    private suspend fun buildExportPlan(
        db: OrtDatabase,
        filesDir: File,
        sessionId: String,
        target: SessionAudioExportTarget,
        session: org.ort.data.entity.SessionEntity,
        clock: Clock = SystemClock,
    ): ExportPlan {
        val includeOverAudio = target != SessionAudioExportTarget.RAW_ARCHIVE &&
            session.overAudioRemovedAtMillis == null
        val includeArchive = target != SessionAudioExportTarget.OVER_AUDIO && session.archiveState == "KEPT"

        val transmissions = db.transmissionDao().listBySession(sessionId)
        val overFiles = mutableListOf<SessionAudioExportFile>()
        val overManifests = transmissions.map { transmission ->
            val (manifest, file) = buildOverManifest(db, filesDir, sessionId, transmission, includeOverAudio)
            file?.let { overFiles += it }
            manifest
        }

        val archiveFiles = if (includeArchive) {
            archiveChunkFiles(filesDir, sessionId)
        } else {
            emptyList()
        }

        val archiveState = archiveStateOf(session.archiveState)
        val manifest = SessionAudioExportManifest(
            formatVersion = MANIFEST_FORMAT_VERSION,
            sessionId = sessionId,
            startedAtUtcMillis = session.startedAt,
            endedAtUtcMillis = session.endedAt,
            appVersion = session.appVersion,
            exportedAtUtcMillis = clock.wallMillis(),
            target = target,
            overAudio = SessionAudioExportAudioHalf(
                removedAtUtcMillis = session.overAudioRemovedAtMillis,
                includedInThisExport = includeOverAudio,
            ),
            archive = SessionAudioExportArchiveHalf(
                state = archiveState,
                removedAtUtcMillis = session.archiveRemovedAtMillis,
                includedInThisExport = includeArchive,
            ),
            overs = overManifests,
        )
        val manifestJson = SessionAudioManifestWriter.write(manifest).toByteArray(Charsets.UTF_8)
        return ExportPlan(manifest, manifestJson, overFiles + archiveFiles)
    }

    private suspend fun buildOverManifest(
        db: OrtDatabase,
        filesDir: File,
        sessionId: String,
        transmission: TransmissionEntity,
        includeOverAudio: Boolean,
    ): Pair<SessionAudioExportOverManifest, SessionAudioExportFile?> {
        val transcript = db.transcriptDao().getCurrent(transmission.id)
        val station = transmission.stationId?.let { db.catalogDao().getStation(it) }
        val callsign = station?.callsign?.takeIf { it.isNotBlank() } ?: transmission.stationId
        val attribution = toManifestAttribution(transmission, callsign)

        val source = File(filesDir, transmission.audioPath())
        val audioIncluded = includeOverAudio && source.isFile
        val file = if (audioIncluded) {
            SessionAudioExportFile(
                entryName = "$OVER_AUDIO_DIR_NAME/$sessionId/${transmission.id}.flac",
                sourceFile = source,
                sizeBytes = source.length(),
            )
        } else {
            null
        }

        val manifest = SessionAudioExportOverManifest(
            transmissionId = transmission.id,
            startedAtUtcMillis = transmission.startedAtUtc,
            endedAtUtcMillis = transmission.endedAtUtc,
            durationMs = transmission.durationMs,
            frequencyHz = transmission.frequencyHz,
            frequencyProvenance = transmission.frequencyProvenance,
            mode = transmission.mode,
            attribution = attribution,
            transcriptText = transcript?.text,
            transcriptModelId = transcript?.modelId,
            transcriptModelVersion = transcript?.modelVersion,
            processedTier = transmission.processedTier?.name,
            executionProvider = transmission.executionProvider,
            audioIncluded = audioIncluded,
        )
        return manifest to file
    }

    /** Every chunk file under `archive/<sessionId>/`, oldest-sample-first — cosmetic ordering only
     * (each chunk's filename already carries its own `startSample`, so a reader never needs zip
     * order to reconstruct the timeline); falls back to name order for a filename this pattern
     * does not match rather than throwing. */
    private fun archiveChunkFiles(filesDir: File, sessionId: String): List<SessionAudioExportFile> {
        val dir = File(filesDir, "$ARCHIVE_DIR_NAME/$sessionId")
        val chunkPattern = Regex("""chunk-(\d+)\.flac""")
        return (dir.listFiles { f -> f.isFile && f.name.endsWith(".flac") } ?: emptyArray())
            .sortedBy { chunkPattern.find(it.name)?.groupValues?.get(1)?.toLongOrNull() ?: Long.MAX_VALUE }
            .map { chunk ->
                SessionAudioExportFile(
                    entryName = "$ARCHIVE_DIR_NAME/$sessionId/${chunk.name}",
                    sourceFile = chunk,
                    sizeBytes = chunk.length(),
                )
            }
    }

    private fun nothingToExportReason(
        sessionId: String,
        target: SessionAudioExportTarget,
        overAudioRemovedAtMillis: Long?,
        archiveState: String?,
    ): String {
        val overAudioFact = if (overAudioRemovedAtMillis != null) {
            "over audio was removed at $overAudioRemovedAtMillis"
        } else {
            null
        }
        val archiveFact = when (archiveState) {
            "REMOVED" -> "the raw archive was removed"
            else -> "no continuous archive was ever kept for this session"
        }
        val facts = when (target) {
            SessionAudioExportTarget.OVER_AUDIO -> listOfNotNull(overAudioFact)
            SessionAudioExportTarget.RAW_ARCHIVE -> listOf(archiveFact)
            SessionAudioExportTarget.BOTH -> listOfNotNull(overAudioFact, archiveFact)
        }
        return "nothing to export for session $sessionId: " + facts.joinToString("; ")
    }

    private fun archiveStateOf(raw: String?): ArchiveState = when (raw) {
        "KEPT" -> ArchiveState.KEPT
        "REMOVED" -> ArchiveState.REMOVED
        else -> ArchiveState.NONE
    }

    /**
     * Mirrors `org.ort.app.export.ExportCoordinator.toExportAttribution` exactly (that function is
     * `internal` to `:app` and `:pipeline` may not depend on `:app` regardless — see this
     * package's own report for why this is a second, pipeline-local implementation of the same
     * rule rather than shared code). See that function's own kdoc (register R-1039) for why a
     * `CONFIRMED`/`INFERRED` row can still reach here with [callsign] `null` despite a resolved
     * state, and why that is a real data-integrity defect worth stating rather than hiding.
     */
    internal fun toManifestAttribution(transmission: TransmissionEntity, callsign: String?): ExportAttribution =
        when (transmission.attributionState) {
            AttributionState.CONFIRMED -> if (!callsign.isNullOrBlank()) {
                ExportAttribution.Confirmed(
                    callsign = callsign,
                    confidence = transmission.attributionConfidence,
                    corrected = transmission.corrected,
                )
            } else {
                ExportAttribution.UnresolvedCallsign(
                    state = AttributionState.CONFIRMED,
                    reason = "CONFIRMED transmission ${transmission.id} has no station id recorded — " +
                        "data integrity defect",
                )
            }
            AttributionState.INFERRED -> if (!callsign.isNullOrBlank()) {
                ExportAttribution.Inferred(
                    callsign = callsign,
                    confidence = transmission.attributionConfidence,
                    corrected = transmission.corrected,
                )
            } else {
                ExportAttribution.UnresolvedCallsign(
                    state = AttributionState.INFERRED,
                    reason = "INFERRED transmission ${transmission.id} has no station id recorded — " +
                        "data integrity defect",
                )
            }
            AttributionState.AMBIGUOUS -> ExportAttribution.Ambiguous
            AttributionState.UNKNOWN -> ExportAttribution.Unknown
        }

    // -- streaming zip write ---------------------------------------------------------------------

    /** [ZipEntry.STORED] (never re-compressed, class kdoc): Zip's stored method requires
     * `size`/`compressedSize`/`crc` set *before* [ZipOutputStream.putNextEntry], so [file] is read
     * once to compute the CRC32 and once more to copy — both passes streamed in
     * [STREAM_BUFFER_BYTES] chunks, never [File.readBytes]. */
    private fun putStoredFileEntry(zip: ZipOutputStream, name: String, file: File) {
        val size = file.length()
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            this.size = size
            compressedSize = size
            crc = crc32Of(file)
        }
        zip.putNextEntry(entry)
        file.inputStream().use { input ->
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                zip.write(buffer, 0, read)
            }
        }
        zip.closeEntry()
    }

    private fun crc32Of(file: File): Long {
        val crc = CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }
}
