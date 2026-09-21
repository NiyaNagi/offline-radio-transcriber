package org.ort.app.backup

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.CorrectionEntity
import org.ort.data.entity.OverCountsByAttributionState
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TerminationReason
import org.ort.data.entity.ThreadEntity
import org.ort.data.entity.ThreadKind
import org.ort.data.entity.ThreadKindSource
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
import org.ort.data.entity.VoiceprintEntity
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * FR-STO-9, AC-170. The round-trip centrepiece: export a populated database, restore into an
 * empty one, and assert the sessions, transmissions, corrections and audio files match -- plus the
 * companion case build-plan P30 explicitly asks for, where the restoring device is *not* empty:
 * one record genuinely conflicts, and the test proves it is shown as a conflict, never silently
 * merged or overwritten, while everything that does not conflict is still restored.
 *
 * Bundle files here are built directly with [BackupRecordCodecs.kt]'s own codecs ([bundleFile])
 * rather than through a second, real `:data` database read via [BackupBundleBuilder.write] --
 * that write path (real database in, real zip out) is [BackupBundleBuilderTest]'s own job to
 * prove; this class's job is the restore/conflict logic given a bundle. The restoring side uses
 * the same, single, default-named database every other `:data`-backed test in this module opens
 * ([org.ort.app.export.ExportCoordinatorTest], [BackupBundleBuilderTest]) -- a custom on-disk name
 * opened fresh mid-test reproducibly hit `SQLITE_CANTOPEN` against this environment's
 * `BundledSQLiteDriver`/Room combination the moment a real query followed it, so this suite avoids
 * that path entirely and relies solely on every session/transmission/transcript/correction id, and
 * therefore every derived audio path, being suffixed with [testId] (fresh per JUnit-instantiated
 * test method) for isolation between the three tests below, whatever this Robolectric environment
 * does or does not reset between methods.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRestoreCoordinatorTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val testId = System.nanoTime()

    private val s1 = "S1-$testId"
    private val s2 = "S2-$testId"
    private val t1Id = "T1-$testId"
    private val t2Id = "T2-$testId"
    private val tr1 = "TR1-$testId"
    private val tr2 = "TR2-$testId"
    private val c1 = "C1-$testId"

    private fun session(id: String, endedAt: Long? = null, terminationReason: TerminationReason? = null) =
        SessionEntity(
            id = id,
            startedAt = 0L,
            endedAt = endedAt,
            profileId = null,
            deviceTier = null,
            appVersion = "test",
            terminationReason = terminationReason,
            sourceId = null,
            schemaVersion = OrtDatabase.SCHEMA_VERSION,
        )

    private fun transmission(id: String, sessionId: String, stationId: String) = TransmissionEntity(
        id = id,
        sessionId = sessionId,
        threadId = null,
        startedAtUtc = 1_000L,
        endedAtUtc = 2_000L,
        durationMs = 1_000L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = 146_520_000L,
        frequencyProvenance = "measured",
        mode = "FM",
        signalStrength = null,
        channelName = null,
        voiceprintId = null,
        attributionState = AttributionState.CONFIRMED,
        stationId = stationId,
        attributionConfidence = 0.9,
        attributionSourceTransmissionId = null,
        processingState = TransmissionState.COMPLETE,
        rejectionReason = null,
        samplePosition = 0L,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    private fun transcript(id: String, transmissionId: String, text: String) = TranscriptEntity(
        id = id,
        transmissionId = transmissionId,
        pass = TranscriptPass.B,
        text = text,
        modelId = "whisper-small",
        modelVersion = "1.2.0",
        quantization = null,
        decodeParams = null,
        noSpeechProb = null,
        confidence = null,
        isCurrent = true,
        createdAt = 0L,
    )

    private fun station(id: String, userName: String? = null) = StationEntity(
        id = id,
        callsign = id,
        firstHeardAt = 0L,
        lastHeardAt = 0L,
        transmissionCount = 1,
        isUserPinned = false,
        notes = null,
        userName = userName,
        frequenciesHeard = null,
        activityByHourDow = null,
        potaRefs = null,
        spokenGrids = null,
        ituRegionFromPrefix = null,
        overCountsByAttributionState = OverCountsByAttributionState.EMPTY.serialize(),
    )

    private fun voiceprint(id: String, boundStationId: String? = null) = VoiceprintEntity(
        id = id,
        embedding = byteArrayOf(1, 2, 3),
        memberCount = 1,
        centroidUpdatedAt = null,
        boundStationId = boundStationId,
        bindingConfidence = null,
        lastConfirmedAt = null,
        isEnrolled = false,
        enrolmentObservationCount = 0,
        enrolmentSessionIds = null,
        enrolledAt = null,
        lastMatchedAt = null,
        bindingSource = null,
        embeddingModelId = null,
        embeddingModelVersion = null,
    )

    private fun thread(id: String, sessionId: String) = ThreadEntity(
        id = id,
        sessionId = sessionId,
        startedAt = 0L,
        endedAt = null,
        frequencyHz = null,
        transmissionCount = 1,
        participantStationIds = null,
        digestText = null,
        kind = ThreadKind.QSO,
        kindSource = ThreadKindSource.DETECTED,
        participantOrder = null,
    )

    private fun writeAudio(transmission: TransmissionEntity, bytes: ByteArray) {
        val file = File(context.filesDir, transmission.audioPath())
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    /**
     * The exact zip shape [BackupBundleBuilder.write] produces -- see this class's own kdoc for
     * why this test class builds it directly with the codecs instead of through a second, real
     * database. [audioFiles] pairs an entry name (already in [TransmissionEntity.audioPath]'s own
     * shape) with the real bytes to embed.
     */
    private fun bundleFile(
        sessions: List<SessionEntity> = emptyList(),
        transmissions: List<TransmissionEntity> = emptyList(),
        transcripts: List<TranscriptEntity> = emptyList(),
        corrections: List<CorrectionEntity> = emptyList(),
        stations: List<StationEntity> = emptyList(),
        voiceprints: List<VoiceprintEntity> = emptyList(),
        threads: List<ThreadEntity> = emptyList(),
        audioFiles: List<Pair<String, ByteArray>> = emptyList(),
    ): File {
        val file = File(context.cacheDir, "test-backup-$testId.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun jsonEntry(name: String, json: Any) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(json.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            jsonEntry(BACKUP_MANIFEST_ENTRY, org.json.JSONObject())
            jsonEntry(BACKUP_SESSIONS_ENTRY, JSONArray(sessions.map(SessionCodec::toJson)))
            jsonEntry(BACKUP_TRANSMISSIONS_ENTRY, JSONArray(transmissions.map(TransmissionCodec::toJson)))
            jsonEntry(BACKUP_TRANSCRIPTS_ENTRY, JSONArray(transcripts.map(TranscriptCodec::toJson)))
            jsonEntry(BACKUP_CORRECTIONS_ENTRY, JSONArray(corrections.map(CorrectionCodec::toJson)))
            jsonEntry(BACKUP_STATIONS_ENTRY, JSONArray(stations.map(StationCodec::toJson)))
            jsonEntry(BACKUP_VOICEPRINTS_ENTRY, JSONArray(voiceprints.map(VoiceprintCodec::toJson)))
            jsonEntry(BACKUP_THREADS_ENTRY, JSONArray(threads.map(ThreadCodec::toJson)))
            for ((entryName, bytes) in audioFiles) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `AC_170 restoring into an empty device reproduces every session, transmission, correction and audio file`() =
        runTest {
            val t1 = transmission(t1Id, s1, "KI7ABC")
            val t2 = transmission(t2Id, s2, "W7NPC")
            val bundle = bundleFile(
                sessions = listOf(session(s1), session(s2)),
                transmissions = listOf(t1, t2),
                transcripts = listOf(transcript(tr1, t1Id, "this is KI7ABC"), transcript(tr2, t2Id, "this is W7NPC")),
                corrections = listOf(
                    CorrectionEntity(
                        id = c1,
                        transmissionId = t1Id,
                        field = "stationId",
                        previousValue = "OLD",
                        newValue = "KI7ABC",
                        correctedAt = 0L,
                    ),
                ),
                audioFiles = listOf("audio/$s1/$t1Id.flac" to byteArrayOf(1, 2, 3)),
            )

            // The restoring device is genuinely empty -- no session/transmission/transcript/
            // correction id here has ever existed before this test method ran.
            val plan = BackupRestoreCoordinator.analyze(context, bundle)
            assertFalse("expected no conflicts against an empty device", plan.hasConflicts)
            assertEquals(2, plan.sessionsToAdd.size)
            assertEquals(2, plan.transmissionsToAdd.size)
            assertEquals(2, plan.transcriptsToAdd.size)
            assertEquals(1, plan.correctionsToAdd.size)
            assertEquals(1, plan.audioEntriesToAdd.size)

            val result = BackupRestoreCoordinator.apply(context, bundle, plan)
            assertEquals(2, result.sessionsAdded)
            assertEquals(2, result.transmissionsAdded)
            assertEquals(2, result.transcriptsAdded)
            assertEquals(1, result.correctionsAdded)
            assertEquals(1, result.audioFilesAdded)

            val restoredDb = OrtDatabase.create(context)
            assertEquals(session(s1), restoredDb.sessionDao().getById(s1))
            assertEquals(session(s2), restoredDb.sessionDao().getById(s2))
            assertEquals(t1, restoredDb.transmissionDao().getById(t1Id))
            assertEquals(t2, restoredDb.transmissionDao().getById(t2Id))
            assertEquals("this is KI7ABC", restoredDb.transcriptDao().getCurrent(t1Id)?.text)
            assertEquals("this is W7NPC", restoredDb.transcriptDao().getCurrent(t2Id)?.text)
            assertEquals(1, restoredDb.correctionDao().correctionsFor(t1Id).size)
            val restoredAudio = File(context.filesDir, t1.audioPath())
            assertTrue("expected the restored audio file to exist", restoredAudio.isFile)
            assertTrue(restoredAudio.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        }

    @Test
    fun `AC_170 an existing session is shown as a conflict, never overwritten -- the rest still restores`() = runTest {
        val t1 = transmission(t1Id, s1, "KI7ABC")
        val t2 = transmission(t2Id, s2, "W7NPC")
        val bundle = bundleFile(
            sessions = listOf(session(s1), session(s2)),
            transmissions = listOf(t1, t2),
            transcripts = listOf(transcript(tr1, t1Id, "this is KI7ABC"), transcript(tr2, t2Id, "this is W7NPC")),
            corrections = listOf(
                CorrectionEntity(
                    id = c1,
                    transmissionId = t1Id,
                    field = "stationId",
                    previousValue = "OLD",
                    newValue = "KI7ABC",
                    correctedAt = 0L,
                ),
            ),
            // The bundle's own copy of T1's audio, as it was at export time.
            audioFiles = listOf("audio/$s1/$t1Id.flac" to byteArrayOf(1, 2, 3)),
        )

        // The restoring device already has its own S1/T1/C1 (real prior activity -- S1
        // diverged: its own endedAt/terminationReason are not what the bundle carries) but has
        // never seen S2 at all -- the ordinary "restoring onto a device that is not empty" case
        // FR-STO-9 names. Its own real audio file for T1 also differs from the bundle's.
        val restoring = OrtDatabase.create(context)
        restoring.sessionDao().insert(session(s1, endedAt = 555_555L, terminationReason = TerminationReason.CRASH))
        restoring.transmissionDao().insert(t1)
        restoring.correctionDao().insert(
            CorrectionEntity(
                id = c1,
                transmissionId = t1Id,
                field = "stationId",
                previousValue = "OLD",
                newValue = "KI7ABC",
                correctedAt = 0L,
            ),
        )
        writeAudio(t1, byteArrayOf(9, 9, 9))

        val plan = BackupRestoreCoordinator.analyze(context, bundle)

        assertTrue(plan.hasConflicts)
        assertEquals(listOf(s1), plan.sessionConflicts.map { it.id })
        assertEquals(listOf(s2), plan.sessionsToAdd.map { it.id })
        assertEquals(listOf(t1Id), plan.transmissionConflicts.map { it.id })
        assertEquals(listOf(t2Id), plan.transmissionsToAdd.map { it.id })
        // TR1 belongs to the conflicting T1 -- never inserted orphaned against a transmission
        // this restore did not touch.
        assertEquals(listOf(t2Id), plan.transcriptsToAdd.map { it.transmissionId })
        assertEquals(listOf(c1), plan.correctionConflicts.map { it.id })
        assertTrue(plan.correctionsToAdd.isEmpty())
        assertEquals(listOf("audio/$s1/$t1Id.flac"), plan.audioConflicts)
        assertTrue(plan.audioEntriesToAdd.isEmpty())

        val result = BackupRestoreCoordinator.apply(context, bundle, plan)
        assertEquals(1, result.sessionsAdded)
        assertEquals(1, result.sessionsSkipped)
        assertEquals(1, result.transmissionsAdded)
        assertEquals(1, result.transmissionsSkipped)
        assertEquals(1, result.correctionsSkipped)
        assertEquals(0, result.correctionsAdded)
        assertEquals(1, result.audioFilesSkipped)
        assertEquals(0, result.audioFilesAdded)

        // The device's own, already-diverged S1 row is untouched -- never resolved back to the
        // bundle's older value.
        val liveS1 = restoring.sessionDao().getById(s1)
        assertEquals(555_555L, liveS1?.endedAt)
        assertEquals(TerminationReason.CRASH, liveS1?.terminationReason)

        // S2, T2 and TR2 -- the non-conflicting records -- are genuinely present now.
        assertEquals(session(s2), restoring.sessionDao().getById(s2))
        assertEquals(t2, restoring.transmissionDao().getById(t2Id))
        assertEquals("this is W7NPC", restoring.transcriptDao().getCurrent(t2Id)?.text)

        // Nothing already present was deleted or overwritten as a side effect (constitution
        // III) -- T1's own pre-existing row and its own real audio bytes are both still exactly
        // what this device already had, never the bundle's own conflicting copy.
        assertEquals(t1, restoring.transmissionDao().getById(t1Id))
        val stillLiveAudio = File(context.filesDir, t1.audioPath())
        assertTrue(stillLiveAudio.isFile)
        assertTrue(
            "expected the device's own pre-existing audio bytes, never the bundle's conflicting copy",
            stillLiveAudio.readBytes().contentEquals(byteArrayOf(9, 9, 9)),
        )
    }

    /**
     * Register R-1095's own judgement, made concrete: the coarse "device always wins" rule is
     * safe wherever a whole record either matches or is left alone -- but before this fix, a
     * **new** correction (a fresh id the device had never seen) belonging to a transmission that
     * *itself* conflicted still slipped through [BackupRestorePlan.correctionsToAdd], because
     * [correctionExists] only checks the correction's own id, never its transmission's. [apply]
     * would then insert that correction row with a bare `CorrectionDao.insert` -- never
     * `recordCorrection`, so the transmission's own `attributionState`/`stationId`/`corrected`
     * columns are never touched -- landing a correction *history entry* on a transmission whose
     * live attribution never actually changed to match it. That is not "limited," it is wrong:
     * the restored record would show correction history for a callsign it was never actually
     * corrected to (constitution I). The fix folds a transmission-conflict correction into
     * [BackupRestorePlan.correctionConflicts] instead, the same "left exactly as it was" outcome
     * [transcriptsToAdd] already gives an orphaned transcript.
     */
    @Test
    fun `R_1095 a new correction for a conflicting transmission is never inserted orphaned against it`() = runTest {
        val t1 = transmission(t1Id, s1, "KI7ABC")
        val newCorrectionId = "C-NEW-$testId"
        val bundle = bundleFile(
            sessions = listOf(session(s1)),
            transmissions = listOf(t1),
            // A correction made on the source device *after* the two devices diverged -- its own
            // id has never been seen on the restoring device, only its transmission has.
            corrections = listOf(
                CorrectionEntity(
                    id = newCorrectionId,
                    transmissionId = t1Id,
                    field = "stationId",
                    previousValue = "OLD",
                    newValue = "KI7ABC",
                    correctedAt = 999L,
                ),
            ),
        )

        // The restoring device already has its own T1 (a real prior transmission -- the ordinary
        // conflict case) but has never recorded any correction for it at all.
        val restoring = OrtDatabase.create(context)
        restoring.sessionDao().insert(session(s1))
        restoring.transmissionDao().insert(t1)

        val plan = BackupRestoreCoordinator.analyze(context, bundle)
        assertTrue("expected T1 to conflict -- it already exists on the restoring device", plan.hasConflicts)
        assertEquals(listOf(t1Id), plan.transmissionConflicts.map { it.id })
        assertTrue(
            "expected the new correction never to be queued for insertion against an untouched transmission",
            plan.correctionsToAdd.isEmpty(),
        )
        assertEquals(listOf(newCorrectionId), plan.correctionConflicts.map { it.id })

        val result = BackupRestoreCoordinator.apply(context, bundle, plan)
        assertEquals(0, result.correctionsAdded)
        assertEquals(1, result.correctionsSkipped)

        // The device gains no orphaned correction history -- and T1's own attribution is
        // untouched, exactly as constitution III promises for a conflicting record.
        assertTrue(restoring.correctionDao().correctionsFor(t1Id).isEmpty())
        assertEquals(t1, restoring.transmissionDao().getById(t1Id))
    }

    /**
     * Register R-1094's own centrepiece: write a bundle from a populated database, restore into
     * an empty one, and assert that sessions, corrections, audio, the station catalog, a thread
     * **and both a transmission's current and superseded transcript** all arrive intact — nothing
     * silently merged away (constitution III). [t1Id]'s transcript is superseded once
     * ([trSuperseded] -> [tr1]), the same append-then-flip shape [org.ort.data.dao.TranscriptDao
     * .supersede] uses in production, so a restore that returned only the current row would be
     * exactly the quiet deletion this row exists to catch.
     */
    @Test
    fun `R_1094 restoring into an empty device reproduces the station catalog, threads and superseded transcripts`() =
        runTest {
            val trSuperseded = "TR0-$testId"
            val t1 = transmission(t1Id, s1, "KI7ABC")
            val bundle = bundleFile(
                sessions = listOf(session(s1)),
                transmissions = listOf(t1),
                transcripts = listOf(
                    transcript(trSuperseded, t1Id, "partial guess").copy(isCurrent = false, createdAt = 0L),
                    transcript(tr1, t1Id, "this is KI7ABC").copy(createdAt = 100L),
                ),
                corrections = listOf(
                    CorrectionEntity(
                        id = c1,
                        transmissionId = t1Id,
                        field = "stationId",
                        previousValue = "OLD",
                        newValue = "KI7ABC",
                        correctedAt = 0L,
                    ),
                ),
                stations = listOf(station("KI7ABC", userName = "Alex")),
                voiceprints = listOf(voiceprint("VP1-$testId", boundStationId = "KI7ABC")),
                threads = listOf(thread("TH1-$testId", s1)),
                audioFiles = listOf("audio/$s1/$t1Id.flac" to byteArrayOf(1, 2, 3)),
            )

            val plan = BackupRestoreCoordinator.analyze(context, bundle)
            assertFalse("expected no conflicts against an empty device", plan.hasConflicts)
            assertEquals(2, plan.transcriptsToAdd.size)
            assertEquals(1, plan.stationsToAdd.size)
            assertEquals(1, plan.voiceprintsToAdd.size)
            assertEquals(1, plan.threadsToAdd.size)

            val result = BackupRestoreCoordinator.apply(context, bundle, plan)
            assertEquals(2, result.transcriptsAdded)
            assertEquals(1, result.stationsAdded)
            assertEquals(1, result.voiceprintsAdded)
            assertEquals(1, result.threadsAdded)

            val restoredDb = OrtDatabase.create(context)
            // Both transcript versions are reachable -- current and superseded.
            val allVersions = restoredDb.transcriptDao().getAllVersions(t1Id)
            assertEquals(setOf(trSuperseded, tr1), allVersions.map { it.id }.toSet())
            assertEquals("this is KI7ABC", restoredDb.transcriptDao().getCurrent(t1Id)?.text)
            assertEquals(
                "partial guess",
                allVersions.first { it.id == trSuperseded }.text,
            )
            assertFalse(
                "expected the superseded row to stay marked superseded, not silently promoted",
                allVersions.first { it.id == trSuperseded }.isCurrent,
            )

            // The station catalog -- carried in full, including the operator's own userName --
            // except overCountsByAttributionState, which the restore path never trusts (see
            // StationCodec's own kdoc); it lands as the honest EMPTY placeholder, not the bundle's
            // stale copy, and the very next getStation() read re-derives it from T1 (CONFIRMED).
            val restoredStationRaw = restoredDb.catalogDao().getStationRaw("KI7ABC")
            assertEquals("Alex", restoredStationRaw?.userName)
            val emptyCounts = OverCountsByAttributionState.EMPTY.serialize()
            assertEquals(emptyCounts, restoredStationRaw?.overCountsByAttributionState)
            val restoredStation = requireNotNull(restoredDb.catalogDao().getStation("KI7ABC"))
            val restoredCounts = OverCountsByAttributionState.parse(restoredStation.overCountsByAttributionState)
            assertEquals(1, restoredCounts.countFor(AttributionState.CONFIRMED))

            // The voiceprint -- biometric data, carried only for this device-to-device transfer.
            assertEquals("KI7ABC", restoredDb.catalogDao().getVoiceprint("VP1-$testId")?.boundStationId)

            // The thread.
            assertEquals(s1, restoredDb.catalogDao().getThread("TH1-$testId")?.sessionId)
        }

    @Test
    fun `AC_170 restoring an empty bundle changes nothing and never throws`() = runTest {
        val bundle = bundleFile()
        val plan = BackupRestoreCoordinator.analyze(context, bundle)
        assertFalse(plan.hasConflicts)
        assertTrue(plan.sessionsToAdd.isEmpty())
        val result = BackupRestoreCoordinator.apply(context, bundle, plan)
        assertEquals(0, result.sessionsAdded)
        val restoringDb = OrtDatabase.create(context)
        assertNull(restoringDb.sessionDao().getById("anything"))
    }
}
