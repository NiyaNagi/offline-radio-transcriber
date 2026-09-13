package org.ort.app.ui.improve

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.ort.pipeline.reprocess.ReprocessWorker
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * register R-1067 round 2 (coordinator item 1, constitution I): [ImproveContent] itself — not a
 * real `ReaderActivity` — is disposed and recomposed here (a plain `createComposeRule`, no
 * Activity at all), the shape coordinator round 2 named directly: "compose it away and back, not
 * just recreate". `ImproveContentActivityTest`'s own `R_1067` case proves an Activity recreation
 * (rotation, font scale, dark mode) never cancels the real run; this proves the *screen's own
 * composition* being torn down and rebuilt — a drawer switch away from Improve and back, which
 * `OrtNavHost` does by simply not composing this destination's content — reattaches to the exact
 * same `WorkInfo`, not a fresh, silently-restarted one.
 */
@RunWith(RobolectricTestRunner::class)
class ImproveContentReattachTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        ShedStatus.reset()
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @After
    fun tearDown() {
        ShedStatus.reset()
        CaptureState.idle(clearSession = true)
    }

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

    private fun workInfos() =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(ReprocessWorker.UNIQUE_WORK_NAME).get()

    @Test
    fun `R_1067_round2 disposing and recomposing ImproveContent reattaches to the same running work id`() {
        seedTierSession("s-reattach", "s-reattach-tx")

        var show by mutableStateOf(true)
        composeTestRule.setContent {
            OrtTheme {
                if (show) ImproveContent(context = context, onDrawer = {})
            }
        }

        val startButton = hasText("Improve all", substring = true)
        composeTestRule.waitUntil(15_000) { composeTestRule.onAllNodes(startButton).fetchSemanticsNodes().isNotEmpty() }
        // Armed *after* Root has already resolved its own real state (a higher shed level changes
        // ImprovePolling's own "current tier" computation -- arming this before Root loads would
        // corrupt eligibility, not just freeze the run) but *before* the tap that starts it --
        // freezes the real ReprocessRunner at its very first item (the engine's own capture-
        // priority yield) so this test observes a stable Running board instead of racing a real,
        // fast, model-less reprocess to Done. The identical technique
        // ImproveContentActivityTest's own R_1063/R_1067 cases already use.
        CaptureState.capturing("live-session")
        ShedStatus.update(level = 3, backlog = 0) // ReprocessRunner.BUSY_SHED_LEVEL_THRESHOLD
        composeTestRule.onNode(startButton).performClick()

        val waitingText = hasText("waiting", substring = true)
        composeTestRule.waitUntil(15_000) { composeTestRule.onAllNodes(waitingText).fetchSemanticsNodes().isNotEmpty() }

        val infosBefore = workInfos()
        assertEquals("exactly one run must be enqueued, never a duplicate", 1, infosBefore.size)
        val idBefore = infosBefore.single().id

        // Dispose ImproveContent entirely (not an Activity recreation -- a plain composition
        // removal, the shape a drawer switch away from Improve produces) and recompose it.
        show = false
        composeTestRule.waitForIdle()
        show = true
        composeTestRule.waitForIdle()

        composeTestRule.waitUntil(15_000) { composeTestRule.onAllNodes(waitingText).fetchSemanticsNodes().isNotEmpty() }
        val infosAfter = workInfos()
        assertEquals(1, infosAfter.size)
        assertEquals(
            "the SAME WorkInfo id must still be tracked -- a different id would mean disposing " +
                "and recomposing ImproveContent silently started a second, duplicate run",
            idBefore,
            infosAfter.single().id,
        )
    }
}
