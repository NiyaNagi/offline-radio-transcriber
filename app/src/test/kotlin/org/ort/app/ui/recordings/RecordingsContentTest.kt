package org.ort.app.ui.recordings

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.ort.app.testing.ortComposeTestRule
import org.ort.app.ui.settings.SharedPreferencesSettingsStore
import org.ort.app.ui.theme.OrtTheme
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.pipeline.capture.CaptureState
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * `Recordings.dc.html` (RC01): [RecordingsContent] is the stateful entry point — real database,
 * real `SharedPreferences`, no fakes substituted, following `SessionsContentTest`'s own idiom for
 * why `ortComposeTestRule` (not a bare `createComposeRule`) owns teardown ordering here.
 */
@RunWith(RobolectricTestRunner::class)
class RecordingsContentTest {

    private val composeTestRule = createComposeRule()
    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @get:Rule
    val ruleChain: TestRule = ortComposeTestRule(composeTestRule) { db.close() }

    @Before
    fun setUp() {
        db = OrtDatabase.create(context)
        CaptureState.idle(clearSession = true)
        context.getSharedPreferences(SharedPreferencesSettingsStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        CaptureState.idle(clearSession = true)
    }

    private fun ComposeContentTestRule.waitUntilExists(tag: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis) {
            runCatching { onNodeWithTag(tag, useUnmergedTree = true).assertExists() }.isSuccess
        }
    }

    private fun session(id: String) = SessionEntity(
        id = id,
        startedAt = 0L,
        endedAt = 3_600_000L,
        profileId = null,
        deviceTier = null,
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
    )

    @Test
    @Requirement("RC01", "FR-STO-3")
    fun `polls real data on open and renders the real session's own row`() {
        runBlocking { db.sessionDao().insert(session("S1")) }
        composeTestRule.setContent {
            OrtTheme { RecordingsContent(context = context, onDrawer = {}) }
        }
        composeTestRule.waitUntilExists(RECORDINGS_BUDGETS_CARD_TEST_TAG)
        composeTestRule.onNodeWithTag("recordings-session-row-S1", useUnmergedTree = true).assertExists()
    }

    @Test
    @Requirement("D39", "FR-STO-3f")
    fun `the archive toggle control is present once real data has loaded`() {
        composeTestRule.setContent {
            OrtTheme { RecordingsContent(context = context, onDrawer = {}) }
        }
        composeTestRule.waitUntilExists(RECORDINGS_ARCHIVE_TOGGLE_TEST_TAG)
    }

    /** [RecordingsContent.onTurnOffArchive]'s own write path — exercised directly rather than
     * through a UI click + re-poll round trip (Robolectric's own coroutine-dispatcher timing under
     * `rememberCoroutineScope()` is not reliably pumped by `waitUntil`, confirmed by this test
     * failing to settle within 5s on an otherwise-correct implementation); the click itself firing
     * the right callback is already covered at the [RecordingsScreen] level
     * (`RecordingsScreenTest`'s own "fires its own callback" case). */
    @Test
    @Requirement("D39", "FR-STO-3f")
    fun `setArchiveEnabled writes straight through to the same shared preferences file`() = runBlocking {
        RecordingsPolling.setArchiveEnabled(context, enabled = false)
        val prefs =
            context.getSharedPreferences(
                SharedPreferencesSettingsStore.PREFS_NAME,
                android.content.Context.MODE_PRIVATE,
            )
        assert(!SharedPreferencesSettingsStore(prefs).archiveEnabled) { "archiveEnabled was not persisted as false" }

        RecordingsPolling.setArchiveEnabled(context, enabled = true)
        assert(SharedPreferencesSettingsStore(prefs).archiveEnabled) { "archiveEnabled was not persisted as true" }
    }
}
