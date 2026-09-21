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
 * `FailureMapperTest.kt` past the threshold. This file owns F7/F8 — the thermal banner
 * ([FailurePresentation.Thermal]) and the backlog banner ([FailurePresentation.Backlog]), including
 * R-149's queue/storage timelines and R-254's Rate row — kept together, not split further, because
 * both banners are driven by the same shed-level mechanism and one priority rule
 * (`F7 thermal outranks backlog when both are true`) genuinely needs both in the same case.
 * `signals(...)` is duplicated from `FailureMapperTest.kt` rather than shared, the same choice that
 * file's own split precedent already made.
 */
class ThermalAndBacklogFailureMapperTest {

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
}
