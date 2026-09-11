package org.ort.app.ui.failures

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.app.ui.data.ModelId
import org.ort.app.ui.data.ModelsController
import org.ort.app.ui.data.StagedActivation
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.testing.Requirement

/**
 * WP11b, register R-100/R-101: [FailureMapper.map] is pure — no Android dependency, no Robolectric
 * needed — so every priority rule this package's brief asks for ("at most one failure to show")
 * is asserted directly against plain `:pipeline`/`:data` values.
 */
class FailureMapperTest {

    private val usbDevice = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Device")
    private val builtInMic = AudioDeviceDescriptor("mic-0", AudioDeviceKind.BUILT_IN_MIC, "Built-in microphone")

    private fun gap(startedAt: Long, endedAt: Long?, cause: CaptureGapCause) =
        CaptureGapEntity("gap1", "s1", startedAt, endedAt, cause, recoveredAutomatically = true)

    @Suppress("LongParameterList")
    private fun signals(
        captureState: CaptureState.State = CaptureState.State.Capturing,
        inputStatus: InputStatus.State = InputStatus.State.None,
        levelStatus: LevelStatus.State = LevelStatus.State.NotMeasured,
        thermalStatus: ThermalStatus.State = ThermalStatus.State.Nominal(0, null),
        rigStatus: RigStatus.State = RigStatus.State.Absent,
        storageForecast: StorageForecast.State = StorageForecast.State.NotYetMeasured(0L, 0L),
        shedLevel: Int = 0,
        shedBacklog: Int = 0,
        newestGap: CaptureGapEntity? = null,
        nowMillis: Long = 1_000_000L,
        debugOverride: FailurePresentation? = null,
        sessionStartedAtMillis: Long? = null,
        sessionTransmissionCount: Int = 0,
        storageForecastHistory: List<StorageForecastSample> = emptyList(),
        backlogHistory: List<BacklogSample> = emptyList(),
        stagedActivation: StagedActivation? = null,
        stagedActivationActiveLabel: String? = null,
    ) = FailureSignals(
        captureState, inputStatus, levelStatus, thermalStatus, rigStatus, storageForecast,
        shedLevel, shedBacklog, newestGap, nowMillis, debugOverride,
        sessionStartedAtMillis, sessionTransmissionCount, storageForecastHistory, backlogHistory,
        stagedActivation, stagedActivationActiveLabel,
    )

    @Test
    @Requirement("R-101")
    fun `R_101 an input mismatch is a takeover, F1, no matter what else is true`() {
        val presentation = FailureMapper.map(
            signals(
                inputStatus = InputStatus.State.Mismatch(usbDevice, builtInMic),
                shedLevel = 3, // would otherwise read as a thermal/backlog banner
            ),
        )
        assertTrue(presentation is FailurePresentation.Route)
        presentation as FailurePresentation.Route
        assertEquals("USB Audio Device", presentation.state.expectedLabel)
        assertEquals("Built-in microphone", presentation.state.actualLabel)
    }

    @Test
    @Requirement("R-126")
    fun `R_126 a route mismatch computes the session's elapsed time and overs kept`() {
        val presentation = FailureMapper.map(
            signals(
                inputStatus = InputStatus.State.Mismatch(usbDevice, builtInMic),
                nowMillis = 1_000_000L + (3 * 3_600_000L + 9 * 60_000L + 40_000L),
                sessionStartedAtMillis = 1_000_000L,
                sessionTransmissionCount = 188,
            ),
        )
        assertTrue(presentation is FailurePresentation.Route)
        presentation as FailurePresentation.Route
        assertEquals("3:09:40", presentation.state.elapsedLabel)
        assertEquals(188, presentation.state.oversKeptCount)
        assertTrue(presentation.state.sessionElapsedKnown)
    }

    @Test
    @Requirement("R-126")
    fun `R_126 a route mismatch with no session to measure against reports elapsed as unknown, never invented`() {
        val presentation = FailureMapper.map(
            signals(
                inputStatus = InputStatus.State.Mismatch(usbDevice, builtInMic),
                sessionStartedAtMillis = null,
            ),
        )
        assertTrue(presentation is FailurePresentation.Route)
        presentation as FailurePresentation.Route
        assertTrue(!presentation.state.sessionElapsedKnown)
    }

