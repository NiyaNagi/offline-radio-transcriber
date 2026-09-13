package org.ort.app.ui.improve

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ort.pipeline.reprocess.ReprocessRunSnapshot

/**
 * register R-1067 round 3 (coordinator review, constitution I): [reattachToRunningWork]'s own
 * catch must be narrowed to the exact, named `WorkManager.getInstance` "not initialized" message —
 * real only in a test harness that composes [ImproveContent] without `WorkManagerTestInitHelper',
 * never on a real device (WorkManager auto-initializes) — and must never swallow any other
 * `IllegalStateException`, which would otherwise show Root as if nothing were running while a real
 * bug (in this code, or in [ImproveRunner.observeState] itself) is actually the cause.
 */
class ReattachToRunningWorkTest {

    private class ThrowingImproveRunner(private val exception: Throwable) : ImproveRunner {
        override fun run(transmissionIds: List<String>) = flowOf(ImproveRunProgress(0, 0))
        override suspend fun cancel() = Unit

        // Thrown synchronously from the call itself -- the real shape `ReprocessWorker
        // .observeSnapshot`'s own `WorkManager.getInstance(context)` call takes (eager, not
        // deferred to Flow collection).
        override fun observeState(): Flow<ReprocessRunSnapshot> = throw exception
    }

    @Test
    fun `R_1067_round3 the WorkManager-not-initialized message is caught and treated as NotRunning`() = runBlocking {
        val runner = ThrowingImproveRunner(
            IllegalStateException(
                "WorkManager is not initialized properly.  You have explicitly disabled " +
                    "WorkManagerInitializer in your manifest, have not manually called " +
                    "WorkManager#initialize at this point, and your Application does not " +
                    "implement Configuration.Provider.",
            ),
        )
        var reattached = false

        reattachToRunningWork(
            runner = runner,
            isRunningPage = false,
            isRootPage = true,
            onReattachRunning = { reattached = true },
            onJustFinished = {},
        )

        assertFalse("the named not-initialized case must never reattach or crash", reattached)
    }

    @Test(expected = IllegalStateException::class)
    fun `R_1067_round3 an unrelated IllegalStateException is never swallowed`(): Unit = runBlocking {
        val runner = ThrowingImproveRunner(IllegalStateException("some other real bug in observeState"))

        reattachToRunningWork(
            runner = runner,
            isRunningPage = false,
            isRootPage = true,
            onReattachRunning = {},
            onJustFinished = {},
        )
    }

    @Test
    fun `R_1067_round3 an unrelated IllegalStateException carries its own real message when it propagates`() {
        val runner = ThrowingImproveRunner(IllegalStateException("some other real bug in observeState"))

        val thrown = try {
            runBlocking {
                reattachToRunningWork(
                    runner = runner,
                    isRunningPage = false,
                    isRootPage = true,
                    onReattachRunning = {},
                    onJustFinished = {},
                )
            }
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertTrue("expected the real exception to propagate, not be swallowed", thrown != null)
        assertEquals("some other real bug in observeState", thrown?.message)
    }
}
