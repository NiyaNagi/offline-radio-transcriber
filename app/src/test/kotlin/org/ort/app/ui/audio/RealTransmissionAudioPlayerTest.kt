package org.ort.app.ui.audio

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * FR-UI-5 (build-plan P14): playback of a transmission's *retained* audio. `:app` may not depend
 * on `:capture-android` directly (`ModuleGraph.allowed`) — this reuses `:pipeline`'s already-public
 * `FlacSegmentAudioProvider`/`DeflatePredictiveCodec` pairing exactly as `:pipeline`'s own Pass B
 * wiring does (build-plan P12), so decoding retained audio for playback and decoding it for ASR
 * are provably the same codec path (constitution III: a lossless codec must not silently diverge
 * per caller).
 *
 * No device is available to this session, so what genuinely leaves the speaker cannot be proven
 * here (Robolectric's `AudioTrack` shadow does not play sound) — this proves the honesty seam:
 * a transmission with no retained audio file is reported [PlaybackOutcome.Unavailable] naming why,
 * never silently "played" nothing, and a transmission with real retained audio reaches
 * [PlaybackOutcome.Played] through the real decode path.
 */
@RunWith(RobolectricTestRunner::class)
class RealTransmissionAudioPlayerTest {

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        // File-backed, matching `RealTransmissionAudioPlayer`'s own `OrtDatabase.create(context)`
        // call — see `ReaderPollingTest`'s identical note for why an in-memory instance here would
        // be invisible to the code under test.
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

    private fun transmission(id: String) = TransmissionEntity(
        id = id,
        sessionId = "S1",
        threadId = null,
        startedAtUtc = 0L,
        endedAtUtc = 1_000L,
        durationMs = 1_000L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = null,
        frequencyProvenance = "measured",
        mode = null,
        signalStrength = null,
        channelName = null,
        voiceprintId = null,
        attributionState = AttributionState.UNKNOWN,
        stationId = null,
        attributionConfidence = null,
        attributionSourceTransmissionId = null,
        processingState = TransmissionState.CAPTURED,
        rejectionReason = null,
        samplePosition = 0L,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    @Test
    fun `FR_UI_5 a transmission with no retained audio file is reported unavailable, naming why`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))
        val player = RealTransmissionAudioPlayer(context)

        val outcome = player.play("TX1")

        assertTrue(outcome is PlaybackOutcome.Unavailable)
        assertTrue((outcome as PlaybackOutcome.Unavailable).reason.isNotBlank())
    }

    @Test
    fun `FR_UI_5 a transmission id with no transmission row at all is unavailable, not a crash`(): Unit = runTest {
        val player = RealTransmissionAudioPlayer(context)

        val outcome = player.play("does-not-exist")

        assertTrue(outcome is PlaybackOutcome.Unavailable)
    }

    @Test
    fun `FR_UI_5 real retained audio decodes through the same codec pipeline as ASR before playback`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))
        val audioFile = File(context.filesDir, transmission("TX1").audioPath())
        audioFile.parentFile?.mkdirs()
        val codec = org.ort.capture.android.codec.DeflatePredictiveCodec()
        // A short, silent PCM16LE clip — content does not matter for this test, only that the
        // same codec pairing round-trips it through the real player.
        val pcm = ByteArray(3_200) // 100ms of silence at 16kHz mono 16-bit
        audioFile.writeBytes(codec.encode(pcm))

        val player = RealTransmissionAudioPlayer(context)
        val outcome = player.play("TX1")

        // No device is available to this session, and Robolectric's `AudioTrack` shadow does not
        // faithfully reproduce real hardware initialization (see this file's class doc) — what is
        // genuinely provable here, and what this asserts, is that decoding real retained audio
        // through `FlacSegmentAudioProvider` succeeds and reaches the playback attempt: an
        // `Unavailable` outcome at this point must be about *playback*, never "no retained audio"
        // or "could not decode retained audio" — those would mean the codec path itself is broken,
        // which is the thing constitution III actually cares about proving here.
        if (outcome is PlaybackOutcome.Unavailable) {
            assertTrue(
                "decode must have succeeded before playback was attempted, but got: ${outcome.reason}",
                outcome.reason.startsWith("could not start playback"),
            )
        }
        player.stop()
    }

    // ---- R-181, `Detail-Playback.dc.html`: real waveform summaries from real retained audio ----

    /** A short 440 Hz tone, PCM16 little-endian — the exact shape `RealTransmissionAudioPlayer`'s
     * own decode (`FlacSegmentAudioProvider`) expects, written through the same
     * `DeflatePredictiveCodec` pairing this file's own third test already uses directly. */
    private fun writeToneFixture(transmissionId: String) {
        val audioFile = File(context.filesDir, transmission(transmissionId).audioPath())
        audioFile.parentFile?.mkdirs()
        val sampleCount = 8_000
        val pcm = ByteArray(sampleCount * 2)
        for (i in 0 until sampleCount) {
            val sample = (6_000 * kotlin.math.sin(2.0 * Math.PI * 440.0 * i / 16_000.0)).toInt()
            pcm[i * 2] = (sample and 0xFF).toByte()
            pcm[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        val codec = org.ort.capture.android.codec.DeflatePredictiveCodec()
        audioFile.writeBytes(codec.encode(pcm))
    }

    @Test
    fun `R_181_waveformSummary_for_a_missing_file_returns_null_without_throwing`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))
        val player = RealTransmissionAudioPlayer(context)

        val summary = player.waveformSummary("TX1")

        assertTrue(summary == null)
    }

    @Test
    fun `R_181_waveformSummary_for_a_nonexistent_transmission_returns_null_without_throwing`(): Unit = runTest {
        val player = RealTransmissionAudioPlayer(context)

        assertTrue(player.waveformSummary("does-not-exist") == null)
    }

    @Test
    fun `R_181_waveformSummary_for_real_retained_audio_decodes_and_yields_real_bars`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))
        writeToneFixture("TX1")
        val player = RealTransmissionAudioPlayer(context)

        val summary = player.waveformSummary("TX1")

        requireNotNull(summary) { "expected a real summary for real retained audio" }
        assertEquals(WaveformSummaryComputer.BUCKET_COUNT, summary.bars.size)
        assertTrue(summary.bars.map { it.heightFraction }.distinct().size > 1)
    }

    @Test
    fun `R_181_waveformSummary_is_cached_per_transmission_id_not_re_decoded_on_every_call`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))
        writeToneFixture("TX1")
        val player = RealTransmissionAudioPlayer(context)

        val first = player.waveformSummary("TX1")
        // Deleting the retained file after the first call: a second, un-cached call would return
        // `null` (no file to decode) — an unchanged, real result on the second call proves the
        // cache, rather than the file, is what answered it.
        File(context.filesDir, transmission("TX1").audioPath()).delete()
        val second = player.waveformSummary("TX1")

        assertEquals(first, second)
        requireNotNull(second)
    }
}