    @Test
    @Requirement("R-100")
    fun `R_100 storage at the floor with capture failed is the storage takeover, not a banner`() {
        val presentation = FailureMapper.map(
            signals(
                captureState = CaptureState.State.Failed("storage exhausted"),
                storageForecast = StorageForecast.State.AtFloor(50_000_000L, 900_000_000L),
            ),
        )
        assertTrue(presentation is FailurePresentation.StorageHalt)
    }

    @Test
    @Requirement("R-100")
    fun `R_100 storage at the floor without a Failed capture state is not shown as a takeover`() {
        // AtFloor alone (a stale read racing the real stop) must not claim capture halted.
        val presentation = FailureMapper.map(
            signals(storageForecast = StorageForecast.State.AtFloor(50_000_000L, 900_000_000L)),
        )
        assertTrue(presentation !is FailurePresentation.StorageHalt)
    }

    @Test
    @Requirement("R-100")
    fun `R_100 an input lost is the disconnect banner`() {
        val opened = InputStatus.State.Opened(usbDevice, 48_000, "id", true, true, 0L)
        val presentation = FailureMapper.map(
            signals(inputStatus = InputStatus.State.Lost(opened, sinceMillis = 900_000L)),
        )
        assertTrue(presentation is FailurePresentation.Disconnect)
        assertEquals("USB Audio Device", (presentation as FailurePresentation.Disconnect).state.deviceLabel)
    }

    // -----------------------------------------------------------------------------------------
    // A Bluetooth-audio drop is F23, never the generic F2 disconnect (checklist row E2-G05).
    // -----------------------------------------------------------------------------------------

    private val btDevice = AudioDeviceDescriptor("bt-1", AudioDeviceKind.BLUETOOTH, "Handheld BT")

    @Test
    @Requirement("FR-CAP-5")
    fun `FR_CAP_5 an input lost on a Bluetooth route is F23, not the generic F2 disconnect`() {
        val opened = InputStatus.State.Opened(btDevice, 16_000, "none", true, true, 0L)
        val presentation = FailureMapper.map(
            signals(inputStatus = InputStatus.State.Lost(opened, sinceMillis = 900_000L)),
        )
        assertTrue(presentation is FailurePresentation.BluetoothAudioDropped)
        val state = (presentation as FailurePresentation.BluetoothAudioDropped).state
        assertEquals("Handheld BT", state.deviceLabel)
    }

    @Test
    @Requirement("FR-CAP-5")
    fun `FR_CAP_5 F23 states whether the rig link is still up, independent of the audio drop`() {
        val opened = InputStatus.State.Opened(btDevice, 16_000, "none", true, true, 0L)
        val stillConnected = RigStatus.State.Connected("TH-D75A", emptyList())

        val presentationRigUp = FailureMapper.map(
            signals(
                inputStatus = InputStatus.State.Lost(opened, sinceMillis = 900_000L),
                rigStatus = stillConnected,
            ),
        )
        assertTrue((presentationRigUp as FailurePresentation.BluetoothAudioDropped).state.rigLinkStillUp)

        val presentationRigDown = FailureMapper.map(
            signals(
                inputStatus = InputStatus.State.Lost(opened, sinceMillis = 900_000L),
                rigStatus = RigStatus.State.Stale(stillConnected, sinceMillis = 900_000L),
            ),
        )
        assertTrue(!(presentationRigDown as FailurePresentation.BluetoothAudioDropped).state.rigLinkStillUp)
    }

    // WPC3 (schema v9): InputStatus.State.Lost's real ladder-position fields, threaded straight
    // through onto BluetoothAudioDroppedViewState — no session read, no re-derivation.
    @Test
    @Requirement("FR-CAP-5")
    fun `FR_CAP_5 F23 reports the real retry ladder position when the caller has one`() {
        val opened = InputStatus.State.Opened(btDevice, 16_000, "none", true, true, 0L)
        val presentation = FailureMapper.map(
            signals(
                inputStatus = InputStatus.State.Lost(
                    opened,
                    sinceMillis = 900_000L,
                    attempt = 3,
                    ofTotal = 8,
                    nextRetryInMillis = 20_000L,
                ),
            ),
        )
        val state = (presentation as FailurePresentation.BluetoothAudioDropped).state
        assertEquals(3, state.retryAttempt)
        assertEquals(8, state.retryTotal)
        assertEquals(20, state.nextRetrySeconds)
    }

