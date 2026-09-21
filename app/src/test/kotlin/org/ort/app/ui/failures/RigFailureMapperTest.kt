package org.ort.app.ui.failures

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.app.ui.data.StagedActivation
import org.ort.data.entity.CaptureGapEntity
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.testing.Requirement

/**
 * `FailureMapperTest.kt` split — detekt's `LargeClass` finding, the same fix `RowsTest.kt`'s own
 * `NavRowTest.kt` split and `CorrectionPollingTest.kt`'s own `CorrectionPollingPassAndRevisionsTest.kt`
 * split used (see either file's own doc comment): register R-1120's own two new cases pushed
 * `FailureMapperTest.kt` past the threshold. This file owns F9 — `Rig-Lost.dc.html`'s stale-rig
 * banner ([FailurePresentation.Rig]) — a self-contained family already, not entangled with any
 * other signal this package maps. `signals(...)` is duplicated from `FailureMapperTest.kt` rather
 * than shared, the same choice that file's own split precedent already made.
 */
class RigFailureMapperTest {

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
        databaseOpenFailureReason: String? = null,
    ) = FailureSignals(
        captureState, inputStatus, levelStatus, thermalStatus, rigStatus, storageForecast,
        shedLevel, shedBacklog, newestGap, nowMillis, debugOverride,
        sessionStartedAtMillis, sessionTransmissionCount, storageForecastHistory, backlogHistory,
        stagedActivation, stagedActivationActiveLabel, databaseOpenFailureReason,
    )

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
}
