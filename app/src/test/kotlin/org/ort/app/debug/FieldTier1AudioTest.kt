package org.ort.app.debug

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.failures.DebugFailureOverride
import org.ort.app.ui.setup.SharedPreferencesSetupStore
import org.ort.capture.android.codec.DeflatePredictiveCodec
import org.ort.data.OrtDatabase
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.pipeline.capture.VadAvailability
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * R-290 (halt): `field-tier1`'s retained-audio fixture, split out of `ScenariosTest.kt` (detekt's
 * `LargeClass` finding — the same split `NavRowTest.kt` already established for `RowsTest.kt`, for
 * the same reason: a self-contained cluster, not entangled with the rest of that file's own
 * coverage). See `Scenarios.kt`'s own `fieldTier1` kdoc for the full defect history: the real
 * `ReprocessRunner`/`RealImproveRunner` reaches this scenario's overs through
 * [org.ort.pipeline.passb.FlacSegmentAudioProvider], which `check`s that a real, decodable file
 * exists at each transmission's [org.ort.data.entity.TransmissionEntity.audioPath] and throws if
 * not — before this fix, `field-tier1` seeded only the database rows, so `Improve-Running`/
 * `Improve-Done` (R03/R04) crashed the app on the first item instead of ever completing.
 */
@RunWith(RobolectricTestRunner::class)
class FieldTier1AudioTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: OrtDatabase

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    @After
    fun resetProcessWideAvailability() {
        AsrAvailability.reset()
        VadAvailability.reset()
        CaptureState.idle(clearSession = true)
        ShedStatus.reset()
        ThermalStatus.reset()
        RigStatus.reset()
        StorageForecast.reset()
        LevelStatus.reset()
        InputStatus.reset()
        DebugFailureOverride.clear()
        context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    @Requirement("R-290")
    fun `R_290 field-tier1 seeds a real retained-audio file for every improvable over`() = runTest {
        val result = Scenarios.load(context, "field-tier1")
        val sessionId = requireNotNull(result.primarySessionId)
        val transmissions = db.transmissionDao().listBySession(sessionId)

        assertTrue("expected at least one over", transmissions.isNotEmpty())
        transmissions.forEach { tx ->
            val file = File(context.filesDir, tx.audioPath())
            assertTrue("expected retained audio at ${file.path}", file.isFile)
            assertTrue("expected a non-empty audio file at ${file.path}", file.length() > 0L)
        }
    }

    @Test
    @Requirement("R-290")
    fun `R_290 field-tier1's real audio decodes to the recorded duration with the real audio-provider codec`() =
        runTest {
            val result = Scenarios.load(context, "field-tier1")
            val tx = db.transmissionDao().listBySession(requireNotNull(result.primarySessionId)).first()

            val file = File(context.filesDir, tx.audioPath())
            val pcmBytes = DeflatePredictiveCodec().decode(file.readBytes())
            val decodedDurationMs = (pcmBytes.size / 2) / SAMPLES_PER_MS_FIXTURE

            assertTrue(
                "expected the decoded PCM duration (${decodedDurationMs}ms) to match the transmission's own " +
                    "durationMs (${tx.durationMs}ms)",
                kotlin.math.abs(decodedDurationMs - tx.durationMs) <= 1L,
            )
        }

    @Test
    @Requirement("R-290")
    fun `R_290 field-tier1's retained-audio files are cleared like every other scenario's own`() = runTest {
        val fieldTier1 = Scenarios.load(context, "field-tier1")
        val fieldTier1SessionId = requireNotNull(fieldTier1.primarySessionId)
        val firstTx = db.transmissionDao().listBySession(fieldTier1SessionId).first()
        val firstFile = File(context.filesDir, firstTx.audioPath())
        assertTrue(firstFile.isFile)

        Scenarios.load(context, "empty")

        assertFalse(
            "expected field-tier1's retained audio to be cleared by the next scenario load",
            firstFile.exists(),
        )
    }

    // Mirrors `ScenarioFixtures.writeAudioFixture`'s own private 16 kHz-mono sample rate (its own
    // kdoc: "flac/16k/mono") — needed here only to decode the fixture's real duration back out for
    // this file's own round-trip assertion, not duplicated production logic.
    private companion object {
        const val SAMPLES_PER_MS_FIXTURE: Long = 16L
    }
}
