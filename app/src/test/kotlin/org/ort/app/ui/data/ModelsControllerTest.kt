package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
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
import org.ort.pipeline.capture.CaptureState
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
 *
 * **WPG follow-up (D35, FR-AST-1): every real catalog entry now ships bundled**, so
 * [ModelsController.download] refuses unconditionally before it would ever reach
 * [org.ort.net.ModelAcquisition]. The tests below that exist to prove the underlying
 * fetch-through-the-fake mechanism (still real code — `sideload`, replacement, staging on
 * `FR-AST-4` all route through it) now pass `isBundled = { false }` explicitly, mirroring
 * [ModelsController.download]'s own `specFor` seam, so they keep proving that mechanism without
 * needing a hypothetical non-bundled catalog entry to do it. New tests prove the real,
 * bundled-refuses-immediately behavior against the actual default. The old
 * `FR_AST_1 download refuses ... for an unknown-checksum entry` and
 * `R_267 the tokens row's not-installed detail ...` tests are removed: `ASR_TOKENS` is no longer
 * an unknown-checksum entry after WPG's trust-on-first-fetch pinning (`ModelCatalogTest`'s own
 * `WPG`/`R_267` tests now cover the same reason-formatting contract directly against
 * [ModelCatalog.checksumStateFor], since no real [ModelId] is left in that state to drive it
 * through [ModelsController.currentState] at all).
 */
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

    @After
    fun tearDown() {
        // [CaptureState] and [ModelsController] are both process-wide singletons (Robolectric does
        // not reset them between `@Test` methods in this class) — never leave a live session or a
        // staged fact bleeding into the next test. `activateStaged` is the real API, not a test-only
        // reset hook: draining whatever this test left staged is itself a legitimate call.
        CaptureState.idle(clearSession = true)
        runBlocking { ModelsController.activateStaged(context) }
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
                isBundled = { false }, // proving the fetch-through-the-fake mechanism, see class KDoc
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
            isBundled = { false }, // proving the fetch-through-the-fake mechanism, see class KDoc
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
            isBundled = { false }, // proving the fetch-through-the-fake mechanism, see class KDoc
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
    @Requirement("D35", "FR-AST-1")
    fun `WPG download refuses with no network call at all for a bundled entry, using the real default`(): Unit =
        runTest {
            // A client that throws if it is ever invoked — proves ModelAcquisition.fetch (and
            // therefore the network) is never reached, using the REAL ModelCatalog-backed
            // isBundled default (every entry ships bundled today, D35).
            val neverCalled = object : org.ort.net.HttpRangeClient {
                override fun get(url: String, rangeStart: Long): org.ort.net.HttpRangeResult =
                    error("download() must never make a network call for a bundled entry")
            }

            val result = ModelsController.download(context, ModelId.VAD, client = neverCalled)

            assertTrue("expected a Failure, got $result", result is ModelActionResult.Failure)
            assertTrue(
                (result as ModelActionResult.Failure).reason.contains("ships bundled"),
            )
        }

    @Test
    @Requirement("FR-AST-1")
    fun `FR_AST_1 sideloading with no verifiable spec installs it unverified, never claiming verified`(): Unit =
        runTest {
            // Every real ModelId is ChecksumState.Known today (WPG's trust-on-first-fetch pinning
            // — see ModelCatalogTest's own WPG-tagged tests), so this injects specFor = { null }
            // — the same seam `download` uses — to exercise sideload's own trust-on-first-use
            // branch (unverifiedSpecFor) directly, rather than needing a hypothetical
            // unknown-checksum catalog entry to reach it. sideload's own logic never touches
            // checksumState at all (only url/destination), so this injection is safe.
            val tempFilesDir = Files.createTempDirectory("models-controller-tofu-test").toFile()
            val fakeContext = object : android.content.ContextWrapper(context) {
                override fun getFilesDir(): File = tempFilesDir
            }
            val sourceFile = File(tempFilesDir, "user-picked-tokens.txt")
            sourceFile.writeBytes(body)
            val noSpec: (ModelId, File) -> ModelFetchSpec? = { _, _ -> null }

            val before = ModelsController.currentState(fakeContext, specFor = noSpec)
            assertEquals(
                ModelRowStatus.NOT_INSTALLED,
                before.rows.first { it.id == ModelId.ASR_TOKENS }.status,
            )
            assertFalse(before.rows.first { it.id == ModelId.ASR_TOKENS }.checksumKnown)

            val result = ModelsController.sideload(fakeContext, ModelId.ASR_TOKENS, sourceFile, specFor = noSpec)

            assertTrue("expected a Success, got $result", result is ModelActionResult.Success)
            val after = ModelsController.currentState(fakeContext, specFor = noSpec)
            val row = after.rows.first { it.id == ModelId.ASR_TOKENS }
            assertEquals(ModelRowStatus.INSTALLED_UNVERIFIED, row.status)
            assertFalse(row.checksumKnown)
        }

    @Test
    @Requirement("FR-AST-4")
    fun `FR_AST_4 a model install mid-session stages the reprocess, never requeues immediately`(): Unit = runTest {
        db.sessionDao().insert(session("S-STAGED"))
        db.transmissionDao().insert(transmission("TX-STAGED-1", "S-STAGED"))
        val queue = WorkQueue(db, SystemClock, maxAttempts = 1)
        queue.enqueue("TX-STAGED-1", PassId.B_OFFLINE)
        val leased = queue.leaseBatch("run-staged-1", limit = 10) { 60_000L }.single()
        queue.failPass(leased, "ASR unavailable: no ASR model installed")

        CaptureState.capturing("S-STAGED")
        val result = ModelsController.download(
            context,
            ModelId.VAD,
            client = FakeHttpRangeClient(body),
            specFor = specFor(goodChecksum),
            isBundled = { false }, // proving the fetch-through-the-fake mechanism, see class KDoc
        )

        val success = result as ModelActionResult.Success
        assertEquals(
            "nothing was really requeued yet — the file installed, only the reprocess is staged",
            0,
            success.requeuedCount,
        )
        assertEquals(
            "the failed item must stay FAILED, never silently reprocessed mid-session (FR-AST-4)",
            TransmissionState.FAILED,
            db.transmissionDao().getById("TX-STAGED-1")!!.processingState,
        )
        val staged = ModelsController.stagedActivation.value
        assertTrue("expected a staged activation, got null", staged != null)
        assertEquals(ModelId.VAD.name, staged!!.assetId)
        assertTrue("reason must cite the real requirement, got: ${staged.reason}", staged.reason.contains("FR-AST-4"))
    }

    @Test
    @Requirement("FR-AST-4")
    fun `FR_AST_4 activateStaged requeues a staged model install once the session has ended`(): Unit = runTest {
        db.sessionDao().insert(session("S-STAGED-2"))
        db.transmissionDao().insert(transmission("TX-STAGED-2", "S-STAGED-2"))
        val queue = WorkQueue(db, SystemClock, maxAttempts = 1)
        queue.enqueue("TX-STAGED-2", PassId.B_OFFLINE)
        val leased = queue.leaseBatch("run-staged-2", limit = 10) { 60_000L }.single()
        queue.failPass(leased, "ASR unavailable: no ASR model installed")

        CaptureState.capturing("S-STAGED-2")
        ModelsController.download(
            context,
            ModelId.VAD,
            client = FakeHttpRangeClient(body),
            specFor = specFor(goodChecksum),
            isBundled = { false }, // proving the fetch-through-the-fake mechanism, see class KDoc
        )
        CaptureState.idle(clearSession = true)

        val activated = ModelsController.activateStaged(context)

        assertTrue("expected the staged activation to apply, got null", activated != null)
        assertEquals(
            TransmissionState.PROCESSING,
            db.transmissionDao().getById("TX-STAGED-2")!!.processingState,
        )
        assertNull(ModelsController.stagedActivation.value)
    }

    @Test
    @Requirement("FR-AST-4")
    fun `FR_AST_4 with no session ever live, download requeues immediately exactly as before`(): Unit = runTest {
        db.sessionDao().insert(session("S-NOT-STAGED"))
        db.transmissionDao().insert(transmission("TX-NOT-STAGED-1", "S-NOT-STAGED"))
        val queue = WorkQueue(db, SystemClock, maxAttempts = 1)
        queue.enqueue("TX-NOT-STAGED-1", PassId.B_OFFLINE)
        val leased = queue.leaseBatch("run-not-staged-1", limit = 10) { 60_000L }.single()
        queue.failPass(leased, "ASR unavailable: no ASR model installed")

        val result = ModelsController.download(
            context,
            ModelId.VAD,
            client = FakeHttpRangeClient(body),
            specFor = specFor(goodChecksum),
            isBundled = { false }, // proving the fetch-through-the-fake mechanism, see class KDoc
        )

        assertEquals(1, (result as ModelActionResult.Success).requeuedCount)
        assertNull(ModelsController.stagedActivation.value)
    }

    @Test
    @Requirement("FR-AST-4")
    fun `FR_AST_4 activateStaged is a safe no-op with nothing staged`(): Unit = runTest {
        val activated = ModelsController.activateStaged(context)

        assertNull(activated)
    }

    @Test
    @Requirement("D35", "FR-AST-1")
    fun `WPG every row reports bundled true, matching the real catalog`() {
        val state = ModelsController.currentState(context)

        state.rows.forEach { row -> assertTrue("${row.id} must report bundled", row.bundled) }
    }

    @Test
    @Requirement("FR-AST-3a", "AC-138")
    fun `WPG a tier-3-only bundled asset reports tierEligible false below tier 3, true at tier 3`() {
        val gemmaAtT0 = ModelsController.currentState(context, currentTierLabel = { "T0" })
            .rows.first { it.id == ModelId.LLM_GEMMA3_1B }
        assertFalse("Gemma is tier-3-only; must not be eligible at tier 0", gemmaAtT0.tierEligible)

        val vadAtT0 = ModelsController.currentState(context, currentTierLabel = { "T0" })
            .rows.first { it.id == ModelId.VAD }
        assertTrue("VAD ships for every tier; must remain eligible at tier 0", vadAtT0.tierEligible)

        val gemmaAtT3 = ModelsController.currentState(context, currentTierLabel = { "T3" })
            .rows.first { it.id == ModelId.LLM_GEMMA3_1B }
        assertTrue("Gemma must be eligible at tier 3", gemmaAtT3.tierEligible)
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
