package org.ort.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * R-413 (register, halt, Reviewer D): a Band tile's mono frequency value used to wrap one
 * character per line down the whole screen — every `Tile` in the row asked for its own
 * `Modifier.fillMaxWidth()` inside a plain, unweighted `Row`; the first tile claimed the entire
 * row, squeezing the second into ~0px. R-444 (design): `Settings-Rig`'s own disconnected-state
 * copy leaked "FR-RIG's module contract (…)", a spec id, into operator prose.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRigScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun connectedState() = SettingsRigViewState(
        descriptorLabel = "TH-D75A",
        connected = true,
        staleSinceLabel = null,
        bands = listOf(
            SettingsRigBandViewState("A", "146.960", "FM · squelch open", squelchOpen = true),
            SettingsRigBandViewState("B", "146.960", "FM · closed", squelchOpen = false),
        ),
    )

    /** The real, rendered line count via the same `SemanticsActions.GetTextLayoutResult` action
     * TalkBack itself would use — `LogRowResponsiveTest.kt`'s own established idiom (that file's
     * own doc comment: a pixel-width guess this host's degenerate font metrics cannot be trusted
     * for) — for the [index]-th band tile's frequency `Text` specifically. */
    private fun frequencyLineCount(index: Int): Int {
        val node = composeTestRule
            .onAllNodesWithTag(BAND_TILE_FREQUENCY_TEST_TAG, useUnmergedTree = true)[index]
            .fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.singleOrNull()?.lineCount
            ?: error("no TextLayoutResult for band tile frequency #$index — is it a real Text node?")
    }

    @Test
    @Requirement("R-413")
    fun `R_413 both band tiles' frequency values render as one line at font scale 1-0`() {
        composeTestRule.setContent {
            OrtTheme { SettingsRigScreen(state = connectedState(), onBack = {}) }
        }

        composeTestRule.onAllNodesWithTag(BAND_TILE_TEST_TAG).assertCountEquals(2)
        assert(frequencyLineCount(0) == 1) { "expected band A's frequency as one line" }
        assert(frequencyLineCount(1) == 1) { "expected band B's frequency as one line" }
    }

    @Test
    @Requirement("R-413")
    fun `R_413 both band tiles' frequency values render as one line at font scale 2-0`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme { SettingsRigScreen(state = connectedState(), onBack = {}) }
            }
        }

        assert(frequencyLineCount(0) == 1) { "expected band A's frequency as one line at 2.0" }
        assert(frequencyLineCount(1) == 1) { "expected band B's frequency as one line at 2.0" }
    }

    @Test
    @Requirement("R-444")
    fun `R_444 the disconnected state never leaks the FR-RIG spec id into operator copy`() {
        val disconnected = SettingsRigViewState(
            descriptorLabel = "TH-D75A",
            connected = false,
            staleSinceLabel = null,
            bands = emptyList(),
        )
        composeTestRule.setContent {
            OrtTheme { SettingsRigScreen(state = disconnected, onBack = {}) }
        }

        composeTestRule.onNodeWithText("No rig configured", substring = true).assertExists()
        composeTestRule.onNodeWithText("FR-RIG", substring = true).assertDoesNotExist()
    }

    @Test
    @Requirement("FR-RIG-14")
    fun `R_839 the no-rig-configured state retires the old build-limitation copy for an honest, ordinary one`() {
        val disconnected = SettingsRigViewState(
            descriptorLabel = "TH-D75A",
            connected = false,
            staleSinceLabel = null,
            bands = emptyList(),
        )
        composeTestRule.setContent {
            OrtTheme { SettingsRigScreen(state = disconnected, onBack = {}) }
        }

        composeTestRule.onNodeWithText("No radio support in this build yet", substring = true).assertDoesNotExist()
        composeTestRule
            .onNodeWithText("No rig configured", substring = true)
            .assertExists()
        composeTestRule
            .onNodeWithText("Overs are logged against the frequency you set", substring = true)
            .assertExists()
    }

    @Test
    @Requirement("FR-RIG-14")
    fun `R_839 Change radio is real and live even while no rig has ever connected`() {
        var changed = false
        val disconnected = SettingsRigViewState(
            descriptorLabel = "TH-D75A",
            connected = false,
            staleSinceLabel = null,
            bands = emptyList(),
        )
        composeTestRule.setContent {
            OrtTheme { SettingsRigScreen(state = disconnected, onBack = {}, onSwitchTransport = { changed = true }) }
        }

        composeTestRule.onAllNodesWithText("Change radio")[0].performClick()
        assert(changed) { "expected Change radio to call onSwitchTransport even with nothing ever connected" }
    }

    // --- CF06 (`spec/e2e-capture-modes-plan.md` E2-F03, FR-RIG-14/15) — the Link/Reconnect rewrite ---

    @Test
    @Requirement("FR-RIG-14")
    fun `E2_F03 a connected rig names its transport in the subtitle`() {
        val state = connectedState().copy(transportLabel = "Bluetooth SPP")
        composeTestRule.setContent { OrtTheme { SettingsRigScreen(state = state, onBack = {}) } }

        composeTestRule.onNodeWithText("Connected · Bluetooth SPP").assertExists()
    }

    @Test
    @Requirement("FR-RIG-14")
    fun `E2_F03 the Link row names the address when known, and honestly says so when it is not`() {
        val known = connectedState().copy(transportLabel = "Bluetooth SPP", linkAddressLabel = "D8:3A:DD:41:0C:7F")
        composeTestRule.setContent { OrtTheme { SettingsRigScreen(state = known, onBack = {}) } }
        composeTestRule.onNodeWithText("Bluetooth SPP · D8:3A:DD:41:0C:7F", substring = true).assertExists()
    }

    @Test
    @Requirement("FR-RIG-14")
    fun `E2_F03 an unknown link address reads honestly, never fabricated`() {
        val unknown = connectedState()
        composeTestRule.setContent { OrtTheme { SettingsRigScreen(state = unknown, onBack = {}) } }
        composeTestRule
            .onNodeWithText("transport and address not yet reported by this build", substring = true)
            .assertExists()
    }

    @Test
    @Requirement("FR-RIG-14")
    fun `E2_F03 Switch on the Link row calls back`() {
        var switched = false
        composeTestRule.setContent {
            OrtTheme {
                SettingsRigScreen(state = connectedState(), onBack = {}, onSwitchTransport = { switched = true })
            }
        }
        composeTestRule.onNodeWithText("Switch").performScrollTo().performClick()
        assert(switched) { "expected Switch to call onSwitchTransport" }
    }

    @Test
    @Requirement("FR-RIG-14")
    fun `E2_F03 Reconnect is enabled once a rig has ever connected`() {
        composeTestRule.setContent {
            OrtTheme { SettingsRigScreen(state = connectedState(), onBack = {}) }
        }
        composeTestRule.onNodeWithText("Reconnect").assertIsEnabled()
    }

    @Test
    @Requirement("FR-RIG-14")
    fun `E2_F03 Reconnect calls back`() {
        var reconnected = false
        composeTestRule.setContent {
            OrtTheme { SettingsRigScreen(state = connectedState(), onBack = {}, onReconnect = { reconnected = true }) }
        }
        composeTestRule.onNodeWithText("Reconnect").performScrollTo().performClick()
        assert(reconnected) { "expected Reconnect to call onReconnect" }
    }

    @Test
    @Requirement("FR-RIG-14")
    fun `E2_F03 Reconnect is disabled while no rig has ever connected`() {
        val disconnected = SettingsRigViewState(
            descriptorLabel = "TH-D75A",
            connected = false,
            staleSinceLabel = null,
            bands = emptyList(),
        )
        composeTestRule.setContent { OrtTheme { SettingsRigScreen(state = disconnected, onBack = {}) } }
        composeTestRule.onNodeWithText("Reconnect").assertIsNotEnabled()
    }

    @Test
    @Requirement("FR-RIG-14")
    fun `E2_F03 a stale rig shows the subtitle, retry-ladder sentence and Link row, not the no-rig FailedState`() {
        val stale = SettingsRigViewState(
            descriptorLabel = "TH-D75A",
            connected = false,
            staleSinceLabel = "since 04:02",
            bands = emptyList(),
            transportLabel = "USB serial",
        )
        composeTestRule.setContent { OrtTheme { SettingsRigScreen(state = stale, onBack = {}) } }

        composeTestRule.onNodeWithText("Stale since 04:02").assertExists()
        composeTestRule.onNodeWithText("the link is retried with backoff", substring = true).assertExists()
        composeTestRule.onNodeWithText("No radio support in this build yet", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Reconnect").assertIsEnabled()
    }

    // --- R-835/R-845 (register, round 3 tour finding) — the rest of the board's Connection section ---

    @Test
    @Requirement("FR-RIG-15")
    fun `R_835 the attribution-explanation and Rig-module rows render the board's own real copy`() {
        val state = connectedState().copy(
            transportLabel = "Bluetooth SPP",
            rigModuleLabel = "kenwood-thd75a · built in · verified command set FREQUENCY, SQUELCH_STATE, SUB_BAND",
        )
        composeTestRule.setContent { OrtTheme { SettingsRigScreen(state = state, onBack = {}) } }

        composeTestRule.onNodeWithText("HOW OVERS ARE ATTRIBUTED TO A BAND").assertExists()
        composeTestRule.onNodeWithText("By squelch state, BY").assertExists()
        composeTestRule
            .onNodeWithText("the radio mixes both bands into one audio stream", substring = true)
            .assertExists()
        composeTestRule.onNodeWithText("Rig module").assertExists()
        composeTestRule
            .onNodeWithText(
                "kenwood-thd75a · built in · verified command set FREQUENCY, SQUELCH_STATE, SUB_BAND",
                substring = true,
            )
            .assertExists()
    }

    @Test
    @Requirement("FR-RIG-15")
    fun `R_835 an unmatched descriptor's Rig-module row reads the honest not-reported fallback`() {
        val state = connectedState().copy(transportLabel = "Bluetooth SPP")
        composeTestRule.setContent { OrtTheme { SettingsRigScreen(state = state, onBack = {}) } }

        composeTestRule
            .onNodeWithContentDescription("Rig module, $NOT_REPORTED_BY_RIG_MODULE")
            .assertExists()
    }

    @Test
    @Requirement("FR-RIG-15")
    fun `R_835 the Link row folds in paired-in-system-settings and the other-transport clause`() {
        val state = connectedState().copy(
            transportLabel = "Bluetooth SPP",
            linkAddressLabel = "D8:3A:DD:41:0C:7F",
            otherTransportLabel = "USB serial also supported",
        )
        composeTestRule.setContent { OrtTheme { SettingsRigScreen(state = state, onBack = {}) } }

        composeTestRule
            .onNodeWithText(
                "Bluetooth SPP · D8:3A:DD:41:0C:7F · paired in system settings · USB serial also supported",
                substring = true,
            )
            .assertExists()
    }

    @Test
    @Requirement("FR-RIG-15")
    fun `R_835_reopened a known transport with an unknown address states so honestly, never silently drops it`() {
        val state = connectedState().copy(transportLabel = "Bluetooth SPP", linkAddressLabel = null)
        composeTestRule.setContent { OrtTheme { SettingsRigScreen(state = state, onBack = {}) } }

        composeTestRule
            .onNodeWithText("Bluetooth SPP · address not yet reported by this build", substring = true)
            .assertExists()
    }

    @Test
    @Requirement("FR-RIG-15")
    fun `R_835 a USB link never claims paired in system settings`() {
        val state = connectedState().copy(transportLabel = "USB serial", linkAddressLabel = "vid 0x0451 pid 0x16a8")
        composeTestRule.setContent { OrtTheme { SettingsRigScreen(state = state, onBack = {}) } }

        composeTestRule.onNodeWithText("paired in system settings", substring = true).assertDoesNotExist()
    }

    @Test
    @Requirement("FR-RIG-15")
    fun `R_835 the Auto-information row renders only when the descriptor declares push, real On value`() {
        val withAi = connectedState().copy(
            autoInformation = SettingsRigAutoInformationViewState(
                label = "Auto-information, AI 1",
                subLine = "changes arrive without polling · fallback poll every 2 s if it stops",
            ),
        )
        composeTestRule.setContent { OrtTheme { SettingsRigScreen(state = withAi, onBack = {}) } }

        composeTestRule.onNodeWithText("Auto-information, AI 1").assertExists()
        composeTestRule.onNodeWithText("changes arrive without polling", substring = true).assertExists()
    }

    @Test
    @Requirement("FR-RIG-15")
    fun `R_835 no Auto-information row at all when the descriptor declares no push`() {
        composeTestRule.setContent { OrtTheme { SettingsRigScreen(state = connectedState(), onBack = {}) } }

        composeTestRule.onNodeWithText("Auto-information", substring = true).assertDoesNotExist()
    }

    @Test
    @Requirement("FR-RIG-15")
    fun `R_835 the Radio-battery row always renders, honest fallback with no real reader`() {
        composeTestRule.setContent { OrtTheme { SettingsRigScreen(state = connectedState(), onBack = {}) } }

        composeTestRule
            .onNodeWithContentDescription("Radio battery, $NOT_REPORTED_BY_RIG_MODULE, BL")
            .assertExists()
    }

    @Test
    @Requirement("FR-RIG-15")
    fun `R_881 the Radio-battery row names the label plainly and the mnemonic never truncates it`() {
        composeTestRule.setContent { OrtTheme { SettingsRigScreen(state = connectedState(), onBack = {}) } }

        composeTestRule.onNodeWithText("Radio battery").assertExists()
        composeTestRule.onNodeWithText("Radio battery, BL").assertDoesNotExist()
    }

    @Test
    @Requirement("FR-RIG-15")
    fun `R_881 no real CF06 row's content-desc carries a stray empty segment`() {
        composeTestRule.setContent { OrtTheme { SettingsRigScreen(state = connectedState(), onBack = {}) } }

        composeTestRule
            .onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") }
            .forEach { description ->
                assert(!description.contains(", ,") && !description.contains(",  ,")) {
                    "expected no stray empty segment, got: $description"
                }
            }
    }

    @Test
    @Requirement("FR-RIG-15")
    fun `R_835 both band tiles show an over count, honest fallback with no real per-band source`() {
        composeTestRule.setContent { OrtTheme { SettingsRigScreen(state = connectedState(), onBack = {}) } }

        composeTestRule.onAllNodesWithTag(BAND_TILE_OVER_COUNT_TEST_TAG).assertCountEquals(2)
    }

    @Test
    @Requirement("FR-RIG-15")
    fun `R_835 both Reconnect and Change radio render side by side once connected`() {
        composeTestRule.setContent { OrtTheme { SettingsRigScreen(state = connectedState(), onBack = {}) } }

        composeTestRule.onNodeWithText("Reconnect").assertExists()
        composeTestRule.onAllNodesWithText("Change radio").assertCountEquals(1)
    }

    @Test
    @Requirement("FR-RIG-15")
    fun `R_845 the subtitle's polling clause is real, from the state, not a hardcoded unpolled claim`() {
        val polled = connectedState().copy(transportLabel = "USB serial", pollingClause = "polled every 2 s")
        composeTestRule.setContent { OrtTheme { SettingsRigScreen(state = polled, onBack = {}) } }

        composeTestRule.onNodeWithText("Connected · USB serial · polled every 2 s").assertExists()
        composeTestRule.onNodeWithText("unpolled", substring = true).assertDoesNotExist()
    }

    @Test
    @Requirement("FR-RIG-15")
    fun `R_845 the subtitle names unpolled only while the real pollingClause says so`() {
        val pushed = connectedState().copy(
            transportLabel = "Bluetooth SPP",
            pollingClause = "reading both bands unpolled",
        )
        composeTestRule.setContent { OrtTheme { SettingsRigScreen(state = pushed, onBack = {}) } }

        composeTestRule.onNodeWithText("Connected · Bluetooth SPP · reading both bands unpolled").assertExists()
    }

    /**
     * Register R-980 (halt, run 6): confirms CF06's own `KeyValueRow` with a trailing action (the
     * `Link` row's own `Switch`) does not regress into the same per-character collapse CF02's own
     * "Re-verify the route now" row showed — a real, long `subLine` (transport + address + paired
     * + other-transport, this row's own established shape) at font scale 2.0 on the tour's real
     * 390dp AVD width. `@GraphicsMode.NATIVE` and a real 390dp width: this package's own
     * established discipline for real glyph-wrap measurement.
     */
    @Test
    @Requirement("FR-RIG-15")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_980 the Link rows own long sub-line never starves into a per-character collapse at 2_0`() {
        val state = connectedState().copy(
            transportLabel = "Bluetooth SPP",
            linkAddressLabel = "D8:3A:DD:41:0C:7F",
            otherTransportLabel = "USB serial also supported",
        )
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    Box(modifier = Modifier.width(390.dp)) {
                        SettingsRigScreen(state = state, onBack = {})
                    }
                }
            }
        }

        val subLineNode = composeTestRule
            .onNodeWithText("Bluetooth SPP · D8:3A:DD:41:0C:7F", substring = true, useUnmergedTree = true)
            .fetchSemanticsNode()
        val widthPx = subLineNode.size.width
        assert(widthPx > 60) {
            "expected the Link row's own sub-line to keep a real wrap width, got ${widthPx}px (a " +
                "per-character collapse measures only a few px wide)"
        }
    }
}
