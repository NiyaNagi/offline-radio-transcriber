package org.ort.app.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * Build-plan P31, AC-192/AC-197: add/edit/delete a watch of each kind from Settings, and every
 * watch is visible and manageable from one screen. FR-ALR-5/AC-196's wording discipline is proven
 * at the content-builder layer (`:pipeline`'s `AlertNotificationContentBuilderTest`, a plain JVM
 * test) — this suite proves the screen itself wires the operator's actions through honestly.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsAlertsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun watch(
        id: String = "w1",
        kind: SettingsAlertWatchKind = SettingsAlertWatchKind.CALLSIGN,
        label: String = "K7ABC",
        enabled: Boolean = true,
    ) = SettingsAlertWatchRowViewState(id, kind, label, enabled)

    private fun state(
        alertsEnabled: Boolean = true,
        notificationsPermissionGranted: Boolean = true,
        watches: List<SettingsAlertWatchRowViewState> = listOf(watch()),
    ) = SettingsAlertsViewState(alertsEnabled, notificationsPermissionGranted, watches)

    @Test
    fun `AC_197 every watch is visible from the one screen`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsAlertsScreen(
                    state = state(
                        watches = listOf(
                            watch(id = "w1", kind = SettingsAlertWatchKind.CALLSIGN, label = "K7ABC"),
                            watch(id = "w2", kind = SettingsAlertWatchKind.KEYWORD, label = "skywarn"),
                            watch(id = "w3", kind = SettingsAlertWatchKind.FREQUENCY, label = "146.520 MHz"),
                        ),
                    ),
                    onBack = {},
                    actions = SettingsAlertsActions(),
                )
            }
        }

        composeTestRule.onNodeWithText("K7ABC").assertExists()
        composeTestRule.onNodeWithText("skywarn").assertExists()
        composeTestRule.onNodeWithText("146.520 MHz").assertExists()
    }

    @Test
    fun `FR_ALR_6 the master toggle calls back with the new value`() {
        var toggled: Boolean? = null
        composeTestRule.setContent {
            OrtTheme {
                SettingsAlertsScreen(
                    state = state(alertsEnabled = true),
                    onBack = {},
                    actions = SettingsAlertsActions(onToggleAlertsEnabled = { toggled = it }),
                )
            }
        }

        composeTestRule.onNodeWithTag("alerts-master-toggle").performClick()

        assert(toggled == false) { "expected the master toggle to report turning off, got $toggled" }
    }

    @Test
    fun `AC_192 toggling one watch calls back with its own id`() {
        var toggledId: String? = null
        var toggledValue: Boolean? = null
        composeTestRule.setContent {
            OrtTheme {
                SettingsAlertsScreen(
                    state = state(watches = listOf(watch(id = "w1", enabled = true))),
                    onBack = {},
                    actions = SettingsAlertsActions(
                        onToggleWatch = { id, value ->
                            toggledId = id
                            toggledValue = value
                        },
                    ),
                )
            }
        }

        composeTestRule.onNodeWithTag("alert-watch-toggle-w1").performClick()

        assert(toggledId == "w1")
        assert(toggledValue == false)
    }

    @Test
    fun `AC_192 remove calls back with the watch id`() {
        var removedId: String? = null
        composeTestRule.setContent {
            OrtTheme {
                SettingsAlertsScreen(
                    state = state(watches = listOf(watch(id = "w1"))),
                    onBack = {},
                    actions = SettingsAlertsActions(onRemoveWatch = { removedId = it }),
                )
            }
        }

        composeTestRule.onNodeWithText("Remove").performClick()

        assert(removedId == "w1")
    }

    @Test
    fun `AC_192 adding a callsign types a value and calls back with it`() {
        var added: String? = null
        composeTestRule.setContent {
            OrtTheme {
                SettingsAlertsScreen(
                    state = state(watches = emptyList()),
                    onBack = {},
                    actions = SettingsAlertsActions(onAddCallsignWatch = { added = it }),
                )
            }
        }

        composeTestRule.onNodeWithText("Add a callsign").performClick()
        composeTestRule.onNodeWithContentDescription("New watch value").performTextInput("K7XYZ")
        composeTestRule.onNodeWithText("Add").performClick()

        assert(added == "K7XYZ") { "expected the typed callsign to reach onAddCallsignWatch, got $added" }
    }

    @Test
    fun `AC_192 editing a watch types a new value and calls back with the same id`() {
        var editedId: String? = null
        var editedKind: SettingsAlertWatchKind? = null
        var editedValue: String? = null
        composeTestRule.setContent {
            OrtTheme {
                SettingsAlertsScreen(
                    state = state(watches = listOf(watch(id = "w1", label = "K7ABC"))),
                    onBack = {},
                    actions = SettingsAlertsActions(
                        onEditWatch = { id, kind, value ->
                            editedId = id
                            editedKind = kind
                            editedValue = value
                        },
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("Edit").performClick()
        composeTestRule.onNodeWithContentDescription("Edit watch value").performTextReplacement("K7XYZ")
        composeTestRule.onNodeWithText("Save").performClick()

        assert(editedId == "w1")
        assert(editedKind == SettingsAlertWatchKind.CALLSIGN)
        assert(editedValue == "K7XYZ") { "expected the edit to carry the field's own text, got $editedValue" }
    }

    @Test
    fun `FR_ALR_2 a denied notification permission shows the honest banner`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsAlertsScreen(
                    state = state(notificationsPermissionGranted = false),
                    onBack = {},
                    actions = SettingsAlertsActions(),
                )
            }
        }

        composeTestRule.onNodeWithText("Alerts cannot fire").assertExists()
    }

    @Test
    fun `a granted notification permission shows no banner`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsAlertsScreen(
                    state = state(notificationsPermissionGranted = true),
                    onBack = {},
                    actions = SettingsAlertsActions(),
                )
            }
        }

        composeTestRule.onNodeWithText("Alerts cannot fire").assertDoesNotExist()
    }
}
