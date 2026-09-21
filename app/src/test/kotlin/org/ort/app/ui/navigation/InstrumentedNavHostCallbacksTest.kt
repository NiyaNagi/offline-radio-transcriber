package org.ort.app.ui.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.app.analytics.UsageAction
import org.ort.app.ui.data.TimeWindow

/**
 * R-1100: [instrumentedNavHostCallbacks] is the one place this app reports a feature *action*
 * (not just a screen view) — proves every field of a wrapped [NavHostCallbacks] both reports the
 * right [UsageAction] against the screen the operator was actually on, and still runs the real
 * underlying callback with the real argument, exactly once, before returning. A plain JVM test,
 * no Robolectric and no Compose: this function is pure lambda-wrapping with no Android dependency.
 */
class InstrumentedNavHostCallbacksTest {

    private var recordedCalls = mutableListOf<String>()
    private var current = ReaderDestination.NOW

    private fun realCallbacks(): NavHostCallbacks = NavHostCallbacks(
        onOpenDrawer = { recordedCalls += "onOpenDrawer()" },
        onSearchDestination = { recordedCalls += "onSearchDestination()" },
        onCloseDrillIns = { recordedCalls += "onCloseDrillIns()" },
        onOpenCapture = { recordedCalls += "onOpenCapture()" },
        onOpenLog = { recordedCalls += "onOpenLog()" },
        onOpenTransmission = { id -> recordedCalls += "onOpenTransmission($id)" },
        onOpenStation = { id -> recordedCalls += "onOpenStation($id)" },
        onOpenFrequency = { hz -> recordedCalls += "onOpenFrequency($hz)" },
        onOpenThread = { id -> recordedCalls += "onOpenThread($id)" },
        onOpenStations = { recordedCalls += "onOpenStations()" },
        onOpenModels = { recordedCalls += "onOpenModels()" },
        onOpenSettingsStorage = { recordedCalls += "onOpenSettingsStorage()" },
        onOpenLevelMeter = { recordedCalls += "onOpenLevelMeter()" },
        onOpenOvers = { hz, window -> recordedCalls += "onOpenOvers($hz,$window)" },
        onReviewSession = { id -> recordedCalls += "onReviewSession($id)" },
        onOpenActivationThread = { id -> recordedCalls += "onOpenActivationThread($id)" },
        onOpenStationOvers = { id -> recordedCalls += "onOpenStationOvers($id)" },
        onOpenAttributedStation = { id -> recordedCalls += "onOpenAttributedStation($id)" },
        onOpenHour = { from, to -> recordedCalls += "onOpenHour($from,$to)" },
        onViewAffectedOvers = { txId, overIds -> recordedCalls += "onViewAffectedOvers($txId,$overIds)" },
        onOpenChangedOvers = { overIds -> recordedCalls += "onOpenChangedOvers($overIds)" },
        onOpenRecordingSession = { id -> recordedCalls += "onOpenRecordingSession($id)" },
        onOpenRecordingSessionLog = { sid, overIds -> recordedCalls += "onOpenRecordingSessionLog($sid,$overIds)" },
    )

    private fun instrumented(): NavHostCallbacks {
        val reported = mutableListOf<Pair<ReaderDestination, UsageAction>>()
        this.reportedActions = reported
        return instrumentedNavHostCallbacks(
            realCallbacks(),
            currentDestination = { current },
            report = { destination, action -> reported += destination to action },
        )
    }

    private var reportedActions: List<Pair<ReaderDestination, UsageAction>> = emptyList()

    @Test
    fun `R_1100_onOpenTransmission reports OPEN_TRANSMISSION on the current screen, then runs the real callback`() {
        current = ReaderDestination.LOG
        val callbacks = instrumented()

        callbacks.onOpenTransmission("tx-1")

        assertEquals(listOf(ReaderDestination.LOG to UsageAction.OPEN_TRANSMISSION), reportedActions)
        assertEquals(listOf("onOpenTransmission(tx-1)"), recordedCalls)
    }

    @Test
    fun `R_1100_the screen reported is read at call time, not at wrap time`() {
        current = ReaderDestination.NOW
        val callbacks = instrumented()
        current = ReaderDestination.STATIONS // the operator navigated before taking this action

        callbacks.onOpenStation("station-1")

        assertEquals(listOf(ReaderDestination.STATIONS to UsageAction.OPEN_STATION), reportedActions)
    }

    @Test
    fun `R_1100_every field of NavHostCallbacks is instrumented and still forwards its real arguments`() {
        current = ReaderDestination.NOW
        val callbacks = instrumented()

        callbacks.onOpenDrawer()
        callbacks.onSearchDestination()
        callbacks.onCloseDrillIns()
        callbacks.onOpenCapture()
        callbacks.onOpenLog()
        callbacks.onOpenTransmission("tx")
        callbacks.onOpenStation("st")
        callbacks.onOpenFrequency(14_250_000L)
        callbacks.onOpenThread("th")
        callbacks.onOpenStations()
        callbacks.onOpenModels()
        callbacks.onOpenSettingsStorage()
        callbacks.onOpenLevelMeter()
        callbacks.onOpenOvers(14_250_000L, TimeWindow(0L, 3_600_000L))
        callbacks.onReviewSession("session")
        callbacks.onOpenActivationThread("activation")
        callbacks.onOpenStationOvers("st-2")
        callbacks.onOpenAttributedStation("st-3")
        callbacks.onOpenHour(0L, 3_600_000L)
        callbacks.onViewAffectedOvers("tx-2", setOf("a", "b"))
        callbacks.onOpenChangedOvers(setOf("c"))
        callbacks.onOpenRecordingSession("rec")
        callbacks.onOpenRecordingSessionLog("rec-2", setOf("d"))

        val expectedActions = UsageAction.entries.toList()
        assertEquals(expectedActions, reportedActions.map { it.second })
        assertTrue(reportedActions.all { it.first == ReaderDestination.NOW })
        assertEquals(23, recordedCalls.size, "every real callback must still run exactly once")
    }
}