    @Test
    @Requirement("FR-CAP-5")
    fun `FR_CAP_5 F23 reports no retry ladder position for a caller that predates WPC3, never fabricated`() {
        val opened = InputStatus.State.Opened(btDevice, 16_000, "none", true, true, 0L)
        val presentation = FailureMapper.map(
            signals(inputStatus = InputStatus.State.Lost(opened, sinceMillis = 900_000L)),
        )
        val state = (presentation as FailurePresentation.BluetoothAudioDropped).state
        assertEquals(null, state.retryAttempt)
        assertEquals(null, state.retryTotal)
        assertEquals(null, state.nextRetrySeconds)
    }

    @Test
    @Requirement("FR-CAP-5")
    fun `FR_CAP_5 a takeover still outranks F23, matching the same priority every other banner has`() {
        val presentation = FailureMapper.map(
            signals(inputStatus = InputStatus.State.Mismatch(usbDevice, btDevice)),
        )
        assertTrue(presentation is FailurePresentation.Route)
    }

    @Test
    @Requirement("R-112")
    fun `R_112 a quiet level below the threshold is the level banner`() {
        val presentation = FailureMapper.map(
            signals(
                levelStatus = LevelStatus.State.Measured(-34f, -40f, -58f, clipped = false, 0, 48_000, 900_000L),
            ),
        )
        assertTrue(presentation is FailurePresentation.Level)
        assertEquals(LevelProblem.QUIET, (presentation as FailurePresentation.Level).state.problem)
    }

    @Test
    @Requirement("R-112")
    fun `R_112 a level within the band is not shown as a failure`() {
        val presentation = FailureMapper.map(
            signals(
                levelStatus = LevelStatus.State.Measured(-14f, -18f, -58f, clipped = false, 0, 48_000, 900_000L),
            ),
        )
        assertTrue(presentation is FailurePresentation.None)
    }

    @Test
    @Requirement("R-112")
    fun `R_112 clipping outranks a merely-quiet reading`() {
        val presentation = FailureMapper.map(
            signals(
                levelStatus = LevelStatus.State.Measured(0f, -6f, -55f, clipped = true, 12, 48_000, 900_000L),
            ),
        )
        assertTrue(presentation is FailurePresentation.Level)
        assertEquals(LevelProblem.CLIPPING, (presentation as FailurePresentation.Level).state.problem)
    }

    @Test
    @Requirement("F-005")
    fun `F5 a recently-closed OS_STOPPED gap is the killed banner`() {
        val theGap = gap(940_000L, 950_000L, CaptureGapCause.OS_STOPPED)
        val presentation = FailureMapper.map(signals(newestGap = theGap, nowMillis = 960_000L))
        assertTrue(presentation is FailurePresentation.Killed)
    }

    @Test
    @Requirement("F-005")
    fun `F5 an old OS_STOPPED gap outside the recent window is not shown`() {
        val theGap = gap(0L, 1_000L, CaptureGapCause.OS_STOPPED)
        val presentation = FailureMapper.map(signals(newestGap = theGap, nowMillis = 999_999_999L))
        assertTrue(presentation is FailurePresentation.None)
    }

    @Test
    @Requirement("R-105")
    fun `R_105 one night left is the storage warning banner`() {
        val presentation = FailureMapper.map(
            signals(storageForecast = StorageForecast.State.OneNightLeft(2_000_000_000L, 900_000_000L, 0.8)),
        )
        assertTrue(presentation is FailurePresentation.StorageWarning)
        assertEquals("1", (presentation as FailurePresentation.StorageWarning).state.nightsLeftLabel)
    }

