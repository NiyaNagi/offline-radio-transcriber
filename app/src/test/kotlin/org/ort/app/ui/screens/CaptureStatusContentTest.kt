package org.ort.app.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.components.LOADING_STATE_TEST_TAG
import org.ort.app.ui.settings.SharedPreferencesSettingsStore
import org.ort.app.ui.theme.OrtTheme
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.pipeline.capture.ArchiveWriteRateForecast
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.pipeline.capture.VadAvailability
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import java.io.RandomAccessFile

/**
 * The Capture destination's polling wrapper (ui-conformance-plan WP4, R-039, design-intent N08,
 * `Capture.dc.html`). Proves the merged surface against real process-wide holders and a real
 * (file-backed) `OrtDatabase`, the same pattern `TransmissionDetailContentTest` (WP6) already uses.
 *
 * N08/WPCAP: this class used to prove [CaptureStatusContent]'s own internal N04→N06/N07
 * sub-navigation — that navigation no longer exists (the merged [CaptureScreen] renders the level
 * envelope and this session's overs inline, always, never behind a tap), so the cases that proved
 * it are replaced with cases proving the equivalent facts are visible on the one surface without
 * navigating anywhere. [CaptureStatusScreenTest]/[LevelMeterScreenTest]/[LiveMonitorScreenTest]
 * keep proving their own composables directly — those files are untouched.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureStatusContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    @After
    fun resetProcessWideAvailability() {
        AsrAvailability.reset()
        VadAvailability.reset()
        CaptureState.idle(clearSession = true)
        ShedStatus.reset()
        ThermalStatus.reset()
        RigStatus.reset()
        StorageForecast.reset()
        InputStatus.reset()
        LevelStatus.reset()
        ArchiveWriteRateForecast.reset()
        settingsStore().audioBudgetGb = null
        settingsStore().archiveEnabled = true
    }

    private fun settingsStore() = SharedPreferencesSettingsStore(
        context.getSharedPreferences(SharedPreferencesSettingsStore.PREFS_NAME, android.content.Context.MODE_PRIVATE),
    )

    private fun ComposeContentTestRule.waitUntilTagExists(tag: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeContentTestRule.waitUntilTextExists(text: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis) {
            onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun session(id: String = "S1") = SessionEntity(
        id = id,
        startedAt = 0L,
        endedAt = null,
        profileId = null,
        deviceTier = null,
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
    )

    private fun transmission(id: String, sessionId: String, samplePosition: Long) = TransmissionEntity(
        id = id,
        sessionId = sessionId,
        threadId = null,
        startedAtUtc = samplePosition,
        endedAtUtc = samplePosition + 1_000L,
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
        processingState = TransmissionState.CAPTURED,
        rejectionReason = null,
        samplePosition = samplePosition,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    @Test
    @Requirement("FR-UI-7", "N08")
    fun `N08 the level envelope is inline and visible without opening anything`() {
        runBlocking { db.sessionDao().insert(session()) }
        CaptureState.capturing("S1")
        LevelStatus.update(
            LevelStatus.State.Measured(
                peakDbfs = -14f,
                rmsDbfs = -20f,
                noiseFloorDbfs = -58f,
                clipped = false,
                clipCountLastSecond = 0,
                sampleRateHz = 16_000,
                updatedAtMillis = 0L,
            ),
            peakHistoryDbfs = List(10) { -20f },
        )

        composeTestRule.setContent { OrtTheme { CaptureStatusContent(context = context, sessionId = "S1") } }

        composeTestRule.waitUntilTagExists("live-monitor-level-chart")
        composeTestRule.onNodeWithTag("live-monitor-level-chart").assertExists()
        composeTestRule.onNodeWithTag("capture-title").assertExists()
    }

    @Test
    @Requirement("FR-UI-7", "FR-RUN-12", "N08")
    fun `N08 this session's overs are inline and visible without opening anything`() {
        runBlocking {
            db.sessionDao().insert(session("S1"))
            db.transmissionDao().insert(transmission("S1-tx1", "S1", 1L))
        }
        CaptureState.capturing("S1")

        composeTestRule.setContent { OrtTheme { CaptureStatusContent(context = context, sessionId = "S1") } }

        composeTestRule.waitUntilTagExists("live-monitor-row-S1-tx1")
        composeTestRule.onNodeWithTag("live-monitor-row-S1-tx1").assertExists()
    }

    @Test
    @Requirement("R-172")
    fun `R_172 follows CaptureState's live session, never a stale host-supplied sessionId, while capturing`() {
        // S1 is the host's own (stale) sessionId argument — say, the session the reader launched
        // against — and S2 is the session actually capturing right now (CaptureState.sessionId),
        // e.g. a fresh Start-capture tap or a scenario broadcast after this reader was already
        // open (R-171). The screen must show S2's own facts throughout, never a mix of S1's DB rows
        // and S2's live holders.
        runBlocking {
            db.sessionDao().insert(session("S1"))
            db.transmissionDao().insert(transmission("S1-tx1", "S1", 1L))
            db.sessionDao().insert(session("S2"))
            db.transmissionDao().insert(transmission("S2-tx1", "S2", 1L))
            db.transmissionDao().insert(transmission("S2-tx2", "S2", 2L))
        }
        CaptureState.capturing("S2")

        composeTestRule.setContent { OrtTheme { CaptureStatusContent(context = context, sessionId = "S1") } }

        composeTestRule.waitUntilTextExists("2 overs")
        composeTestRule.onNodeWithText("2 overs", substring = true).assertExists()
        composeTestRule.onNodeWithText("1 over ", substring = true).assertDoesNotExist()
    }

    @Test
    @Requirement("R-172")
    fun `R_172 a null host sessionId still polls once a session starts capturing, not stuck idle forever`() {
        runBlocking {
            db.sessionDao().insert(session("S2"))
            db.transmissionDao().insert(transmission("S2-tx1", "S2", 1L))
        }
        CaptureState.capturing("S2")

        composeTestRule.setContent { OrtTheme { CaptureStatusContent(context = context, sessionId = null) } }

        composeTestRule.waitUntilTagExists("live-monitor-row-S2-tx1")
        composeTestRule.onNodeWithTag("live-monitor-row-S2-tx1").assertExists()
    }

    @Test
    @Requirement("R-232")
    fun `R_232 the title respects a top-padded modifier the way the host's banner-height inset relies on`() {
        // R-232: `OrtNavHost.kt`'s `NavHostBody` (WP3's file) already applies the host's real,
        // measured banner height as top padding to the *outer* Box every destination's content sits
        // inside — `DestinationContent`'s `ReaderDestination.CAPTURE` branch calls this composable
        // with that already-padded space, unchanged, the same as every other destination (confirmed
        // by reading `OrtNavHost.kt` before writing this test: the padding is applied once, at
        // `NavHostBody`, never per-destination). So closing R-232 needs no code change in this
        // package's own files — `CaptureStatusContent`/`CaptureScreen` take whatever `modifier`
        // they are given and were never the ones dropping it. This test proves that contract holds:
        // a caller-supplied top inset (standing in for the host's real `contentTopPadding`) is
        // respected, not silently reset by an internal `fillMaxSize()` that ignores its parent.
        val insetDp = 64.dp
        runBlocking { db.sessionDao().insert(session()) }
        CaptureState.capturing("S1")

        composeTestRule.setContent {
            OrtTheme {
                CaptureStatusContent(context = context, sessionId = "S1", modifier = Modifier.padding(top = insetDp))
            }
        }

        composeTestRule.waitUntilTagExists("capture-title")
        val density = composeTestRule.density
        val insetPx = with(density) { insetDp.toPx() }
        val titleTop = composeTestRule.onNodeWithTag("capture-title").fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "expected the title to sit at or below the caller's top inset (${insetPx}px), was ${titleTop}px",
            titleTop >= insetPx - 1f, // sub-pixel rounding tolerance
        )
    }

    // checklist row E2-G01 (N04's Input/Radio sub-lines).
    @Test
    @Requirement("FR-CAP-13")
    fun `FR_CAP_13 the Input sub-line names the session's own mode and route from the v7 columns`() {
        runBlocking {
            db.sessionDao().insert(
                session("BT-1").copy(
                    captureMode = "BLUETOOTH_RADIO",
                    audioRouteKind = "BLUETOOTH_SCO",
                    audioRouteLabel = "Handheld BT",
                    rigTransport = "BLUETOOTH_SPP",
                ),
            )
        }
        CaptureState.capturing("BT-1")
        InputStatus.opened(
            descriptor = AudioDeviceDescriptor("bt-1", AudioDeviceKind.BLUETOOTH, "Handheld BT"),
            nativeRateHz = 16_000,
            resamplerId = "none",
            routeVerified = true,
            routedDeviceMatches = true,
            openedAtMillis = 0L,
        )
        RigStatus.connected("TH-D75A", listOf(RigStatus.BandState("A", 145_230_000L, null, squelchOpen = true)))

        composeTestRule.setContent { OrtTheme { CaptureStatusContent(context = context, sessionId = "BT-1") } }

        composeTestRule.waitUntilTextExists("Bluetooth audio")
        composeTestRule.onNodeWithText("Bluetooth audio", substring = true).assertExists()
        composeTestRule.onNodeWithText("Bluetooth-connected radio", substring = true).assertExists()
        composeTestRule.onNodeWithText("Bluetooth SPP", substring = true).assertExists()
    }

    /**
     * Register R-1051 (halt, constitution I/IV): before this fix, this composable's own initial
     * `remember` value was `idleCaptureStatus()` directly — a real "Not capturing" claim — so a
     * session that is genuinely live from the very first frame (this test's own
     * `CaptureState.capturing`, set before composition) still read "Not capturing" until the first
     * poll landed. `mainClock.autoAdvance = false` freezes recomposition before `setContent`
     * returns, so the first assertion below inspects the composed tree before the `LaunchedEffect`
     * poll can ever be observed. Reverting the fix (seeding `idleCaptureStatus()` again) makes this
     * fail with "Not capturing" visible in that first frame instead of the loading marker.
     */
    @Test
    @Requirement("R-1051")
    fun `R_1051 first frame is loading, never Not capturing, for an already-live session`() {
        runBlocking {
            db.sessionDao().insert(session("LIVE-1"))
            db.transmissionDao().insert(transmission("LIVE-1-tx1", "LIVE-1", 1L))
        }
        CaptureState.capturing("LIVE-1")

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent { OrtTheme { CaptureStatusContent(context = context, sessionId = "LIVE-1") } }

        composeTestRule.onNodeWithTag(LOADING_STATE_TEST_TAG).assertExists()
        composeTestRule.onNodeWithText("Not capturing", substring = true).assertDoesNotExist()

        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitUntilTagExists("capture-title")
        composeTestRule.onNodeWithTag(LOADING_STATE_TEST_TAG).assertDoesNotExist()
    }

    @Test
    @Requirement("D40", "FR-STO-3e", "AC-160", "N08")
    fun `N08 the over-audio warning is visible without a tap once the real budget is exceeded`() {
        runBlocking { db.sessionDao().insert(session()) }
        CaptureState.capturing("S1")
        settingsStore().audioBudgetGb = 1
        val audioDir = java.io.File(context.filesDir, "audio/S1")
        audioDir.mkdirs()
        RandomAccessFile(java.io.File(audioDir, "over.flac"), "rw").use { it.setLength(1_100_000_000L) }

        composeTestRule.setContent { OrtTheme { CaptureStatusContent(context = context, sessionId = "S1") } }

        composeTestRule.waitUntilTextExists("Over budget")
        composeTestRule.onNodeWithText("Over budget", substring = true).assertExists()
    }

    @Test
    @Requirement("D39", "FR-STO-3f", "AC-158", "AC-159", "N08")
    fun `N08 the archive disclosure states its real on-off state beside the control that changes it`() {
        runBlocking { db.sessionDao().insert(session()) }
        CaptureState.capturing("S1")

        composeTestRule.setContent { OrtTheme { CaptureStatusContent(context = context, sessionId = "S1") } }

        composeTestRule.waitUntilTagExists(CAPTURE_ARCHIVE_TOGGLE_TEST_TAG)
        composeTestRule.onNodeWithTag(CAPTURE_ARCHIVE_TOGGLE_TEST_TAG).assertExists()
        // AC-158/159: the rate — measured or, before anything is measured, D39's estimate,
        // visibly labelled as one — sits on the exact same line as the on/off state, beside the
        // control that changes it, never on a second screen. [CaptureScreenTest] proves the toggle
        // itself flips the rendered on/off state directly, with no polling cadence in the way; this
        // test's own job is only that the real, polled facts reach the screen at all.
        val rateConfig = composeTestRule.onNodeWithTag(CAPTURE_ARCHIVE_RATE_TEST_TAG).fetchSemanticsNode().config
        val rateText = rateConfig.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        assertTrue(rateText.contains("estimated"))
        assertTrue(rateText.startsWith("on"))
    }
}
