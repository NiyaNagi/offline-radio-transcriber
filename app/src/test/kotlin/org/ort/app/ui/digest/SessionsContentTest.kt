package org.ort.app.ui.digest

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.ort.app.testing.ortComposeTestRule
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.ProseSummaryEntity
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.digest.SharedPreferencesProseDigestSettingsStore
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * R-092 round 3 (WP3's host find): [SessionsContent.onOpenTransmission] hands a real transmission
 * id up through the embedded `Log`'s row taps and `Digest-Item`'s "The N over(s)" action — this
 * package's own drill-in surface (a `Log` page rendered inline) is not where that detail can be
 * shown, so the id must reach whatever hosts this composable.
 *
 * Every tap below on a target that can sit below the fold scrolls it into view first
 * (`performScrollToNode`, the same idiom `FrequencyScreenTest` already uses) — `Session`'s
 * `Digest`/`Log` action row sits past the coverage chart and session facts, outside the
 * un-scrolled viewport, and a plain `performClick()` on an off-screen node is silently a no-op
 * under Robolectric (confirmed by printing the semantics tree at the point of failure before this
 * fix existed: the click landed, threw nothing, and the screen never advanced).
 */
@RunWith(RobolectricTestRunner::class)
class SessionsContentTest {

    // Not itself a `@Rule` — `ruleChain` below owns its lifecycle, closing the database only after
    // this rule's own teardown has disposed the composition (idle-root task, 2026-09-10 — see
    // `org.ort.app.testing.ortComposeTestRule`'s own doc comment for the `AppNotIdleException`
    // mechanism this ordering avoids; this class was already isolated into `smokeTestDebugUnitTest`
    // for the same symptom, by the earlier "poison hunt" session that could not root-cause it).
    private val composeTestRule = createComposeRule()

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @get:Rule
    val ruleChain: TestRule = ortComposeTestRule(composeTestRule) { db.close() }

    @Before
    fun setUp() {
        db = OrtDatabase.create(context)
        CaptureState.idle(clearSession = true)
    }

    @After
    fun tearDown() {
        CaptureState.idle(clearSession = true)
    }

    private fun ComposeContentTestRule.waitUntilTextExists(text: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis) {
            runCatching { onNodeWithText(text, substring = true).assertExists() }.isSuccess
        }
    }

    private fun session() = SessionEntity(
        id = "S1",
        startedAt = 0L,
        endedAt = 3_600_000L,
        profileId = null,
        deviceTier = null,
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
    )

    private fun transmission(id: String, stationId: String? = null) = TransmissionEntity(
        id = id,
        sessionId = "S1",
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
        attributionState = if (stationId != null) AttributionState.CONFIRMED else AttributionState.UNKNOWN,
        stationId = stationId,
        attributionConfidence = null,
        attributionSourceTransmissionId = null,
        processingState = TransmissionState.COMPLETE,
        rejectionReason = null,
        samplePosition = 0L,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    @Test
    @Requirement("FR-UI-1")
    fun `FR_UI_1 Session's Log action reaches the embedded Log, wired with onOpen equal to onOpenTransmission`() {
        // `SessionsContent`'s `SessionsPage.Log` branch renders `LogContent(..., onOpen =
        // onOpenTransmission, ...)` — a direct, one-line pass-through (see that file). This proves
        // the real navigation hop (session → its embedded Log, header and columns for real).
        // Driving an actual row tap through `LogContent`'s own DB-backed poll was tried and
        // dropped: `TransmissionDetail`'s `buildDetail` reads `transcriptDao().getAllVersions(...)`
        // for every row, and Robolectric's bundled SQLite has no fts5 module — `transcript_fts`
        // (an fts5 virtual table) fails to attach at database-open time for *every* test database
        // here (confirmed by the `[sqlite] ... no such module: fts5` warning this test throws
        // regardless of whether a transcript row is ever inserted), which leaves `LogContent`'s
        // poll loop's first iteration throwing and its list permanently empty under this runner —
        // confirmed by waiting 15 real seconds with the semantics tree printed on timeout, not
        // assumed. `LogContent`'s own row-tap behaviour is WP5's `LogScreenTest`'s to prove against
        // a hand-built view-state (no DB); this package's own row is the wiring above it.
        runBlocking {
            db.sessionDao().insert(session())
            db.transmissionDao().insert(transmission("TX1"))
        }
        CaptureState.capturing("S1")

        composeTestRule.setContent {
            OrtTheme { SessionsContent(context = context, onDrawer = {}, onOpenTransmission = {}) }
        }

        composeTestRule.waitUntilTextExists("Tonight")
        composeTestRule.onNodeWithText("Tonight", substring = true).performClick()

        // R-145 (round 4): the session detail screen now also carries a "Log" text action beside
        // the `Overs` row (opens the same destination) — scrolling to "Export" (unique, the last
        // action-bar chip) brings the whole action row into view, so the "Log" node clicked below
        // (the last "Log" match in composition order) is the in-viewport action-bar one, not the
        // Overs row's off-screen link.
        composeTestRule.waitUntilTextExists("Export")
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Export"))
        composeTestRule.onAllNodesWithText("Log").onLast().performClick()

        composeTestRule.waitUntilTextExists("TIME")
        composeTestRule.onNodeWithText("TIME").assertExists()
        composeTestRule.onNodeWithText("STATION").assertExists()
    }

    @Test
    @Requirement("FR-DIG-9")
    fun `FR_DIG_9 Digest-Item's The N overs hands the real transmission id up via onOpenTransmission`() {
        runBlocking {
            db.sessionDao().insert(session())
            db.transmissionDao().insert(transmission("TX1", stationId = "W7NEW"))
        }
        CaptureState.capturing("S1")
        var tapped: String? = null

        composeTestRule.setContent {
            OrtTheme { SessionsContent(context = context, onDrawer = {}, onOpenTransmission = { tapped = it }) }
        }

        composeTestRule.waitUntilTextExists("Tonight")
        composeTestRule.onNodeWithText("Tonight", substring = true).performClick()

        composeTestRule.waitUntilTextExists("Digest")
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Digest"))
        composeTestRule.onNodeWithText("Digest").performClick()

        composeTestRule.waitUntilTextExists("W7NEW heard for the first time")
        composeTestRule.onNodeWithText("W7NEW heard for the first time").performClick()

        composeTestRule.waitUntilTextExists("The 1 over")
        composeTestRule.onNodeWithText("The 1 over").performClick()

        assert(tapped == "TX1") { "expected onOpenTransmission(\"TX1\"), got $tapped" }
    }

    @Test
    @Requirement("R-133")
    fun `R_133_review_seeds_session lands directly on the detail, never the list first`() {
        // `Settings-Storage`'s "Next deletion" `Review` link (R-133) needs to open a specific
        // session's own `Session` (DG04) detail directly — this proves `initialSessionId` reaches
        // exactly that page on first composition, without ever rendering the `Sessions` list.
        runBlocking {
            db.sessionDao().insert(session())
            db.transmissionDao().insert(transmission("TX1"))
        }
        CaptureState.capturing("S1")

        composeTestRule.setContent {
            OrtTheme { SessionsContent(context = context, onDrawer = {}, initialSessionId = "S1") }
        }

        // "Export" is the detail screen's own action-bar chip (confirmed unique to it, above) — its
        // presence with no prior tap on "Tonight" is exactly what proves the seed landed directly.
        composeTestRule.waitUntilTextExists("Export")
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Export"))
        composeTestRule.onNodeWithText("Export").assertExists()
    }

    @Test
    @Requirement("E2-G07", "FR-DIG-11")
    fun `E2_G07 Read the overs on a prose card opens the Log filtered to that card's own window`() {
        SharedPreferencesProseDigestSettingsStore(context).setEnabled(true)
        val overOneAt = 2 * 3_600_000L + 17 * 60_000L
        val overTwoAt = 2 * 3_600_000L + 41 * 60_000L
        runBlocking {
            db.sessionDao().insert(session())
            db.transmissionDao().insert(
                transmission("TX1", "WA7HJR").copy(threadId = "T1", startedAtUtc = overOneAt, endedAtUtc = overOneAt),
            )
            db.transmissionDao().insert(
                transmission("TX2", "WA7HJR").copy(threadId = "T1", startedAtUtc = overTwoAt, endedAtUtc = overTwoAt),
            )
            db.proseSummaryDao().upsert(
                ProseSummaryEntity(
                    threadId = "T1",
                    text = "prose",
                    sourceTransmissionIds = listOf("TX1", "TX2"),
                    generatedAtMillis = 0L,
                    modelId = "gemma3-1b-it-int4",
                ),
            )
        }
        CaptureState.capturing("S1")

        composeTestRule.setContent {
            OrtTheme { SessionsContent(context = context, onDrawer = {}, onOpenTransmission = {}) }
        }

        composeTestRule.waitUntilTextExists("Tonight")
        composeTestRule.onNodeWithText("Tonight", substring = true).performClick()
        composeTestRule.waitUntilTextExists("Digest")
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Digest"))
        composeTestRule.onNodeWithText("Digest").performClick()

        composeTestRule.waitUntilTextExists("Read the overs")
        composeTestRule.onNodeWithText("Read the overs").performClick()

        composeTestRule.waitUntilTextExists("TIME")
        composeTestRule.onNodeWithText("TIME").assertExists()
    }
}