    @Test
    @Requirement("R-149")
    fun `R_149 the storage timeline carries only the stages FailureSignalsPolling actually observed, oldest first`() {
        val history = listOf(
            StorageForecastSample(StorageForecast.State.ThreeNightsLeft(6_000_000_000L, 0L, 2.4), atMillis = 900_000L),
            StorageForecastSample(StorageForecast.State.OneNightLeft(2_000_000_000L, 0L, 0.8), atMillis = 950_000L),
        )
        val presentation = FailureMapper.map(
            signals(
                storageForecast = StorageForecast.State.OneNightLeft(2_000_000_000L, 900_000_000L, 0.8),
                storageForecastHistory = history,
            ),
        )
        assertTrue(presentation is FailurePresentation.StorageWarning)
        val timeline = (presentation as FailurePresentation.StorageWarning).state.timeline
        assertEquals(3, timeline.size)
        assertTrue(timeline[0].label.endsWith("warned at 3 nights left"))
        assertTrue(timeline[1].label.endsWith("warned at 1 night left"))
        assertEquals("Not reached", timeline[2].label)
        assertTrue(timeline[0].reached)
        assertTrue(!timeline[2].reached)
    }

    @Test
    @Requirement("R-149")
    fun `R_149 with no observed history, only the unreached floor stage shows, never a fabricated one`() {
        val presentation = FailureMapper.map(
            signals(storageForecast = StorageForecast.State.OneNightLeft(2_000_000_000L, 900_000_000L, 0.8)),
        )
        val timeline = (presentation as FailurePresentation.StorageWarning).state.timeline
        assertEquals(1, timeline.size)
        assertTrue(!timeline[0].reached)
    }

    @Test
    @Requirement("R-149")
    fun `R_149 F8 backlog carries a growth-rate label computed from the real backlog history`() {
        val history = listOf(
            BacklogSample(count = 20, atMillis = 0L, transmissionCount = 200),
            BacklogSample(count = 24, atMillis = 60_000L, transmissionCount = 210),
        )
        val presentation = FailureMapper.map(signals(shedBacklog = 41, backlogHistory = history))
        assertTrue(presentation is FailurePresentation.Backlog)
        val state = (presentation as FailurePresentation.Backlog).state
        assertEquals("Band 10.0 overs/min · Pass B 6.0/min", state.growthRateLabel)
        assertEquals(2, state.queueHistory.size)
        assertEquals(1f, state.queueHistory.last())
    }

    @Test
    @Requirement("R-149")
    fun `R_149 F8 backlog with fewer than two samples never invents a growth rate`() {
        val presentation = FailureMapper.map(
            signals(shedBacklog = 41, backlogHistory = listOf(BacklogSample(count = 41, atMillis = 0L))),
        )
        val state = (presentation as FailurePresentation.Backlog).state
        assertEquals(null, state.growthRateLabel)
    }

    @Test
    @Requirement("R-254")
    fun `R_254 Rate row derives Band from real arrivals and Pass B algebraically, never a fabricated split`() {
        // Band = Δ(sessionTransmissionCount)/Δt — a real, directly-measured arrival rate. Pass B =
        // Band − net backlog growth — exact given two real measurements, not a guess.
        val history = listOf(
            BacklogSample(count = 20, atMillis = 0L, transmissionCount = 200),
            BacklogSample(count = 30, atMillis = 120_000L, transmissionCount = 220),
        )
        assertEquals("Band 10.0 overs/min · Pass B 5.0/min", FailureMapper.backlogRateLabel(history))
    }

    @Test
    @Requirement("R-254")
    fun `R_254 Rate row reads Not measured with fewer than two samples`() {
        val presentation = FailureMapper.map(
            signals(shedBacklog = 41, backlogHistory = listOf(BacklogSample(count = 41, atMillis = 0L))),
        )
        val state = (presentation as FailurePresentation.Backlog).state
        assertEquals(null, state.growthRateLabel)
    }

    @Test
    @Requirement("R-254")
    fun `R_254 the queue chart's axis labels are the real clock times of the oldest and newest sample`() {
        val history = listOf(
            BacklogSample(count = 20, atMillis = 1_000_000L, transmissionCount = 200),
            BacklogSample(count = 30, atMillis = 1_120_000L, transmissionCount = 220),
        )
        val presentation = FailureMapper.map(signals(shedBacklog = 30, backlogHistory = history))
        val state = (presentation as FailurePresentation.Backlog).state
        assertEquals(FailureMapper.clockLabel(1_000_000L), state.queueHistoryOldestLabel)
        assertEquals(FailureMapper.clockLabel(1_120_000L), state.queueHistoryNewestLabel)
    }

