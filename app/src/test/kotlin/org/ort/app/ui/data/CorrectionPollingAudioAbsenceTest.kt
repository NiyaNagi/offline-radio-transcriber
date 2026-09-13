package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.robolectric.RobolectricTestRunner

/**
 * This task (constitution I, III): [CorrectionPolling.audioAbsenceReason]'s own real `:data` read —
 * the one place a session's real [SessionEntity.overAudioRemovedAtMillis] is looked up for the
 * detail screen's "No retained audio" card. See [AudioAbsenceReason]'s own kdoc for why
 * [SessionEntity.archiveRemovedAtMillis] is deliberately never read here at all.
 */
@RunWith(RobolectricTestRunner::class)
class CorrectionPollingAudioAbsenceTest {

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    private fun session(
        id: String,
        overAudioRemovedAtMillis: Long? = null,
        archiveState: String? = null,
        archiveRemovedAtMillis: Long? = null,
    ) = SessionEntity(
        id = id,
        startedAt = 0L,
        endedAt = null,
        profileId = null,
        deviceTier = null,
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
        archiveState = archiveState,
        archiveRemovedAtMillis = archiveRemovedAtMillis,
        overAudioRemovedAtMillis = overAudioRemovedAtMillis,
    )

    @Test
    fun `hasAudio true never reads the session row at all`(): Unit = runTest {
        // No session inserted at all -- if this read the session row despite hasAudio, it would
        // throw or resolve Unknown; asserting null instead proves the short-circuit.
        val reason = CorrectionPolling.audioAbsenceReason(context, sessionId = "NO-SUCH-SESSION", hasAudio = true)

        assertEquals(null, reason)
    }

    @Test
    fun `an operator-removed session reads as RemovedByOperator with the real date`(): Unit = runTest {
        val removedAt = java.time.LocalDate.of(2026, 8, 8)
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        db.sessionDao().insert(session("S1", overAudioRemovedAtMillis = removedAt))

        val reason = CorrectionPolling.audioAbsenceReason(context, sessionId = "S1", hasAudio = false)

        assertEquals(AudioAbsenceReason.RemovedByOperator("8 Aug"), reason)
    }

    /**
     * The decisive case: a session whose *continuous archive* was pruned
     * ([SessionEntity.archiveRemovedAtMillis] set) but whose over audio was never touched by the
     * operator. `ArchivePruner`'s own doc comment ("never touches audio/<sessionId>") and
     * `OverAudioBudgetState`'s own doc comment (D40/register R-1037: over audio is never
     * automatically deleted) both establish that this fact has no bearing on this transmission's
     * own missing audio file — a naive implementation that read `archiveRemovedAtMillis` here would
     * fail this test by reporting `PrunedByRetentionBudget` for a directory this screen's own
     * playback never reads.
     */
    @Test
    fun `an archived-and-pruned session with no operator removal reads as NeverRetained`(): Unit = runTest {
        val archivePrunedAt = java.time.LocalDate.of(2026, 8, 1)
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        db.sessionDao().insert(
            session("S1", archiveState = "REMOVED", archiveRemovedAtMillis = archivePrunedAt),
        )

        val reason = CorrectionPolling.audioAbsenceReason(context, sessionId = "S1", hasAudio = false)

        assertEquals(AudioAbsenceReason.NeverRetained, reason)
    }

    @Test
    fun `a session that was never touched at all reads as NeverRetained`(): Unit = runTest {
        db.sessionDao().insert(session("S1"))

        val reason = CorrectionPolling.audioAbsenceReason(context, sessionId = "S1", hasAudio = false)

        assertEquals(AudioAbsenceReason.NeverRetained, reason)
    }

    @Test
    fun `a session id with no matching row at all reads as Unknown, never a guessed NeverRetained`(): Unit = runTest {
        val reason = CorrectionPolling.audioAbsenceReason(context, sessionId = "GHOST-SESSION", hasAudio = false)

        assertEquals(AudioAbsenceReason.Unknown, reason)
    }
}
