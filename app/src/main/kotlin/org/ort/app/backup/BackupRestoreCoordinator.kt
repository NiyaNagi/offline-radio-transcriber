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
import java.util.zip.ZipFile

/**
 * FR-STO-9's own plan, computed *before* anything is written — [BackupRestoreCoordinator.apply]
 * only ever acts on exactly this, never re-deciding conflicts itself, so what the operator was
 * shown and what actually happens can never drift (the same "preview and write share one producer"
 * discipline [BackupBundleBuilder]'s own kdoc states for the export half).
 *
 * **The conflict rule, stated once, here:** a record already present on the restoring device —
 * same [SessionEntity.id]/[TransmissionEntity.id]/[CorrectionEntity.id]/[StationEntity.id]/
 * [VoiceprintEntity.id]/[ThreadEntity.id], or an audio file already on disk at the path the
 * bundle's own entry would write to — is a conflict. [apply] **never** inserts or overwrites a
 * conflicting record; every conflicting id is skipped entirely and the device's own existing row
 * is left exactly as it was (constitution III, FR-STO-9). Nothing here merges field-by-field or
 * lets the incoming row win — this round's own choice is "the device always wins a conflict, and
 * the operator always sees the count before it happens," not a per-record "keep mine / take
 * theirs" picker (register R-1095: a real, separate feature, deliberately not built this round —
 * see that register row for the judgement on whether the coarse rule is safe in the meantime).
 *
 * [transcriptsToAdd] is scoped to transmissions actually being added ([transmissionsToAdd]) — a
 * transcript for a transmission that itself conflicts is never inserted orphaned against a
 * transmission this restore did not touch (that transmission's own current transcript, if any,
 * already exists on the device and is left alone, the identical "never overwrite" rule applied one
 * level down). Register R-1094: [transcriptsToAdd] now carries every version (current and
 * superseded) of each added transmission's transcript, not only the current one.
 *
 * Register R-1094 widened this plan to cover the station catalog, voiceprints and threads too —
 * see `BackupRecordCodecs.kt`'s own top-of-file kdoc for what is and is not carried for each, and
 * [org.ort.app.backup.StationCodec]'s own kdoc for why a restored station's
 * [StationEntity.overCountsByAttributionState] is never taken from the bundle.
 */
public data class BackupRestorePlan(
    val sessionsToAdd: List<SessionEntity>,
    val sessionConflicts: List<SessionEntity>,
    val transmissionsToAdd: List<TransmissionEntity>,
    val transmissionConflicts: List<TransmissionEntity>,
    val transcriptsToAdd: List<TranscriptEntity>,
    val correctionsToAdd: List<CorrectionEntity>,
    val correctionConflicts: List<CorrectionEntity>,
    val stationsToAdd: List<StationEntity>,
    val stationConflicts: List<StationEntity>,
    val voiceprintsToAdd: List<VoiceprintEntity>,
    val voiceprintConflicts: List<VoiceprintEntity>,
    val threadsToAdd: List<ThreadEntity>,
    val threadConflicts: List<ThreadEntity>,
    val audioEntriesToAdd: List<String>,
    val audioConflicts: List<String>,
) {
    public val sessionCountInBundle: Int get() = sessionsToAdd.size + sessionConflicts.size
    public val transmissionCountInBundle: Int get() = transmissionsToAdd.size + transmissionConflicts.size
    public val correctionCountInBundle: Int get() = correctionsToAdd.size + correctionConflicts.size
    public val stationCountInBundle: Int get() = stationsToAdd.size + stationConflicts.size
    public val voiceprintCountInBundle: Int get() = voiceprintsToAdd.size + voiceprintConflicts.size
    public val threadCountInBundle: Int get() = threadsToAdd.size + threadConflicts.size
    public val audioCountInBundle: Int get() = audioEntriesToAdd.size + audioConflicts.size

    public val hasConflicts: Boolean
        get() = sessionConflicts.isNotEmpty() ||
            transmissionConflicts.isNotEmpty() ||
            correctionConflicts.isNotEmpty() ||
            stationConflicts.isNotEmpty() ||
            voiceprintConflicts.isNotEmpty() ||
            threadConflicts.isNotEmpty() ||
            audioConflicts.isNotEmpty()
}

/** What [BackupRestoreCoordinator.apply] actually did — real counts, the same ones the operator
 * was already shown in the [BackupRestorePlan] that produced them. */
public data class BackupRestoreResult(
    val sessionsAdded: Int,
    val transmissionsAdded: Int,
    val transcriptsAdded: Int,
    val correctionsAdded: Int,
    val stationsAdded: Int,
    val voiceprintsAdded: Int,
    val threadsAdded: Int,
    val audioFilesAdded: Int,
    val sessionsSkipped: Int,
    val transmissionsSkipped: Int,
    val correctionsSkipped: Int,
    val stationsSkipped: Int,
    val voiceprintsSkipped: Int,
    val threadsSkipped: Int,
    val audioFilesSkipped: Int,
)

