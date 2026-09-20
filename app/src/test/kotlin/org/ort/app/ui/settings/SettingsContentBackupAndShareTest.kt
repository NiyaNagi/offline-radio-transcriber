package org.ort.app.ui.settings

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * P30 (FR-EXP-3, FR-EXP-7, FR-STO-6, FR-STO-9). [org.ort.app.export.ExportCoordinator
 * .buildPotaActivity]/[org.ort.app.export.ShareCoordinator]/[org.ort.app.backup.BackupBundleBuilder]
 * all existed and were unit-tested in isolation but had no caller anywhere before this unit —
 * these tests are what makes each one reachable, following
 * [SettingsContentExportAndDebugDumpTest]'s own idiom exactly: assert the real launched [Intent],
 * never a toast's wording.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsContentBackupAndShareTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun clearShareUriResolver() {
        shareUriResolverForTest = null
    }

    private fun seedOneOverWithAudio(context: android.content.Context) = runBlocking {
        val db = OrtDatabase.create(context)
        db.sessionDao().insert(
            SessionEntity(
                id = "S1", startedAt = 0L, endedAt = null, profileId = null, deviceTier = null,
                appVersion = "test", terminationReason = null, sourceId = null,
                schemaVersion = OrtDatabase.SCHEMA_VERSION,
            ),
        )
        val transmission = TransmissionEntity(
            id = "T1", sessionId = "S1", threadId = null, startedAtUtc = 1_000L, endedAtUtc = 2_000L,
            durationMs = 1_000L, audioFormat = "flac/16k/mono", preRollMs = 200, postRollMs = 200,
            frequencyHz = 146_520_000L, frequencyProvenance = "measured", mode = "FM", signalStrength = null,
            channelName = null, voiceprintId = null, attributionState = AttributionState.CONFIRMED,
            stationId = "KI7ABC", attributionConfidence = 0.9, attributionSourceTransmissionId = null,
            processingState = TransmissionState.COMPLETE, rejectionReason = null, samplePosition = 0L,
            monotonicStartNanos = 0L, utcOffsetMinutes = 0, calibrationId = null, executionProvider = null,
        )
        db.transmissionDao().insert(transmission)
        db.transcriptDao().insert(
            TranscriptEntity(
                id = "TR1", transmissionId = "T1", pass = TranscriptPass.B, text = "this is KI7ABC",
                modelId = "m", modelVersion = "1", quantization = null, decodeParams = null,
                noSpeechProb = null, confidence = null, isCurrent = true, createdAt = 0L,
            ),
        )
        val audioFile = java.io.File(context.filesDir, transmission.audioPath())
        audioFile.parentFile?.mkdirs()
        audioFile.writeBytes(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `AC_169 Export POTA activity launches a real SAF CreateDocument intent naming a real csv file`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalSettingsSaveIoDispatcher provides Dispatchers.Unconfined) {
                OrtTheme {
                    SettingsContent(
                        context = composeTestRule.activity,
                        onDrawer = {},
                        initialScreen = SettingsScreenId.EXPORT,
                    )
                }
            }
        }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Export POTA activity", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("export-screen-scroll")
            .performScrollToNode(hasTestTag("export-pota-button"))
        composeTestRule.onNodeWithTag("export-pota-button").performClick()

        val started = shadowOf(composeTestRule.activity).nextStartedActivityForResult
        assertNotNull("expected Export POTA activity to actually launch a SAF picker intent", started)
        val intent = started.intent
        assertTrue(Intent.ACTION_CREATE_DOCUMENT == intent.action)
        assertTrue("text/csv" == intent.type)
        val title = intent.getStringExtra(Intent.EXTRA_TITLE)
        assertNotNull(title)
        assertTrue(
            "expected the real ExportCoordinator.suggestedPotaFileName shape, got '$title'",
            title!!.startsWith("ort-pota-tonight-") && title.endsWith(".csv"),
        )
    }

    @Test
    fun `AC_171 Share the most recent over's audio launches a real ACTION_SEND intent through the FileProvider`() {
        seedOneOverWithAudio(composeTestRule.activity)
        // The fake FileProvider-backed target build-plan P30 itself asks this unit to ship — see
        // [shareUriResolverForTest]'s own kdoc for why the *real* FileProvider cannot be exercised
        // reliably under this Windows/Robolectric combination, and why this still proves the real
        // assertions that matter.
        shareUriResolverForTest = { _, authority, file -> android.net.Uri.parse("content://$authority/${file.name}") }
        composeTestRule.setContent {
            CompositionLocalProvider(LocalSettingsSaveIoDispatcher provides Dispatchers.Unconfined) {
                OrtTheme {
                    SettingsContent(
                        context = composeTestRule.activity,
                        onDrawer = {},
                        initialScreen = SettingsScreenId.EXPORT,
                    )
                }
            }
        }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Share the most recent over's audio", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("export-screen-scroll")
            .performScrollToNode(hasTestTag("share-audio-button"))
        composeTestRule.onNodeWithTag("share-audio-button").performClick()
        // The real share build hops onto `Dispatchers.IO` inside `ShareCoordinator` itself (never
        // overridden by [LocalSettingsSaveIoDispatcher], which only reaches this file's own outer
        // hop) — `waitForIdle()` does not track that off-composition work, since it writes no
        // Compose state before calling `startActivity`; poll the real shadow instead. `peekNextStartedActivity`
        // (never popping the queue) so the later, real read below still sees it.
        composeTestRule.waitUntil(5_000) { shadowOf(composeTestRule.activity).peekNextStartedActivity() != null }

        val started = shadowOf(composeTestRule.activity).nextStartedActivity
        assertNotNull("expected the share tap to actually start a chooser activity", started)
        // ACTION_SEND is wrapped inside the ACTION_CHOOSER intent this app builds with
        // Intent.createChooser — the real content Uri and its authority live on that inner intent.
        val sendIntent = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull("expected the chooser to wrap a real ACTION_SEND intent", sendIntent)
        assertEquals(Intent.ACTION_SEND, sendIntent!!.action)
        assertEquals("audio/flac", sendIntent.type)
        val uri = sendIntent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        assertNotNull("expected a real content Uri for the audio clip", uri)
        assertEquals("org.ort.app.fileprovider", uri!!.authority)
        assertTrue(
            "expected FLAG_GRANT_READ_URI_PERMISSION on the shared intent",
            sendIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
    }

    @Test
    fun `FR_STO_6 Save backup launches a real SAF CreateDocument intent naming a real zip file`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalSettingsSaveIoDispatcher provides Dispatchers.Unconfined) {
                OrtTheme {
                    SettingsContent(
                        context = composeTestRule.activity,
                        onDrawer = {},
                        initialScreen = SettingsScreenId.BACKUP,
                    )
                }
            }
        }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Save backup", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("backup-screen-scroll").performScrollToNode(hasTestTag("backup-save-button"))
        composeTestRule.onNodeWithTag("backup-save-button").performClick()

        val started = shadowOf(composeTestRule.activity).nextStartedActivityForResult
        assertNotNull("expected Save backup to actually launch a SAF picker intent", started)
        val intent = started.intent
        assertTrue(Intent.ACTION_CREATE_DOCUMENT == intent.action)
        assertTrue("application/zip" == intent.type)
        val title = intent.getStringExtra(Intent.EXTRA_TITLE)
        assertNotNull(title)
        assertTrue(
            "expected the real BackupBundleBuilder.suggestedFileName shape, got '$title'",
            title!!.startsWith("ort-backup-") && title.endsWith(".zip"),
        )
    }

    @Test
    fun `FR_STO_9 Choose a backup file launches a real SAF OpenDocument intent`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalSettingsSaveIoDispatcher provides Dispatchers.Unconfined) {
                OrtTheme {
                    SettingsContent(
                        context = composeTestRule.activity,
                        onDrawer = {},
                        initialScreen = SettingsScreenId.BACKUP,
                    )
                }
            }
        }
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Choose a backup file", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("backup-screen-scroll").performScrollToNode(hasTestTag("restore-pick-button"))
        composeTestRule.onNodeWithTag("restore-pick-button").performClick()

        val started = shadowOf(composeTestRule.activity).nextStartedActivityForResult
        assertNotNull("expected Choose a backup file to actually launch a SAF picker intent", started)
        assertTrue(Intent.ACTION_OPEN_DOCUMENT == started.intent.action)
    }
}
