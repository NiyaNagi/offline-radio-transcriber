package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.core.PassId
import org.ort.core.SystemClock
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.WorkQueue
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.net.Checksum
import org.ort.net.ModelFetchSpec
import org.ort.net.fake.FakeHttpRangeClient
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Audit F-008 (FR-ASR-1, constitution V): before this change, `:net`'s `ModelAcquisition` had no
 * `:app` call site at all — no ASR or VAD model could ever reach the device. These tests drive
 * [ModelsController] — the "Models" screen's read/write path — purely against
 * [FakeHttpRangeClient], the same behavioural fake `:net`'s own test suite uses, so no test here
 * makes a real network call (constitution V).
 *
 * [ModelCatalog]'s own checksums are not yet pinned to real published bytes (see
 * `ModelsViewData.kt`'s top doc comment) — these tests supply their own `specFor` override with a
 * checksum they can actually satisfy, to prove the *mechanism* (fetch through the fake, verify,
 * install, report, requeue) end to end, exactly as [ModelsController.currentState]/`download`/
 * `sideload`'s own doc comments describe that seam existing for.
 */
/** R-267: a generous single-line budget for [ModelRowViewState.detail] — well under a full sentence,
 * let alone the multi-paragraph maintainer note this bug used to surface verbatim. */
private const val R_267_MAX_DETAIL_LENGTH = 60

