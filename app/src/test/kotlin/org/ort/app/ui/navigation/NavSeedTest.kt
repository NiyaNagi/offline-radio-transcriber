package org.ort.app.ui.navigation

import android.content.Context
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.debug.Scenarios
import org.ort.app.ui.data.FrequencyDetailView
import org.ort.app.ui.data.LogFilterSelection
import org.ort.app.ui.screens.CorrectionTierStep
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

    // R-1140 (register, coordinator diagnosis): every one of this helper's own five new failures
    // traced to `hasText`'s own default exact/case-sensitive match against text this app composes
    // as a *substring* of a longer sentence (`FailureReasonLine`'s own "$failed failed — $reason")
    // or *uppercased* (`SectionHeader`'s own `label.uppercase()`) — never to the wait itself timing
    // out on real work, and never to a screen genuinely being unreachable. `substring`/`ignoreCase`
    // default `false` so every existing passing call keeps its own exact-match behaviour unchanged.
    private fun ComposeContentTestRule.waitUntilTextExists(
        text: String,
        timeoutMillis: Long = 15_000,
        substring: Boolean = false,
        ignoreCase: Boolean = false,
    ) {
        waitUntil(timeoutMillis) {
            onAllNodes(hasText(text, substring = substring, ignoreCase = ignoreCase)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ComposeContentTestRule.waitUntilTagExists(tag: String, timeoutMillis: Long = 15_000) {
        waitUntil(timeoutMillis) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
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

    // R-1117 (register): the real, shipped product affordance — before this, `AttributionMarker`/
    // `AttributionRow`/`TitleAttributionRow` carried no `onClick` at all, so no marker anywhere
    // (this one included) opened the screen that explains the callsign; the only way in was the
    // small "Full lattice" text action further down the same screen. This seeds nothing beyond the
    // ordinary drill-in — the click itself is what proves the fix, not a `NavSeed` field.
    @Test
    fun `R_1117 tapping the title attribution marker opens Detail-Why`() {
        composeTestRule.setContent {
            OrtTheme { OrtNavHost(sessionId = SESSION_ID, seed = NavSeed(openTransmissionId = TRANSMISSION_ID)) }
        }
        composeTestRule.waitUntilContentDescriptionExists("Back to Log")
        composeTestRule.onNodeWithContentDescription("Confirmed", substring = true).performClick()
        // R-1140 (register): `DetailWhyScreen`'s real subtitle is the full sentence below, not
        // this prefix alone — `hasText`'s own default exact match never found the shorter string
        // (the five-test diagnosis this row records); `substring = true` matches it as written.
        composeTestRule.waitUntilTextExists("Everything the resolver saw", substring = true)
    }

    // R-1117/R-1127 (register): D05 (`Detail-Why.dc.html`) had no seed of its own at all before
    // this — the tour could only reach it by simulating the real "Full lattice" tap.
    @Test
    fun `openTransmissionId with openTransmissionWhy lands directly on Detail-Why`() {
        composeTestRule.setContent {
            OrtTheme {
                OrtNavHost(
                    sessionId = SESSION_ID,
                    seed = NavSeed(openTransmissionId = TRANSMISSION_ID, openTransmissionWhy = true),
                )
            }
        }
        // `DetailWhyScreen`'s own subtitle — present only on the exhaustive screen, never on
        // `TransmissionDetailScreen`'s own inline "Why this callsign" preview, which shares that
        // section's own header label but not this sentence. R-1140: `substring = true` — see
        // `R_1117`'s own case above for why the bare prefix never exact-matched.
        composeTestRule.waitUntilTextExists("Everything the resolver saw", substring = true)
    }

    // R-056/R-1127 (register): the labelled-sample form is interaction-only local state with no
    // seed of its own before this.
    @Test
    fun `openTransmissionId with openTransmissionLabel lands with the labelled-sample form already open`() {
        composeTestRule.setContent {
            OrtTheme {
                OrtNavHost(
                    sessionId = SESSION_ID,
                    seed = NavSeed(openTransmissionId = TRANSMISSION_ID, openTransmissionLabel = true),
                )
            }
        }
        composeTestRule.waitUntilTextExists("Labelled callsign")
    }

    // R-052/R-1127 (register): D08-D11 the correction sheets — genuinely interaction-only, no
    // destination of their own, so each tier needs its own direct seed.
    @Test
    fun `openTransmissionId with correctionStep MAIN lands directly on the correction sheet`() {
        composeTestRule.setContent {
            OrtTheme {
                OrtNavHost(
                    sessionId = SESSION_ID,
                    seed = NavSeed(openTransmissionId = TRANSMISSION_ID, correctionStep = CorrectionTierStep.MAIN),
                )
            }
        }
        composeTestRule.waitUntilTextExists("Who was it?")
    }

    @Test
    fun `openTransmissionId with correctionStep SEARCH lands directly on Tier B`() {
        composeTestRule.setContent {
            OrtTheme {
                OrtNavHost(
                    sessionId = SESSION_ID,
                    seed = NavSeed(openTransmissionId = TRANSMISSION_ID, correctionStep = CorrectionTierStep.SEARCH),
                )
            }
        }
        // R-1140: `SearchTier`'s own header is a `SectionHeader`, which renders `label.uppercase()`
        // ("A STATION HEARD BEFORE") — `ignoreCase = true` matches it as rendered; `hasText`'s own
        // default exact/case-sensitive match never did.
        composeTestRule.waitUntilTextExists("A station heard before", ignoreCase = true)
    }

    @Test
    fun `openTransmissionId with correctionStep TYPE lands directly on Tier C`() {
        composeTestRule.setContent {
            OrtTheme {
                OrtNavHost(
                    sessionId = SESSION_ID,
                    seed = NavSeed(openTransmissionId = TRANSMISSION_ID, correctionStep = CorrectionTierStep.TYPE),
                )
            }
        }
        // R-1140: `TypeTier`'s own header is a `SectionHeader` too — same fix as Tier B above.
        composeTestRule.waitUntilTextExists("Type a callsign", ignoreCase = true)
    }

    // R-350/R-1127 (register): `ImprovePage` is unseeded local state — R04 could only ever be
    // reached by driving a real reprocess run to completion.
    @Test
    fun `improveDonePreview lands on Improve-Done with a real-shaped summary`() {
        composeTestRule.setContent {
            OrtTheme { OrtNavHost(sessionId = null, seed = NavSeed(improveDonePreview = true)) }
        }
        composeTestRule.waitUntilTextExists("All groups")
        // The one failure reason and its "Install" action (R-350's own fix) — proves this is a
        // real-shaped summary, not merely `ImprovePage.Done` with a null one. R-1140: real text is
        // `FailureReasonLine`'s own "1 failed — No transcription model installed", never this
        // humanised reason alone — `substring = true` matches it as composed.
        composeTestRule.waitUntilTextExists("No transcription model installed", substring = true)
    }

    @Test
    fun `openTransmissionId with openTransmissionRevisions lands directly on Earlier versions`() {
        composeTestRule.setContent {
            OrtTheme {
                OrtNavHost(
                    sessionId = SESSION_ID,
                    seed = NavSeed(openTransmissionId = TRANSMISSION_ID, openTransmissionRevisions = true),
                )
            }
        }
        // `DetailRevisionsScreen`'s own title — the fact that distinguishes landing directly on
        // `Revisions` from the plain detail root (both share the same `DrillInHeader` parent label).
        composeTestRule.waitUntilTextExists("Earlier versions")
    }

    // IA-6 (information-architecture review, approved — WPNAV): the transmission's own attributed
    // station, one tap away — every other drill-in reachable from a transmission already had a
    // way in; the station it was actually attributed to did not. `TX1` is `CONFIRMED` to
    // `STATION_ID` (this class's own fixture), so the link is real, not merely rendered.
    @Test
    fun `IA_6 View station on a transmission opens its real attributed station`() {
        composeTestRule.setContent {
            OrtTheme { OrtNavHost(sessionId = SESSION_ID, seed = NavSeed(openTransmissionId = TRANSMISSION_ID)) }
        }
        composeTestRule.waitUntilContentDescriptionExists("Back to Log")

        composeTestRule.onNodeWithText("View station").performScrollTo().performClick()

        // `StationScreen.kt`'s own "Attribution, N confirmed · N inferred · N corrected" fact row —
        // present only on the station's own detail screen, never the transmission's (whose header
        // already shows the same callsign text regardless, so that alone would not prove real
        // navigation happened). Not a second, stacked drill-in on top of the transmission's own
        // (`onOpenAttributedStation` closes it first, the same shape `onOpenActivationThread`
        // already uses).
        composeTestRule.waitUntilContentDescriptionExists("Attribution,")
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
    fun `openCaptureLevelMeter lands on the Capture surface's own level content`() {
        composeTestRule.setContent {
            OrtTheme { OrtNavHost(sessionId = SESSION_ID, seed = NavSeed(openCaptureLevelMeter = true)) }
        }
        // N08/WPCAP (coordinator round): `openLevelMeter` no longer opens a separate
        // `LevelMeterScreen` sub-screen with its own `DrillInHeader(parentLabel = "Capture", ...)`
        // back label (that composable is superseded, no longer reachable) — the merged
        // `CaptureScreen` (design-intent `Capture.dc.html`) always shows the level content inline,
        // so landing on `CAPTURE` at all already satisfies this seed. `live-monitor-level-not
        // -measured` is the level card's own honest tag when `LevelStatus` (never seeded here) has
        // nothing measured yet — the real replacement for the old "Back to Capture" marker.
        composeTestRule.waitUntilTagExists("capture-title")
        composeTestRule.onNodeWithTag("live-monitor-level-not-measured").assertExists()
    }

    @Test
    fun `pendingReviewSessionId lands on the reviewed session detail directly`() {
        composeTestRule.setContent {
            OrtTheme { OrtNavHost(sessionId = SESSION_ID, seed = NavSeed(pendingReviewSessionId = SESSION_ID)) }
        }
        // R-1070 (register, polish): `SessionDetailScreen`'s own `DrillInHeader(parentLabel =
        // "Recordings", ...)` default — `SessionsContent`'s only real caller today (this seeded
        // `pendingReviewSessionId` path) never opened this screen via its own internal `List`, so
        // it takes the "Recordings" default, not DG03's own superseded "Earlier nights".
        composeTestRule.waitUntilContentDescriptionExists("Back to Recordings")
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
        // R-1043: this test asserted immediately after `setContent` with no wait at all — the one
        // case in this file not built on this class's own `waitUntil...Exists` discipline every
        // other case here already uses. `assertExists()` needs the node in the semantics tree *now*;
        // under a loaded full gate, `setContent`'s own internal idle wait can return before the
        // filters sheet's own composition has actually landed, and a bare assertion has no recourse
        // but to fail outright (`ComposeTimeoutException`/`AssertionError`, never a legitimate "the
        // sheet is not open"). Polling for the tag first is the same fix this file already applies
        // to every other seed.
        composeTestRule.waitUntilTagExists("search-filters-sheet")
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