/**
 * FR-STO-9 (M): restore, the read half of [BackupBundleBuilder]. [analyze] reads a bundle
 * ([BackupBundleBuilder]'s own zip shape) and the live database and produces a [BackupRestorePlan]
 * — nothing is written yet. [apply] performs exactly that plan. Splitting the two means the
 * screen can show the operator what would happen (and let them cancel) before anything real
 * changes on the device (constitution III: "nothing is deleted quietly" applies with equal force
 * to "nothing is added quietly" for a restore the operator has not yet confirmed).
 */
public object BackupRestoreCoordinator {

    public suspend fun analyze(
        context: Context,
        bundleFile: File,
        databaseName: String = OrtDatabase.DATABASE_NAME,
    ): BackupRestorePlan = withContext(Dispatchers.IO) {
        ZipFile(bundleFile).use { zip ->
            val sessions = readEntities(zip, BACKUP_SESSIONS_ENTRY, SessionCodec::fromJson)
            val transmissions = readEntities(zip, BACKUP_TRANSMISSIONS_ENTRY, TransmissionCodec::fromJson)
            val transcripts = readEntities(zip, BACKUP_TRANSCRIPTS_ENTRY, TranscriptCodec::fromJson)
            val corrections = readEntities(zip, BACKUP_CORRECTIONS_ENTRY, CorrectionCodec::fromJson)
            val stations = readEntities(zip, BACKUP_STATIONS_ENTRY, StationCodec::fromJson)
            val voiceprints = readEntities(zip, BACKUP_VOICEPRINTS_ENTRY, VoiceprintCodec::fromJson)
            val threads = readEntities(zip, BACKUP_THREADS_ENTRY, ThreadCodec::fromJson)
            val audioEntryNames = audioEntryNames(zip)

            val db = OrtDatabase.create(context.applicationContext, name = databaseName)
            val (sessionsToAdd, sessionConflicts) = partitionByExistence(sessions) {
                db.sessionDao().getById(it.id) !=
                    null
            }
            val (transmissionsToAdd, transmissionConflicts) = partitionByExistence(transmissions) {
                db.transmissionDao().getById(it.id) != null
            }
            val addedTransmissionIds = transmissionsToAdd.map { it.id }.toSet()
            val transcriptsToAdd = transcripts.filter { it.transmissionId in addedTransmissionIds }
            // Register R-1095: a correction whose own id is genuinely new (never restored before)
            // is still never inserted orphaned against a transmission this restore did not touch
            // -- the identical protective scoping [transcriptsToAdd] already applies just above,
            // extended here to close a gap the R-1095 judgement found: without it, a transmission
            // conflict correctly left the device's own attribution untouched, but the coarse
            // per-table "same id already exists" check let the correction's own audit row through
            // anyway (its id was new), landing a correction history entry against a transmission
            // whose attributionState/stationId never actually changed to match it -- worse than
            // not restoring it, since the record now looks corrected without being corrected
            // (constitution I: an attribution's history must match its own state, not merely be
            // present). A correction is now a conflict, exactly like [transcriptsToAdd]'s own
            // records, whenever either its own id already exists OR its transmission does not
            // belong to [addedTransmissionIds].
            val (correctionsById, correctionIdConflicts) = partitionByExistence(corrections) {
                correctionExists(db, it)
            }
            val (correctionsToAdd, orphanedCorrections) =
                correctionsById.partition { it.transmissionId in addedTransmissionIds }
            val correctionConflicts = correctionIdConflicts + orphanedCorrections
            val (stationsToAdd, stationConflicts) = partitionByExistence(stations) { stationExists(db, it.id) }
            val (voiceprintsToAdd, voiceprintConflicts) = partitionByExistence(voiceprints) {
                voiceprintExists(db, it.id)
            }
            val (threadsToAdd, threadConflicts) = partitionByExistence(threads) { threadExists(db, it.id) }
            val (audioToAdd, audioConflicts) = audioEntryNames.partition { name -> !audioFileExists(context, name) }

            BackupRestorePlan(
                sessionsToAdd = sessionsToAdd,
                sessionConflicts = sessionConflicts,
                transmissionsToAdd = transmissionsToAdd,
                transmissionConflicts = transmissionConflicts,
                transcriptsToAdd = transcriptsToAdd,
                correctionsToAdd = correctionsToAdd,
                correctionConflicts = correctionConflicts,
                stationsToAdd = stationsToAdd,
                stationConflicts = stationConflicts,
                voiceprintsToAdd = voiceprintsToAdd,
                voiceprintConflicts = voiceprintConflicts,
                threadsToAdd = threadsToAdd,
                threadConflicts = threadConflicts,
                audioEntriesToAdd = audioToAdd,
                audioConflicts = audioConflicts,
            )
        }
    }

