package org.ort.pipeline.archive

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.core.Tier
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.LabelCertainty
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
import org.ort.data.entity.TransmissionLabelEntity
import org.ort.data.entity.VoiceprintEntity
import org.ort.pipeline.capture.CaptureState
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * `Recording-Session.dc.html`'s (RC02) Export action, for a session's audio. Closes on a real
 * [OrtDatabase], never a stand-in — the same discipline `SessionAudioDeletionServiceTest`'s own
 * kdoc holds itself to.
 */
@RunWith(RobolectricTestRunner::class)
class SessionAudioExportTest {

    private fun tempFilesDir(): File = Files.createTempDirectory("session-audio-export-test").toFile()

    private fun session(id: String = "S1", overAudioRemovedAtMillis: Long? = null, appVersion: String? = "0.1.1") =
        SessionEntity(
            id = id,
            startedAt = 1_000L,
            endedAt = 9_000L,
            profileId = null,
            deviceTier = null,
            appVersion = appVersion,
            terminationReason = null,
            sourceId = null,
            schemaVersion = OrtDatabase.SCHEMA_VERSION,
            overAudioRemovedAtMillis = overAudioRemovedAtMillis,
        )

    private fun transmission(
        id: String,
        sessionId: String = "S1",
        attributionState: AttributionState = AttributionState.UNKNOWN,
        stationId: String? = null,
        attributionConfidence: Double? = null,
        processedTier: Tier? = null,
        executionProvider: String? = null,
    ) = TransmissionEntity(
        id = id,
        sessionId = sessionId,
        threadId = null,
        startedAtUtc = 2_000L,
        endedAtUtc = 3_000L,
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
        attributionState = attributionState,
        stationId = stationId,
        attributionConfidence = attributionConfidence,
        attributionSourceTransmissionId = null,
        processingState = TransmissionState.CAPTURED,
        rejectionReason = null,
        samplePosition = 0L,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = executionProvider,
        processedTier = processedTier,
    )

    private fun writeOverAudioFile(filesDir: File, sessionId: String, transmissionId: String, bytes: Int) {
        val dir = File(filesDir, "audio/$sessionId")
        dir.mkdirs()
        File(dir, "$transmissionId.flac").writeBytes(ByteArray(bytes) { it.toByte() })
    }

    private fun writeArchiveChunk(filesDir: File, sessionId: String, startSample: Long, bytes: Int) {
        val dir = File(filesDir, "archive/$sessionId")
        dir.mkdirs()
        File(dir, "chunk-$startSample.flac").writeBytes(ByteArray(bytes) { it.toByte() })
    }

    private fun zipEntries(bytes: ByteArray): Map<String, Pair<ZipEntry, ByteArray>> {
        val tmp = File.createTempFile("session-audio-export", ".zip")
        tmp.writeBytes(bytes)
        return try {
            ZipFile(tmp).use { zip ->
                zip.entries().asSequence().associate { entry ->
                    entry.name to (entry to zip.getInputStream(entry).use { it.readBytes() })
                }
            }
        } finally {
            tmp.delete()
        }
    }

    @Before
    fun resetCaptureState() {
        CaptureState.idle(clearSession = true)
    }

    @After
    fun tearDownCaptureState() {
        CaptureState.idle(clearSession = true)
    }

    // -- canExport ------------------------------------------------------------------------------