    @Test
    @Requirement("R-254")
    fun `R_254 the Rate row's sub-line names the real tier and RTF, never invented just for this row`() {
        val history = listOf(
            BacklogSample(count = 20, atMillis = 0L, transmissionCount = 200),
            BacklogSample(count = 30, atMillis = 60_000L, transmissionCount = 210),
        )
        val presentation = FailureMapper.map(
            signals(
                shedBacklog = 30,
                shedLevel = 1,
                thermalStatus = ThermalStatus.State.Nominal(osThermalStatus = 0, realTimeFactor = 0.62),
                backlogHistory = history,
            ),
        )
        val state = (presentation as FailurePresentation.Backlog).state
        assertEquals("tier 2, RTF 0.62 while the net runs", state.rateSubLabel)
    }

    @Test
    @Requirement("R-254")
    fun `R_254 the Rate row's sub-line is absent when the real-time factor has not been measured`() {
        val presentation = FailureMapper.map(signals(shedBacklog = 30))
        val state = (presentation as FailurePresentation.Backlog).state
        assertEquals(null, state.rateSubLabel)
    }

    @Test
    @Requirement("R-177")
    fun R_177_thermal_banner_time_is_the_transition_moment() {
        val transitionedAt = 500_000L
        val presentation = FailureMapper.map(
            signals(
                thermalStatus = ThermalStatus.State.Warm(2, 0.9, sinceMillis = transitionedAt),
                shedLevel = 1,
                nowMillis = 900_000L,
            ),
        )
        assertTrue(presentation is FailurePresentation.Thermal)
        presentation as FailurePresentation.Thermal
        assertEquals(FailureMapper.clockLabel(transitionedAt), presentation.state.sinceLabel)
    }

    @Test
    @Requirement("R-104")
    fun `F7 a warm thermal state is the thermal banner, computed tier from shed level`() {
        val presentation = FailureMapper.map(
            signals(thermalStatus = ThermalStatus.State.Warm(2, 0.9), shedLevel = 1),
        )
        assertTrue(presentation is FailurePresentation.Thermal)
        assertEquals(2, (presentation as FailurePresentation.Thermal).state.tier)
    }

    @Test
    @Requirement("F-008")
    fun `F8 a growing backlog is the backlog banner, when thermal is nominal`() {
        val presentation = FailureMapper.map(signals(shedBacklog = 41))
        assertTrue(presentation is FailurePresentation.Backlog)
        assertEquals(41, (presentation as FailurePresentation.Backlog).state.waitingCount)
    }

    @Test
    @Requirement("F-008")
    fun `F8 a small backlog under the threshold is not shown`() {
        val presentation = FailureMapper.map(signals(shedBacklog = 2))
        assertTrue(presentation is FailurePresentation.None)
    }

    @Test
    @Requirement("F-007")
    fun `F7 thermal outranks backlog when both are true, matching the thermal scenario`() {
        val presentation = FailureMapper.map(
            signals(thermalStatus = ThermalStatus.State.Warm(2, 0.9), shedLevel = 3, shedBacklog = 112),
        )
        assertTrue(presentation is FailurePresentation.Thermal)
    }

    @Test
    @Requirement("F-009")
    fun `F9 a stale rig is the rig banner`() {
        val connected = RigStatus.State.Connected("TH-D75A", emptyList())
        val presentation = FailureMapper.map(
            signals(rigStatus = RigStatus.State.Stale(connected, sinceMillis = 900_000L)),
        )
        assertTrue(presentation is FailurePresentation.Rig)
        assertEquals("TH-D75A", (presentation as FailurePresentation.Rig).state.deviceLabel)
    }

    // R-870 (register, polish): the shared `stripRigManufacturerPrefix` helper (R-845/R-916/R-920)
    // drops the descriptor's own leading manufacturer word everywhere else — F9's own banner title
    // was the one caller still rendering it verbatim.
    @Test
    @Requirement("R-870")
    fun `R_870 F9 drops the descriptor's own leading manufacturer word, matching every other screen`() {
        val connected = RigStatus.State.Connected("Kenwood TH-D75A", emptyList())
        val presentation = FailureMapper.map(
            signals(rigStatus = RigStatus.State.Stale(connected, sinceMillis = 900_000L)),
        )
        assertEquals("TH-D75A", (presentation as FailurePresentation.Rig).state.deviceLabel)
    }

