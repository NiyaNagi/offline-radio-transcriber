package org.ort.app.ui.navigation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.audio.FakeTransmissionAudioPlayer
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * AC-168 (register R-1006, build-plan P26): "navigating away from a transmission detail view
 * while its clip plays stops playback, and returning does not auto-resume" — restored at the
 * transport-bar/nav-host layer (`shouldStopPlaybackOnTransmissionLeave`'s own doc comment has the
 * decision and its rationale), not the per-screen layer C10 fixed it at originally and then
 * reversed (`TransmissionDetailScreen.kt`'s own doc comment on [org.ort.app.ui.screens
 * .PlaybackSection]).
 *
 * Composes the real [OrtNavHost] — the exact host AC-168 asks this to be fixed at, not a stand-in
 * — seeded directly onto the transmission drill-in (`NavSeed.openTransmissionId`), with a real
 * session/transmission row and a real (placeholder) audio file on disk so `TransmissionDetail
 * .hasAudio` is genuinely `true` (`ReaderPolling.kt`'s own `audioFile.isFile` check). Playback
 * itself goes through [FakeTransmissionAudioPlayer] — injected via [OrtNavHost]'s own
 * `transportAudioPlayer` test seam — never a real `AudioTrack` decode
 * (`TransportBarHostLayoutTest.kt`'s own doc comment: Robolectric's `AudioTrack` shadow does not
 * faithfully reproduce real hardware, so no test in this codebase drives that real path; this one
 * does not either).
 *
 * Discrimination (constitution II): with `NavHostBody`'s own `DisposableEffect` commented out,
 * [FakeTransmissionAudioPlayer.stopCallCount] reads `0` after the back tap — the exact "the bar's
 * current 'survives navigation' logic" AC-168's own build-plan text names; restored, it reads `1`.
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackStopsOnNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun seedTransmission(sessionId: String, transmissionId: String): Unit = runBlocking {
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

    @Test
    fun `AC_168 closing the transmission drill-in for real stops the shared player`() {
        val sessionId = "ac168-session"
        val transmissionId = "ac168-tx"
        seedTransmission(sessionId, transmissionId)
        val player = FakeTransmissionAudioPlayer()

        composeTestRule.setContent {
            OrtTheme {
                OrtNavHost(
                    sessionId = null,
                    seed = NavSeed(openTransmissionId = transmissionId),
                    transportAudioPlayer = player,
                )
            }
        }

        composeTestRule.waitUntil(15_000) {
            composeTestRule.onAllNodesWithContentDescription("Play retained audio")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription("Play retained audio").performClick()
        composeTestRule.waitForIdle()
        assertEquals("setup: expected play($transmissionId)", listOf(transmissionId), player.playCalls)
        assertEquals("setup: nothing has left the screen yet", 0, player.stopCallCount)

        // AC-168: the real back tap this screen's own `DrillInHeader` exposes — a genuine leave,
        // not a poll tick or a pause/resume.
        composeTestRule.onNodeWithTag("drill-in-header-back").performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            "AC-168: leaving the transmission's own detail screen for real must stop the shared player",
            1,
            player.stopCallCount,
        )
    }
}
