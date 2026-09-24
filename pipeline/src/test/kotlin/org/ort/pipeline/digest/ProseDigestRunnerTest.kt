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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.pipeline.diagnostics.DiagnosticsLog
import org.ort.pipeline.diagnostics.ModelAssetId
import org.ort.testing.TestClock
import org.robolectric.RobolectricTestRunner
import java.io.File

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

    @After
    fun tearDownDiagnosticsLog() {
        DiagnosticsLog.shutdown()
    }

    private fun capturePipelineLog(): List<String> {
        val file = File(File(context.filesDir, "diagnostics-logs"), DiagnosticsLog.Category.PIPELINE.fileName)
        return if (file.isFile) file.readLines() else emptyList()
    }

    @Test
    fun `doWork reports success honestly when the bundled model is not yet installed`() = runTest {
        val worker = TestListenableWorkerBuilder<ProseDigestRunner>(context).build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    /**
     * Register R-1052 (halt): the same "a Kotlin catch cannot stop a native abort" shape as
     * `RealVadProvider`/`RealAsrEngineProvider` exists here too — `MediaPipeLlmEngine.load()`
     * wraps `LlmInference.createFromOptions` in `catch (e: Exception)`, which does not catch an
     * `Error` (a `LinkageError`/`UnsatisfiedLinkError` under Robolectric, or a native abort on a
     * real device) at all. [ProseDigestRunner] must never even construct that engine for a file
     * that fails [org.ort.core.assets.ModelFileVerifier.verify] — proven here by a stub file with
     * no verified-install record reaching `Result.failure()` rather than attempting to load.
     */
    @Test
    fun `R_1052 doWork refuses an unverified installed model rather than loading it`() = runTest {
        val modelFile = LlmModelLocator.locate(context.filesDir)
            ?: run {
                val dir = LlmModelLocator.modelsDir(context.filesDir)
                dir.mkdirs()
                java.io.File(dir, "${LlmModelLocator.MODEL_ID}.task")
            }
        modelFile.writeBytes(ByteArray(64)) // R-1052's exact shape: a debug stub, no markers at all
        val worker = TestListenableWorkerBuilder<ProseDigestRunner>(context).build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    /** Register R-1058 (spec): the same verification failure above must also reach DiagnosticsLog,
     * naming the closed-vocabulary LLM asset id and failure kind -- never just the in-memory
     * `Result.failure()`. */
    @Test
    fun `R_1058 an unverified LLM model logs a model_verification_failed event naming LLM`() = runTest {
        DiagnosticsLog.configure(context.filesDir, TestClock())
        val modelFile = LlmModelLocator.locate(context.filesDir)
            ?: run {
                val dir = LlmModelLocator.modelsDir(context.filesDir)
                dir.mkdirs()
                File(dir, "${LlmModelLocator.MODEL_ID}.task")
            }
        modelFile.writeBytes(ByteArray(64))
        val worker = TestListenableWorkerBuilder<ProseDigestRunner>(context).build()

        worker.doWork()
        // Register R-1190: `runBlocking`, like the other fifteen DiagnosticsLog.flush() sites.
        // Not a live race before this change -- the await is a plain suspension bounded by
        // `runTest`'s own 60 s *wall-clock* timeout, not by virtual time -- but it is the one site
        // of sixteen that broke the convention, and a single added `withTimeout` inside this body
        // would have made it R-1180 exactly.
        runBlocking { DiagnosticsLog.flush() }

        val written = capturePipelineLog()
        assertEquals("expected exactly one event, got: $written", 1, written.size)
        assertTrue(written[0].contains("assetId=${ModelAssetId.LLM.name}"))
        assertTrue(written[0].contains("kind=MISSING_RECORD"))
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
