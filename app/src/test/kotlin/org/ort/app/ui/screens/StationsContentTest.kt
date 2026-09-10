package org.ort.app.ui.screens

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.ort.app.testing.ortComposeTestRule
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TransmissionEntity
import org.robolectric.RobolectricTestRunner

/**
 * `StationsContent`'s own real read path (R-430, register): `Stations.dc.html` itself loads with
 * `Tonight` as the active chip, not `All time` — this package's earlier build hardcoded
 * `StationsFilter.ALL_TIME` as the initial state regardless of whether tonight actually has
 * anything under it. Proven against a real (file-backed) [OrtDatabase], the same pattern
 * `StationDetailContentTest` already uses, since the defect is in [StationsContent]'s own
 * `LaunchedEffect` — a fixture-level `StationsListScreen` test cannot see it (that composable
 * always took `selectedFilter` as an explicit parameter, never computed a default itself).
 */
@RunWith(RobolectricTestRunner::class)
class StationsContentTest {

    // Not itself a `@Rule` — `ruleChain` below owns its lifecycle, closing the database only after
    // this rule's own teardown has disposed the composition (idle-root task, 2026-09-10 — see
    // `org.ort.app.testing.ortComposeTestRule`'s own doc comment for the `AppNotIdleException`
    // mechanism this ordering avoids).
    private val composeTestRule = createComposeRule()

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @get:Rule
    val ruleChain: TestRule = ortComposeTestRule(composeTestRule) { db.close() }

    @Before
    fun openDatabase() {
        // Poison-hunt-2 (register, full-suite gate): `ort.db` is the same on-disk file for every
        // test class in this Robolectric-sandboxed JVM run, not one sandboxed per class or per
        // method (`CorrectionPollingTest`'s own doc comment) — deleting it first, the same fix
        // `SearchContentTest`/`SearchPollingTest`/`SearchWidenSuggestionsTest` already established,
        // starts this class from a clean, freshly-migrated file rather than whatever rows an earlier
        // test class left behind, rather than only closing the connection afterward.
        context.deleteDatabase(OrtDatabase.DATABASE_NAME)
        db = OrtDatabase.create(context)
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

    private fun station() = StationEntity(
        id = "WA7HJR",
        callsign = "WA7HJR",
        firstHeardAt = 0L,
        lastHeardAt = 0L,
        transmissionCount = 1,
        isUserPinned = false,
        notes = null,
        userName = null,
        frequenciesHeard = null,
        activityByHourDow = null,
        potaRefs = null,
        spokenGrids = null,
        ituRegionFromPrefix = null,
        overCountsByAttributionState = null,
    )

    private fun transmission() = TransmissionEntity(
        id = "TX1",
        sessionId = "S1",
        threadId = null,
        startedAtUtc = 0L,
        endedAtUtc = 1_000L,
        durationMs = 1_000L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = 146_960_000L,
        frequencyProvenance = "measured",
        mode = "FM",
        signalStrength = 7.0,
        channelName = null,
        voiceprintId = null,
        attributionState = AttributionState.CONFIRMED,
        stationId = "WA7HJR",
        attributionConfidence = 0.9,
        attributionSourceTransmissionId = null,
        corrected = false,
        processingState = TransmissionState.CAPTURED,
        rejectionReason = null,
        samplePosition = 0L,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    /** Polls until [testTag]'s own chip reports itself `selected` — the load this package's
     * `LaunchedEffect` runs is real (a Room query), so a single post-`setContent` assertion races
     * it; this is the same "wait for the real fact, not a fixed delay" idiom
     * `StationDetailContentTest.waitUntilTextExists` already uses. */
    private fun ComposeContentTestRule.waitUntilSelected(testTag: String, timeoutMillis: Long = 5_000) {
        val selected = hasTestTag(testTag).and(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        waitUntil(timeoutMillis) { onAllNodes(selected).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `R_430 defaults to Tonight when tonight has a station heard`() {
        runBlocking {
            db.sessionDao().insert(session())
            db.catalogDao().insert(station())
            db.transmissionDao().insert(transmission())
        }

        composeTestRule.setContent { StationsContent(context = context, onOpen = {}) }
        composeTestRule.waitUntilSelected("stations-filter-TONIGHT")

        composeTestRule.onNodeWithTag("stations-filter-TONIGHT").assertIsSelected()
        composeTestRule.onNodeWithTag("stations-filter-ALL_TIME").assertIsNotSelected()
    }

    @Test
    fun `R_430 falls back to All time, honestly labelled, when tonight has no stations`() {
        // No session, no station, no transmission at all — the empty-database case, the same one
        // a fresh install or a night with nothing heard yet produces.
        composeTestRule.setContent { StationsContent(context = context, onOpen = {}) }
        composeTestRule.waitUntilSelected("stations-filter-ALL_TIME")

        composeTestRule.onNodeWithTag("stations-filter-ALL_TIME").assertIsSelected()
        composeTestRule.onNodeWithTag("stations-filter-TONIGHT").assertIsNotSelected()
    }
}