    // checklist row E2-G06 (F9's transport naming).
    @Test
    @Requirement("FR-RIG-15")
    fun `FR_RIG_15 F9 names the Bluetooth transport when that is what dropped`() {
        val connected = RigStatus.State.Connected(
            "TH-D75A",
            emptyList(),
            transportKind = org.ort.rig.RigTransportKind.BLUETOOTH_SPP,
        )
        val presentation = FailureMapper.map(
            signals(rigStatus = RigStatus.State.Stale(connected, sinceMillis = 900_000L)),
        )
        assertEquals("the Bluetooth SPP transport", (presentation as FailurePresentation.Rig).state.transportLabel)
    }

    @Test
    @Requirement("FR-RIG-15")
    fun `FR_RIG_15 F9 names no transport for a caller that predates WPC2, never fabricated`() {
        val connected = RigStatus.State.Connected("TH-D75A", emptyList())
        val presentation = FailureMapper.map(
            signals(rigStatus = RigStatus.State.Stale(connected, sinceMillis = 900_000L)),
        )
        assertEquals(null, (presentation as FailurePresentation.Rig).state.transportLabel)
    }

    // WPC3 (schema v9): RigStatus.State.Stale's own real ladder-position fields, the identical
    // shape F23 reads off InputStatus.State.Lost.
    @Test
    @Requirement("FR-RIG-15")
    fun `FR_RIG_15 F9 reports the real retry ladder position when the caller has one`() {
        val connected = RigStatus.State.Connected("TH-D75A", emptyList())
        val presentation = FailureMapper.map(
            signals(
                rigStatus = RigStatus.State.Stale(
                    connected,
                    sinceMillis = 900_000L,
                    attempt = 2,
                    ofTotal = 5,
                    nextRetryInMillis = 12_000L,
                ),
            ),
        )
        val state = (presentation as FailurePresentation.Rig).state
        assertEquals(2, state.retryAttempt)
        assertEquals(5, state.retryTotal)
        assertEquals(12, state.nextRetrySeconds)
    }

    @Test
    @Requirement("FR-RIG-15")
    fun `FR_RIG_15 F9 reports no retry ladder position for a caller that predates WPC3, never fabricated`() {
        val connected = RigStatus.State.Connected("TH-D75A", emptyList())
        val presentation = FailureMapper.map(
            signals(rigStatus = RigStatus.State.Stale(connected, sinceMillis = 900_000L)),
        )
        val state = (presentation as FailurePresentation.Rig).state
        assertEquals(null, state.retryAttempt)
        assertEquals(null, state.retryTotal)
        assertEquals(null, state.nextRetrySeconds)
    }

    @Test
    @Requirement("F-015")
    fun `F15 a recently-closed CALL gap while capturing is the call banner`() {
        val theGap = gap(940_000L, 950_000L, CaptureGapCause.CALL)
        val presentation = FailureMapper.map(signals(newestGap = theGap, nowMillis = 960_000L))
        assertTrue(presentation is FailurePresentation.Call)
    }

    @Test
    @Requirement("F-015")
    fun `F15 a CALL gap is not shown when capture is not live`() {
        val theGap = gap(940_000L, 950_000L, CaptureGapCause.CALL)
        val presentation = FailureMapper.map(
            signals(captureState = CaptureState.State.Idle, newestGap = theGap, nowMillis = 960_000L),
        )
        assertTrue(presentation is FailurePresentation.None)
    }

    @Test
    @Requirement("R-100")
    fun `R_100 nothing wrong maps to None`() {
        assertTrue(FailureMapper.map(signals()) is FailurePresentation.None)
    }

    @Test
    @Requirement("R-100")
    fun `R_100 a debug override wins outright, over every real signal`() {
        val override = FailurePresentation.Clock(ClockViewState("PDT → PST", "8 h 30 m", "23:10", "06:40"))
        val presentation = FailureMapper.map(
            signals(inputStatus = InputStatus.State.Mismatch(usbDevice, builtInMic), debugOverride = override),
        )
        assertEquals(override, presentation)
    }

