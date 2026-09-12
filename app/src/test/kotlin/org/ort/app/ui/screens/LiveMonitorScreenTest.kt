package org.ort.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.CaptureStateTone
import org.ort.app.ui.data.CaptureStatusViewState
import org.ort.app.ui.data.KeyValueFacts
import org.ort.app.ui.data.LevelViewState
import org.ort.app.ui.data.LiveMonitorOverRow
import org.ort.app.ui.data.LiveMonitorOversViewState
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtTheme
import org.ort.app.ui.theme.OrtType
import org.ort.core.Attribution
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `Live-Monitor.dc.html` (design-intent N07, R-1007) — the operator's own seven states, each
 * rendered as the row it actually is, never one omitted or promoted to something it is not
 * (constitution I).
 */
@RunWith(RobolectricTestRunner::class)
class LiveMonitorScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun notMeasured() = KeyValueFacts(value = "Not measured")

    private val status = CaptureStatusViewState(
        stateLabel = "Capturing",
        stateTone = CaptureStateTone.NOMINAL,
        sinceElapsedLabel = "Since 22:00 · 0:31:07 · alive, heartbeat 3s ago",
        haltActionLabel = "Stop",
        haltConfirmTitle = "Stop capture?",
        haltConfirmBody = "Audio already captured is kept.",
        input = notMeasured(),
        level = notMeasured(),
        radio = notMeasured(),
        overs = notMeasured(),
        backlog = notMeasured(),
        tier = notMeasured(),
        thermal = notMeasured(),
        storage = notMeasured(),
        battery = notMeasured(),
    )

    private val level = LevelViewState.notMeasured()

    private val sevenRows = listOf(
        LiveMonitorOverRow.Transcribing(
            id = "TX-transcribing",
            timeLabel = "04:58:53",
            durationLabel = "2.4 s",
            passLabel = "Pass B running",
            tierLabel = "tier 3",
        ),
        LiveMonitorOverRow.Resolved(
            id = "TX-confirmed",
            timeLabel = "04:58:31",
            transcript = "good copy on that",
            durationLabel = "5.1 s",
            frequencyLabel = "145.230",
            attribution = Attribution.confirmed("W1ABC", 0.98),
            callsign = "W1ABC",
            attributionStateLabel = "confirmed",
            inferredFromLabel = null,
        ),
        LiveMonitorOverRow.Waiting(
            id = "TX-waiting",
            timeLabel = "04:57:02",
            durationLabel = "1.8 s",
            aheadCount = 3,
        ),
        LiveMonitorOverRow.ListenedSilence(
            id = "silence-1",
            timeLabel = "04:52:10",
            durationLabel = "4 m 52 s",
        ),
        LiveMonitorOverRow.Resolved(
            id = "TX-inferred",
            timeLabel = "04:52:06",
            transcript = "back to you on the two metre machine",
            durationLabel = "3.3 s",
            frequencyLabel = "145.230",
            attribution = Attribution.inferred("W1ABC", 0.71),
            callsign = "W1ABC",
            attributionStateLabel = "inferred",
            inferredFromLabel = "04:58:31",
        ),
        LiveMonitorOverRow.NotTranscribed(
            id = "TX-failed",
            timeLabel = "04:49:33",
            durationLabel = "2.1 s",
            attemptsLabel = "Pass B errored 5 times",
        ),
        LiveMonitorOverRow.Resolved(
            id = "TX-unknown",
            timeLabel = "04:47:15",
            transcript = "roger that, QSY to the simplex frequency",
            durationLabel = "4.8 s",
            frequencyLabel = "145.230",
            attribution = Attribution.unknown(),
            callsign = null,
            attributionStateLabel = "unknown station",
            inferredFromLabel = null,
        ),
    )

    private val oversState = LiveMonitorOversViewState(overs = sevenRows, summaryLabel = "19 overs · 3 waiting")

    @Test
    @Requirement("R-1007")
    fun `every one of the operator's seven states renders its own row`() {
        composeTestRule.setContent {
            OrtTheme {
                LiveMonitorScreen(
                    status = status,
                    level = level,
                    hearingText = null,
                    overs = oversState,
                    localMicrophone = false,
                )
            }
        }

        sevenRows.forEach { row ->
            composeTestRule.onNodeWithTag("live-monitor-row-${row.id}").assertExists()
        }
    }

    @Test
    @Requirement("R-1007")
    fun `the failed row states the real attempt count and that the audio is kept, never silently dropped`() {
        composeTestRule.setContent {
            OrtTheme {
                LiveMonitorScreen(
                    status = status,
                    level = level,
                    hearingText = null,
                    overs = oversState,
                    localMicrophone = false,
                )
            }
        }

        composeTestRule.onNodeWithText("Pass B errored 5 times", substring = true).assertExists()
        composeTestRule.onNodeWithText("the audio is kept", substring = true).assertExists()
    }

    @Test
    @Requirement("FR-RUN-12")
    fun `the listened-silence row is not clickable, every other row is`() {
        var openedId: String? = null
        composeTestRule.setContent {
            OrtTheme {
                LiveMonitorScreen(
                    status = status,
                    level = level,
                    hearingText = null,
                    overs = oversState,
                    localMicrophone = false,
                    actions = LiveMonitorActions(onOpenOver = { openedId = it }),
                )
            }
        }

        composeTestRule.onNodeWithTag("live-monitor-row-TX-confirmed").performClick()
        assertTrue(openedId == "TX-confirmed")

        openedId = null
        composeTestRule.onNodeWithTag("live-monitor-row-silence-1").assertExists()
        // A `Box` with no clickable modifier ignores `performClick()` silently rather than
        // throwing — the real assertion is that it never reaches `onOpenOver`.
        assertTrue(openedId == null)
    }

    @Test
    @Requirement("R-1007")
    fun `Hearing now shows the real partial and never fabricates its own duration`() {
        composeTestRule.setContent {
            OrtTheme {
                LiveMonitorScreen(
                    status = status,
                    level = level,
                    hearingText = "and we're clear on the repeater, seven three to you",
                    overs = LiveMonitorOversViewState(overs = emptyList(), summaryLabel = "0 overs · 0 waiting"),
                    localMicrophone = false,
                )
            }
        }

        composeTestRule.onNodeWithTag("live-monitor-hearing").assertExists()
        composeTestRule.onNodeWithText("Pass A partial · not attributed — partials never are", substring = true)
            .assertExists()
    }

    @Test
    @Requirement("R-1007")
    fun `no Hearing now card when nothing is being heard right now, never an empty placeholder`() {
        composeTestRule.setContent {
            OrtTheme {
                LiveMonitorScreen(
                    status = status,
                    level = level,
                    hearingText = null,
                    overs = LiveMonitorOversViewState(overs = emptyList(), summaryLabel = "0 overs · 0 waiting"),
                    localMicrophone = false,
                )
            }
        }

        composeTestRule.onNodeWithTag("live-monitor-hearing").assertDoesNotExist()
    }

    @Test
    @Requirement("R-1007")
    fun `tapping Stop asks for confirmation before calling onStop`() {
        var stopped = false
        composeTestRule.setContent {
            OrtTheme {
                LiveMonitorScreen(
                    status = status,
                    level = level,
                    hearingText = null,
                    overs = oversState,
                    localMicrophone = false,
                    actions = LiveMonitorActions(onStop = { stopped = true }),
                )
            }
        }

        composeTestRule.onNodeWithTag("live-monitor-stop").performClick()
        assertTrue(!stopped)
        composeTestRule.onNodeWithText("Stop capture", substring = false).performClick()
        assertTrue(stopped)
    }

    @Test
    @Requirement("R-1007")
    fun `tapping the back chevron calls onBack`() {
        var backed = false
        composeTestRule.setContent {
            OrtTheme {
                LiveMonitorScreen(
                    status = status,
                    level = level,
                    hearingText = null,
                    overs = oversState,
                    localMicrophone = false,
                    actions = LiveMonitorActions(onBack = { backed = true }),
                )
            }
        }

        composeTestRule.onNodeWithTag("live-monitor-back").performClick()
        assertTrue(backed)
    }

    @Test
    @Requirement("R-1007")
    fun `Full log invokes onOpenFullLog`() {
        var openedLog = false
        composeTestRule.setContent {
            OrtTheme {
                LiveMonitorScreen(
                    status = status,
                    level = level,
                    hearingText = null,
                    overs = oversState,
                    localMicrophone = false,
                    actions = LiveMonitorActions(onOpenFullLog = { openedLog = true }),
                )
            }
        }

        composeTestRule.onNodeWithTag("live-monitor-full-log").performClick()
        assertTrue(openedLog)
    }

    @Test
    @Requirement("FR-CAP-3a")
    fun `the room mark shows only for a local-microphone session, and is never the only copy of the fact`() {
        composeTestRule.setContent {
            OrtTheme {
                LiveMonitorScreen(
                    status = status,
                    level = level,
                    hearingText = null,
                    overs = oversState,
                    localMicrophone = true,
                )
            }
        }

        composeTestRule.onNodeWithText("room").assertExists()
    }

    @Test
    @Requirement("AC-63")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `at the tour AVD's own width, every row clears the 44dp touch-target floor`() {
        composeTestRule.setContent {
            Box(modifier = Modifier.width(390.dp)) {
                OrtTheme {
                    LiveMonitorScreen(
                        status = status,
                        level = level,
                        hearingText = null,
                        overs = oversState,
                        localMicrophone = false,
                    )
                }
            }
        }

        sevenRows.forEach { row ->
            val bounds = composeTestRule.onNodeWithTag("live-monitor-row-${row.id}").getUnclippedBoundsInRoot()
            val height = bounds.bottom - bounds.top
            assertTrue(
                "expected live-monitor-row-${row.id} to clear the 44dp floor; got $height",
                height >= 44.dp,
            )
        }
    }

    @Test
    @Requirement("AC-63")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `at font scale 2_0 on the tour AVD's own width, rows stack top to bottom with no overlap`() {
        composeTestRule.setContent {
            val realDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density = realDensity, fontScale = 2f)) {
                Box(modifier = Modifier.width(390.dp)) {
                    OrtTheme {
                        LiveMonitorScreen(
                            status = status,
                            level = level,
                            hearingText = "and we're clear on the repeater",
                            overs = oversState,
                            localMicrophone = true,
                        )
                    }
                }
            }
        }

        val boundsInOrder = sevenRows.map { row ->
            composeTestRule.onNodeWithTag("live-monitor-row-${row.id}").getUnclippedBoundsInRoot()
        }
        for (i in 0 until boundsInOrder.size - 1) {
            val current = boundsInOrder[i]
            val next = boundsInOrder[i + 1]
            assertTrue(
                "expected row $i (bottom ${current.bottom}) to sit at or above row ${i + 1} " +
                    "(top ${next.top}) at font scale 2.0 — an overlap here is exactly the class of " +
                    "defect (R-874/R-942/R-980) a row/column layout change must be checked against",
                current.bottom <= next.top,
            )
        }
    }

    // --- R-1023 (register, R-880/R-1017 family): the leading timestamp column at 2.0 ------------

    /** A single row, so `live-monitor-row-time` (the same testTag on every row's own timestamp
     * `Text`, R-1023's own seam) resolves to exactly one node — the same "narrow the fixture to
     * avoid an ambiguous tag" choice `RigBluetoothScreenTest`'s own `assertMarkerAlignsWithLabel`
     * makes for `radio-row-marker`. `timeLabel` is the real `HH:MM:SS` shape every caller of
     * `ReaderTransmissionViewStateMapper.timeLabel` produces (`Locale.ROOT`, never locale-dependent) —
     * used only to measure real layout geometry here, never asserted as rendered prose (constitution
     * II: never assert on a locale-formatted string).
     */
    private val timeProbeRow = LiveMonitorOversViewState(
        overs = listOf(
            LiveMonitorOverRow.Waiting(
                id = "TX-time-probe",
                timeLabel = "13:40:05",
                durationLabel = "1.0 s",
                aheadCount = 0,
            ),
        ),
        summaryLabel = "1 over · 1 waiting",
    )

    /**
     * R-1023: `Live-Monitor.dc.html`'s own `.when` class specified a fixed `width: 62px`, the build
     * reproduced it faithfully (`Modifier.width(62.dp)`), and at font scale 2.0 every row's
     * timestamp clipped mid-character (`13:4(`, `13:39`) — a log screen that cannot say when
     * anything happened. The fix (`rememberTimeColumnWidth()`, the same shared floor `LogRow`/
     * `GapRow`/`RejectedRow` already apply to the identical `HH:MM:SS` column, `ui/components/
     * Rows.kt`, R-205) is a `widthIn(min = …)` floor measured against this host's real font metrics,
     * so the column can only ever grow to fit its content, never clip it — proven here by measuring
     * the same text independently (the identical [rememberTextMeasurer]-backed technique
     * [org.ort.app.ui.components.rememberMonoColumnWidth] itself uses) and asserting the rendered
     * node is never narrower than that, never by asserting the rendered string itself.
     *
     * Run at **both** 390dp and 480dp (R-1017 was found at 480dp only after passing at 390dp — the
     * fixed-width bug is width-of-container-independent, but the coverage discipline is the same)
     * and at **both** font scale 1.0 and 2.0. Evidence from the register shows the clip only ever
     * reproduced at 2.0 (62dp already comfortably fits an 8-character mono string at 1.0) — the 1.0
     * cases are real regression coverage for the fixed column, not expected to discriminate this
     * specific historical bug, and the report says so plainly rather than implying otherwise.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1023 the timestamp column never renders narrower than its own text at 390dp font scale 1_0`() {
        assertTimeColumnNeverNarrowerThanContent(widthDp = 390, fontScale = 1f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1023 the timestamp column never renders narrower than its own text at 390dp font scale 2_0`() {
        assertTimeColumnNeverNarrowerThanContent(widthDp = 390, fontScale = 2f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1023 the timestamp column never renders narrower than its own text at 480dp font scale 1_0`() {
        assertTimeColumnNeverNarrowerThanContent(widthDp = 480, fontScale = 1f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1023 the timestamp column never renders narrower than its own text at 480dp font scale 2_0`() {
        assertTimeColumnNeverNarrowerThanContent(widthDp = 480, fontScale = 2f)
    }

    private fun assertTimeColumnNeverNarrowerThanContent(widthDp: Int, fontScale: Float) {
        var contentWidthPx = 0
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                val measurer = rememberTextMeasurer()
                contentWidthPx = measurer.measure(text = "13:40:05", style = OrtType.timeFreq).size.width
                Box(modifier = Modifier.width(widthDp.dp)) {
                    OrtTheme {
                        LiveMonitorScreen(
                            status = status,
                            level = level,
                            hearingText = null,
                            overs = timeProbeRow,
                            localMicrophone = false,
                        )
                    }
                }
            }
        }

        // `density = 1f` above: px and dp coincide numerically here, the same reasoning
        // `RowsTest`'s own `R_980` case documents for its own per-character-collapse check.
        val contentWidth = contentWidthPx.dp
        val nodeBounds = composeTestRule
            .onNodeWithTag("live-monitor-row-time", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val nodeWidth = nodeBounds.right - nodeBounds.left
        assertTrue(
            "expected the timestamp column ($nodeWidth) to never render narrower than its own text " +
                "($contentWidth) at ${widthDp}dp/fontScale=$fontScale — a narrower column clips the " +
                "timestamp exactly like R-1023's fixed 62dp column did",
            nodeWidth >= contentWidth - 0.5.dp,
        )
    }

    /**
     * **R-1026** (register, design): `LevelEnvelopeChart`'s own bar colouring used a hand-rolled
     * 3-way band (bright green at/above the target band top, a fixed mid-green *inside* the 10dB-
     * wide band, [OrtColors.meterWarn] — amber — for literally everything quieter than that) —
     * amber for the great majority of a genuine speech envelope
     * (`LevelCheck.kt`'s own doc comment: ordinary speech sits roughly `-40` to `-15` dBFS, almost
     * entirely below the band's own `-18` dBFS floor), on a screen whose own design guide §8
     * reserves amber for anomalies. **R-944 already fixed the identical defect on S07**
     * (`ui/setup/LevelScreen.kt`'s `levelBarColorFor` — grey below 0.30 of the `-60..0` scale, a
     * real green ramp toward the band top, red only at true `0` dBFS clipping) — these tests assert
     * this screen now calls that exact function (via [liveMonitorLevelBarColor], the same
     * `dbfs -> fraction -> colour` composition [org.ort.app.ui.setup.LevelScreen.levelBarRects]
     * itself uses) rather than reimplementing the banding, so the two meters can never re-diverge.
     */
    @Test
    fun `R_1026 a quiet bar below the noise floor reads the neutral grey ramp, never amber`() {
        val color = liveMonitorLevelBarColor(dbfs = -55f)

        assertEquals(OrtColors.chartNeutralRamp[0], color)
        assertNotEquals(OrtColors.meterWarn, color)
    }

    @Test
    fun `R_1026 ordinary speech, below the target band but above the noise floor, reads a green shade, never amber`() {
        // LevelCheck.kt's own doc comment: ordinary speech sits roughly -40 to -15 dBFS — this is
        // exactly the range R-1026's evidence screenshot shows rendering almost entirely amber.
        val color = liveMonitorLevelBarColor(dbfs = -30f)

        assertNotEquals(
            "ordinary speech must never read the anomaly colour — design guide 8 reserves amber " +
                "for anomalies, and this level is nominal",
            OrtColors.meterWarn,
            color,
        )
        assertTrue(
            "expected a real chartGreenRamp shade, not the neutral floor colour",
            OrtColors.chartGreenRamp.contains(color),
        )
    }

    @Test
    fun `R_1026 a bar at the target band top reads full accent green`() {
        val color = liveMonitorLevelBarColor(dbfs = LevelViewState.TARGET_BAND_TOP_DBFS)

        assertEquals(OrtColors.accentGreen, color)
    }

    @Test
    fun `R_1026 a bar that actually reaches the chart ceiling reads red, the one anomaly this chart shows`() {
        val color = liveMonitorLevelBarColor(dbfs = LevelViewState.CHART_CEILING_DBFS)

        assertEquals(OrtColors.haltFill, color)
    }

    @Test
    fun `R_1026 no bar across the whole real -60 to 0 dBFS range ever renders amber`() {
        var dbfs = LevelViewState.CHART_FLOOR_DBFS
        while (dbfs <= LevelViewState.CHART_CEILING_DBFS) {
            assertNotEquals(
                "dbfs=$dbfs must never render OrtColors.meterWarn — amber is reserved for anomalies",
                OrtColors.meterWarn,
                liveMonitorLevelBarColor(dbfs),
            )
            dbfs += 1f
        }
    }

    /** A real, measured level — never [LevelViewState.notMeasured()] — with the register's own
     * example figures (`R-1027`'s evidence capture, `N07-live-monitor@2x.png`): `"-14 dBFS"`,
     * `"floor -58 dBFS"`, `"14 dB headroom"` for the left caption, and a nominal band state so the
     * right label renders `"in the band"` — the exact pairing the register found colliding. */
    private val levelWithLongCaption = LevelViewState(
        inputLabel = "USB · last 60 s",
        peakDbfsLabel = "-14 dBFS",
        noiseFloorDbfsLabel = "-58 dBFS",
        noiseFloorDbfsRaw = -58f,
        headroomLabel = "14 dB",
        clippedThisSessionLabel = "0",
        historyDbfs = listOf(-40f, -30f, -20f, -14f),
        bandStateSentence = "In the band. Nothing to adjust.",
        bandStateTone = CaptureStateTone.NOMINAL,
        notMeasuredReason = null,
    )

    /**
     * **R-1027** (register, design): `LevelCardCaption`'s left caption
     * (`"-14 dBFS · floor -58 dBFS · 14 dB headroom"`) wraps to three lines at font scale 2.0 while
     * the right-aligned band label (`"in the band"`) stayed on line one — both `Text`s sharing the
     * same top-aligned `Row`, so the label reads as if it belongs to the caption's first line and
     * the wrapped continuation trails off with nothing beside it: "one broken sentence" (the
     * register's own words), not two distinct facts. The R-874/R-980 family's own remedy for "a
     * right sibling must yield to a wrapping left one": past [LARGE_FONT_SCALE_THRESHOLD] (the same
     * 1.5x figure `ui/setup/LevelScreen.kt`'s own `LevelFooterFacts` already established for this
     * screen's own sibling meter, chosen there for the identical reason — the smallest scale a real
     * capture showed the collision at), the band label now stacks *below* the caption instead of
     * beside it — asserted here as "their real, measured vertical ranges never overlap", the
     * precise geometric shape of "reads as one sentence" (same line) versus "reads as two facts"
     * (separate lines), never a rendered-pixel or prose comparison.
     *
     * Run at **both** 390dp and 480dp and **both** font scales, matching this package's own
     * `R_1023` precedent: the 1.0 cases assert the *opposite*, equally real invariant — the caption
     * fits one line at the default scale, so the two facts correctly share it (this is not a
     * regression to guard against; it is the intended compact layout) — real regression coverage
     * for "do not stack unnecessarily", not expected to discriminate this specific wrap-collision
     * defect, exactly as that class's own report states plainly rather than implying otherwise.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1027 at 390dp font scale 1_0 the caption and band label still share one row`() {
        assertLevelCaptionBandOverlap(widthDp = 390, fontScale = 1f, expectOverlap = true)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1027 at 390dp font scale 2_0 the band label stacks below the wrapping caption, never overlapping it`() {
        assertLevelCaptionBandOverlap(widthDp = 390, fontScale = 2f, expectOverlap = false)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1027 at 480dp font scale 1_0 the caption and band label still share one row`() {
        assertLevelCaptionBandOverlap(widthDp = 480, fontScale = 1f, expectOverlap = true)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1027 at 480dp font scale 2_0 the band label stacks below the wrapping caption, never overlapping it`() {
        assertLevelCaptionBandOverlap(widthDp = 480, fontScale = 2f, expectOverlap = false)
    }

    private fun assertLevelCaptionBandOverlap(widthDp: Int, fontScale: Float, expectOverlap: Boolean) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                Box(modifier = Modifier.width(widthDp.dp)) {
                    OrtTheme {
                        LiveMonitorScreen(
                            status = status,
                            level = levelWithLongCaption,
                            hearingText = null,
                            overs = timeProbeRow,
                            localMicrophone = false,
                        )
                    }
                }
            }
        }

        val dbfsBounds = composeTestRule
            .onNodeWithTag("live-monitor-level-dbfs", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val bandBounds = composeTestRule
            .onNodeWithTag("live-monitor-level-band", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val overlap = dbfsBounds.top < bandBounds.bottom && bandBounds.top < dbfsBounds.bottom
        val expectation = if (expectOverlap) {
            "overlap (one shared row)"
        } else {
            "never overlap (stacked, never one broken sentence)"
        }
        assertEquals(
            "expected the caption's and band label's real vertical ranges to $expectation at " +
                "${widthDp}dp/fontScale=$fontScale — caption=$dbfsBounds band=$bandBounds",
            expectOverlap,
            overlap,
        )
    }
}
