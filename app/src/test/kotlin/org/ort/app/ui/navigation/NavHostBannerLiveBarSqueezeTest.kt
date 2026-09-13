package org.ort.app.ui.navigation

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
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
import org.ort.pipeline.capture.LevelStatus
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Register R-1061 (lead capture `05-done@2x.png`, misnamed — it is the Improve `Root` board, not
 * `Done`): with a live session's own pinned [org.ort.app.ui.components.TransportBar] at the bottom
 * and a real "too quiet" [org.ort.app.ui.failures.FailurePresentation.Level] banner at the top, at
 * font scale 2.0, `Improve records`/`N overs can get better` sit directly above the bar and the
 * card's own `Improve all N` button is cut to a sliver under it — unreachable by any scroll.
 *
 * Unlike [org.ort.app.ui.improve.ImproveScreensHostLayoutTest]'s own R-1056 coverage (the `Done`/
 * `Running` boards, each with their own fixed [org.ort.app.ui.improve.ImproveActionBarScaffold]
 * action bar, no host live bar in the picture), this is the `Root` board — no action bar of its
 * own, `Improve all N` sitting inline near the top of its one scrollable column — sharing the
 * screen with the *host's* own pinned bar, not a package-owned one. `NavHostBody`'s own
 * `bannerClearance`/content-box math is what is under test here, so this drives the real
 * [OrtNavHost] end to end (real [org.ort.app.ui.failures.FailureHost], real
 * [org.ort.app.ui.improve.ImproveContent]) rather than reproducing its shape in miniature —
 * [TransportBarHostLayoutTest]'s own R-1049 lesson: a stand-in host shape can bound away the exact
 * growth a register entry is about.
 */
@RunWith(RobolectricTestRunner::class)
class NavHostBannerLiveBarSqueezeTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        CaptureState.idle(clearSession = true)
        LevelStatus.reset()
    }

    @After
    fun tearDown() {
        CaptureState.idle(clearSession = true)
        LevelStatus.reset()
    }

    /** Mirrors `ImproveContentActivityTest.seedTierSession` (a host-owned copy, not a shared
     * import — that class lives in `ui/improve`, not this package's row): a real T1-tier session
     * with one real transmission, so `ImprovePolling.root(context)` finds a real, non-empty group
     * and `ImproveScreen` renders a real `Improve all N` button rather than the empty state. */
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

    /** [org.ort.pipeline.capture.LevelStatus]'s own "too quiet" shape — `Fail-Level.dc.html`,
     * `FailureMapper.QUIET_PEAK_THRESHOLD_DBFS` is `-30f`; `-34f` matches that board's own copy. */
    private fun armTooQuietBanner() {
        LevelStatus.update(
            measured = LevelStatus.State.Measured(
                peakDbfs = -34f,
                rmsDbfs = -34f,
                noiseFloorDbfs = null,
                clipped = false,
                clipCountLastSecond = 0,
                sampleRateHz = 16_000,
                updatedAtMillis = 0L,
            ),
            peakHistoryDbfs = emptyList(),
        )
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `R_1061 Improve all is scrollable fully above the live bar with a too-quiet banner at font scale 2_0`() {
        val sessionId = "r1061-improve-squeeze-session"
        val transmissionId = "r1061-improve-squeeze-tx"
        seedTierSession(sessionId, transmissionId)
        armTooQuietBanner()
        CaptureState.capturing(sessionId)

        composeTestRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    density = androidx.compose.ui.platform.LocalDensity.current.density,
                    fontScale = 2f,
                ),
            ) {
                OrtTheme {
                    OrtNavHost(
                        sessionId = sessionId,
                        navigator = rememberReaderNavigator(initialDestination = ReaderDestination.IMPROVE_RECORDS),
                    )
                }
            }
        }

        val improveAllButton = hasText("Improve all", substring = true) and hasClickAction()
        composeTestRule.waitUntil(15_000) {
            composeTestRule.onAllNodes(improveAllButton).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitUntil(15_000) {
            composeTestRule.onAllNodesWithTag("live-bar-clearance").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitUntil(15_000) {
            composeTestRule.onAllNodesWithTag("failure-banner-overlay").fetchSemanticsNodes().isNotEmpty()
        }

        // The discriminating assertion (register R-1061): scrolled fully into view, the button's
        // own bottom edge must clear the live bar's own top edge — never a sliver clipped under it.
        val buttonBounds = composeTestRule.onNode(improveAllButton).performScrollTo().getUnclippedBoundsInRoot()
        val barTop = composeTestRule.onNodeWithTag("live-bar-clearance").getUnclippedBoundsInRoot().top

        assertTrue(
            "expected 'Improve all' (bottom=${buttonBounds.bottom}) to be scrollable fully above the " +
                "live bar (top=$barTop) at font scale 2.0 with a too-quiet banner showing (register " +
                "R-1061) — got an overlap of ${buttonBounds.bottom - barTop}",
            buttonBounds.bottom <= barTop,
        )
    }
}
