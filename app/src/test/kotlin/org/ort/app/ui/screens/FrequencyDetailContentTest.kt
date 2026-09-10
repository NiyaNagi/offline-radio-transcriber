package org.ort.app.ui.screens

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.ort.app.testing.ortComposeTestRule
import org.ort.app.ui.data.FrequencyDetailView
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.robolectric.RobolectricTestRunner

/**
 * `FrequencyDetailContent`'s own real read path (R-276, register, spec, coordinator round
 * 2026-09-08) — proves [FrequencyDetailContent]'s `initialView` parameter lands the operator
 * directly on `Frequency-Change` when reopened from the Log (R-276's "The N overs" round trip via
 * [org.ort.app.ui.data.TimeWindow]/`onOpenOvers`), never back on the drill-in's own root, against
 * a real (file-backed) [OrtDatabase] — the same pattern `StationDetailContentTest` already uses.
 *
 * idle-root task (2026-09-10): this class is CI run 34444706036's own confirmed poisoner — its
 * lone test PASSED immediately before *every* test in `LogFilterSheetTest`/`LogScreenTest` (which
 * touch no `Context` or database at all) failed with `AppNotIdleException`, in that exact JVM
 * fork order — the first time either "poison hunt" session (CHANGELOG's cb1d8cd, 7ac846b) had a
 * real, reproducible ordering to point at rather than a jstack signature alone. Root cause found
 * and fixed structurally: this class's own `@After fun closeDatabase()` used to close [OrtDatabase]
 * *before* `composeTestRule`'s own teardown had disposed the composition — JUnit4 runs a bare
 * `@After` inside the `@Rule`-wrapped statement, so it always executes first — closing the file
 * out from under a still-live composition in that window. See
 * [org.ort.app.testing.ortComposeTestRule]'s own doc comment for the full mechanism; that function
 * is what this class now routes its teardown through. **Also still isolated into
 * `smokeTestDebugUnitTest`** (`app/build.gradle.kts`): a targeted local reproduction of the exact
 * `FrequencyDetailContentTest` → `LogFilterSheetTest` → `LogScreenTest` fork ordering passed both
 * with and without the ordering fix on this session's own many-core workstation, so the fix could
 * not be *proven* sufficient locally the way it was proven necessary from the CI log — the CI
 * machine's own 2 cores are plausibly what turns a race into a reliable failure. Isolation stays
 * as the certain fix for the reported failure; the ordering fix stays as a genuine, independently
 * justified correctness improvement (and applies to five sibling classes with the identical shape
 * — see `app/build.gradle.kts`'s own note on this).
 */
@RunWith(RobolectricTestRunner::class)
class FrequencyDetailContentTest {

    // Not itself a `@Rule` — `ruleChain` below owns its lifecycle, in the order
    // `ortComposeTestRule`'s own doc comment explains. Test methods still call
    // `composeTestRule.setContent { ... }` exactly as before.
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
        stationId = null,
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
    fun `R_276_back initialView Change opens directly on Frequency-Change, not the drill-in root`() {
        runBlocking {
            db.sessionDao().insert(session())
            db.transmissionDao().insert(transmission())
        }

        composeTestRule.setContent {
            FrequencyDetailContent(
                context = context,
                frequencyHz = 146_960_000L,
                onBack = {},
                initialView = FrequencyDetailView.Change,
            )
        }

        // `Frequency-Change`'s own closing paragraph opener — it has no equivalent on the
        // drill-in's root (`FrequencyDetailScreen` never renders this sentence), so its presence
        // proves `sub` was seeded from `initialView`, never defaulted back to `Detail`.
        composeTestRule.waitUntilTextExists("A departure is a finding, not an alarm.")
    }
}
