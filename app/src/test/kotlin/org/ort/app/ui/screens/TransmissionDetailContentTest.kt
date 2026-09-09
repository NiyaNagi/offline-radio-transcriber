package org.ort.app.ui.screens

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import org.ort.data.entity.CallsignCandidateEntity
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
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

    /**
     * The same fix `CorrectionPollingTest.closeDatabase`'s own doc comment describes in full: every
     * file-backed-[OrtDatabase] test class that never closes its own `RoomDatabase` leaves a leaked
     * writer behind for the rest of this Gradle test-worker JVM to contend against (`ort.db` is the
     * same on-disk file for every test class, not one sandboxed per method). This class was one of
     * the never-closed instances; closed here the same way.
     */
    @After
    fun closeDatabase() {
        db.close()
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

    /**
     * R-261 (spec, `Detail-Correct-*.dc.html`, guide §11.2): the detail's own `Back to Log` header
     * button stayed focusable/reachable behind the correction sheet's scrim — the dimmed content
     * now carries [org.ort.app.ui.components.clearedWhileOverlaid] (WP2), which removes it from the
     * merged semantics tree entirely while the sheet is open, not just visually. Proved both ways:
     * gone while the sheet is up, back once it is dismissed.
     */
    @Test
    fun `R_261_back_to_log_is_not_in_the_merged_tree_while_the_correction_sheet_is_open`() {
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
        composeTestRule.waitUntilDescriptionExists("Back to Log")
        composeTestRule.onNodeWithContentDescription("Back to Log").assertExists()

        composeTestRule.onNodeWithText("Not right?").performClick()
        composeTestRule.waitUntilTextExists("Who was it?")

        composeTestRule.onNodeWithContentDescription("Back to Log").assertDoesNotExist()

        composeTestRule.onNodeWithTag("correction-sheet-scrim").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Back to Log").assertExists()
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
     * R-189 (halt): the accessibility validator saw the detail revert to UNKNOWN about 2s after
     * applying a fresh typed correction — no `Undo` involved. `CorrectionDao.applyCorrectedAttribution`
     * writes `attributionState = INFERRED` with `attributionConfidence = NULL` (there is no
     * calibrated number for "a human said so"); `ReaderPolling`'s own attribution derivation
     * requires a non-null confidence for every INFERRED row and silently downgraded that to
     * `Attribution.unknown()` on the very next poll — this reproduces the poll ("Back to the over"
     * re-runs `refresh()`, the exact call a live poll makes) end to end through the real composed
     * screen and the real database, not a direct call to `CorrectionPolling.currentAttribution`.
     */
    @Test
    fun `R_189_after_a_fresh_typed_correction_the_next_poll_still_shows_the_corrected_callsign_not_unknown`() {
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
        composeTestRule.onNodeWithContentDescription("Typed callsign").performScrollTo().performTextInput("VE7ABC")
        composeTestRule.onNodeWithText("Save unverified correction").performScrollTo().performClick()
        composeTestRule.waitUntilTextExists("Corrected to VE7ABC")

        // The exact re-poll a live "check back a couple seconds later" would trigger.
        composeTestRule.onNodeWithText("Back to the over").performClick()
        composeTestRule.waitUntilTextExists("VE7ABC")

        composeTestRule.onNodeWithText("VE7ABC").assertExists()
        composeTestRule.onNodeWithText("unknown station", substring = true, ignoreCase = true).assertDoesNotExist()
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

        // R-261: the detail screen underneath is still *composed* while the sheet is up (dimmed,
        // not replaced — the scrim and sheet exist at their own tags), but is no longer reachable —
        // `clearedWhileOverlaid` removes it from the merged semantics tree entirely, so its own
        // header callsign must not be findable while the sheet is open.
        composeTestRule.onNodeWithContentDescription("K7LWH", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("correction-sheet-scrim").assertExists()
        composeTestRule.onNodeWithTag("correction-sheet").assertExists()

        composeTestRule.onNodeWithTag("correction-sheet-scrim").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Who was it?").assertDoesNotExist()
        composeTestRule.onNodeWithText("Not right?").assertExists()
        // ...and reachable again once the sheet is dismissed.
        composeTestRule.onNodeWithContentDescription("K7LWH", substring = true).assertExists()
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

    /**
     * R-180 (halt): the validator reported "Full lattice" a dead tap — D05 (the exhaustive
     * `DetailWhyScreen`) never reached, "the same screen re-renders" instead. Proves the whole
     * `TransmissionDetailContent` → `DetailDestination.Why` → `DetailWhyScreen` wiring end to end,
     * through a real tap, against a transmission with real candidate data (not an empty
     * `InspectionViewState`, which would make `Full lattice` genuinely have nothing new to show).
     */
    @Test
    fun `R_180_full_lattice_opens_the_exhaustive_why_screen`() {
        runBlocking {
            db.sessionDao().insert(session())
            db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH"))
            db.catalogDao().insert(
                CallsignCandidateEntity(
                    id = "c1",
                    transmissionId = "TX1",
                    callsign = "K7LWH",
                    rank = 0,
                    score = 8.6,
                    grammarValid = true,
                    ituPrefix = "K",
                    ituCountry = "United States",
                    priorBreakdown = mapOf("database" to 0.8),
                    databaseHit = true,
                    selected = true,
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
        composeTestRule.waitUntilTextExists("Full lattice")

        composeTestRule.onNodeWithText("Full lattice").performScrollTo().performClick()

        composeTestRule.waitUntilTextExists("Everything the resolver saw")
        composeTestRule
            .onNodeWithText("Everything the resolver saw, in the order it used it")
            .assertExists()
        // The exhaustive screen's own candidate row, not just the inline preview's — proves D05
        // itself rendered, not a re-render of the same detail screen.
        composeTestRule.onNodeWithText("3 · Candidates that survived", ignoreCase = true, substring = true)
            .assertExists()
    }

    /**
     * R-183: `Detail.dc.html`'s INFERRED explanation names the source over's real time ("to
     * 02:14:07, where the callsign was heard clearly") and offers a real link to it — proved end to
     * end against a real source transmission's own `startedAtUtc`, not a generic placeholder. The
     * source id must be a real ULID — `attributionSourceTransmissionId` is round-tripped through
     * `TransmissionId.parse` (`ReaderPolling.sourceId`) before an INFERRED reasoning line can link
     * to it at all (the exact trap `OvernightScenario.kt`'s own R-241 comment documents).
     */
    @Test
    fun `R_183_the_inferred_explanation_names_the_real_source_over_time_and_links_to_it`() {
        val sourceId = org.ort.core.Ulid.generate().toString()
        runBlocking {
            db.sessionDao().insert(session())
            db.transmissionDao().insert(transmission(sourceId, stationId = "K7LWH").copy(startedAtUtc = 0L))
            db.transmissionDao().insert(
                transmission("TX1", stationId = "K7LWH").copy(
                    startedAtUtc = 500_000L,
                    attributionSourceTransmissionId = sourceId,
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
        // `detail` and `sourceOverTimeLabel` are two separate state writes inside one `refresh()`
        // call — waiting on the generic "Matched by voice" text (present in both the honest
        // fallback wording and the final one) can catch the composition between those two writes;
        // waiting on the real time clause itself is the one condition that is only true once both
        // have landed.
        composeTestRule.waitUntilTextExists("00:00:00")

        composeTestRule.onNodeWithText("00:00:00", substring = true).assertExists()
        composeTestRule.onNodeWithText("Open the source over").assertExists()
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

    // ---- R-242, F04 `Fail-Hallucination.dc.html`, FR-ASR-5: the rejected detail state, end to end ----

    /**
     * The exact real fixture `overnight`'s own rejected row (`OvernightScenario.kt`'s `tx8`) writes —
     * `processingState = REJECTED`, a real `"$rule: $detail"` reason, retained audio, no transcript —
     * reproduced directly here so this test proves [TransmissionDetailContent]'s own read/render path
     * against a real polled `processingState`, not a hand-picked shortcut shape.
     */
    @Test
    fun `R_242_tapping_through_to_a_rejected_transmission_opens_the_rejected_detail_state_with_its_real_reason`() {
        runBlocking {
            db.sessionDao().insert(session())
            db.transmissionDao().insert(
                transmission("TX1", stationId = null).copy(
                    processingState = TransmissionState.REJECTED,
                    rejectionReason = "VAD_NO_SPEECH: squelch tail, 0.4 s",
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
        composeTestRule.waitUntilTextExists("REJECTED")

        composeTestRule.onNodeWithTag("rejected-section").assertExists()
        // R-331: the operator-prose mapping (`LogItemsMapper.whyFor`), never the raw
        // "VAD_NO_SPEECH: squelch tail, 0.4 s" record.
        composeTestRule.onNodeWithText("No speech detected, squelch tail, 0.4 s", substring = true).assertExists()
        composeTestRule.onNodeWithText("VAD_NO_SPEECH", substring = true).assertDoesNotExist()
        // The retained audio's own waveform still renders — nothing is deleted quietly (constitution III).
        composeTestRule.onNodeWithTag("waveform-card").assertExists()
    }

    // ---- R-194, `Detail-Revisions.dc.html`: version-card copy and the closing note, end to end ----

    /**
     * The exact real fixture `revisions` (`Scenarios.kt`'s own scenario) shape: one transmission,
     * two transcript versions, the older superseded — reproduced directly so this proves
     * [DetailRevisionsScreen]'s own render against a real poll, not a hand-built [DetailViewState].
     */
    @Test
    fun `R_194_the_revisions_screen_shows_every_version_cards_marker_callsign_and_the_boards_closing_note`() {
        // A transmission/transcript id no other test in this class touches — this class's `TX1` is
        // reused across many tests with no per-test `@After` clear, so a fresh id sidesteps any
        // cross-test leakage of `:data`'s file-backed db within one Gradle test-worker JVM (the same
        // sharp edge `CorrectionPollingTest`'s own `closeDatabase` doc comment names).
        val txId = "TXREV1"
        runBlocking {
            db.sessionDao().insert(session())
            db.transmissionDao().insert(transmission(txId, stationId = "W7NPC").copy(corrected = true))
            db.transcriptDao().insert(
                TranscriptEntity(
                    id = "$txId-t1",
                    transmissionId = txId,
                    pass = TranscriptPass.A,
                    text = "and we're clear on the repeater, seven th",
                    modelId = "whisper-small",
                    modelVersion = "1",
                    quantization = null,
                    decodeParams = null,
                    noSpeechProb = null,
                    confidence = null,
                    isCurrent = false,
                    createdAt = 10L,
                ),
            )
            db.transcriptDao().insert(
                TranscriptEntity(
                    id = "$txId-t2",
                    transmissionId = txId,
                    pass = TranscriptPass.B,
                    text = "and we're clear on the repeater, seven three",
                    modelId = "whisper-small",
                    modelVersion = "1",
                    quantization = null,
                    decodeParams = null,
                    noSpeechProb = null,
                    confidence = 0.9,
                    isCurrent = true,
                    createdAt = 20L,
                ),
            )
        }

        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailContent(
                    context = context,
                    transmissionId = txId,
                    player = FakeTransmissionAudioPlayer(),
                    onBack = {},
                    onOpenTransmission = {},
                )
            }
        }
        composeTestRule.waitUntilTextExists("1 earlier version")

        composeTestRule.onNodeWithText("1 earlier version").performScrollTo().performClick()
        // `Detail-Revisions.dc.html`'s own closing note is static — present even before
        // [org.ort.app.ui.data.CorrectionPolling.revisions]'s own async fetch (a second, real
        // `:data` read off the composition clock, the same reason this whole file's class doc
        // names [waitUntilTextExists] necessary) lands — so waiting on it alone would race the real
        // version list. "2 versions" only appears once that fetch has actually populated the screen.
        composeTestRule.waitUntilTextExists("2 versions")
        composeTestRule.waitUntilTextExists("Nothing here can be deleted from this screen")

        // Every version card carries the transmission's own real, current marker + callsign +
        // corrected badge (`TranscriptVersionViewState`'s own doc comment: not a fabricated
        // per-version history) and the board's own closing note, worded exactly.
        composeTestRule.onAllNodesWithText("W7NPC", substring = true).onFirst().assertExists()
        composeTestRule.onAllNodesWithText("corrected", substring = true, ignoreCase = true).onFirst().assertExists()
        composeTestRule
            .onNodeWithText(
                "Restoring an earlier version makes it current and keeps this one as superseded. " +
                    "Nothing here can be deleted from this screen — retention handles audio, and never transcripts.",
            )
            .assertExists()
    }
}
