package org.ort.capture.android

import android.media.MediaRecorder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

/**
 * R-1169, technical design §5.1: the decision the spec made years before any code did —
 * `MediaRecorder.AudioSource.UNPROCESSED` where the device reports it, `VOICE_RECOGNITION`
 * otherwise, and never `MIC` with effects. Pure, so both branches are provable without a device;
 * `AndroidAudioIoAudioSourceTest` proves the probe that feeds it is actually wired.
 */
class CaptureAudioSourceTest {

    @Test
    @Requirement("FR-CAP-1", "R-1169")
    fun `FR_CAP_1 a device reporting unprocessed support gets the unprocessed source`() {
        assertEquals(
            CaptureAudioSource.UNPROCESSED,
            CaptureAudioSource.preferredFor(unprocessedSupported = true),
        )
    }

    @Test
    @Requirement("FR-CAP-1", "R-1169")
    fun `FR_CAP_1 a device reporting no unprocessed support falls back to voice recognition`() {
        assertEquals(
            CaptureAudioSource.VOICE_RECOGNITION,
            CaptureAudioSource.preferredFor(unprocessedSupported = false),
        )
    }

    @Test
    @Requirement("FR-CAP-1", "R-1169")
    fun `FR_CAP_1 each source names the real platform constant, never MIC with effects`() {
        assertEquals(MediaRecorder.AudioSource.UNPROCESSED, CaptureAudioSource.UNPROCESSED.androidSource)
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, CaptureAudioSource.VOICE_RECOGNITION.androidSource)
        assertNull(
            CaptureAudioSource.fromAndroidSource(MediaRecorder.AudioSource.MIC),
            "MIC is explicitly not one of this project's capture sources (technical design §5.1)",
        )
    }

    @Test
    @Requirement("FR-CAP-1", "R-1169")
    fun `FR_CAP_1 a real platform constant maps back to the source that was obtained`() {
        assertEquals(
            CaptureAudioSource.UNPROCESSED,
            CaptureAudioSource.fromAndroidSource(MediaRecorder.AudioSource.UNPROCESSED),
        )
        assertEquals(
            CaptureAudioSource.VOICE_RECOGNITION,
            CaptureAudioSource.fromAndroidSource(MediaRecorder.AudioSource.VOICE_RECOGNITION),
        )
    }
}
