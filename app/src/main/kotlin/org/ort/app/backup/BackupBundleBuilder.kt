package org.ort.app.backup

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.ort.data.OrtDatabase
import org.ort.data.entity.CorrectionEntity
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.ThreadEntity
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.data.entity.VoiceprintEntity
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** FR-STO-6's own format version — bumped only if a future change to this bundle's own shape is
 * not backward-readable by [BackupRestoreCoordinator] as it stands today. */
public const val BACKUP_FORMAT_VERSION: Int = 1

public const val BACKUP_MANIFEST_ENTRY: String = "manifest.json"
public const val BACKUP_SESSIONS_ENTRY: String = "sessions.json"
public const val BACKUP_TRANSMISSIONS_ENTRY: String = "transmissions.json"
public const val BACKUP_TRANSCRIPTS_ENTRY: String = "transcripts.json"
public const val BACKUP_CORRECTIONS_ENTRY: String = "corrections.json"
public const val BACKUP_STATIONS_ENTRY: String = "stations.json"
public const val BACKUP_VOICEPRINTS_ENTRY: String = "voiceprints.json"
public const val BACKUP_THREADS_ENTRY: String = "threads.json"
public const val BACKUP_AUDIO_ENTRY_PREFIX: String = "audio/"

/** [BackupBundleBuilder.preview]'s own real counts and total byte size — never a second,
 * independently-scoped estimate from [BackupBundleBuilder.write]'s own real output, the same
 * "read twice, write once" discipline [org.ort.app.export.ExportCoordinator.previewSizeBytes]'s
 * own kdoc already documents for its sibling screen. */
public data class BackupPreview(
    val sessionCount: Int,
    val transmissionCount: Int,
    val correctionCount: Int,
    val audioFileCount: Int,
    val stationCount: Int,
    val voiceprintCount: Int,
    val threadCount: Int,
    val totalSizeBytes: Long,
)

/**
 * FR-STO-6 (M): "Support export of the full database and audio archive to user-chosen storage."
 * **Genuinely new** — not a rename or extension of [org.ort.app.diagnostics.localsave
 * .LocalSaveBundleBuilder], which writes *rendered, scrubbed diagnostics* for a human to read
 * after a failed field session (see that object's own kdoc); this object writes the real
 * `session`/`transmission`/`transcript`/`correction`/`station`/`voiceprint`/`thread` rows (via
 * [BackupRecordCodecs.kt]'s own codecs — see that file's top-of-file kdoc for exactly which
 * tables and why only those) plus every retained over-audio file, so a reinstall or a new phone
 * can get the log back, not a log report about it.
 *
 * **Unlike every export writer in `org.ort.app.export`, this bundle is not filtered against the
 * export screen's own "never included" promise** — `Settings-Export.dc.html`'s banner is about
 * what leaves the device through *export*; a backup the operator restores onto their own next
 * phone is the "your own device-to-device transfer" case FR-SPK-20 states voiceprints/embeddings
 * MAY travel through, and station knowledge/user-supplied names are the operator's own log of
 * their own station, not a third party's.
 *
 * **Register R-1094 widened this bundle to seven tables**: sessions, transmissions, every
 * transcript version (current and superseded — constitution III), corrections, the station
 * catalog, voiceprints and threads — see `BackupRecordCodecs.kt`'s own top-of-file kdoc for
 * exactly which columns of each, and why [org.ort.data.entity.StationEntity
 * .overCountsByAttributionState] specifically is never carried as a trusted value. What remains
 * out of scope (lattice/candidate detail, calibration, assets, digests, and the rest) is a stated
 * limitation (`SettingsBackupScreen`'s own "what this backup does not yet carry" list), not a
 * silent gap.
 *
 * A zip, written with [ZipOutputStream] directly onto the caller's [OutputStream] — the identical
 * shape [LocalSaveBundleBuilder][org.ort.app.diagnostics.localsave.LocalSaveBundleBuilder] and
 * [org.ort.app.diagnostics.DiagnosticsBundleBuilder] already use, not a third, differently-shaped
 * writer. Audio entries are `STORED` (already-compressed FLAC — deflating it again wastes CPU for
 * no size benefit, the same choice `:pipeline`'s `SessionAudioExport` already makes for the
 * identical reason); every JSON entry is left at the default `DEFLATED` method.
 */
public object BackupBundleBuilder {

