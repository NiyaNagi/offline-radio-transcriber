package org.ort.app.ui.screens

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.audio.FakeTransmissionAudioPlayer
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.AttributionState
import org.ort.core.PassId
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.data.entity.WorkQueueItemEntity
import org.ort.data.entity.WorkQueueState
import org.robolectric.RobolectricTestRunner

/**
 * ui-conformance WP6: the content composable [org.ort.app.ui.navigation.OrtNavHost] dispatches to
 * — proves the whole loop against a real (file-backed) [OrtDatabase], the same pattern
 * `ReaderPollingTest`/`RealTransmissionAudioPlayerTest` already use, since [TransmissionDetailContent]
 * itself reads real `:data` state rather than a fake.
 *
 * Room's coroutine DAOs dispatch off Compose's own test clock (they run on Room's real query
 * executor, not the composition's dispatcher), so `waitForIdle()` alone is not enough after the
 * initial `LaunchedEffect` poll — [waitUntilTextExists]/[waitUntilDescriptionExists] poll with a
 * real timeout instead, the same shape `androidx.compose.ui.test`'s own `waitUntil` documents for
 * exactly this "state arrives from off the composition clock" case.
 */
@RunWith(RobolectricTestRunner::class)
class TransmissionDetailContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    private fun session() = SessionEntity(
        id = "S1",
        startedAt = 0L,
        endedAt = null,
        profileId = null,
        deviceTier = null,
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
    )

    private fun transmission(id: String, stationId: String?, voiceprintId: String? = "V1") = TransmissionEntity(
        id = id,
        sessionId = "S1",
        threadId = null,
        startedAtUtc = 0L,
        endedAtUtc = 1_000L,
        durationMs = 1_000L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = 145_230_000L,
        frequencyProvenance = "measured",
        mode = null,
        signalStrength = null,
        channelName = null,
        voiceprintId = voiceprintId,
        attributionState = if (stationId != null) AttributionState.INFERRED else AttributionState.UNKNOWN,
        stationId = stationId,
        attributionConfidence = if (stationId != null) 0.7 else null,
        attributionSourceTransmissionId = null,
        processingState = TransmissionState.COMPLETE,
        rejectionReason = null,
        samplePosition = 0L,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    private fun ComposeContentTestRule.waitUntilTextExists(text: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeContentTestRule.waitUntilDescriptionExists(description: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis) {
            onAllNodes(hasContentDescription(description, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `renders the real transmission once the poll resolves`() {
        runBlocking {
            db.sessionDao().insert(session())
            db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH"))
        }

        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailContent(
                    context = context,
                    transmissionId = "TX1",
                    player = FakeTransmissionAudioPlayer(),
                    onBack = {},
                    onOpenTransmission = {},
                )
            }
        }
        composeTestRule.waitUntilDescriptionExists("K7LWH")

        composeTestRule.onNodeWithContentDescription("K7LWH", substring = true).assertExists()
    }

    /**
     * R-017: [TransmissionDetailContent.backLabel] defaults to `"Log"` (today's only real entry
     * point, so `OrtNavHost.kt` compiles unchanged), but a caller that knows the true origin (a
     * station's overs, a thread, a search result) can name it — proven directly rather than only by
     * the default staying green.
     */
    @Test
    fun `R_017 a caller-supplied backLabel names the header's origin instead of the default`() {
        runBlocking {
            db.sessionDao().insert(session())
            db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH"))
        }

        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailContent(
                    context = context,
                    transmissionId = "TX1",
                    player = FakeTransmissionAudioPlayer(),
                    onBack = {},
                    onOpenTransmission = {},
                    backLabel = "Search",
                )
            }
        }
        composeTestRule.waitUntilDescriptionExists("Back to Search")

        composeTestRule.onNodeWithContentDescription("Back to Search").assertExists()
        composeTestRule.onNodeWithText("Search").assertExists()
    }

    @Test
    fun `R_052 correcting via Not right applies and reaches the propagated screen`() {
        runBlocking {
            db.sessionDao().insert(session())
            db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH"))
        }

        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailContent(
                    context = context,
                    transmissionId = "TX1",
                    player = FakeTransmissionAudioPlayer(),
                    onBack = {},
                    onOpenTransmission = {},
                )
            }
        }
        composeTestRule.waitUntilTextExists("Not right?")

        composeTestRule.onNodeWithText("Not right?").performClick()
        composeTestRule.waitUntilTextExists("Type a callsign")
        composeTestRule.onNodeWithText("Type a callsign").performScrollTo().performClick()
        composeTestRule.onNodeWithContentDescription("Typed callsign").performScrollTo().performTextInput("KA7LWH")
        composeTestRule.onNodeWithText("Save unverified correction").performScrollTo().performClick()
        composeTestRule.waitUntilTextExists("Corrected to KA7LWH")

        composeTestRule.onNodeWithText("Corrected to KA7LWH", substring = true).assertExists()
        val corrected = runBlocking { db.transmissionDao().getById("TX1") }
        assert(corrected!!.stationId == "KA7LWH")
    }

    /**
     * R-052, `Detail-Correct-A/B/C.dc.html`: the sheet sits over a dimmed backdrop within the
     * detail screen — not a second full screen — and tapping the scrim dismisses back to it
     * (the identical pattern `SearchScreen.kt`'s `FiltersSheetOverlay`, WP7, uses for its filters
     * sheet). Both the underlying callsign and the scrim/sheet coexist in the tree while
     * correcting, and dismissing removes the sheet, leaving the underlying detail unchanged.
     */
    @Test
    fun `R_052 the correction sheet sits over a dimmed detail screen, dismissed on scrim tap`() {
        runBlocking {
            db.sessionDao().insert(session())
            db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH"))
        }

        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailContent(
                    context = context,
                    transmissionId = "TX1",
                    player = FakeTransmissionAudioPlayer(),
                    onBack = {},
                    onOpenTransmission = {},
                )
            }
        }
        composeTestRule.waitUntilTextExists("Not right?")
        composeTestRule.onNodeWithText("Not right?").performClick()
        composeTestRule.waitUntilTextExists("Who was it?")

        // The detail screen underneath is still in the tree (dimmed, not replaced) while the sheet
        // is up — the header callsign remains findable, and the scrim exists at its own tag.
        composeTestRule.onNodeWithContentDescription("K7LWH", substring = true).assertExists()
        composeTestRule.onNodeWithTag("correction-sheet-scrim").assertExists()
        composeTestRule.onNodeWithTag("correction-sheet").assertExists()

        composeTestRule.onNodeWithTag("correction-sheet-scrim").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Who was it?").assertDoesNotExist()
        composeTestRule.onNodeWithText("Not right?").assertExists()
    }

    @Test
    fun `R_058 confirming an INFERRED transmission records an audit row and leaves it INFERRED`() {
        runBlocking {
            db.sessionDao().insert(session())
            db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH"))
        }

        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailContent(
                    context = context,
                    transmissionId = "TX1",
                    player = FakeTransmissionAudioPlayer(),
                    onBack = {},
                    onOpenTransmission = {},
                )
            }
        }
        composeTestRule.waitUntilTextExists("Confirm")

        composeTestRule.onNodeWithText("Confirm").performClick()
        composeTestRule.waitForIdle()

        runBlocking {
            var entity = db.transmissionDao().getById("TX1")!!
            var attempts = 0
            while (db.correctionDao().correctionsFor("TX1").isEmpty() && attempts < 50) {
                kotlinx.coroutines.delay(50)
                entity = db.transmissionDao().getById("TX1")!!
                attempts++
            }
            assert(entity.attributionState == AttributionState.INFERRED)
            assert(!entity.corrected)
            assert(db.correctionDao().correctionsFor("TX1").isNotEmpty())
        }
    }

    // ---- R-153, F18 Fail-Pass, FR-RUN-9 ----

    @Test
    fun `R_153_a_failed_transmission_shows_the_failed_pass_state_from_the_real_queue_row`() {
        runBlocking {
            db.sessionDao().insert(session())
            db.transmissionDao().insert(
                transmission("TX1", stationId = null).copy(processingState = TransmissionState.FAILED),
            )
            db.workQueueDao().insert(
                WorkQueueItemEntity(
                    transmissionId = "TX1",
                    pass = PassId.B_OFFLINE,
                    state = WorkQueueState.FAILED,
                    priority = 0,
                    attemptCount = 3,
                    lastError = "out of memory in the decoder",
                    enqueuedAt = 0L,
                ),
            )
        }

        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailContent(
                    context = context,
                    transmissionId = "TX1",
                    player = FakeTransmissionAudioPlayer(),
                    onBack = {},
                    onOpenTransmission = {},
                )
            }
        }
        composeTestRule.waitUntilTextExists("not transcribed")

        composeTestRule.onNodeWithText("Pass B errored 3 times", substring = true).assertExists()
        composeTestRule.onNodeWithText("Retry now").assertExists()
        composeTestRule.onNodeWithText("Keep the partial").assertExists()
    }

    /**
     * FR-RUN-9: `Retry this pass` genuinely requeues the real, single, per-transmission
     * [org.ort.data.entity.WorkQueueItemEntity] — proven end to end through a real tap, not a
     * direct call to [org.ort.app.ui.data.CorrectionPolling.retryFailedPass].
     */
    @Test
    fun `FR_RUN_9_tapping_retry_now_requeues_the_real_item_and_returns_the_transmission_to_processing`() {
        runBlocking {
            db.sessionDao().insert(session())
            db.transmissionDao().insert(
                transmission("TX1", stationId = null).copy(processingState = TransmissionState.FAILED),
            )
            db.workQueueDao().insert(
                WorkQueueItemEntity(
                    transmissionId = "TX1",
                    pass = PassId.B_OFFLINE,
                    state = WorkQueueState.FAILED,
                    priority = 0,
                    attemptCount = 3,
                    lastError = "out of memory in the decoder",
                    enqueuedAt = 0L,
                ),
            )
        }

        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailContent(
                    context = context,
                    transmissionId = "TX1",
                    player = FakeTransmissionAudioPlayer(),
                    onBack = {},
                    onOpenTransmission = {},
                )
            }
        }
        composeTestRule.waitUntilTextExists("Retry now")

        composeTestRule.onNodeWithText("Retry now").performClick()

        runBlocking {
            var item = db.workQueueDao().findByTransmissionAndPass("TX1", PassId.B_OFFLINE.name).single()
            var attempts = 0
            while (item.state != WorkQueueState.READY && attempts < 50) {
                kotlinx.coroutines.delay(50)
                item = db.workQueueDao().findByTransmissionAndPass("TX1", PassId.B_OFFLINE.name).single()
                attempts++
            }
            assert(item.state == WorkQueueState.READY) { "expected READY, item was still ${item.state}" }
            assert(db.transmissionDao().getById("TX1")!!.processingState == TransmissionState.PROCESSING)
        }
    }
}
