package org.ort.app.ui.screens

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.ActivityPatternMapper
import org.ort.app.ui.data.FrequencyDetailViewState
import org.ort.app.ui.data.FrequencyListEntryViewState
import org.ort.app.ui.data.FrequencyRegularViewState
import org.ort.app.ui.data.HourActivityState
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.robolectric.RobolectricTestRunner

/** R-074 (ui-conformance-plan WP8): everything heard on one frequency, across every session. */
@RunWith(RobolectricTestRunner::class)
class FrequencyScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `an empty frequency list shows an honest empty state, not a fabricated placeholder`() {
        composeTestRule.setContent { OrtTheme { FrequenciesListScreen(frequencies = emptyList(), onOpen = {}) } }

        composeTestRule.onNodeWithContentDescription("No frequencies recorded yet").assertExists()
    }

    @Test
    fun `tapping a frequency row opens that frequency`() {
        var opened: Long? = null
        composeTestRule.setContent {
            OrtTheme {
                FrequenciesListScreen(
                    frequencies = listOf(
                        FrequencyListEntryViewState(146_960_000L, "146.960 MHz", transmissionCount = 5),
                    ),
                    onOpen = { opened = it },
                )
            }
        }

        composeTestRule.onNodeWithText("146.960").performClick()

        assert(opened == 146_960_000L)
    }

    @Test
    fun `R_074 a frequency row shows what it is, tonight's split and the 14-night sparkline`() {
        composeTestRule.setContent {
            OrtTheme {
                FrequenciesListScreen(
                    frequencies = listOf(
                        FrequencyListEntryViewState(
                            frequencyHz = 145_230_000L,
                            label = "145.230 MHz",
                            transmissionCount = 4112,
                            whatItIs = "Repeater · 2 m · FM",
                            tonightCount = 318,
                            tonightStationCount = 12,
                            nights = List(14) { HourActivityState.HEARD },
                        ),
                    ),
                    onOpen = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Repeater · 2 m · FM").assertExists()
        composeTestRule.onNodeWithText("318 overs tonight · 12 stations").assertExists()
    }

    @Test
    fun `R_074 a busier-than-usual frequency reads in amber text, never colour alone`() {
        composeTestRule.setContent {
            OrtTheme {
                FrequenciesListScreen(
                    frequencies = listOf(
                        FrequencyListEntryViewState(
                            frequencyHz = 146_960_000L,
                            label = "146.960 MHz",
                            transmissionCount = 94,
                            tonightCount = 94,
                            tonightStationCount = 7,
                            busierThanUsual = true,
                        ),
                    ),
                    onOpen = {},
                )
            }
        }

        composeTestRule.onNodeWithText("94 overs tonight · 7 stations · busier than usual").assertExists()
    }

    @Test
    fun `R_074 frequency detail shows the facts table, the typical-night chart and REGULARS`() {
        val pattern = ActivityPatternMapper.buildPattern(emptyList(), emptyList(), 0L)
        val state = FrequencyDetailViewState(
            frequencyHz = 145_230_000L,
            label = "145.230 MHz",
            whatItIs = "Repeater · 2 m · FM",
            transmissionCount = 4112,
            transmissionCountTonight = 318,
            stationCountAllTime = 64,
            stationCountTonight = 12,
            activityPattern = pattern,
            regulars = listOf(
                FrequencyRegularViewState(
                    stationId = "W7NPC",
                    label = "W7NPC",
                    attribution = Attribution.confirmed("W7NPC", 0.9),
                    countContext = "612 over(s) · 14 session(s)",
                    lastHeardLabel = "02:14",
                ),
            ),
            transmissions = emptyList(),
        )

        composeTestRule.setContent { OrtTheme { FrequencyDetailScreen(state = state, onBack = {}) } }

        composeTestRule.onNodeWithText("145.230 MHz").assertExists()
        composeTestRule.onNodeWithText("Repeater · 2 m · FM").assertExists()
        composeTestRule.onNodeWithText("4112 all time · 318 tonight").assertExists()
        composeTestRule.onNodeWithContentDescription("Activity by hour of day", substring = true).assertExists()
        // Below the fold in a LazyColumn — scroll the list to it (`performScrollToNode`, which
        // can bring an as-yet-uncomposed lazy item into being), matching how a real user or
        // TalkBack reaches it.
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("regular-W7NPC"))
        composeTestRule.onNodeWithText("W7NPC").assertExists()
        composeTestRule.onNodeWithText("612 over(s) · 14 session(s)").assertExists()
    }

    @Test
    fun `R_074 tapping a regular opens that station`() {
        var opened: String? = null
        val state = FrequencyDetailViewState(
            frequencyHz = 145_230_000L,
            label = "145.230 MHz",
            transmissionCount = 1,
            activityPattern = ActivityPatternMapper.buildPattern(emptyList(), emptyList(), 0L),
            regulars = listOf(
                FrequencyRegularViewState(
                    stationId = "W7NPC",
                    label = "W7NPC",
                    attribution = Attribution.confirmed("W7NPC", 0.9),
                    countContext = "1 over(s) · 1 session(s)",
                    lastHeardLabel = null,
                ),
            ),
            transmissions = emptyList(),
        )

        composeTestRule.setContent {
            OrtTheme { FrequencyDetailScreen(state = state, onBack = {}, onOpenStation = { opened = it }) }
        }
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("regular-W7NPC"))
        composeTestRule.onNodeWithTag("regular-W7NPC").performClick()

        assert(opened == "W7NPC")
    }

    @Test
    fun `frequency detail with no regulars shows an honest empty state`() {
        val state = FrequencyDetailViewState(
            frequencyHz = 146_960_000L,
            label = "146.960 MHz",
            transmissionCount = 0,
            activityPattern = ActivityPatternMapper.buildPattern(emptyList(), emptyList(), 0L),
            transmissions = emptyList(),
        )

        composeTestRule.setContent { OrtTheme { FrequencyDetailScreen(state = state, onBack = {}) } }

        composeTestRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("No regular stations recorded on this frequency yet"))
        composeTestRule.onNodeWithText("No regular stations recorded on this frequency yet").assertExists()
    }

    @Test
    fun `R_074 a busier-than-usual frequency detail offers the way to Frequency-Change`() {
        var openedChange = false
        val state = FrequencyDetailViewState(
            frequencyHz = 146_960_000L,
            label = "146.960 MHz",
            transmissionCount = 61,
            activityPattern = ActivityPatternMapper.buildPattern(emptyList(), emptyList(), 0L),
            transmissions = emptyList(),
            busierThanUsual = true,
        )

        composeTestRule.setContent {
            OrtTheme { FrequencyDetailScreen(state = state, onBack = {}, onOpenChange = { openedChange = true }) }
        }
        composeTestRule.onNodeWithText("Busier than usual — see what changed").performClick()

        assert(openedChange)
    }

    @Test
    fun `R_017 backLabel defaults to Frequencies, matching OrtNavHost's own hardcoded label today`() {
        val state = FrequencyDetailViewState(
            frequencyHz = 146_960_000L,
            label = "146.960 MHz",
            transmissionCount = 0,
            activityPattern = ActivityPatternMapper.buildPattern(emptyList(), emptyList(), 0L),
            transmissions = emptyList(),
        )

        composeTestRule.setContent { OrtTheme { FrequencyDetailScreen(state = state, onBack = {}) } }

        composeTestRule.onNodeWithText("Frequencies").assertExists()
    }

    @Test
    fun `R_017 backLabel is a real parameter WP3 can wire to the true navigation origin`() {
        val state = FrequencyDetailViewState(
            frequencyHz = 146_960_000L,
            label = "146.960 MHz",
            transmissionCount = 0,
            activityPattern = ActivityPatternMapper.buildPattern(emptyList(), emptyList(), 0L),
            transmissions = emptyList(),
        )

        composeTestRule.setContent {
            OrtTheme { FrequencyDetailScreen(state = state, onBack = {}, backLabel = "Search") }
        }

        composeTestRule.onNodeWithText("Search").assertExists()
        composeTestRule.onNodeWithText("Frequencies").assertDoesNotExist()
    }
}
