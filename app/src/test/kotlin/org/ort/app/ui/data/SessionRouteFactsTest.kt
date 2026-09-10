package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.capture.AudioRouteKind
import org.ort.core.capture.BluetoothAudioProfile
import org.ort.core.capture.CaptureMode
import org.ort.core.capture.RigTransportKind
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.robolectric.RobolectricTestRunner

/**
 * The WPF seam for the package still in flight (WPC2): FR-CAP-13's v7 columns are the record of
 * truth for a session's mode/route/profile/transport until WPC2 lands its own
 * `RigStatus`/`InputStatus` extensions — see this package's report for the exact adapter shape
 * WPC2's follow-up will replace this with.
 */
@RunWith(RobolectricTestRunner::class)
class SessionRouteFactsTest {

    private lateinit var db: OrtDatabase

    @Before
    fun openDatabase() {
        // Not `inMemory = true`: `RoomSessionRouteFactsReader` calls the production
        // `OrtDatabase.create(context.applicationContext)` internally, which caches one shared
        // on-disk instance per context (`OrtDatabase.create`'s own kdoc) — an in-memory database
        // here would be a second, invisible-to-the-reader database, not a fake at all.
        db = OrtDatabase.create(ApplicationProvider.getApplicationContext())
    }

    @Suppress("LongParameterList")
    private fun session(
        id: String,
        captureMode: String? = null,
        audioRouteKind: String? = null,
        audioRouteLabel: String? = null,
        bluetoothProfile: String? = null,
        rigTransport: String? = null,
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
        captureMode = captureMode,
        audioRouteKind = audioRouteKind,
        audioRouteLabel = audioRouteLabel,
        bluetoothProfile = bluetoothProfile,
        rigTransport = rigTransport,
    )

    @Test
    fun `a null session id reads as not tracked, never a fabricated fact`() = runTest {
        val reader = RoomSessionRouteFactsReader(ApplicationProvider.getApplicationContext())
        val facts = reader.forSession(null)
        assertEquals(SessionRouteFacts.NOT_TRACKED, facts)
        assertFalse(facts.isBluetoothAudio)
        assertFalse(facts.isLocalMicrophone)
    }

    @Test
    fun `a pre-v7 session with null columns reads as not tracked`() = runTest {
        db.sessionDao().insert(session("S1"))
        val reader = RoomSessionRouteFactsReader(ApplicationProvider.getApplicationContext())

        val facts = reader.forSession("S1")

        assertNull(facts.captureMode)
        assertNull(facts.audioRouteKind)
        assertFalse(facts.isBluetoothAudio)
    }

    @Test
    fun `a local-microphone session reads its own real facts`() = runTest {
        db.sessionDao().insert(
            session(
                "ROOM-1",
                captureMode = "LOCAL_MICROPHONE",
                audioRouteKind = "BUILT_IN_MIC",
                audioRouteLabel = "Built-in Microphone",
            ),
        )
        val reader = RoomSessionRouteFactsReader(ApplicationProvider.getApplicationContext())

        val facts = reader.forSession("ROOM-1")

        assertEquals(CaptureMode.LOCAL_MICROPHONE, facts.captureMode)
        assertEquals(AudioRouteKind.BUILT_IN_MIC, facts.audioRouteKind)
        assertEquals("Built-in Microphone", facts.audioRouteLabel)
        assertTrue(facts.isLocalMicrophone)
        assertFalse(facts.isBluetoothAudio)
    }

    @Test
    fun `a Bluetooth-audio session reads its profile and is flagged Bluetooth audio`() = runTest {
        db.sessionDao().insert(
            session(
                "BT-1",
                captureMode = "BLUETOOTH_RADIO",
                audioRouteKind = "BLUETOOTH_SCO",
                audioRouteLabel = "Handheld BT",
                bluetoothProfile = "HFP_MSBC",
                rigTransport = "BLUETOOTH_SPP",
            ),
        )
        val reader = RoomSessionRouteFactsReader(ApplicationProvider.getApplicationContext())

        val facts = reader.forSession("BT-1")

        assertTrue(facts.isBluetoothAudio)
        assertEquals(BluetoothAudioProfile.HFP_MSBC, facts.bluetoothProfile)
        assertEquals(RigTransportKind.BLUETOOTH_SPP, facts.rigTransport)
    }

    @Test
    fun `an unrecognised stored value never crashes, it reads as unknown rather than a fabricated enum`() = runTest {
        db.sessionDao().insert(session("GARBLED", captureMode = "SOMETHING_FUTURE_ADDED"))
        val reader = RoomSessionRouteFactsReader(ApplicationProvider.getApplicationContext())

        val facts = reader.forSession("GARBLED")

        assertNull(facts.captureMode)
    }

    @Test
    fun `the fake reader lets a caller script per-session facts without a real database`() = runTest {
        val fake = FakeSessionRouteFactsReader()
        fake.set("BT-1", SessionRouteFacts.NOT_TRACKED.copy(audioRouteKind = AudioRouteKind.BLUETOOTH_SCO))

        assertTrue(fake.forSession("BT-1").isBluetoothAudio)
        assertEquals(SessionRouteFacts.NOT_TRACKED, fake.forSession("unscripted"))
        assertEquals(SessionRouteFacts.NOT_TRACKED, fake.forSession(null))
    }
}
