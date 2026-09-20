package org.ort.app.ui.setup

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.capture.VadDetectorKind
import org.ort.data.dao.SessionCaptureInfo
import org.ort.data.dao.SessionDao
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TerminationReason

/** The minimal behavioural fake [RealOvernightSurvivalChecker] needs — only [listAll] is ever
 * consulted by that class; every other member exists purely to satisfy [SessionDao]'s interface. */
private class FakeSessionDaoForOvernight(private val sessions: List<SessionEntity>) : SessionDao {
    override suspend fun insert(entity: SessionEntity) = error("not exercised by this test")
    override suspend fun getById(id: String): SessionEntity? = sessions.firstOrNull { it.id == id }
    override suspend fun listAll(): List<SessionEntity> = sessions
    override suspend fun setEnded(id: String, endedAt: Long, terminationReason: TerminationReason?) =
        error("not exercised by this test")
    override suspend fun closeIfStillOpen(id: String, endedAt: Long, terminationReason: TerminationReason?) =
        error("not exercised by this test")
    override suspend fun getCaptureInfo(id: String): SessionCaptureInfo? = error("not exercised by this test")
    override suspend fun setAudioRouteVerified(id: String, verified: Boolean) = error("not exercised by this test")
    override suspend fun setArchiveKept(id: String) = error("not exercised by this test")
    override suspend fun setArchiveRemoved(id: String, removedAtMillis: Long) = error("not exercised by this test")
    override suspend fun setOverAudioRemoved(id: String, removedAtMillis: Long) = error("not exercised by this test")
}

/** P22 (AC-189, constitution IV): "liveness is proven by heartbeat, never by
 * `isIgnoringBatteryOptimizations()`" — [isSurvivalEvidence] is the pure decision behind that
 * proof; [RealOvernightSurvivalChecker] merely reads `:data`'s real session history and applies it. */
class OvernightSurvivalTest {

    private fun session(
        startedAt: Long = 0L,
        endedAt: Long? = OVERNIGHT_SURVIVAL_THRESHOLD_MILLIS,
        terminationReason: TerminationReason? = TerminationReason.USER,
    ) = SessionEntity(
        id = "s1",
        startedAt = startedAt,
        endedAt = endedAt,
        profileId = null,
        deviceTier = null,
        appVersion = null,
        terminationReason = terminationReason,
        sourceId = null,
        schemaVersion = 14,
        vadDetector = VadDetectorKind.UNKNOWN,
    )

    @Test
    fun `AC_189 a clean session at least the threshold long is survival evidence`() {
        assertTrue(isSurvivalEvidence(session(endedAt = OVERNIGHT_SURVIVAL_THRESHOLD_MILLIS)))
    }

    @Test
    fun `AC_189 a session shorter than the threshold is not evidence`() {
        assertFalse(isSurvivalEvidence(session(endedAt = OVERNIGHT_SURVIVAL_THRESHOLD_MILLIS - 1)))
    }

    @Test
    fun `AC_189 a still-running session with no endedAt is not evidence`() {
        assertFalse(isSurvivalEvidence(session(endedAt = null)))
    }

    @Test
    fun `AC_189 a session that ended unclean is not evidence, however long it ran`() {
        assertFalse(
            isSurvivalEvidence(
                session(
                    endedAt = OVERNIGHT_SURVIVAL_THRESHOLD_MILLIS * 10,
                    terminationReason = TerminationReason.KILLED,
                ),
            ),
        )
    }

    @Test
    fun `AC_189 a session with no termination reason recorded is not evidence`() {
        assertFalse(isSurvivalEvidence(session(terminationReason = null)))
    }

    @Test
    fun `AC_189 RealOvernightSurvivalChecker is true when any recorded session is evidence`() = runTest {
        val dao = FakeSessionDaoForOvernight(
            listOf(
                session(endedAt = 1_000L), // too short
                session(endedAt = OVERNIGHT_SURVIVAL_THRESHOLD_MILLIS), // real evidence
            ),
        )
        val checker = RealOvernightSurvivalChecker(dao)
        assertTrue(checker.hasProvenSurvival())
    }

    @Test
    fun `AC_189 RealOvernightSurvivalChecker is false when no session ever qualifies`() = runTest {
        val dao = FakeSessionDaoForOvernight(listOf(session(endedAt = 1_000L)))
        val checker = RealOvernightSurvivalChecker(dao)
        assertFalse(checker.hasProvenSurvival())
    }

    @Test
    fun `AC_189 the fake checker reports the scripted state and counts its own calls`() = runTest {
        val fake = FakeOvernightSurvivalChecker(proven = false)
        assertFalse(fake.hasProvenSurvival())
        fake.proven = true
        assertTrue(fake.hasProvenSurvival())
        assertTrue(fake.callCount == 2)
    }

    @Test
    fun `DebugOvernightSurvivalOverride is ignored outside a debug build`() {
        try {
            DebugOvernightSurvivalOverride.isDebugBuild = { false }
            DebugOvernightSurvivalOverride.show(FakeOvernightSurvivalChecker(proven = true))
            assertTrue(DebugOvernightSurvivalOverride.activeOverride == null)
        } finally {
            DebugOvernightSurvivalOverride.clear()
            DebugOvernightSurvivalOverride.isDebugBuild = { org.ort.app.BuildConfig.DEBUG }
        }
    }
}
