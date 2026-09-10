package org.ort.app.ui.screens

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.StationSubScreen
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TransmissionEntity
import org.robolectric.RobolectricTestRunner

/**
 * `StationDetailContent`'s own real read path (R-272, register, halt, V5 pass 2 @8d1456f) — proves
 * the whole loop against a real (file-backed) [OrtDatabase], the same pattern
 * `TransmissionDetailContentTest` already uses, since the halt this closes (`SplitSubScreen`
 * treating "still fetching" and "genuinely nothing to split" as the same `null`) only reproduces
 * against a real `StationPolling.voiceSplitCandidates` call, not a screen-level fixture.
 */
@RunWith(RobolectricTestRunner::class)
class StationDetailContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

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

    // Poison-hunt-2 (register, full-suite gate): this class's own `OrtDatabase.create(context)`
    // was never closed — a leaked writer against `ort.db` for the rest of this Gradle test-worker
    // JVM to contend against, the same shape `CorrectionPollingTest`'s own doc comment already
    // established and `TransmissionDetailContentTest`/`NowContentTest`/`CaptureStatusContentTest`
    // already fixed the same way. Closed here so this class stops being one of the never-closed
    // instances the jstack-confirmed `ThreadDetailScreenTest` wedge traced back to.
    @After
    fun closeDatabase() {
        db.close()
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

    // The real WA7HJR-shaped bug: a station heard by callsign (CONFIRMED) but with no voiceprint
    // cluster ever bound — exactly the "single cluster, nothing to split" case `voiceSplitCandidates`
    // honestly returns `null` for, before and after this fix.
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

    private fun ComposeContentTestRule.waitUntilTextExists(text: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `R_272 the split screen resolves to a real empty state within a bounded time, never a bare Loading forever`() {
        runBlocking {
            db.sessionDao().insert(session())
            db.catalogDao().insert(station())
            db.transmissionDao().insert(transmission())
        }

        composeTestRule.setContent {
            StationDetailContent(context = context, stationId = "WA7HJR", onBack = {})
        }

        composeTestRule.waitUntilTextExists("WA7HJR")
        composeTestRule.onNodeWithTag("station-identity-open").performClick()
        composeTestRule.waitUntilTextExists("Split")
        composeTestRule.onNodeWithTag("station-identity-split").performClick()

        // The bounded wait is the test itself: `voiceSplitCandidates` returning `null` used to be
        // indistinguishable from "still fetching", so this text never arrived — the screen sat on
        // a bare "Loading…" forever. Resolving within a real timeout, to the honest empty state
        // (not a crash, not a second "Loading…"), is exactly what R-272 requires.
        composeTestRule.waitUntilTextExists("One cluster, nothing to split")
        composeTestRule.onNodeWithText("One cluster, nothing to split").assertExists()
    }

    @Test
    fun `R_TOUR_station_sub_screen initialSubScreen PATTERN opens directly on Station-Pattern (ST03)`() {
        runBlocking {
            db.sessionDao().insert(session())
            db.catalogDao().insert(station())
            db.transmissionDao().insert(transmission())
        }

        composeTestRule.setContent {
            StationDetailContent(
                context = context,
                stationId = "WA7HJR",
                onBack = {},
                initialSubScreen = StationSubScreen.PATTERN,
            )
        }

        // `Station-Pattern.dc.html`'s own screen title — it has no equivalent on the drill-in's
        // root (`StationDetailScreen` never renders this sentence), so its presence proves `sub`
        // was seeded from `initialSubScreen`, never defaulted back to `NONE` — the WP12 tour's own
        // ST03 capture depends on exactly this, without a real tap through Overview first.
        composeTestRule.waitUntilTextExists("When they are around")
    }
}