    /**
     * WP11b follow-up history: this test used to prove F21 (`Fail-Asset-Swap`) had *no* real signal
     * — [FailureMapper.map] mapped every real [FailureSignals] snapshot to `None`, never
     * `AssetSwap`, because the underlying asset activation guard (FR-AST-4) did not exist anywhere
     * in `:lexicon`/`:data`/`:app` (that investigation's own findings — [DebugFailureOverride]'s
     * class kdoc, `Scenarios.kt`'s `load` doc comment, [FailAssetSwapScreen]'s own kdoc,
     * [AssetSwapViewState]'s own file section header, `LexiconImportInstaller.installValidated`
     * activating unconditionally — are unchanged history, not restated here). **WP10's own
     * `ModelsController.stagedActivation` guard has since landed** (FR-AST-4, register R-448
     * follow-up), so this is now the real positive case that guard's own kdoc says this package's
     * mapper is expected to read: a real [StagedActivation] maps to a real
     * [FailurePresentation.AssetSwap], its `stagedLabel` carrying [StagedActivation]'s own asset
     * name, version and staged-at time, never invented ones — and the debug-override path (still
     * the only way the other five ids in that original investigation remain reachable) keeps
     * working exactly as before, asserted below for completeness.
     */
    @Test
    @Requirement("R-448")
    fun `R_448_asset_swap_signal a real staged activation maps to the real AssetSwap presentation`() {
        val staged = StagedActivation(
            assetId = ModelsController.CALLSIGN_LEXICON_ASSET_ID,
            version = "2026.09",
            stagedAtMillis = 500_000L,
            reason = "a session is live — activating a new callsign lexicon mid-session would change " +
                "which callsigns read as usual until this session ends (FR-AST-4)",
        )
        val presentation = FailureMapper.map(
            signals(stagedActivation = staged, stagedActivationActiveLabel = "2026.08 · 41,200 records active"),
        )
        assertTrue(presentation is FailurePresentation.AssetSwap, "expected AssetSwap, was $presentation")
        presentation as FailurePresentation.AssetSwap
        assertEquals("2026.08 · 41,200 records active", presentation.state.activeLabel)
        assertEquals(
            "callsign lexicon 2026.09 · staged ${FailureMapper.clockLabel(500_000L)}",
            presentation.state.stagedLabel,
        )
        assertEquals(2, presentation.state.options.size)
        assertEquals(staged.reason, presentation.state.options[0].subLine)
        assertEquals(0, presentation.state.selectedOption)
    }

    @Test
    @Requirement("R-448")
    fun `R_448_asset_swap_signal a staged model uses the model's own label, not the lexicon's`() {
        val staged = StagedActivation(
            assetId = ModelId.ASR_TOKENS.name,
            version = "a1b2c3d4",
            stagedAtMillis = 500_000L,
            reason = "a session is live — reprocessing previously failed overs with " +
                "${ModelId.ASR_TOKENS.label} mid-session would change what this session finds usual (FR-AST-4)",
        )
        val presentation = FailureMapper.map(signals(stagedActivation = staged, stagedActivationActiveLabel = null))
        assertTrue(presentation is FailurePresentation.AssetSwap, "expected AssetSwap, was $presentation")
        presentation as FailurePresentation.AssetSwap
        assertEquals("not measured", presentation.state.activeLabel)
        assertTrue(
            presentation.state.stagedLabel.startsWith(ModelId.ASR_TOKENS.label),
            "expected the model's own label, was ${presentation.state.stagedLabel}",
        )
    }

    @Test
    @Requirement("R-448")
    fun `R_100 a debug override still wins outright, over a real staged activation`() {
        val staged = StagedActivation(
            assetId = ModelsController.CALLSIGN_LEXICON_ASSET_ID,
            version = "2026.09",
            stagedAtMillis = 500_000L,
            reason = "a session is live",
        )
        val override = FailurePresentation.AssetSwap(
            AssetSwapViewState(
                activeLabel = "callsigns-2026.08 · 41,200 entries",
                stagedLabel = "callsigns-2026.09 · 41,600 entries",
                options = listOf(AssetSwapOption("Wait for the session to end", "the default · nothing else to do")),
                selectedOption = 0,
            ),
        )
        assertEquals(override, FailureMapper.map(signals(stagedActivation = staged, debugOverride = override)))
    }
}
