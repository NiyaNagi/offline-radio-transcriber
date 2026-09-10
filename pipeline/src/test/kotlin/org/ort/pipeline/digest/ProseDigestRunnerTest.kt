package org.ort.pipeline.digest

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The real `androidx.work.CoroutineWorker` adapter — [ProseDigestWorkRunnerTest] covers the
 * decision logic in full; this proves the Worker class itself is wired correctly against real
 * WorkManager test infrastructure (`androidx.work:work-testing`), and that a not-yet-installed
 * bundled model is an honest success, never a crash or an infinite retry (constitution I).
 */
@RunWith(RobolectricTestRunner::class)
class ProseDigestRunnerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun initWorkManager() {
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun `doWork reports success honestly when the bundled model is not yet installed`() = runTest {
        val worker = TestListenableWorkerBuilder<ProseDigestRunner>(context).build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `schedule enqueues the unique work chain`() {
        ProseDigestRunner.schedule(context)

        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(ProseDigestRunner.UNIQUE_WORK_NAME).get()

        assertEquals(1, infos.size)
        assertTrue(infos.single().state == WorkInfo.State.ENQUEUED)
    }

    @Test
    fun `a second schedule call is a no-op while the chain is already running`() {
        ProseDigestRunner.schedule(context)
        ProseDigestRunner.schedule(context)

        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(ProseDigestRunner.UNIQUE_WORK_NAME).get()

        assertEquals(1, infos.size)
    }

    @Test
    fun `cancel removes the scheduled work`() {
        ProseDigestRunner.schedule(context)
        ProseDigestRunner.cancel(context)

        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(ProseDigestRunner.UNIQUE_WORK_NAME).get()

        assertTrue(infos.all { it.state == WorkInfo.State.CANCELLED })
    }
}
