package org.ort.pipeline.alerts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.AttributionState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * Build-plan P31: [AlertEvaluationCoordinator] wiring — watch matching, the coalescing rule
 * (functional spec §7.19), and FR-ALR-4/AC-195's non-blocking guarantee.
 */
class AlertEvaluationCoordinatorTest {

    private fun input(resolvedCallsign: String? = "K7ABC", state: AttributionState = AttributionState.CONFIRMED) =
        AlertMatchInput(
            "TX1",
            state,
            stationId = if (state == AttributionState.CONFIRMED) resolvedCallsign else null,
            resolvedCallsign = resolvedCallsign,
            transcriptText = null,
            frequencyHz = null,
        )

    @Test
    fun `FR_ALR_1 a matching enabled watch dispatches, a non-matching one does not`() {
        val store = InMemoryAlertWatchStore(listOf(AlertWatch.Callsign(id = "w1", callsign = "K7ABC")))
        val dispatcher = FakeAlertNotificationDispatcher()
        val coordinator = AlertEvaluationCoordinator(store, dispatcher)

        coordinator.evaluate(input(resolvedCallsign = "K7ABC"))
        assertEquals(1, dispatcher.firings.size)

        coordinator.evaluate(input(resolvedCallsign = "W7XYZ"))
        assertEquals(1, dispatcher.firings.size, "a non-matching station must not add a second firing")
    }

    @Test
    fun `FR_ALR_6 a disabled watch never fires`() {
        val store = InMemoryAlertWatchStore(listOf(AlertWatch.Callsign(id = "w1", callsign = "K7ABC", enabled = false)))
        val dispatcher = FakeAlertNotificationDispatcher()
        val coordinator = AlertEvaluationCoordinator(store, dispatcher)

        coordinator.evaluate(input(resolvedCallsign = "K7ABC"))

        assertTrue(dispatcher.firings.isEmpty())
    }

    @Test
    fun `FR_ALR_6 the master switch suppresses every watch when turned off`() {
        val store = InMemoryAlertWatchStore(
            listOf(AlertWatch.Callsign(id = "w1", callsign = "K7ABC")),
            alertsEnabled = false,
        )
        val dispatcher = FakeAlertNotificationDispatcher()
        val coordinator = AlertEvaluationCoordinator(store, dispatcher)

        coordinator.evaluate(input(resolvedCallsign = "K7ABC"))

        assertTrue(dispatcher.firings.isEmpty())
    }

    @Test
    fun `coalescing rule -- repeated matches inside the window update one episode, not fifty`() {
        val store = InMemoryAlertWatchStore(listOf(AlertWatch.Callsign(id = "w1", callsign = "K7ABC")))
        val dispatcher = FakeAlertNotificationDispatcher()
        var now = 0L
        val coordinator = AlertEvaluationCoordinator(store, dispatcher, clockMillis = { now })

        repeat(50) {
            coordinator.evaluate(input(resolvedCallsign = "K7ABC"))
            now += 1_000L // one second apart -- well inside the 10-minute coalescing window
        }

        assertEquals(50, dispatcher.firings.size, "every match is still handed to the dispatcher")
        assertFalse(dispatcher.firings.first().isRepeat, "the first match of an episode is not a repeat")
        assertTrue(dispatcher.firings.drop(1).all { it.isRepeat }, "every match after the first must coalesce")
        assertEquals(50, dispatcher.firings.last().occurrenceCount, "the running count must reach 50")
    }

    @Test
    fun `coalescing rule -- a match after the window elapses starts a fresh, alerting episode`() {
        val store = InMemoryAlertWatchStore(listOf(AlertWatch.Callsign(id = "w1", callsign = "K7ABC")))
        val dispatcher = FakeAlertNotificationDispatcher()
        var now = 0L
        val coordinator = AlertEvaluationCoordinator(
            store,
            dispatcher,
            clockMillis = { now },
            coalesceWindowMillis = 10_000L,
        )

        coordinator.evaluate(input(resolvedCallsign = "K7ABC"))
        now += 20_000L // past the window
        coordinator.evaluate(input(resolvedCallsign = "K7ABC"))

        assertEquals(2, dispatcher.firings.size)
        assertFalse(dispatcher.firings[1].isRepeat, "a match after the window must alert again, not coalesce")
        assertEquals(1, dispatcher.firings[1].occurrenceCount, "a fresh episode restarts its own count at 1")
    }

    @Test
    fun `AC_195 fireAndForget returns without waiting on a stalled dispatcher`() {
        val store = InMemoryAlertWatchStore(listOf(AlertWatch.Callsign(id = "w1", callsign = "K7ABC")))
        val dispatchStarted = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        val stallingDispatcher = object : AlertNotificationDispatcher {
            override fun canDeliver() = true
            override fun dispatch(firing: AlertFiring): Boolean {
                dispatchStarted.countDown()
                releaseDispatch.await(5, TimeUnit.SECONDS)
                return true
            }
        }
        val coordinator = AlertEvaluationCoordinator(store, stallingDispatcher)

        val elapsed = measureTimeMillis { coordinator.fireAndForget(input(resolvedCallsign = "K7ABC")) }

        assertTrue(elapsed < 200, "fireAndForget must return immediately, took ${elapsed}ms")
        // Prove the launched evaluation genuinely started (not just that the call site is fast
        // because nothing was scheduled at all) before releasing it, so the test itself does not
        // leak a hung thread past its own lifetime.
        assertTrue(dispatchStarted.await(2, TimeUnit.SECONDS), "the async evaluation never ran")
        releaseDispatch.countDown()
    }

    @Test
    fun `a throwing watch store never propagates out of fireAndForget`() {
        val throwingStore = object : AlertWatchStore {
            override var alertsEnabled: Boolean = true
            override fun list(): List<AlertWatch> = error("boom")
            override fun add(watch: AlertWatch) = Unit
            override fun update(watch: AlertWatch) = Unit
            override fun remove(id: String) = Unit
        }
        // Dispatchers.Unconfined: runs the launched coroutine inline (evaluate() never suspends),
        // so this test observes the outcome deterministically instead of racing a background
        // thread -- the coalescing/AC_195 tests above cover the real, threaded fire-and-forget
        // shape; this one isolates just "does the exception escape".
        val coordinator = AlertEvaluationCoordinator(
            throwingStore,
            FakeAlertNotificationDispatcher(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        // No exception must reach this line -- fireAndForget's own runCatching (inside the launched
        // coroutine) is what this asserts.
        coordinator.fireAndForget(input())
    }
}