    private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC)

    public fun suggestedFileName(now: Instant = Instant.now()): String = "ort-backup-${FILE_STAMP.format(now)}.zip"

    public suspend fun preview(context: Context, databaseName: String = OrtDatabase.DATABASE_NAME): BackupPreview =
        withContext(Dispatchers.IO) {
            previewFrom(context, OrtDatabase.create(context.applicationContext, name = databaseName))
        }

    public suspend fun write(
        context: Context,
        target: OutputStream,
        databaseName: String = OrtDatabase.DATABASE_NAME,
    ): Unit = withContext(Dispatchers.IO) {
        writeFrom(context, OrtDatabase.create(context.applicationContext, name = databaseName), target)
    }

    /**
     * The same real work [preview] does, taking an already-open [db] directly rather than opening
     * one from [context] and a name — [preview]'s own real path for production, and the path a
     * test uses to seed and export from one database instance it already holds, without a second,
     * independent [OrtDatabase.create] call for the identical on-disk path (`internal`, not
     * `private`, purely so `BackupBundleBuilderTest`/`BackupRestoreCoordinatorTest` can call it
     * directly).
     */
    internal suspend fun previewFrom(context: Context, db: OrtDatabase): BackupPreview = withContext(Dispatchers.IO) {
        val bundle = collect(context, db)
        BackupPreview(
            sessionCount = bundle.sessions.size,
            transmissionCount = bundle.transmissions.size,
            correctionCount = bundle.corrections.size,
            audioFileCount = bundle.audioFiles.size,
            stationCount = bundle.stations.size,
            voiceprintCount = bundle.voiceprints.size,
            threadCount = bundle.threads.size,
            totalSizeBytes = estimateSizeBytes(bundle),
        )
    }

    /** [previewFrom]'s own sibling for [write] — see that function's own kdoc. */
    internal suspend fun writeFrom(context: Context, db: OrtDatabase, target: OutputStream): Unit =
        withContext(Dispatchers.IO) {
            val bundle = collect(context, db)
            ZipOutputStream(target).use { zip ->
                writeJsonEntry(zip, BACKUP_MANIFEST_ENTRY, manifestJson(bundle))
                writeJsonEntry(zip, BACKUP_SESSIONS_ENTRY, JSONArray(bundle.sessions.map(SessionCodec::toJson)))
                writeJsonEntry(
                    zip,
                    BACKUP_TRANSMISSIONS_ENTRY,
                    JSONArray(bundle.transmissions.map(TransmissionCodec::toJson)),
                )
                writeJsonEntry(
                    zip,
                    BACKUP_TRANSCRIPTS_ENTRY,
                    JSONArray(bundle.transcripts.map(TranscriptCodec::toJson)),
                )
                writeJsonEntry(
                    zip,
                    BACKUP_CORRECTIONS_ENTRY,
                    JSONArray(bundle.corrections.map(CorrectionCodec::toJson)),
                )
                writeJsonEntry(zip, BACKUP_STATIONS_ENTRY, JSONArray(bundle.stations.map(StationCodec::toJson)))
                writeJsonEntry(
                    zip,
                    BACKUP_VOICEPRINTS_ENTRY,
                    JSONArray(bundle.voiceprints.map(VoiceprintCodec::toJson)),
                )
                writeJsonEntry(zip, BACKUP_THREADS_ENTRY, JSONArray(bundle.threads.map(ThreadCodec::toJson)))
                for ((entryName, source) in bundle.audioFiles) {
                    zip.putNextEntry(storedEntry(entryName, source))
                    source.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }

    private fun manifestJson(bundle: CollectedBackup): JSONObject = JSONObject().apply {
        put("formatVersion", BACKUP_FORMAT_VERSION)
        put("exportedAtMillis", System.currentTimeMillis())
        put("sessionCount", bundle.sessions.size)
        put("transmissionCount", bundle.transmissions.size)
        put("transcriptCount", bundle.transcripts.size)
        put("correctionCount", bundle.corrections.size)
        put("stationCount", bundle.stations.size)
        put("voiceprintCount", bundle.voiceprints.size)
        put("threadCount", bundle.threads.size)
        put("audioFileCount", bundle.audioFiles.size)
    }

    private fun writeJsonEntry(zip: ZipOutputStream, name: String, json: Any) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    /** [ZipEntry.STORED] needs the exact size and CRC32 known *before* the entry is opened — a
     * second, streaming pass over [source] (audio is never held whole in memory; the same
     * discipline `:pipeline`'s `SessionAudioExport` already documents for its own FLAC entries). */
    private fun storedEntry(name: String, source: File): ZipEntry {
        val checksum = java.util.zip.CRC32()
        source.inputStream().use { input ->
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                checksum.update(buffer, 0, read)
            }
        }
        return ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = source.length()
            compressedSize = source.length()
            crc = checksum.value
        }
    }

    private fun estimateSizeBytes(bundle: CollectedBackup): Long {
        val jsonBytes = listOf(
            manifestJson(bundle).toString(),
            JSONArray(bundle.sessions.map(SessionCodec::toJson)).toString(),
            JSONArray(bundle.transmissions.map(TransmissionCodec::toJson)).toString(),
            JSONArray(bundle.transcripts.map(TranscriptCodec::toJson)).toString(),
            JSONArray(bundle.corrections.map(CorrectionCodec::toJson)).toString(),
            JSONArray(bundle.stations.map(StationCodec::toJson)).toString(),
            JSONArray(bundle.voiceprints.map(VoiceprintCodec::toJson)).toString(),
            JSONArray(bundle.threads.map(ThreadCodec::toJson)).toString(),
        ).sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
        val audioBytes = bundle.audioFiles.sumOf { it.second.length() }
        return jsonBytes + audioBytes
    }

    private data class CollectedBackup(
        val sessions: List<SessionEntity>,
        val transmissions: List<TransmissionEntity>,
        val transcripts: List<TranscriptEntity>,
        val corrections: List<CorrectionEntity>,
        val stations: List<StationEntity>,
        val voiceprints: List<VoiceprintEntity>,
        val threads: List<ThreadEntity>,
        val audioFiles: List<Pair<String, File>>,
    )

    /**
     * The single real read [preview] and [write] both use — never two independently-scoped
     * queries that could silently disagree (the same discipline every sibling bundle producer in
     * this codebase already holds — see [org.ort.app.export.ExportCoordinator]'s own kdoc).
     *
     * Corrections have no `CorrectionDao.listAll()` (`:data` is outside this unit's ownership to
     * add one to) — gathered instead by walking every transmission's own
     * `CorrectionDao.correctionsFor(id)`, exactly the shape build-plan P29's own report already
     * used this round for an equivalent `:data`-DAO gap ([org.ort.app.fieldreport.bundle
     * .VoiceprintEmbeddingsProducer]'s own precedent, cited there). Transcripts are gathered the
     * same way, via `TranscriptDao.getAllVersions(id)` — register R-1094: every version, current
     * and superseded, not only the current one [TranscriptDao.getCurrent] would return.
     *
     * Stations ([org.ort.data.dao.ActivityDao.listStations]), voiceprints
     * ([org.ort.data.dao.CatalogDao.allVoiceprints]) and threads
     * ([org.ort.data.dao.CatalogDao.listAllThreads]) are each already a real "every row" query —
     * register R-1094 added the latter two.
     *
     * Takes an already-open [db] directly (see [writeFrom]/[previewFrom]'s own kdoc for why).
     */
    private suspend fun collect(context: Context, db: OrtDatabase): CollectedBackup {
        val sessions = db.sessionDao().listAll()
        val transmissions = sessions.flatMap { db.transmissionDao().listBySession(it.id) }
        val transcripts = transmissions.flatMap { db.transcriptDao().getAllVersions(it.id) }
        val corrections = transmissions.flatMap { db.correctionDao().correctionsFor(it.id) }
        val stations = db.activityDao().listStations()
        val voiceprints = db.catalogDao().allVoiceprints()
        val threads = db.catalogDao().listAllThreads()
        val audioFiles = transmissions.mapNotNull { transmission ->
            val source = File(context.filesDir, transmission.audioPath())
            if (source.isFile) {
                "$BACKUP_AUDIO_ENTRY_PREFIX${transmission.sessionId}/${transmission.id}.flac" to source
            } else {
                null
            }
        }
        return CollectedBackup(
            sessions = sessions,
            transmissions = transmissions,
            transcripts = transcripts,
            corrections = corrections,
            stations = stations,
            voiceprints = voiceprints,
            threads = threads,
            audioFiles = audioFiles,
        )
    }

    private const val STREAM_BUFFER_BYTES = 64 * 1024
}
