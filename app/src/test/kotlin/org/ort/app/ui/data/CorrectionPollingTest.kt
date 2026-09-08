package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
import org.robolectric.RobolectricTestRunner

/**
 * ui-conformance WP6 (R-052, R-058), `Detail-Correct-A/B/C.dc.html`, `Detail-Propagated.dc.html`,
 * `Flow-Correct.dc.html`: propagation (every over sharing the corrected over's `voiceprintId`, or
 * `stationId` when there is none, gets a [org.ort.data.entity.CorrectionEntity]) and `Confirm`
 * (an audit-only agreement, never a re-attribution). Its own reads/writes through [OrtDatabase]
 * directly — never through [ReaderPolling], per this package's file-ownership boundary.
 */
@RunWith(RobolectricTestRunner::class)
class CorrectionPollingTest {

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    private fun session() = SessionEntity(
        id = "S1",
        startedAt = 0L,
        endedAt = null,
        profileId = null,
        deviceTier = null,
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
    )

    private fun transmission(
        id: String,
        stationId: String? = "K7LWH",
        voiceprintId: String? = "V1",
        samplePosition: Long = 0L,
    ) = TransmissionEntity(
        id = id,
        sessionId = "S1",
        threadId = null,
        startedAtUtc = samplePosition,
        endedAtUtc = samplePosition + 1_000L,
        durationMs = 1_000L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = 145_230_000L,
        frequencyProvenance = "measured",
        mode = null,
        signalStrength = null,
        channelName = null,
        voiceprintId = voiceprintId,
        attributionState = if (stationId != null) AttributionState.INFERRED else AttributionState.UNKNOWN,
        stationId = stationId,
        attributionConfidence = if (stationId != null) 0.7 else null,
        attributionSourceTransmissionId = null,
        processingState = TransmissionState.COMPLETE,
        rejectionReason = null,
        samplePosition = samplePosition,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    private fun request(transmissionId: String, previous: String?, new: String) = CorrectionRequest(
        transmissionId = transmissionId,
        previousStationId = previous,
        newStationId = new,
        tier = CorrectionTier.PICK_CANDIDATE,
        correctedAtMillis = 500L,
    )

    @Test
    fun `R_052 THIS_OVER_ONLY corrects exactly the one transmission`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))
        db.transmissionDao().insert(transmission("TX2"))

        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "KA7LWH"),
            CorrectionScope.THIS_OVER_ONLY,
        )

        assertEquals(1, outcome.overCount)
        assertEquals("KA7LWH", db.transmissionDao().getById("TX1")!!.stationId)
        assertEquals("K7LWH", db.transmissionDao().getById("TX2")!!.stationId)
    }

    @Test
    fun `R_052 EVERY_OVER_SAME_VOICE propagates to every transmission sharing the voiceprint`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX2", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX3", voiceprintId = "V2"))

        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "KA7LWH"),
            CorrectionScope.EVERY_OVER_SAME_VOICE,
        )

        assertEquals(2, outcome.overCount)
        assertEquals("KA7LWH", db.transmissionDao().getById("TX1")!!.stationId)
        assertEquals("KA7LWH", db.transmissionDao().getById("TX2")!!.stationId)
        assertEquals("K7LWH", db.transmissionDao().getById("TX3")!!.stationId)
        assertEquals(setOf("TX1", "TX2"), outcome.affected.map { it.transmissionId }.toSet())
    }

    @Test
    fun `R_052 with no voiceprint, propagation falls back to the shared stationId`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH", voiceprintId = null))
        db.transmissionDao().insert(transmission("TX2", stationId = "K7LWH", voiceprintId = null))

        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "KA7LWH"),
            CorrectionScope.EVERY_OVER_SAME_VOICE,
        )

        assertEquals(2, outcome.overCount)
    }

    @Test
    fun `R_052 nothing is ever deleted by a correction`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))

        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "KA7LWH"),
            CorrectionScope.THIS_OVER_ONLY,
        )

        assertEquals(0, outcome.deletedCount)
        assertEquals(1, db.correctionDao().correctionsFor("TX1").size)
    }

    @Test
    fun `R_052 undo reverts every affected transmission with a new, kept correction row`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX2", voiceprintId = "V1"))
        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "KA7LWH"),
            CorrectionScope.EVERY_OVER_SAME_VOICE,
        )

        CorrectionPolling.undoAll(context, outcome, atMillis = 600L)

        assertEquals("K7LWH", db.transmissionDao().getById("TX1")!!.stationId)
        assertEquals("K7LWH", db.transmissionDao().getById("TX2")!!.stationId)
        // Undo is itself a correction — the reverted-from row is kept, not deleted (constitution III).
        assertEquals(2, db.correctionDao().correctionsFor("TX1").size)
    }

    // ---- R-058: Confirm is an audit row, never a re-attribution ----

    @Test
    fun `R_058 confirming a CONFIRMED transmission leaves its attribution state untouched`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(
            transmission("TX1", stationId = "K7LWH").copy(attributionState = AttributionState.CONFIRMED),
        )

        CorrectionPolling.confirm(context, transmissionId = "TX1", stationId = "K7LWH", atMillis = 100L)

        val entity = db.transmissionDao().getById("TX1")!!
        assertEquals(AttributionState.CONFIRMED, entity.attributionState)
        assertFalse(entity.corrected)
    }

    @Test
    fun `R_058 confirming records station_confirmed with an unchanged value`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH"))

        CorrectionPolling.confirm(context, transmissionId = "TX1", stationId = "K7LWH", atMillis = 100L)

        val corrections = db.correctionDao().correctionsFor("TX1")
        assertEquals(1, corrections.size)
        assertEquals(FIELD_STATION_CONFIRMED, corrections.single().field)
        assertEquals("K7LWH", corrections.single().previousValue)
        assertEquals("K7LWH", corrections.single().newValue)
    }

    @Test
    fun `affectedOverCount reports the propagation blast radius before applying`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX2", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX3", voiceprintId = "V2"))

        val count = CorrectionPolling.affectedOverCount(context, "TX1", CorrectionScope.EVERY_OVER_SAME_VOICE)

        assertEquals(2, count)
    }

    @Test
    fun `affectedOverCount for THIS_OVER_ONLY is always exactly one`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX2", voiceprintId = "V1"))

        val count = CorrectionPolling.affectedOverCount(context, "TX1", CorrectionScope.THIS_OVER_ONLY)

        assertEquals(1, count)
    }

    // ---- R-055: revisions + Restore ----

    private fun transcript(
        id: String,
        transmissionId: String,
        pass: TranscriptPass,
        text: String,
        isCurrent: Boolean,
        createdAt: Long,
    ) = TranscriptEntity(
        id = id,
        transmissionId = transmissionId,
        pass = pass,
        text = text,
        modelId = "whisper-small",
        modelVersion = "1",
        quantization = null,
        decodeParams = null,
        noSpeechProb = null,
        confidence = 0.9,
        isCurrent = isCurrent,
        createdAt = createdAt,
    )

    @Test
    fun `R_055 revisions lists every version, current first, nothing hidden`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))
        db.transcriptDao().insert(
            transcript("V1", "TX1", TranscriptPass.A, "live partial", isCurrent = false, createdAt = 10L),
        )
        db.transcriptDao().insert(
            transcript("V2", "TX1", TranscriptPass.B, "final text", isCurrent = true, createdAt = 20L),
        )

        val versions = CorrectionPolling.revisions(context, "TX1")

        assertEquals(2, versions.size)
        assertEquals("V2", versions.first().id)
        assertEquals(true, versions.first().isCurrent)
        assertEquals(false, versions[1].isCurrent)
    }

    @Test
    fun `R_055 restore installs a new current transcript row and keeps the old one as superseded`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))
        db.transcriptDao().insert(
            transcript("V1", "TX1", TranscriptPass.A, "earlier text", isCurrent = false, createdAt = 10L),
        )
        db.transcriptDao().insert(
            transcript("V2", "TX1", TranscriptPass.B, "later text", isCurrent = true, createdAt = 20L),
        )

        CorrectionPolling.restore(context, "TX1", versionId = "V1", atMillis = 30L)

        val current = db.transcriptDao().getCurrent("TX1")!!
        assertEquals("earlier text", current.text)
        val all = db.transcriptDao().getAllVersions("TX1")
        assertEquals("restoring adds a row rather than deleting anything", 3, all.size)
        assertEquals(1, all.count { it.isCurrent })
    }
}