    @Test
    @Requirement("FR-STO-6")
    fun `canExport refuses an unknown session`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        assertEquals(
            SessionAudioExportRefusal.SessionNotFound,
            SessionAudioExport.canExport(db, "no-such-session", SessionAudioExportTarget.OVER_AUDIO),
        )
    }

    @Test
    @Requirement("FR-STO-6")
    fun `canExport refuses OVER_AUDIO once it was removed, and names the refusal`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(session(overAudioRemovedAtMillis = 5_000L))

        val refusal = SessionAudioExport.canExport(db, "S1", SessionAudioExportTarget.OVER_AUDIO)

        assertTrue(refusal is SessionAudioExportRefusal.NothingToExport)
        assertTrue((refusal as SessionAudioExportRefusal.NothingToExport).reason.contains("removed"))
    }

    @Test
    @Requirement("FR-STO-6")
    fun `canExport refuses RAW_ARCHIVE when no archive was ever kept`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(session())

        val refusal = SessionAudioExport.canExport(db, "S1", SessionAudioExportTarget.RAW_ARCHIVE)

        assertTrue(refusal is SessionAudioExportRefusal.NothingToExport)
    }

    @Test
    @Requirement("FR-STO-6")
    fun `canExport refuses RAW_ARCHIVE once the archive was removed`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(session())
        db.sessionDao().setArchiveKept("S1")
        db.sessionDao().setArchiveRemoved("S1", 6_000L)

        val refusal = SessionAudioExport.canExport(db, "S1", SessionAudioExportTarget.RAW_ARCHIVE)

        assertTrue(refusal is SessionAudioExportRefusal.NothingToExport)
    }

    @Test
    @Requirement("FR-STO-6")
    fun `canExport allows BOTH when only one half still has audio`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(session(overAudioRemovedAtMillis = 5_000L))
        // No archive was ever kept either -- but over audio removed + archive-never-kept is the
        // one BOTH case that IS refused; assert the opposite: at least one half present passes.
        db.sessionDao().setArchiveKept("S1")

        assertNull(SessionAudioExport.canExport(db, "S1", SessionAudioExportTarget.BOTH))
    }

    @Test
    @Requirement("FR-STO-6")
    fun `canExport refuses BOTH only when neither half has any audio left`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(session(overAudioRemovedAtMillis = 5_000L))

        val refusal = SessionAudioExport.canExport(db, "S1", SessionAudioExportTarget.BOTH)

        assertTrue(refusal is SessionAudioExportRefusal.NothingToExport)
    }

    @Test
    @Requirement("FR-STO-6")
    fun `canExport refuses RAW_ARCHIVE for the session that is capturing right now`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(session())
        db.sessionDao().setArchiveKept("S1")
        CaptureState.capturing("S1")

        assertEquals(
            SessionAudioExportRefusal.ArchiveCapturingNow,
            SessionAudioExport.canExport(db, "S1", SessionAudioExportTarget.RAW_ARCHIVE),
        )
    }

    @Test
    @Requirement("FR-STO-6")
    fun `canExport refuses the archive half of a BOTH request for the session that is capturing right now`() =
        runBlocking {
            val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
            db.sessionDao().insert(session())
            db.sessionDao().setArchiveKept("S1")
            CaptureState.capturing("S1")

            assertEquals(
                SessionAudioExportRefusal.ArchiveCapturingNow,
                SessionAudioExport.canExport(db, "S1", SessionAudioExportTarget.BOTH),
            )
        }

    @Test
    @Requirement("FR-STO-6")
    fun `canExport never refuses OVER_AUDIO for the session that is capturing right now`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(session())
        CaptureState.capturing("S1")

        assertNull(SessionAudioExport.canExport(db, "S1", SessionAudioExportTarget.OVER_AUDIO))
    }

    @Test
    @Requirement("FR-STO-6")
    fun `canExport does not refuse when a different session is the one capturing`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        db.sessionDao().insert(session())
        db.sessionDao().setArchiveKept("S1")
        CaptureState.capturing("SOME-OTHER-SESSION")

        assertNull(SessionAudioExport.canExport(db, "S1", SessionAudioExportTarget.BOTH))
    }

    // -- preview ------------------------------------------------------------------------------

    @Test
    @Requirement("FR-STO-6")
    fun `preview is null for an unknown session, never fabricated`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        assertNull(SessionAudioExport.preview(db, tempFilesDir(), "no-such-session", SessionAudioExportTarget.BOTH))
    }

    @Test
    @Requirement("FR-STO-6")
    fun `preview reports real measured bytes for over audio, from real files`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val filesDir = tempFilesDir()
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))
        writeOverAudioFile(filesDir, "S1", "TX1", 500)

        val preview = SessionAudioExport.preview(db, filesDir, "S1", SessionAudioExportTarget.OVER_AUDIO)!!

        assertEquals(1, preview.files.size)
        assertEquals(500L, preview.files.single().sizeBytes)
        assertEquals(500L + preview.manifestBytes, preview.totalBytes)
    }

    @Test
    @Requirement("FR-STO-6")
    fun `preview never claims audio it does not have on disk`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val filesDir = tempFilesDir()
        db.sessionDao().insert(session())
        // A transmission row exists but its FLAC file was never written (or already deleted).
        db.transmissionDao().insert(transmission("TX1"))

        val preview = SessionAudioExport.preview(db, filesDir, "S1", SessionAudioExportTarget.OVER_AUDIO)!!

        assertTrue("no file may be listed for audio that does not exist on disk", preview.files.isEmpty())
    }

    @Test
    @Requirement("FR-STO-6")
    fun `preview totalBytes never drifts from what write actually produces`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val filesDir = tempFilesDir()
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))
        writeOverAudioFile(filesDir, "S1", "TX1", 777)

        val preview = SessionAudioExport.preview(db, filesDir, "S1", SessionAudioExportTarget.OVER_AUDIO)!!
        val target = ByteArrayOutputStream()
        val result = SessionAudioExport.write(db, filesDir, "S1", SessionAudioExportTarget.OVER_AUDIO, target)
            as SessionAudioExportWriteResult.Written

        assertEquals(preview.totalBytes, result.totalBytes)
    }

    // -- write: refusals never touch the stream ------------------------------------------------

    @Test
    @Requirement("FR-STO-6")
    fun `a refused write never writes anything to the output stream`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val filesDir = tempFilesDir()
        db.sessionDao().insert(session())
        db.sessionDao().setArchiveKept("S1")
        CaptureState.capturing("S1")
        val target = ByteArrayOutputStream()

        val result = SessionAudioExport.write(db, filesDir, "S1", SessionAudioExportTarget.RAW_ARCHIVE, target)

        assertEquals(
            SessionAudioExportWriteResult.Refused(SessionAudioExportRefusal.ArchiveCapturingNow),
            result,
        )
        assertEquals(0, target.toByteArray().size)
    }

    // -- write: contents --------------------------------------------------------------------------

    @Test
    @Requirement("FR-STO-6")
    fun `write produces a zip with the manifest and every over-audio file at the on-disk-mirroring path`() =
        runBlocking {
            val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
            val filesDir = tempFilesDir()
            db.sessionDao().insert(session())
            db.transmissionDao().insert(transmission("TX1"))
            writeOverAudioFile(filesDir, "S1", "TX1", 42)
            val target = ByteArrayOutputStream()

            val result = SessionAudioExport.write(db, filesDir, "S1", SessionAudioExportTarget.OVER_AUDIO, target)
                as SessionAudioExportWriteResult.Written

            val entries = zipEntries(target.toByteArray())
            assertEquals(setOf("manifest.json", "audio/S1/TX1.flac"), entries.keys)
            assertEquals(1, result.fileCount)
            assertEquals(42L, entries.getValue("audio/S1/TX1.flac").second.size.toLong())
        }

    @Test
    @Requirement("FR-STO-6")
    fun `write includes the raw archive's chunk files at the on-disk-mirroring path`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val filesDir = tempFilesDir()
        db.sessionDao().insert(session())
        db.sessionDao().setArchiveKept("S1")
        writeArchiveChunk(filesDir, "S1", 0L, 100)
        writeArchiveChunk(filesDir, "S1", 480_000L, 60)
        val target = ByteArrayOutputStream()

        SessionAudioExport.write(db, filesDir, "S1", SessionAudioExportTarget.RAW_ARCHIVE, target)

        val entries = zipEntries(target.toByteArray())
        assertEquals(
            setOf("manifest.json", "archive/S1/chunk-0.flac", "archive/S1/chunk-480000.flac"),
            entries.keys,
        )
    }

    @Test
    @Requirement("FR-STO-6")
    fun `audio entries are STORED, never re-compressed, with a real precomputed CRC and size`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val filesDir = tempFilesDir()
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))
        writeOverAudioFile(filesDir, "S1", "TX1", 1_000)
        val target = ByteArrayOutputStream()

        SessionAudioExport.write(db, filesDir, "S1", SessionAudioExportTarget.OVER_AUDIO, target)

        val tmp = File.createTempFile("session-audio-export-method", ".zip")
        try {
            tmp.writeBytes(target.toByteArray())
            ZipFile(tmp).use { zip ->
                val entry = zip.getEntry("audio/S1/TX1.flac")
                assertEquals(ZipEntry.STORED, entry.method)
                assertEquals(1_000L, entry.size)
                assertEquals(1_000L, entry.compressedSize)
            }
        } finally {
            tmp.delete()
        }
    }

    @Test
    @Requirement("FR-STO-6")
    fun `the over manifest carries time, duration, attribution, transcript, tier and provider`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val filesDir = tempFilesDir()
        db.sessionDao().insert(session())
        db.catalogDao().insert(
            StationEntity(
                id = "ST1",
                callsign = "KI7ABC",
                firstHeardAt = null,
                lastHeardAt = null,
                transmissionCount = 0,
                isUserPinned = false,
                notes = null,
                userName = null,
                frequenciesHeard = null,
                activityByHourDow = null,
                potaRefs = null,
                spokenGrids = null,
                ituRegionFromPrefix = null,
                overCountsByAttributionState = null,
            ),
        )
        db.transmissionDao().insert(
            transmission(
                "TX1",
                attributionState = AttributionState.CONFIRMED,
                stationId = "ST1",
                attributionConfidence = 0.91,
                processedTier = Tier.T1,
                executionProvider = "cpu",
            ),
        )
        db.transcriptDao().insert(
            TranscriptEntity(
                id = "TR1",
                transmissionId = "TX1",
                pass = TranscriptPass.B,
                text = "this is KI7ABC",
                modelId = "whisper-small",
                modelVersion = "1.2.0",
                quantization = null,
                decodeParams = null,
                noSpeechProb = null,
                confidence = null,
                isCurrent = true,
                createdAt = 0L,
            ),
        )
        val target = ByteArrayOutputStream()

        SessionAudioExport.write(db, filesDir, "S1", SessionAudioExportTarget.OVER_AUDIO, target)

        val manifestJson = zipEntries(target.toByteArray()).getValue("manifest.json").second.toString(Charsets.UTF_8)
        assertTrue(manifestJson.contains("\"transmissionId\":\"TX1\""))
        assertTrue(manifestJson.contains("\"durationMs\":1000"))
        assertTrue(manifestJson.contains("\"state\":\"CONFIRMED\""))
        assertTrue(manifestJson.contains("\"callsign\":\"KI7ABC\""))
        assertTrue(manifestJson.contains("\"confidence\":0.91"))
        assertTrue(manifestJson.contains("\"transcriptText\":\"this is KI7ABC\""))
        assertTrue(manifestJson.contains("\"transcriptModelId\":\"whisper-small\""))
        assertTrue(manifestJson.contains("\"processedTier\":\"T1\""))
        assertTrue(manifestJson.contains("\"executionProvider\":\"cpu\""))
    }

    @Test
    @Requirement("FR-STO-6")
    fun `an over whose audio was not requested is still listed, with audioIncluded false`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val filesDir = tempFilesDir()
        db.sessionDao().insert(session())
        db.sessionDao().setArchiveKept("S1")
        db.transmissionDao().insert(transmission("TX1"))
        writeOverAudioFile(filesDir, "S1", "TX1", 10)
        writeArchiveChunk(filesDir, "S1", 0L, 10)
        val target = ByteArrayOutputStream()

        SessionAudioExport.write(db, filesDir, "S1", SessionAudioExportTarget.RAW_ARCHIVE, target)

        val entries = zipEntries(target.toByteArray())
        assertFalse(
            "over audio was not requested -- its file must not be in the zip",
            entries.containsKey("audio/S1/TX1.flac"),
        )
        val manifestJson = entries.getValue("manifest.json").second.toString(Charsets.UTF_8)
        assertTrue("the over row itself is still listed", manifestJson.contains("\"transmissionId\":\"TX1\""))
        assertTrue(manifestJson.contains("\"audioIncluded\":false"))
    }

    // -- exclusions (constitution V, Q21) ----------------------------------------------------------

    @Test
    @Requirement("FR-SPK-20")
    fun `no voiceprint or embedding bytes ever appear in the export`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val filesDir = tempFilesDir()
        val marker = "VOICEPRINT-EMBEDDING-MARKER-DO-NOT-LEAK"
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", stationId = null))
        db.catalogDao().insert(
            VoiceprintEntity(
                id = "V1",
                embedding = marker.toByteArray(Charsets.UTF_8),
                memberCount = 1,
                centroidUpdatedAt = null,
                boundStationId = null,
                bindingConfidence = null,
                lastConfirmedAt = null,
                isEnrolled = true,
                enrolmentObservationCount = 1,
                enrolmentSessionIds = null,
                enrolledAt = null,
                lastMatchedAt = null,
                bindingSource = null,
                embeddingModelId = "test-embedder",
                embeddingModelVersion = "1",
            ),
        )
        val target = ByteArrayOutputStream()

        SessionAudioExport.write(db, filesDir, "S1", SessionAudioExportTarget.OVER_AUDIO, target)

        val wholeZipText = target.toByteArray().toString(Charsets.ISO_8859_1)
        assertFalse(wholeZipText.contains(marker))
        val manifestJson = zipEntries(target.toByteArray()).getValue("manifest.json").second.toString(Charsets.UTF_8)
        assertFalse(manifestJson.contains(marker))
    }

    @Test
    @Requirement("FR-SPK-25")
    fun `station knowledge -- a user-given name and note -- never appears, though the real callsign does`() =
        runBlocking {
            val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
            val filesDir = tempFilesDir()
            val givenName = "Dave-The-Given-Name-Marker"
            val note = "NOTE-MARKER-lives-on-Whidbey"
            db.sessionDao().insert(session())
            db.catalogDao().insert(
                StationEntity(
                    id = "ST1",
                    callsign = "K7ABC",
                    firstHeardAt = null,
                    lastHeardAt = null,
                    transmissionCount = 0,
                    isUserPinned = false,
                    notes = note,
                    userName = givenName,
                    frequenciesHeard = null,
                    activityByHourDow = null,
                    potaRefs = null,
                    spokenGrids = null,
                    ituRegionFromPrefix = null,
                    overCountsByAttributionState = null,
                ),
            )
            db.transmissionDao().insert(
                transmission("TX1", attributionState = AttributionState.CONFIRMED, stationId = "ST1"),
            )
            val target = ByteArrayOutputStream()

            SessionAudioExport.write(db, filesDir, "S1", SessionAudioExportTarget.OVER_AUDIO, target)

            // The raw zip container: manifest.json is deflate-compressed inside it, so an absence
            // check against the raw bytes is still meaningful (compressed bytes cannot coincidentally
            // spell out a human-readable marker), but a presence check needs the decompressed entry.
            val wholeZipText = target.toByteArray().toString(Charsets.ISO_8859_1)
            assertFalse(wholeZipText.contains(givenName))
            assertFalse(wholeZipText.contains(note))
            val manifestJson = zipEntries(target.toByteArray()).getValue("manifest.json").second
                .toString(Charsets.UTF_8)
            assertFalse(manifestJson.contains(givenName))
            assertFalse(manifestJson.contains(note))
            assertTrue(
                "the real callsign is legitimate attribution data, not station knowledge",
                manifestJson.contains("K7ABC"),
            )
        }

    @Test
    @Requirement("Q21")
    fun `training labels never appear in the export`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val filesDir = tempFilesDir()
        val noteMarker = "TRAINING-NOTE-MARKER-do-not-leak"
        val tacticalMarker = "TACTICAL-CALLSIGN-MARKER"
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))
        db.transmissionLabelDao().upsert(
            TransmissionLabelEntity(
                transmissionId = "TX1",
                markedForTraining = true,
                outcome = null,
                doubled = false,
                truthCallsign = "K9ZZZ",
                callsignCertainty = LabelCertainty.CERTAIN,
                tacticalCallsign = tacticalMarker,
                note = noteMarker,
                rating = "good",
                labelledAtMillis = 0L,
            ),
        )
        val target = ByteArrayOutputStream()

        SessionAudioExport.write(db, filesDir, "S1", SessionAudioExportTarget.OVER_AUDIO, target)

        // manifest.json is deflate-compressed inside the zip (only audio entries are STORED), so
        // the check must be against the decompressed entry -- the raw container bytes would pass
        // this assertion even if the marker really did leak into the manifest's plaintext.
        val manifestJson = zipEntries(target.toByteArray()).getValue("manifest.json").second.toString(Charsets.UTF_8)
        assertFalse(manifestJson.contains(noteMarker))
        assertFalse(manifestJson.contains(tacticalMarker))
        assertFalse(manifestJson.contains("K9ZZZ"))
    }

    // -- streaming (never buffers a whole file into memory) ----------------------------------------

    /** Tracks the largest single `write(byte[], off, len)` call it ever receives — the
     * discriminating signal that [SessionAudioExport.write] streams in bounded chunks rather than
     * `zip.write(file.readBytes())`, which would call this exactly once with the whole file. */
    private class MaxChunkTrackingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var maxSingleWriteLength: Int = 0
            private set
        var totalBytesWritten: Long = 0
            private set

        override fun write(b: Int) {
            delegate.write(b)
            totalBytesWritten += 1
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            maxSingleWriteLength = maxOf(maxSingleWriteLength, len)
            totalBytesWritten += len
        }
    }

    @Test
    @Requirement("FR-STO-6")
    fun `a large over-audio file is streamed in bounded chunks, never buffered whole into one write call`() =
        runBlocking {
            val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
            val filesDir = tempFilesDir()
            db.sessionDao().insert(session())
            db.transmissionDao().insert(transmission("TX1"))
            val largeSize = 8 * 1024 * 1024 // 8 MiB -- far larger than any reasonable single buffer.
            writeOverAudioFile(filesDir, "S1", "TX1", largeSize)
            val tracking = MaxChunkTrackingOutputStream(ByteArrayOutputStream())

            SessionAudioExport.write(db, filesDir, "S1", SessionAudioExportTarget.OVER_AUDIO, tracking)

            assertTrue(
                "the largest single write() call was ${tracking.maxSingleWriteLength} bytes -- " +
                    "a whole-file buffer would have made this call at least $largeSize bytes",
                tracking.maxSingleWriteLength < largeSize / 4,
            )
        }

    // -- progress -------------------------------------------------------------------------------

    @Test
    @Requirement("FR-STO-6")
    fun `onProgress reports the same real total preview would, reaching it exactly at the end`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val filesDir = tempFilesDir()
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))
        writeOverAudioFile(filesDir, "S1", "TX1", 200)
        val target = ByteArrayOutputStream()
        var lastWritten = -1L
        var lastTotal = -1L

        SessionAudioExport.write(db, filesDir, "S1", SessionAudioExportTarget.OVER_AUDIO, target) { written, total ->
            lastWritten = written
            lastTotal = total
        }

        assertEquals(lastTotal, lastWritten)
        assertTrue(lastTotal >= 200L)
    }

    // -- naming -----------------------------------------------------------------------------------

    @Test
    @Requirement("FR-STO-6")
    fun `suggestedFileName names the session, the target and a real timestamp, and ends in zip`() {
        val name = SessionAudioExport.suggestedFileName(
            "S1",
            SessionAudioExportTarget.BOTH,
            java.time.Instant.ofEpochMilli(0L),
        )

        assertEquals("ort-session-S1-audio-19700101-000000.zip", name)
    }

    @Test
    @Requirement("FR-STO-6")
    fun `suggestedFileName distinguishes the three targets by word`() {
        val now = java.time.Instant.ofEpochMilli(0L)
        val overs = SessionAudioExport.suggestedFileName("S1", SessionAudioExportTarget.OVER_AUDIO, now)
        val archive = SessionAudioExport.suggestedFileName("S1", SessionAudioExportTarget.RAW_ARCHIVE, now)
        val both = SessionAudioExport.suggestedFileName("S1", SessionAudioExportTarget.BOTH, now)
        assertTrue(overs.contains("overs"))
        assertTrue(archive.contains("archive"))
        assertTrue(both.contains("audio"))
    }

    // -- idempotency / clock -------------------------------------------------------------------------

    @Test
    @Requirement("FR-STO-6")
    fun `the manifest's exportedAtUtc is the real clock reading passed to write`() = runBlocking {
        val db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        val filesDir = tempFilesDir()
        db.sessionDao().insert(session())
        val target = ByteArrayOutputStream()
        val clock = TestClock(startWallMillis = 123_456L)

        SessionAudioExport.write(db, filesDir, "S1", SessionAudioExportTarget.OVER_AUDIO, target, clock)

        val manifestJson = zipEntries(target.toByteArray()).getValue("manifest.json").second.toString(Charsets.UTF_8)
        assertTrue(manifestJson.contains(java.time.Instant.ofEpochMilli(123_456L).toString()))
    }
}
