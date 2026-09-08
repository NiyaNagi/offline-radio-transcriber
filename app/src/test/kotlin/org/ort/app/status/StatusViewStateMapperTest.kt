package org.ort.app.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.capture.android.heartbeat.UncleanEndReport
import org.ort.pipeline.CaptureStatus
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.VadAvailability
import org.ort.testing.Requirement

class StatusViewStateMapperTest {

    private fun status(
        isCapturing: Boolean = true,
        uncleanEnd: UncleanEndReport? = null,
        isAlive: Boolean = true,
        shedLevel: Int = 0,
        gapCount: Int = 0,
    ) = CaptureStatus(
        sessionId = "s1",
        isCapturing = isCapturing,
        elapsedMillis = 3_661_000,
        transmissionCount = 5,
        gapCount = gapCount,
        shedLevel = shedLevel,
        isAlive = isAlive,
        lastHeartbeatWallMillis = 0L,
        uncleanEndFromPreviousLaunch = uncleanEnd,
    )

    @Test
    @Requirement("FR-UI-7", "FR-PLT-1")
    fun `capture state elapsed count and gaps are all shown`() {
        val view = StatusViewStateMapper.from(status(gapCount = 2))
        assertEquals("Capturing", view.stateLabel)
        assertEquals("01:01:01", view.elapsedLabel)
        assertEquals(5, view.transmissionCount)
        assertEquals(2, view.gapCount)
    }

    @Test
    @Requirement("AC-5", "FR-SVC-5b")
    fun `AC_5 an unclean end from the previous launch produces a banner naming the last heartbeat`() {
        val view = StatusViewStateMapper.from(status(uncleanEnd = UncleanEndReport("prev", 0L)))
        assertTrue(view.uncleanEndBanner != null)
        assertTrue(view.uncleanEndBanner!!.contains("unexpectedly"))
    }

    @Test
    fun `no unclean end means no banner`() {
        assertNull(StatusViewStateMapper.from(status(uncleanEnd = null)).uncleanEndBanner)
    }

    @Test
    @Requirement("AC-65", "NFR-8")
    fun `AC_65 liveness label reflects heartbeat status only`() {
        val alive = StatusViewStateMapper.from(status(isAlive = true)).livenessLabel
        val dead = StatusViewStateMapper.from(status(isAlive = false)).livenessLabel
        assertEquals("Alive (heartbeat current)", alive)
        assertEquals("Not responding (heartbeat stale)", dead)
    }

    @Test
    @Requirement("AC-46", "FR-RUN-3")
    fun `every shed level has a distinct, non-colour label`() {
        val labels = (0..5).map { StatusViewStateMapper.from(status(shedLevel = it)).shedLevelLabel }
        assertEquals(labels.toSet().size, labels.size, "every level must render distinguishably")
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 unset ASR and VAD availability read as not started, never as available`() {
        val view = StatusViewStateMapper.from(status())
        assertTrue(view.asrStatusLabel.contains("not started"), view.asrStatusLabel)
        assertTrue(!view.asrStatusLabel.contains("available"), view.asrStatusLabel)
        assertTrue(view.vadStatusLabel.contains("not started"), view.vadStatusLabel)
        assertTrue(view.transcriptionUnavailableMessage != null)
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 unavailable ASR shows its reason`() {
        val view = StatusViewStateMapper.from(
            status(),
            asrState = AsrAvailability.State.Unavailable("no model at /data/models/asr"),
        )
        assertTrue(view.asrStatusLabel.contains("no model at /data/models/asr"), view.asrStatusLabel)
        assertEquals(
            "No transcription model installed — transcripts will not appear",
            view.transcriptionUnavailableMessage,
        )
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 unavailable VAD shows its fallback reason`() {
        val view = StatusViewStateMapper.from(
            status(),
            vadState = VadAvailability.State.StubWithReason("Silero model not installed"),
        )
        assertTrue(view.vadStatusLabel.contains("Silero model not installed"), view.vadStatusLabel)
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 an available ASR model clears the unavailable message`() {
        val view = StatusViewStateMapper.from(
            status(),
            asrState = AsrAvailability.State.Available("distil-small.en"),
        )
        assertTrue(view.asrStatusLabel.contains("distil-small.en"), view.asrStatusLabel)
        assertNull(view.transcriptionUnavailableMessage)
    }
}