    /**
     * Writes exactly [plan] — sessions before transmissions before transcripts/corrections
     * (`TransmissionEntity`'s own `sessionId` foreign key is `RESTRICT`, so a session must exist
     * before a transmission naming it can be inserted). Stations, threads and voiceprints carry no
     * foreign key to or from a transmission (`TransmissionEntity.stationId`/`.threadId` are plain
     * columns, not a Room `ForeignKey` — see that file), so their own insert order relative to
     * transmissions is a readability choice, not a constraint. Every conflicting id in [plan] is
     * already excluded from every `*ToAdd` list, so this function never has to re-check anything —
     * see [BackupRestorePlan]'s own kdoc for the conflict rule this enforces.
     */
    public suspend fun apply(
        context: Context,
        bundleFile: File,
        plan: BackupRestorePlan,
        databaseName: String = OrtDatabase.DATABASE_NAME,
    ): BackupRestoreResult = withContext(Dispatchers.IO) {
        val db = OrtDatabase.create(context.applicationContext, name = databaseName)
        for (session in plan.sessionsToAdd) db.sessionDao().insert(session)
        for (station in plan.stationsToAdd) db.catalogDao().insert(station)
        for (transmission in plan.transmissionsToAdd) db.transmissionDao().insert(transmission)
        for (thread in plan.threadsToAdd) db.catalogDao().insert(thread)
        for (transcript in plan.transcriptsToAdd) db.transcriptDao().insert(transcript)
        for (correction in plan.correctionsToAdd) db.correctionDao().insert(correction)
        for (voiceprint in plan.voiceprintsToAdd) db.catalogDao().insert(voiceprint)

        var audioAdded = 0
        if (plan.audioEntriesToAdd.isNotEmpty()) {
            ZipFile(bundleFile).use { zip ->
                for (name in plan.audioEntriesToAdd) {
                    val entry = zip.getEntry(name) ?: continue
                    // The zip entry name is already exactly `TransmissionEntity.audioPath()`'s
                    // own shape (`BackupBundleBuilder`'s own writer builds it that way) — the
                    // restore destination under `filesDir` is therefore the entry name itself,
                    // never a second, independently-computed path that could disagree with it.
                    val dest = File(context.filesDir, name)
                    dest.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    audioAdded++
                }
            }
        }

        BackupRestoreResult(
            sessionsAdded = plan.sessionsToAdd.size,
            transmissionsAdded = plan.transmissionsToAdd.size,
            transcriptsAdded = plan.transcriptsToAdd.size,
            correctionsAdded = plan.correctionsToAdd.size,
            stationsAdded = plan.stationsToAdd.size,
            voiceprintsAdded = plan.voiceprintsToAdd.size,
            threadsAdded = plan.threadsToAdd.size,
            audioFilesAdded = audioAdded,
            sessionsSkipped = plan.sessionConflicts.size,
            transmissionsSkipped = plan.transmissionConflicts.size,
            correctionsSkipped = plan.correctionConflicts.size,
            stationsSkipped = plan.stationConflicts.size,
            voiceprintsSkipped = plan.voiceprintConflicts.size,
            threadsSkipped = plan.threadConflicts.size,
            audioFilesSkipped = plan.audioConflicts.size,
        )
    }

    private suspend fun <T> partitionByExistence(
        items: List<T>,
        exists: suspend (T) -> Boolean,
    ): Pair<List<T>, List<T>> {
        val toAdd = mutableListOf<T>()
        val conflicts = mutableListOf<T>()
        for (item in items) {
            if (exists(item)) conflicts += item else toAdd += item
        }
        return toAdd to conflicts
    }

    /** No `CorrectionDao.listAll()`/`getById()` exists (`:data` is outside this unit's ownership
     * to add one to) — the same "walk what the real DAOs already expose" shape
     * [BackupBundleBuilder.collect] itself uses for the same reason. */
    private suspend fun correctionExists(db: OrtDatabase, correction: CorrectionEntity): Boolean =
        db.correctionDao().correctionsFor(correction.transmissionId).any { it.id == correction.id }

    /** Register R-1094: the same conflict rule every other table in this plan already applies —
     * an id already present on the restoring device is a conflict, never merged or overwritten. */
    private suspend fun stationExists(db: OrtDatabase, id: String): Boolean = db.catalogDao().getStationRaw(id) != null

    private suspend fun voiceprintExists(db: OrtDatabase, id: String): Boolean =
        db.catalogDao().getVoiceprint(id) != null

    private suspend fun threadExists(db: OrtDatabase, id: String): Boolean = db.catalogDao().getThread(id) != null

    private fun audioFileExists(context: Context, entryName: String): Boolean = File(context.filesDir, entryName).isFile

    private fun audioEntryNames(zip: ZipFile): List<String> {
        val names = mutableListOf<String>()
        val enumeration = zip.entries()
        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            if (entry.name.startsWith(BACKUP_AUDIO_ENTRY_PREFIX)) names += entry.name
        }
        return names
    }

    private fun <T> readEntities(zip: ZipFile, entryName: String, fromJson: (JSONObject) -> T): List<T> {
        val entry = zip.getEntry(entryName) ?: return emptyList()
        val text = zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8)
        val array = JSONArray(text)
        return (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
    }
}
