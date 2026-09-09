package org.ort.app.ui.navigation

import android.content.Context
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.debug.Scenarios
import org.ort.app.ui.data.FrequencyDetailView
import org.ort.app.ui.data.LogFilterSelection
import org.ort.app.ui.settings.SettingsScreenId
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TransmissionEntity
import org.robolectric.RobolectricTestRunner

/**
 * Round 13 (the coordinator's own seam for WP12's screenshot tour): [NavSeed] was built precisely
 * because every one of these fields was, before this, reachable only by a real tap through the
 * destination that owns it — this class proves each field alone, seeded straight into
 * [OrtNavHost], lands on the right destination or drill-in without that tap. Deliberately the
 * lighter `createComposeRule()` composition [OrtNavHostDestinationDispatchTest] already
 * established for this package (one composition per case, no real `Activity`), not
 * `ReaderActivityDestinationSmokeTest`'s own real-`Activity` harness — that file's own new
 * `R_351_nav_seed_transmission_detail_shows_back_to_log` case is the one end-to-end confirmation
 * that a seed also survives the real `ReaderActivity`/`ScenarioReaderActivity` path this class
 * does not itself exercise.
 *
 * One shared session (`SESSION_ID`), one transmission (`TX1`, station `STATION_ID`, frequency
 * `FREQUENCY_HZ`, thread `THREAD_ID`) and one catalog station row — the same minimal shape
 * `ReaderActivityDestinationSmokeTest.seedSession` already established — real enough for every
 * drill-in and `Log`'s own filter to resolve to genuine data, not an empty/"Loading…" state a
 * `DrillInHeader` assertion could pass against by accident (`TransmissionDetailContent`'s own
 * `DrillInHeader` only renders once its real `detail` poll returns non-null — confirmed by reading
 * that file before relying on it).
 */