@RunWith(RobolectricTestRunner::class)
class ModelsControllerTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: OrtDatabase
    private lateinit var modelsDir: File

    private val body = ByteArray(2048) { (it % 191).toByte() }
    private val goodChecksum = Checksum(value = sha256(body))

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        // File-backed, not in-memory: ModelsController.requeueFailed opens `OrtDatabase.create`
        // with its default storage internally, exactly like `ReaderPolling` — see that test's own
        // note on why a shared in-memory instance would not be observed by the code under test.
        db = OrtDatabase.create(context)
        modelsDir = Files.createTempDirectory("models-controller-test").toFile()
    }

    private fun specFor(checksum: Checksum): (ModelId, File) -> ModelFetchSpec = { id, _ ->
        ModelFetchSpec(
            url = "https://example.invalid/${id.name}",
            destination = File(modelsDir, "${id.name}.bin"),
            checksum = checksum,
        )
    }

    @Test
    @Requirement("FR-ASR-1")
    fun `FR_ASR_1 download drives ModelAcquisition through the fake client and the row shows installed`(): Unit =
        runTest {
            val spec = specFor(goodChecksum)
            val before = ModelsController.currentState(context, specFor = spec)
            assertEquals(ModelRowStatus.NOT_INSTALLED, before.rows.first { it.id == ModelId.VAD }.status)

            val result = ModelsController.download(
                context,
                ModelId.VAD,
                client = FakeHttpRangeClient(body),
                specFor = spec,
            )

            assertTrue("expected a Success, got $result", result is ModelActionResult.Success)
            val dest = spec(ModelId.VAD, modelsDir).destination
            assertTrue("the verified file must land at the destination", dest.isFile)
            assertEquals(body.toList(), dest.readBytes().toList())

            val after = ModelsController.currentState(context, specFor = spec)
            assertEquals(ModelRowStatus.INSTALLED, after.rows.first { it.id == ModelId.VAD }.status)
        }

    @Test
    @Requirement("FR-ASR-1")
    fun `FR_ASR_1 a checksum mismatch reports failure and leaves nothing at the destination`(): Unit = runTest {
        val corruptBody = body.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val spec = specFor(goodChecksum) // pinned to the *original* body's digest

        val result = ModelsController.download(
            context,
            ModelId.ASR_ENCODER,
            client = FakeHttpRangeClient(corruptBody),
            specFor = spec,
        )

        assertTrue("expected a Failure, got $result", result is ModelActionResult.Failure)
        val failure = result as ModelActionResult.Failure
        assertTrue(failure.reason.contains("checksum mismatch"))
        assertFalse(spec(ModelId.ASR_ENCODER, modelsDir).destination.exists())

        val state = ModelsController.currentState(context, specFor = spec)
        assertEquals(ModelRowStatus.NOT_INSTALLED, state.rows.first { it.id == ModelId.ASR_ENCODER }.status)
    }

    @Test
    @Requirement("FR-ASR-1")
    fun `FR_ASR_1 a successful install requeues previously failed work items and reports how many`(): Unit = runTest {
        db.sessionDao().insert(session("S-MODELS"))
        db.transmissionDao().insert(transmission("TX-MODELS-1", "S-MODELS"))
        val queue = WorkQueue(db, SystemClock, maxAttempts = 1)
        queue.enqueue("TX-MODELS-1", PassId.B_OFFLINE)
        val leased = queue.leaseBatch("run-models-1", limit = 10) { 60_000L }.single()
        queue.failPass(leased, "ASR unavailable: no ASR model installed") // exhausts maxAttempts = 1

        val result = ModelsController.download(
            context,
            ModelId.VAD,
            client = FakeHttpRangeClient(body),
            specFor = specFor(goodChecksum),
        )

        val success = result as ModelActionResult.Success
        assertEquals(1, success.requeuedCount)
        assertEquals(
            TransmissionState.PROCESSING,
            db.transmissionDao().getById("TX-MODELS-1")!!.processingState,
        )
    }

    @Test
    @Requirement("FR-ASR-1")
    fun `FR_ASR_1 the models screen renders not installed honestly when nothing is on disk`() {
        val state = ModelsController.currentState(context)

        assertEquals(ModelId.entries.size, state.rows.size)
        state.rows.forEach { row ->
            assertEquals(ModelRowStatus.NOT_INSTALLED, row.status)
        }
        assertNull(state.requeuedMessage)
    }

    @Test
    @Requirement("FR-AST-1")
    fun `FR_AST_1 download refuses with no network call at all for an unknown-checksum entry`(): Unit = runTest {
        // A client that throws if it is ever invoked — proves ModelAcquisition.fetch (and
        // therefore the network) is never reached for ASR_TOKENS, whose catalog checksum is
        // ChecksumState.UnknownSideloadOnly, using the REAL ModelCatalog::specFor default.
        val neverCalled = object : org.ort.net.HttpRangeClient {
            override fun get(url: String, rangeStart: Long): org.ort.net.HttpRangeResult =
                error("download() must never make a network call for an unknown-checksum entry")
        }

        val result = ModelsController.download(context, ModelId.ASR_TOKENS, client = neverCalled)

        assertTrue("expected a Failure, got $result", result is ModelActionResult.Failure)
        assertTrue(
            (result as ModelActionResult.Failure).reason.contains("no published checksum"),
        )
    }

    @Test
    @Requirement("FR-AST-1")
    fun `FR_AST_1 sideloading an unknown-checksum entry installs it unverified, never claiming verified`(): Unit =
        runTest {
            // Real ModelCatalog::specFor default (ASR_TOKENS has no known checksum) with a real
            // temp filesDir so ModelCatalog.entry's own destination function is exercised.
            val tempFilesDir = Files.createTempDirectory("models-controller-tofu-test").toFile()
            val fakeContext = object : android.content.ContextWrapper(context) {
                override fun getFilesDir(): File = tempFilesDir
            }
            val sourceFile = File(tempFilesDir, "user-picked-tokens.txt")
            sourceFile.writeBytes(body)

            val before = ModelsController.currentState(fakeContext)
            assertEquals(
                ModelRowStatus.NOT_INSTALLED,
                before.rows.first { it.id == ModelId.ASR_TOKENS }.status,
            )
            assertFalse(before.rows.first { it.id == ModelId.ASR_TOKENS }.checksumKnown)

            val result = ModelsController.sideload(fakeContext, ModelId.ASR_TOKENS, sourceFile)

            assertTrue("expected a Success, got $result", result is ModelActionResult.Success)
            val after = ModelsController.currentState(fakeContext)
            val row = after.rows.first { it.id == ModelId.ASR_TOKENS }
            assertEquals(ModelRowStatus.INSTALLED_UNVERIFIED, row.status)
            assertFalse(row.checksumKnown)
        }

    @Test
    @Requirement("R-267")
    fun `R_267 the tokens row's not-installed detail is short, never the maintainer's research trail`() = runTest {
        val tempFilesDir = Files.createTempDirectory("models-controller-r267-test").toFile()
        val fakeContext = object : android.content.ContextWrapper(context) {
            override fun getFilesDir(): File = tempFilesDir
        }

        val row = ModelsController.currentState(fakeContext).rows.first { it.id == ModelId.ASR_TOKENS }

        val detail = row.detail
        assertTrue("expected a detail string, got null", detail != null)
        checkNotNull(detail)
        // `Settings-Assets.dc.html`'s row shape (size · checksum prefix · tier, guide §9) has no
        // room for a paragraph — one line, no embedded newline, and short enough it cannot be one.
        assertFalse("detail must not wrap onto a second line: $detail", detail.contains('\n'))
        assertTrue(
            "expected a short operator fact (<= $R_267_MAX_DETAIL_LENGTH chars), got ${detail.length}: $detail",
            detail.length <= R_267_MAX_DETAIL_LENGTH,
        )
        // The maintainer's research trail (git blob SHA-1 vs SHA-256, which HuggingFace/sherpa-onnx
        // endpoints were checked, the date checked) belongs in `ModelCatalog`'s own doc comment,
        // never read aloud to the operator deciding whether to sideload a file.
        assertFalse(detail.contains("HuggingFace"))
        assertFalse(detail.contains("SHA-1"))
        assertFalse(detail.contains("checked 2026"))
    }

    private fun session(id: String) = SessionEntity(
        id = id,
        startedAt = 0L,
        endedAt = null,
        profileId = null,
        deviceTier = null,
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
    )

    private fun transmission(id: String, sessionId: String) = TransmissionEntity(
        id = id,
        sessionId = sessionId,
        threadId = null,
        startedAtUtc = 0L,
        endedAtUtc = 1_000L,
        durationMs = 4_200L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = 146_960_000L,
        frequencyProvenance = "measured",
        mode = null,
        signalStrength = 7.0,
        channelName = null,
        voiceprintId = null,
        attributionState = AttributionState.UNKNOWN,
        stationId = null,
        attributionConfidence = null,
        attributionSourceTransmissionId = null,
        processingState = TransmissionState.CAPTURED,
        rejectionReason = null,
        samplePosition = 0L,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )
}
