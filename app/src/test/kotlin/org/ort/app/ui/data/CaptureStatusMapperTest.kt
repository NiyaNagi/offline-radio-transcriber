package org.ort.app.ui.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.core.capture.AudioRouteKind
import org.ort.core.capture.CaptureMode
import org.ort.core.capture.RigTransportKind
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.pipeline.capture.VadAvailability
import org.ort.testing.Requirement

/**
 * `Capture-Status.dc.html`'s facts, pure (ui-conformance-plan WP4, R-031/R-032/R-034/R-035/R-038,
 * R-112/R-113).
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
        input: InputStatus.State = InputStatus.State.None,
        level: LevelStatus.State = LevelStatus.State.NotMeasured,
        nowMillis: Long = 100_000L,
        batteryExemptionReportsIgnoring: Boolean = true,
        gapCount: Int = 0,
        routeFacts: SessionRouteFacts = SessionRouteFacts.NOT_TRACKED,
    ) = CaptureStatusMapper.from(
        captureState = captureState,
        shedLevel = shedLevel,
        backlog = backlog,
        thermal = thermal,
        rig = rig,
        storage = storage,
        asr = asr,
        vad = vad,
        input = input,
        level = level,
        nowMillis = nowMillis,
        sinceLabel = "23:32",
        elapsedLabel = "6:42:18",
        heartbeatSecondsAgo = 4,
        isAlive = true,
        transmissionCount = 412,
        rejectedCount = 6,
        failedCount = 0,
        gapCount = gapCount,
        batteryPercent = 71,
        batteryCharging = true,
        batteryExemptionReportsIgnoring = batteryExemptionReportsIgnoring,
        routeFacts = routeFacts,
    )

    private fun descriptor(label: String = "USB Audio Device") =
        AudioDeviceDescriptor(id = "usb-1", kind = AudioDeviceKind.USB_DEVICE, label = label)

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
    @Requirement("R-301")
    fun `R_301 the overs row reads 1 gap, singular, never 1 gaps`() {
        val view = state(gapCount = 1)
        assertEquals("6 rejected · 0 failed · 1 gap", view.overs.subLine)
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
    @Requirement("R-263")
    fun `R_263 the no-rig sub-line reads in operator language, never a bare spec id`() {
        val view = state(rig = RigStatus.State.Absent)
        assertEquals("no radio support in this build yet", view.radio.subLine)
        assertFalse(view.radio.subLine!!.contains("FR-"))
    }

    @Test
    fun `R_113 input reads Not measured before any device has been opened this session`() {
        val view = state(input = InputStatus.State.None)
        assertEquals("Not measured", view.input.value)
    }

    @Test
    @Requirement("FR-CAP-2a")
    fun `R_113 an opened, verified input shows the device native rate resampler id and verified`() {
        val view = state(
            input = InputStatus.State.Opened(
                descriptor = descriptor("USB Audio Device"),
                nativeRateHz = 48_000,
                resamplerId = "a41c",
                routeVerified = true,
                routedDeviceMatches = true,
                openedAtMillis = 0L,
            ),
        )
        assertEquals("USB Audio Device", view.input.value)
        assertTrue(view.input.subLine!!.contains("48 kHz"))
        assertTrue(view.input.subLine!!.contains("a41c"))
        assertEquals("verified", view.input.trailingText)
        assertEquals(CaptureStateTone.NOMINAL, view.input.trailingDot)
    }

    @Test
    fun `R_113 an opened but not yet verified input never claims verified early`() {
        val view = state(
            input = InputStatus.State.Opened(
                descriptor = descriptor(),
                nativeRateHz = 48_000,
                resamplerId = "a41c",
                routeVerified = false,
                routedDeviceMatches = false,
                openedAtMillis = 0L,
            ),
        )
        assertFalse(view.input.trailingText == "verified")
    }

    @Test
    fun `R_113 a mismatched route reads mismatch halted, never silently substituted`() {
        val view = state(
            input = InputStatus.State.Mismatch(
                expected = descriptor("USB Audio Device"),
                actual = descriptor("Built-in Microphone"),
            ),
        )
        assertEquals("mismatch — halted", view.input.trailingText)
        assertEquals(CaptureStateTone.HALTED, view.input.trailingDot)
        assertTrue(view.input.subLine!!.contains("Built-in Microphone"))
    }

    @Test
    fun `R_113 a lost input states how long ago it was lost, from the real clock given`() {
        val opened = InputStatus.State.Opened(
            descriptor = descriptor(),
            nativeRateHz = 48_000,
            resamplerId = "a41c",
            routeVerified = true,
            routedDeviceMatches = true,
            openedAtMillis = 0L,
        )
        val view = state(
            input = InputStatus.State.Lost(lastKnown = opened, sinceMillis = 10_000L),
            nowMillis = 25_000L,
        )
        assertEquals("lost 15s ago", view.input.trailingText)
        assertEquals(CaptureStateTone.DEGRADED, view.input.trailingDot)
    }

    @Test
    fun `R_112 level reads Not measured before any frame has been measured this session`() {
        val view = state(level = LevelStatus.State.NotMeasured)
        assertEquals("Not measured", view.level.value)
    }

    @Test
    fun `R_112 a measured level shows peak and RMS dBFS`() {
        val view = state(
            level = LevelStatus.State.Measured(
                peakDbfs = -14f,
                rmsDbfs = -20f,
                noiseFloorDbfs = -58f,
                clipped = false,
                clipCountLastSecond = 0,
                sampleRateHz = 16_000,
                updatedAtMillis = 0L,
            ),
        )
        assertEquals("-14 dBFS peaks", view.level.value)
        assertTrue(view.level.subLine!!.contains("-20"))
        assertTrue(view.level.subLine!!.contains("-58"))
        assertNull(view.level.trailingDot)
    }

    @Test
    fun `R_112 clipping is called out in amber, not folded silently into the peak`() {
        val view = state(
            level = LevelStatus.State.Measured(
                peakDbfs = 0f,
                rmsDbfs = -4f,
                noiseFloorDbfs = -58f,
                clipped = true,
                clipCountLastSecond = 3,
                sampleRateHz = 16_000,
                updatedAtMillis = 0L,
            ),
        )
        assertEquals("clipping", view.level.trailingText)
        assertEquals(CaptureStateTone.DEGRADED, view.level.trailingDot)
    }

    @Test
    fun `R_112 a noise floor not yet tracked reads honestly, never a fabricated figure`() {
        val view = state(
            level = LevelStatus.State.Measured(
                peakDbfs = -14f,
                rmsDbfs = -20f,
                noiseFloorDbfs = null,
                clipped = false,
                clipCountLastSecond = 0,
                sampleRateHz = 16_000,
                updatedAtMillis = 0L,
            ),
        )
        assertTrue(view.level.subLine!!.contains("not yet tracked"))
    }

    @Test
    fun `a Stop action is offered only while capturing`() {
        assertEquals("Stop", state(captureState = CaptureState.State.Capturing).haltActionLabel)
        assertNull(state(captureState = CaptureState.State.Idle).haltActionLabel)
    }

    // -----------------------------------------------------------------------------------------
    // E2-G01 (N04): the Input sub-line names the capture mode and audio route; the Radio
    // sub-line names the rig transport.
    // -----------------------------------------------------------------------------------------

    private fun opened(label: String = "USB Audio Device") = InputStatus.State.Opened(
        descriptor = descriptor(label),
        nativeRateHz = 48_000,
        resamplerId = "a41c",
        routeVerified = true,
        routedDeviceMatches = true,
        openedAtMillis = 0L,
    )

    @Test
    @Requirement("E2-G01", "FR-CAP-13")
    fun `E2_G01 the Input sub-line names the mode and audio-by-cable route before the rate and resampler`() {
        val view = state(
            input = opened(),
            routeFacts = SessionRouteFacts(
                captureMode = CaptureMode.USB_RADIO,
                audioRouteKind = AudioRouteKind.USB,
                audioRouteLabel = "USB Audio Device",
                bluetoothProfile = null,
                rigTransport = RigTransportKind.USB_SERIAL,
            ),
        )
        assertEquals(
            "USB-connected radio · audio by cable · 48 kHz → 16 kHz · resampler a41c",
            view.input.subLine,
        )
    }

    @Test
    @Requirement("E2-G01", "FR-CAP-10")
    fun `E2_G01 the Input sub-line names room audio for a local-microphone session`() {
        val view = state(
            input = opened("Built-in Microphone"),
            routeFacts = SessionRouteFacts(
                captureMode = CaptureMode.LOCAL_MICROPHONE,
                audioRouteKind = AudioRouteKind.BUILT_IN_MIC,
                audioRouteLabel = "Built-in Microphone",
                bluetoothProfile = null,
                rigTransport = null,
            ),
        )
        assertTrue(view.input.subLine!!.contains("room audio"))
        assertTrue(view.input.subLine!!.contains("Local microphone"))
    }

    @Test
    @Requirement("E2-G01", "FR-CAP-11")
    fun `E2_G01 the Input sub-line names Bluetooth audio for a Bluetooth-audio session`() {
        val view = state(
            input = opened("Handheld BT"),
            routeFacts = SessionRouteFacts(
                captureMode = CaptureMode.BLUETOOTH_RADIO,
                audioRouteKind = AudioRouteKind.BLUETOOTH_SCO,
                audioRouteLabel = "Handheld BT",
                bluetoothProfile = null,
                rigTransport = RigTransportKind.BLUETOOTH_SPP,
            ),
        )
        assertTrue(view.input.subLine!!.contains("Bluetooth audio"))
    }

    @Test
    @Requirement("E2-G01")
    fun `E2_G01 with no route facts tracked the Input sub-line still carries the rate and resampler alone`() {
        val view = state(input = opened())
        assertTrue(view.input.subLine!!.contains("48 kHz"))
        assertTrue(view.input.subLine!!.contains("a41c"))
        assertFalse(view.input.subLine!!.contains("·  ·"))
    }

    @Test
    @Requirement("E2-G01", "FR-RIG-14")
    fun `E2_G01 the Radio sub-line names the transport before the band states`() {
        val connected = RigStatus.State.Connected(
            "TH-D75A",
            listOf(
                RigStatus.BandState("A", 145_230_000L, null, squelchOpen = true),
                RigStatus.BandState("B", 146_960_000L, null, squelchOpen = false),
            ),
        )
        val view = state(
            rig = connected,
            routeFacts = SessionRouteFacts(
                captureMode = CaptureMode.BLUETOOTH_RADIO,
                audioRouteKind = AudioRouteKind.WIRED_HEADSET,
                audioRouteLabel = null,
                bluetoothProfile = null,
                rigTransport = RigTransportKind.BLUETOOTH_SPP,
            ),
        )
        assertEquals(
            "Bluetooth SPP · A 145.230 open · B 146.960 closed",
            view.radio.subLine,
        )
    }

    @Test
    @Requirement("E2-G01", "FR-RIG-14")
    fun `E2_G01 RigStatus own live transportKind (WPC2) wins over the session column fallback`() {
        val connected = RigStatus.State.Connected(
            "TH-D75A",
            listOf(RigStatus.BandState("A", 145_230_000L, null, squelchOpen = true)),
            transportKind = org.ort.rig.RigTransportKind.USB_SERIAL,
        )
        val view = state(
            rig = connected,
            // The session column says Bluetooth — the live RigStatus signal must win regardless.
            routeFacts = SessionRouteFacts(
                captureMode = CaptureMode.BLUETOOTH_RADIO,
                audioRouteKind = AudioRouteKind.WIRED_HEADSET,
                audioRouteLabel = null,
                bluetoothProfile = null,
                rigTransport = RigTransportKind.BLUETOOTH_SPP,
            ),
        )
        assertEquals("USB serial · A 145.230 open", view.radio.subLine)
    }

    @Test
    @Requirement("E2-G01")
    fun `E2_G01 with no transport tracked the Radio sub-line falls back to the band states alone`() {
        val connected = RigStatus.State.Connected(
            "TH-D75A",
            listOf(RigStatus.BandState("A", 145_230_000L, null, squelchOpen = true)),
        )
        val view = state(rig = connected)
        assertEquals("A 145.230 open", view.radio.subLine)
    }
}
