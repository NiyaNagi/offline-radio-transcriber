package org.ort.app.ui.navigation

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.ShedStatus
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Register R-1041 (R04) / R-1055 (halt): Improve's Done board, real (`org.ort.app.ui.improve
 * .ImproveContent`, `rememberSaveable`-backed since R-1063), lost its own `Done` summary the moment
 * `current` switched away and back — `DestinationContent`'s own `when(current)` (`OrtNavHost.kt`)
 * fully disposes whichever branch is not the current one, so `ImproveContent`'s own
 * `rememberSaveable` `page` had nothing keeping its *value* alive once its own composable left the
 * tree entirely (a real Activity recreation, R-1063's own case, is a different mechanism — the
 * `SaveableStateRegistry` bundle survives that; a plain destination switch inside the same running
 * process, with no recreation at all, is what this register entry is about, and what R-1063's own
 * fix did not touch).
 *
 * This drives the *identical* mechanism `Improve-Done`'s real "Review the N changes" ->
 * `LogFilterOrigin.Improve` -> back produces, without needing a real ASR model to populate
 * `changedTransmissionIds` (constitution: "No unit test reads a real bundled model" — the only way
 * to make that button itself appear is a real reprocess that actually changed a transcript or
 * attribution, which needs a real installed model this suite must never read). `navigator.open(...)`
 * is the exact one-line effect both the real click path
 * (`OrtNavHost.kt`'s `openLogAndNavigate`/`onOpenChangedOvers`) and the real back path
 * (`restoreLogFilterOrigin`'s `LogFilterOrigin.Improve` branch) reduce to —
 * `navigator.currentState.value = <destination>` — so switching through it here exercises
 * `DestinationContent`'s own dispose/recompose behaviour exactly as either real path does; the
 * `LogFilterOrigin` bookkeeping itself (which destination *to* restore) is a separate, already
 * name-checked mechanism this test does not need to re-prove.
 *
 * Seeded exactly like `org.ort.app.ui.improve.ImproveContentActivityTest.seedTierSession` (a
 * host-owned copy, not a shared import — that class lives in `ui/improve`, not this package's
 * row): a real T1-tier session with one real transmission, reprocessed against no installed model
 * (the same honest "model-less" ending `ImproveContentActivityTest`'s own `R_1063 Done survives...`
 * case uses) — real `headline`/`clearedCount`/`summary` fields, `changedTransmissionIds` empty
 * (so "Review the N changes" itself does not render here — that control's own reachability is
 * proven on the device, per this round's own report, not in this suite).
 */
@RunWith(RobolectricTestRunner::class)
class ImproveDestinationStatePreservationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        ShedStatus.reset()
    }

    @After
    fun tearDown() {
        ShedStatus.reset()
        CaptureState.idle(clearSession = true)
    }

    /** See this class's own kdoc — mirrors `ImproveContentActivityTest.seedTierSession` exactly. */
    private fun seedTierSession(sessionId: String, transmissionId: String): Unit = runBlocking {
        val db = OrtDatabase.create(context)
        val audioFile = File(context.filesDir, "audio/$sessionId/$transmissionId.flac")
        audioFile.parentFile?.mkdirs()
        audioFile.writeBytes(ByteArray(2048))
        db.sessionDao().insert(
            SessionEntity(
                id = sessionId,
                startedAt = 0L,
                endedAt = null,
                profileId = null,
                deviceTier = "T1",
                appVersion = "test",
                terminationReason = null,
                sourceId = null,
                schemaVersion = OrtDatabase.SCHEMA_VERSION,
            ),
        )
        db.transmissionDao().insert(
            TransmissionEntity(
                id = transmissionId,
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
                processingState = TransmissionState.COMPLETE,
                rejectionReason = null,
                samplePosition = 1L,
                monotonicStartNanos = 0L,
                utcOffsetMinutes = 0,
                calibrationId = null,
                executionProvider = null,
            ),
        )
    }

    /**
     * The discriminating case (register R-1041 R04 / R-1055): must fail on the pre-fix host, which
     * disposes `ImproveContent`'s whole composition — losing its `rememberSaveable` `page` along
     * with it — the instant `current` becomes `LOG`, so switching back shows `Improve records`'s
     * own `Root` list, never `Done`.
     */
    @Test
    fun `R_1041_R04 Improve's Done summary survives the same LOG round trip Review changes and back produce`() {
        val sessionId = "r04-improve-done-session"
        val transmissionId = "r04-improve-done-tx"
        seedTierSession(sessionId, transmissionId)

        lateinit var navigator: ReaderNavigator
        composeTestRule.setContent {
            navigator = rememberReaderNavigator(initialDestination = ReaderDestination.IMPROVE_RECORDS)
            OrtTheme { OrtNavHost(sessionId = null, navigator = navigator) }
        }

        val improveAllButton = hasText("Improve all", substring = true) and hasClickAction()
        composeTestRule.waitUntil(15_000) {
            composeTestRule.onAllNodes(improveAllButton).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(improveAllButton).performClick()

        // A real reprocess run against a real, model-less filesDir -- genuine queue-drain work
        // (`ImproveContentActivityTest`'s own precedent), not a fixed delay.
        val clearedLine = hasText("no longer marked as reprocessing candidates", substring = true)
        composeTestRule.waitUntil(30_000) { composeTestRule.onAllNodes(clearedLine).fetchSemanticsNodes().isNotEmpty() }

        // The exact one-line effect the real "Review the N changes" click
        // (`NavHostCallbacks.onOpenChangedOvers` -> `openLogAndNavigate`) produces.
        composeTestRule.runOnIdle { navigator.open(ReaderDestination.LOG) }
        composeTestRule.waitUntil(15_000) {
            composeTestRule.onAllNodes(hasText("Log", substring = false)).fetchSemanticsNodes().isNotEmpty()
        }

        // The exact one-line effect the real back gesture's `restoreLogFilterOrigin`
        // (`LogFilterOrigin.Improve` branch) produces.
        composeTestRule.runOnIdle { navigator.open(ReaderDestination.IMPROVE_RECORDS) }

        // The discriminating assertion: the real summary line is shown again -- never the Root
        // list's own content, and never a summary silently reset to a fabricated default.
        composeTestRule.waitUntil(15_000) { composeTestRule.onAllNodes(clearedLine).fetchSemanticsNodes().isNotEmpty() }
        composeTestRule.onNodeWithText("This phone can do more", substring = true).assertDoesNotExist()
    }
}
