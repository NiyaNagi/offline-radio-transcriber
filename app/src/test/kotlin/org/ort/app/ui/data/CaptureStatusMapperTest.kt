package org.ort.app.ui.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.pipeline.capture.VadAvailability
import org.ort.testing.Requirement

/**
 * `Capture-Status.dc.html`'s facts, pure (ui-conformance-plan WP4, R-031/R-032/R-034/R-035/R-038).
 */
class CaptureStatusMapperTest {

    @Suppress("LongParameterList")
    private fun state(
        captureState: CaptureState.State = CaptureState.State.Capturing,
        shedLevel: Int? = 0,
        backlog: Int? = 0,
        thermal: ThermalStatus.State = ThermalStatus.State.Nominal(0, null),
        rig: RigStatus.State = RigStatus.State.Absent,
        storage: StorageForecast.State = StorageForecast.State.NotYetMeasured(0L, 0L),
        asr: AsrAvailability.State = AsrAvailability.State.NotYetChecked,
        vad: VadAvailability.State = VadAvailability.State.Stub,
        batteryExemptionReportsIgnoring: Boolean = true,
    ) = CaptureStatusMapper.from(
        captureState = captureState,
        shedLevel = shedLevel,
        backlog = backlog,
        thermal = thermal,
        rig = rig,
        storage = storage,
        asr = asr,
        vad = vad,
        sinceLabel = "23:32",
        elapsedLabel = "6:42:18",
        heartbeatSecondsAgo = 4,
        isAlive = true,
        transmissionCount = 412,
        rejectedCount = 6,
        failedCount = 0,
        gapCount = 0,
        batteryPercent = 71,
        batteryCharging = true,
        batteryExemptionReportsIgnoring = batteryExemptionReportsIgnoring,
    )

    @Test
    @Requirement("FR-UI-7")
    fun `R_032 title reads Capturing while nominal`() {
        val view = state()
        assertEquals("Capturing", view.stateLabel)
        assertEquals(CaptureStateTone.NOMINAL, view.stateTone)
    }

    @Test
    fun `R_032 title reads Capturing warm when thermal is not nominal`() {
        val view = state(thermal = ThermalStatus.State.Warm(2, 0.5))
        assertEquals("Capturing, warm", view.stateLabel)
        assertEquals(CaptureStateTone.DEGRADED, view.stateTone)
    }

    @Test
    fun `R_032 a failed capture state reads Halted`() {
        val view = state(captureState = CaptureState.State.Failed("no route"))
        assertEquals("Halted", view.stateLabel)
        assertEquals(CaptureStateTone.HALTED, view.stateTone)
    }

    @Test
    fun `R_032 idle reads Not capturing`() {
        val view = state(captureState = CaptureState.State.Idle)
        assertEquals("Not capturing", view.stateLabel)
        assertEquals(CaptureStateTone.IDLE, view.stateTone)
    }

    @Test
    fun `R_032 since elapsed and heartbeat facts are all present`() {
        val view = state()
        assertTrue(view.sinceElapsedLabel.contains("23:32"))
        assertTrue(view.sinceElapsedLabel.contains("6:42:18"))
        assertTrue(view.sinceElapsedLabel.contains("4s ago"))
    }

    @Test
    fun `R_032 overs row states rejected failed and gap counts, never just the total`() {
        val view = state()
        assertEquals("412 captured", view.overs.value)
        assertEquals("6 rejected · 0 failed · 0 gaps", view.overs.subLine)
    }

    @Test
    @Requirement("FR-RUN-5")
    fun `R_032 backlog is not measured before any shed reading is published, never a fabricated zero`() {
        val view = state(shedLevel = null, backlog = null)
        assertEquals("Not measured", view.backlog.value)
    }

    @Test
    fun `R_038 tier is derived from the shed level by the exact RealCaptureService formula`() {
        assertEquals("3 of 3", state(shedLevel = 0).tier.value)
        assertEquals("1 of 3", state(shedLevel = 2).tier.value)
        assertEquals("Not measured", state(shedLevel = null).tier.value)
    }

    @Test
    @Requirement("FR-UI-7")
    fun `R_034 the tier sub-line states real ASR VAD facts without a doubled prefix or a fabricated Pass C claim`() {
        val view = state(
            asr = AsrAvailability.State.Available("whisper-small"),
            vad = VadAvailability.State.Real,
        )
        assertTrue(view.tier.subLine!!.contains("whisper-small"))
        assertTrue(view.tier.subLine!!.contains("Silero VAD"))
        assertFalse(view.tier.subLine!!.contains("Pass C"))
        assertFalse(view.tier.subLine!!.startsWith("ASR:"))
    }

    @Test
    fun `R_034 an unavailable ASR model reads as no model, pointing at Models, not a raw file path`() {
        val view = state(asr = AsrAvailability.State.Unavailable("no model at /data/models/asr/whisper.bin"))
        assertFalse(view.tier.subLine!!.contains("/data/models"))
        assertTrue(view.tier.subLine!!.contains("no model"))
    }

    @Test
    fun `R_032 thermal states the measured real-time factor honestly when present`() {
        val view = state(thermal = ThermalStatus.State.Nominal(0, 0.31))
        assertTrue(view.thermal.subLine!!.contains("0.31"))
    }

    @Test
    fun `R_032 thermal reports not yet measured, never a fabricated RTF`() {
        val view = state(thermal = ThermalStatus.State.Nominal(0, null))
        assertTrue(view.thermal.subLine!!.contains("not yet measured"))
    }

    @Test
    fun `R_032 the battery exemption flag is always labelled not trusted`() {
        val view = state(batteryExemptionReportsIgnoring = true)
        assertTrue(view.battery.subLine!!.contains("not trusted"))
        assertEquals("71% phone · charging", view.battery.value)
    }

    @Test
    fun `R_032 no rig configured reads honestly, not as a connection failure`() {
        val view = state(rig = RigStatus.State.Absent)
        assertEquals("No rig configured", view.radio.value)
    }

    @Test
    fun `input and level are honestly not measured on every build today`() {
        val view = state()
        assertEquals("Not measured", view.input.value)
        assertEquals("Not measured", view.level.value)
    }

    @Test
    fun `a Stop action is offered only while capturing`() {
        assertEquals("Stop", state(captureState = CaptureState.State.Capturing).haltActionLabel)
        assertNull(state(captureState = CaptureState.State.Idle).haltActionLabel)
    }
}
