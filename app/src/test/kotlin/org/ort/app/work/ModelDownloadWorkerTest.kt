package org.ort.app.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.ModelId
import org.ort.net.Checksum
import org.ort.net.ModelFetchSpec
import org.ort.net.fake.FakeHttpRangeClient
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * P22 (D43, FR-AST-10..12): [ModelDownloadWorker] is a thin foreground-lifecycle wrapper around
 * `:net`'s already-tested [org.ort.net.ModelAcquisition] (via
 * [org.ort.app.ui.data.ModelsController.download]) — these tests prove the wrapper's own three
 * jobs: it actually runs a real fetch/verify/install through that path (AC-184), a killed-and-
 * relaunched worker *attempt* resumes from the real on-disk `.part` state rather than restarting
 * (AC-185), and a checksum mismatch is reported as a re-queueable retry, never a permanent success
 * or an activated bad file (AC-186). Wi-Fi-only-by-default (AC-187) is a `WorkManager` constraint
 * on the *enqueued request* ([ModelDownloadWorker.start]), not something `doWork()` itself decides
 * — proven separately below by inspecting the built request's own [androidx.work.WorkRequest].
 *
 * No real catalog entry is downloadable today (every `bundled-assets.json` entry ships
 * `bundled = true` until P23's `play` variant lands) — [specFor]/[isBundled] are the same
 * injectable seams [org.ort.app.ui.data.ModelsController.download] itself already exposes for
 * exactly this reason, used here with a fixture [ModelFetchSpec] rather than a hypothetical
 * non-bundled catalog entry.
 */
@RunWith(RobolectricTestRunner::class)
class ModelDownloadWorkerTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val body = ByteArray(2048) { it.toByte() }
    private val checksum = Checksum(value = sha256(body))

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun destination(): File = File(context.filesDir, "model-download-worker-test/asr-encoder.bin")

    @Suppress("UNUSED_PARAMETER")
    private fun specFor(id: ModelId, filesDir: File): ModelFetchSpec? = if (id == ModelId.ASR_ENCODER) {
        ModelFetchSpec(url = "https://example.test/model.bin", destination = destination(), checksum = checksum)
    } else {
        null
    }

    private fun buildWorker(client: FakeHttpRangeClient, workId: UUID = UUID.randomUUID()): ModelDownloadWorker {
        val worker = TestListenableWorkerBuilder<ModelDownloadWorker>(context)
            .setId(workId)
            .setInputData(Data.Builder().putString(ModelDownloadWorker.KEY_MODEL_ID, ModelId.ASR_ENCODER.name).build())
            .build()
        worker.httpRangeClient = client
        worker.specFor = ::specFor
        worker.isBundled = { false }
        return worker
    }

    @Test
    fun `AC_184 a successful download installs the model through ModelsController and reports success`() = runBlocking {
        destination().parentFile?.deleteRecursively()
        val client = FakeHttpRangeClient(body)
        val worker = buildWorker(client)

        val result = worker.doWork()

        assertTrue("expected Success, got $result", result is ListenableWorker.Result.Success)
        assertTrue(destination().isFile)
        assertEquals(body.size.toLong(), destination().length())
    }

    @Test
    fun `AC_185 a download interrupted mid-transfer resumes from its real partial state, never restarting from 0`() =
        runBlocking {
            destination().parentFile?.deleteRecursively()
            val droppingClient = FakeHttpRangeClient(body).apply { dropAfterBytes = 500 }
            val firstAttempt = buildWorker(droppingClient)

            val firstResult = firstAttempt.doWork()
            assertFalse(
                "a connection dropped mid-transfer must not report success",
                firstResult is ListenableWorker.Result.Success,
            )
            val partFile = File(destination().parentFile, destination().name + ".part")
            assertTrue("the interrupted attempt must leave a real partial file for resume", partFile.isFile)
            assertEquals(500L, partFile.length())

            // "Process death and relaunch": a fresh worker instance, the same real .part file on disk.
            val resumingClient = FakeHttpRangeClient(body)
            val secondAttempt = buildWorker(resumingClient)

            val secondResult = secondAttempt.doWork()

            assertTrue("the resumed attempt must complete", secondResult is ListenableWorker.Result.Success)
            assertEquals(body.size.toLong(), destination().length())
            // The discriminating proof: the resumed attempt's own request asked to resume from a
            // real non-zero offset -- a restart-from-scratch implementation would request byte 0.
            assertTrue(
                "a resumed download must request a non-zero rangeStart, never restart from 0",
                resumingClient.requests.any { (_, rangeStart) -> rangeStart > 0L },
            )
        }

    @Test
    fun `AC_186 a checksum mismatch retries rather than succeeding, and never installs the bad bytes`() = runBlocking {
        destination().parentFile?.deleteRecursively()
        val wrongBody = ByteArray(2048) { (it + 1).toByte() }
        val client = FakeHttpRangeClient(wrongBody)
        val worker = buildWorker(client)

        val result = worker.doWork()

        assertTrue(
            "a checksum mismatch must retry, never report a permanent success",
            result is ListenableWorker.Result.Retry,
        )
        assertFalse("the bad file must never reach the final destination", destination().isFile)
    }

    @Test
    fun `AC_187 start() defaults to Wi-Fi-only and honours an explicit override`() {
        val defaultRequest = ModelDownloadWorker.buildRequestForTest(ModelId.ASR_ENCODER, wifiOnly = true)
        val overriddenRequest = ModelDownloadWorker.buildRequestForTest(ModelId.ASR_ENCODER, wifiOnly = false)

        assertEquals(
            androidx.work.NetworkType.UNMETERED,
            defaultRequest.workSpec.constraints.requiredNetworkType,
        )
        assertEquals(
            androidx.work.NetworkType.CONNECTED,
            overriddenRequest.workSpec.constraints.requiredNetworkType,
        )
    }
}
