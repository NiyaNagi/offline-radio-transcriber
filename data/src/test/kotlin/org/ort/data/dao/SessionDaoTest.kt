package org.ort.data.dao

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.data.OrtDatabase
import org.ort.data.TestFixtures
import org.ort.data.entity.TerminationReason
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * register R-173: before [setEnded]/[closeIfStillOpen] existed, nothing in `:data` could write
 * `SessionEntity.endedAt` at all — `RealCaptureService` never wrote it, so a real session read
 * "still running" forever.
 */
@RunWith(RobolectricTestRunner::class)
public class SessionDaoTest {

    private lateinit var db: OrtDatabase

    @Before
    public fun openDatabase() {
        db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
    }

    @Test
    @Requirement("R-173")
    public fun R_173_setEnded_writes_endedAt_and_terminationReason_unconditionally(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))

        db.sessionDao().setEnded("S1", endedAt = 5_000L, terminationReason = TerminationReason.USER)

        val session = db.sessionDao().getById("S1")
        assertEquals(5_000L, session?.endedAt)
        assertEquals(TerminationReason.USER, session?.terminationReason)
    }

    @Test
    @Requirement("R-173")
    public fun R_173_setEnded_overwrites_an_already_ended_session(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        db.sessionDao().setEnded("S1", endedAt = 5_000L, terminationReason = TerminationReason.USER)

        db.sessionDao().setEnded("S1", endedAt = 9_000L, terminationReason = TerminationReason.CRASH)

        val session = db.sessionDao().getById("S1")
        assertEquals(9_000L, session?.endedAt)
        assertEquals(TerminationReason.CRASH, session?.terminationReason)
    }

    @Test
    @Requirement("R-173", "FR-RUN-16")
    public fun FR_RUN_16_closeIfStillOpen_closes_a_genuinely_open_session(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))

        db.sessionDao().closeIfStillOpen("S1", endedAt = 7_000L, terminationReason = TerminationReason.KILLED)

        val session = db.sessionDao().getById("S1")
        assertEquals(7_000L, session?.endedAt)
        assertEquals(TerminationReason.KILLED, session?.terminationReason)
    }

    @Test
    @Requirement("R-173", "FR-RUN-16")
    public fun FR_RUN_16_closeIfStillOpen_never_overwrites_a_session_already_closed(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        db.sessionDao().setEnded("S1", endedAt = 1_000L, terminationReason = TerminationReason.USER)

        // A stale on-next-launch recovery pass must not clobber the real, more precise ending
        // stopCaptureInternal already recorded.
        db.sessionDao().closeIfStillOpen("S1", endedAt = 9_999L, terminationReason = TerminationReason.KILLED)

        val session = db.sessionDao().getById("S1")
        assertEquals(1_000L, session?.endedAt)
        assertEquals(TerminationReason.USER, session?.terminationReason)
    }

    @Test
    @Requirement("R-173")
    public fun a_fresh_session_carries_no_endedAt_or_terminationReason(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))

        val session = db.sessionDao().getById("S1")
        assertNull(session?.endedAt)
        assertNull(session?.terminationReason)
    }

    /**
     * FR-CAP-13, AC-129: a session row distinguishes room audio from a radio session — readable
     * back distinctly through [org.ort.data.dao.SessionDao.getCaptureInfo], without touching any
     * audio at all (this test inserts no transmission, no transcript, nothing but the session row).
     */
    @Test
    @Requirement("AC-129", "FR-CAP-13")
    public fun AC_129_session_row_distinguishes_room_from_radio(): Unit = runTest {
        db.sessionDao().insert(
            TestFixtures.session("ROOM-1").copy(
                captureMode = "LOCAL_MICROPHONE",
                audioRouteKind = "BUILT_IN_MIC",
                audioRouteLabel = "Built-in Microphone",
                bluetoothProfile = null,
                rigTransport = null,
            ),
        )
        db.sessionDao().insert(
            TestFixtures.session("RADIO-1").copy(
                captureMode = "USB_RADIO",
                audioRouteKind = "USB",
                audioRouteLabel = "USB Audio Adapter",
                bluetoothProfile = null,
                rigTransport = "USB_SERIAL",
            ),
        )

        val room = db.sessionDao().getCaptureInfo("ROOM-1")
        val radio = db.sessionDao().getCaptureInfo("RADIO-1")

        assertEquals("LOCAL_MICROPHONE", room?.captureMode)
        assertEquals("BUILT_IN_MIC", room?.audioRouteKind)
        assertNull(room?.rigTransport)

        assertEquals("USB_RADIO", radio?.captureMode)
        assertEquals("USB", radio?.audioRouteKind)
        assertEquals("USB_SERIAL", radio?.rigTransport)

        // Distinctly readable, not merely both non-null: they never collide with each other.
        assertNotEquals(room?.captureMode, radio?.captureMode)
        assertNotEquals(room?.audioRouteKind, radio?.audioRouteKind)
    }
}