@RunWith(RobolectricTestRunner::class)
class NavSeedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun seedSession(): Unit = runBlocking {
        val db = OrtDatabase.create(context)
        db.sessionDao().insert(
            SessionEntity(
                id = SESSION_ID,
                startedAt = 0L,
                endedAt = 1_000L,
                profileId = null,
                deviceTier = null,
                appVersion = "test",
                terminationReason = null,
                sourceId = null,
                schemaVersion = OrtDatabase.SCHEMA_VERSION,
            ),
        )
        db.transmissionDao().insert(
            TransmissionEntity(
                id = TRANSMISSION_ID,
                sessionId = SESSION_ID,
                threadId = THREAD_ID,
                startedAtUtc = 0L,
                endedAtUtc = 1_000L,
                durationMs = 4_200L,
                audioFormat = "flac/16k/mono",
                preRollMs = 200,
                postRollMs = 200,
                frequencyHz = FREQUENCY_HZ,
                frequencyProvenance = "measured",
                mode = null,
                signalStrength = 7.0,
                channelName = null,
                voiceprintId = null,
                attributionState = AttributionState.CONFIRMED,
                stationId = STATION_ID,
                attributionConfidence = 0.9,
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
        db.catalogDao().insert(
            StationEntity(
                id = STATION_ID,
                callsign = STATION_ID,
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
            ),
        )
    }

    private fun ComposeContentTestRule.waitUntilContentDescriptionExists(
        substring: String,
        timeoutMillis: Long = 15_000,
    ) {
        waitUntil(timeoutMillis) {
            onAllNodes(hasContentDescription(substring, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ComposeContentTestRule.waitUntilTextExists(text: String, timeoutMillis: Long = 15_000) {
        waitUntil(timeoutMillis) { onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `openTransmissionId lands on the transmission detail drill-in`() {
        composeTestRule.setContent {
            OrtTheme { OrtNavHost(sessionId = SESSION_ID, seed = NavSeed(openTransmissionId = TRANSMISSION_ID)) }
        }
        // `NavSeed.openedFromDestination`: a transmission detail reached by seed reads "Back to
        // Log" — the same origin R-333's own real-tap case establishes.
        composeTestRule.waitUntilContentDescriptionExists("Back to Log")
    }

    @Test
    fun `openStationId lands on the station detail drill-in`() {
        composeTestRule.setContent {
            OrtTheme { OrtNavHost(sessionId = SESSION_ID, seed = NavSeed(openStationId = STATION_ID)) }
        }
        composeTestRule.waitUntilContentDescriptionExists("Back to Stations")
    }

    @Test
    fun `openFrequencyHz lands on the frequency detail drill-in`() {
        composeTestRule.setContent {
            OrtTheme { OrtNavHost(sessionId = SESSION_ID, seed = NavSeed(openFrequencyHz = FREQUENCY_HZ)) }
        }
        composeTestRule.waitUntilContentDescriptionExists("Back to Frequencies")
    }

    @Test
    fun `openFrequencyHz with frequencyInitialView Change lands on Frequency-Change directly`() {
        composeTestRule.setContent {
            OrtTheme {
                OrtNavHost(
                    sessionId = SESSION_ID,
                    seed = NavSeed(
                        openFrequencyHz = FREQUENCY_HZ,
                        frequencyInitialView = FrequencyDetailView.Change,
                    ),
                )
            }
        }
        // `FrequencyChangeScreen`'s own `DrillInHeader(parentLabel = state.label, ...)` — the
        // frequency's own label, not the plain detail's "Back to Frequencies" — the fact that
        // distinguishes landing directly on `Change` from landing on the detail root.
        composeTestRule.waitUntilContentDescriptionExists("Back to $FREQUENCY_LABEL")
    }

    @Test
    fun `openThreadId lands on the thread detail drill-in`() {
        composeTestRule.setContent {
            OrtTheme { OrtNavHost(sessionId = SESSION_ID, seed = NavSeed(openThreadId = THREAD_ID)) }
        }
        composeTestRule.waitUntilContentDescriptionExists("Back to Threads")
    }

    @Test
    fun `pendingLogFilter lands on Log with the frequency quick filter selected`() {
        composeTestRule.setContent {
            OrtTheme {
                OrtNavHost(
                    sessionId = SESSION_ID,
                    seed = NavSeed(pendingLogFilter = LogFilterSelection(frequencyHz = FREQUENCY_HZ)),
                )
            }
        }
        val matcher = hasText(FREQUENCY_LABEL, substring = true) and isSelected()
        composeTestRule.waitUntil(15_000) { composeTestRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `openCaptureLevelMeter lands on the level meter directly`() {
        composeTestRule.setContent {
            OrtTheme { OrtNavHost(sessionId = SESSION_ID, seed = NavSeed(openCaptureLevelMeter = true)) }
        }
        // `LevelMeterScreen`'s own `DrillInHeader(parentLabel = "Capture", ...)` — the same marker
        // R-132's own real-tap case (`Settings-Capture`'s `Meter` action) already establishes.
        composeTestRule.waitUntilContentDescriptionExists("Back to Capture")
    }

    @Test
    fun `pendingReviewSessionId lands on the reviewed session detail directly`() {
        composeTestRule.setContent {
            OrtTheme { OrtNavHost(sessionId = SESSION_ID, seed = NavSeed(pendingReviewSessionId = SESSION_ID)) }
        }
        // `SessionDetailScreen`'s own `DrillInHeader(parentLabel = "Earlier nights", ...)` — the
        // same marker R-133's own real Review-link case already establishes.
        composeTestRule.waitUntilContentDescriptionExists("Back to Earlier nights")
    }

    @Test
    fun `settingsScreen lands directly on that Settings sub-screen`() {
        composeTestRule.setContent {
            OrtTheme { OrtNavHost(sessionId = SESSION_ID, seed = NavSeed(settingsScreen = SettingsScreenId.STORAGE)) }
        }
        composeTestRule.waitUntilContentDescriptionExists("Back to Settings")
        // `SettingsStorageScreen`'s own title — proof this is genuinely the `Storage` sub-screen,
        // not merely any sub-screen reached by accident.
        composeTestRule.waitUntil(15_000) {
            composeTestRule.onAllNodes(hasText("Storage and retention")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `logSheetOpen lands on Log with the filter sheet already open`() {
        composeTestRule.setContent {
            OrtTheme { OrtNavHost(sessionId = SESSION_ID, seed = NavSeed(logSheetOpen = true)) }
        }
        // `LogFilterSheet`'s own title — the same marker `LogContentBackHandlerTest.kt`'s own
        // `R_TOUR_log_sheet_open` case (WP5's) already established.
        composeTestRule.waitUntilTextExists("Filter the log")
    }

    @Test
    fun `searchQuery with searchSubmit lands on Search with a real, submitted result`() {
        // The same `search-corpus` debug fixture WP7's own `SearchContentTest.kt` uses — 14 overs
        // across 3 nights, every transcript reading "...doing a park activation...", so "park"
        // matches all 14 — real background-thread Room I/O this test waits for directly below,
        // not tracked by `waitForIdle()`.
        runBlocking { Scenarios.load(context, "search-corpus") }
        composeTestRule.setContent {
            OrtTheme {
                OrtNavHost(sessionId = null, seed = NavSeed(searchQuery = "park", searchSubmit = true))
            }
        }
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodes(hasText("14 overs", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("search-count-line").assertExists()
    }

    @Test
    fun `searchFiltersOpen lands on Search with the filters sheet already open`() {
        composeTestRule.setContent {
            OrtTheme { OrtNavHost(sessionId = null, seed = NavSeed(searchFiltersOpen = true)) }
        }
        composeTestRule.onNodeWithTag("search-filters-sheet").assertExists()
    }

    @Test
    fun `a null seed changes nothing - OrtNavHost still opens unseeded`() {
        composeTestRule.setContent { OrtTheme { OrtNavHost(sessionId = null, seed = null) } }
        // `NOW`'s own `ScreenHeader` — exactly what `OrtNavHost(sessionId = null)` alone (no
        // `seed` argument at all) already renders, confirmed by every other case in
        // `OrtNavHostDestinationDispatchTest` — nothing here to distinguish from that.
        composeTestRule.waitUntilContentDescriptionExists("Open navigation")
    }

    private companion object {
        const val SESSION_ID = "nav-seed-session"
        const val TRANSMISSION_ID = "nav-seed-tx"
        const val STATION_ID = "K7NAVSEED"
        const val THREAD_ID = "nav-seed-thread"
        const val FREQUENCY_HZ = 146_960_000L
        const val FREQUENCY_LABEL = "146.960"
    }
}
