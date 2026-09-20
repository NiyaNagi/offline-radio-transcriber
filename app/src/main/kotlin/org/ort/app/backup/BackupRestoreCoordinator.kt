package org.ort.app.backup

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.ort.data.OrtDatabase
import org.ort.data.entity.CorrectionEntity
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TransmissionEntity
import java.io.File
import java.util.zip.ZipFile

/**
 * FR-STO-9's own plan, computed *before* anything is written — [BackupRestoreCoordinator.apply]
 * only ever acts on exactly this, never re-deciding conflicts itself, so what the operator was
 * shown and what actually happens can never drift (the same "preview and write share one producer"
 * discipline [BackupBundleBuilder]'s own kdoc states for the export half).
 *
 * **The conflict rule, stated once, here:** a record already present on the restoring device —
 * same [SessionEntity.id]/[TransmissionEntity.id]/[CorrectionEntity.id], or an audio file already
 * on disk at the path the bundle's own entry would write to — is a conflict. [apply] **never**
 * inserts or overwrites a conflicting record; every conflicting id is skipped entirely and the
 * device's own existing row is left exactly as it was (constitution III, FR-STO-9). Nothing here
 * merges field-by-field or lets the incoming row win — this round's own choice is "the device
 * always wins a conflict, and the operator always sees the count before it happens," not a
 * per-record "keep mine / take theirs" picker (a real, separate surface this round does not build
 * — see `SettingsBackupScreen`'s own kdoc).
 *
 * [transcriptsToAdd] is scoped to transmissions actually being added ([transmissionsToAdd]) — a
 * transcript for a transmission that itself conflicts is never inserted orphaned against a
 * transmission this restore did not touch (that transmission's own current transcript, if any,
 * already exists on the device and is left alone, the identical "never overwrite" rule applied one
 * level down).
 */
public data class BackupRestorePlan(
    val sessionsToAdd: List<SessionEntity>,
    val sessionConflicts: List<SessionEntity>,
    val transmissionsToAdd: List<TransmissionEntity>,
    val transmissionConflicts: List<TransmissionEntity>,
    val transcriptsToAdd: List<TranscriptEntity>,
    val correctionsToAdd: List<CorrectionEntity>,
    val correctionConflicts: List<CorrectionEntity>,
    val audioEntriesToAdd: List<String>,
    val audioConflicts: List<String>,
) {
    public val sessionCountInBundle: Int get() = sessionsToAdd.size + sessionConflicts.size
    public val transmissionCountInBundle: Int get() = transmissionsToAdd.size + transmissionConflicts.size
    public val correctionCountInBundle: Int get() = correctionsToAdd.size + correctionConflicts.size
    public val audioCountInBundle: Int get() = audioEntriesToAdd.size + audioConflicts.size

    public val hasConflicts: Boolean
        get() = sessionConflicts.isNotEmpty() ||
            transmissionConflicts.isNotEmpty() ||
            correctionConflicts.isNotEmpty() ||
            audioConflicts.isNotEmpty()
}

/** What [BackupRestoreCoordinator.apply] actually did — real counts, the same ones the operator
 * was already shown in the [BackupRestorePlan] that produced them. */
public data class BackupRestoreResult(
    val sessionsAdded: Int,
    val transmissionsAdded: Int,
    val transcriptsAdded: Int,
    val correctionsAdded: Int,
    val audioFilesAdded: Int,
    val sessionsSkipped: Int,
    val transmissionsSkipped: Int,
    val correctionsSkipped: Int,
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
            val (correctionsToAdd, correctionConflicts) = partitionByExistence(corrections) { correctionExists(db, it) }
            val (audioToAdd, audioConflicts) = audioEntryNames.partition { name -> !audioFileExists(context, name) }

            BackupRestorePlan(
                sessionsToAdd = sessionsToAdd,
                sessionConflicts = sessionConflicts,
                transmissionsToAdd = transmissionsToAdd,
                transmissionConflicts = transmissionConflicts,
                transcriptsToAdd = transcriptsToAdd,
                correctionsToAdd = correctionsToAdd,
                correctionConflicts = correctionConflicts,
                audioEntriesToAdd = audioToAdd,
                audioConflicts = audioConflicts,
            )
        }
    }

    /**
     * Writes exactly [plan] — sessions before transmissions before transcripts/corrections
     * (`TransmissionEntity`'s own `sessionId` foreign key is `RESTRICT`, so a session must exist
     * before a transmission naming it can be inserted). Every conflicting id in [plan] is already
     * excluded from every `*ToAdd` list, so this function never has to re-check anything —
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
        for (transmission in plan.transmissionsToAdd) db.transmissionDao().insert(transmission)
        for (transcript in plan.transcriptsToAdd) db.transcriptDao().insert(transcript)
        for (correction in plan.correctionsToAdd) db.correctionDao().insert(correction)

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
            audioFilesAdded = audioAdded,
            sessionsSkipped = plan.sessionConflicts.size,
            transmissionsSkipped = plan.transmissionConflicts.size,
            correctionsSkipped = plan.correctionConflicts.size,
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
