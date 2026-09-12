package org.ort.app.ui.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * R-137 (register, rounds 7 and 9 System validator): `Settings-Diagnostics.dc.html`'s own
 * scrubbing example — "resolved [callsign] at 0.94" — is now real (WP11e's `CallsignScrubber`
 * actually runs), and the per-file size / header total are real too (WP11e's
 * `DiagnosticsBundleBuilder.preview`, sourced by `SettingsPolling.diagnostics`) — this screen only
 * renders the numbers it is handed, so this suite covers rendering, never `DiagnosticsBundleBuilder`
 * itself (that package's own `DiagnosticsBundleBuilderTest` does).
 */
@RunWith(RobolectricTestRunner::class)
class SettingsDiagnosticsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun state() = SettingsDiagnosticsViewState(
        aliveLabel = "alive",
        realTimeFactorLabel = "0.31",
        failedPassCount = 0,
        files = listOf(
            SettingsDiagnosticsFileViewState("lifecycle.log", "service start, stop", "140 KB"),
            SettingsDiagnosticsFileViewState("capture.log", "route verifications", "88 KB"),
        ),
        totalSizeLabel = "2.1 MB",
    )

    @Test
    @Requirement("R-137")
    fun `R_137 the never-included prose carries the board's own scrubbing example verbatim`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsDiagnosticsScreen(
                    state = state(),
                    onBack = {},
                    bundleActions = SettingsDiagnosticsBundleActions(onPreview = {}, onSaveBundle = {}),
                )
            }
        }

        composeTestRule.onNodeWithText("resolved [callsign] at 0.94", substring = true).assertExists()
    }

    @Test
    @Requirement("R-137")
    fun `R_137_list_from_preview the header names the real file count and total, each row its real size`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsDiagnosticsScreen(
                    state = state(),
                    onBack = {},
                    bundleActions = SettingsDiagnosticsBundleActions(onPreview = {}, onSaveBundle = {}),
                )
            }
        }

        // SectionHeader uppercases its own label (guide's own section-label style).
        composeTestRule.onNodeWithText("IN THE BUNDLE · 2 FILES · 2.1 MB").assertExists()
        composeTestRule.onNodeWithText("140 KB").assertExists()
        composeTestRule.onNodeWithText("88 KB").assertExists()
    }

    @Test
    @Requirement("R-137")
    fun `R_137 Preview opens the real entries and total, Done returns to the bundle screen`() {
        var previewTapped = false
        var dismissed = false
        composeTestRule.setContent {
            OrtTheme {
                SettingsDiagnosticsScreen(
                    state = state(),
                    onBack = {},
                    bundleActions = SettingsDiagnosticsBundleActions(
                        onPreview = { previewTapped = true },
                        onSaveBundle = {},
                    ),
                    previewOpen = previewTapped,
                    onDismissPreview = {
                        dismissed = true
                        previewTapped = false
                    },
                )
            }
        }

        // WP2's R-380/R-381 fix: `PrimaryButton`/`SecondaryButton`/`TextAction`'s own
        // `clearAndSetSemantics` now sets `contentDescription = text` on the button's own node and
        // clears its inner Text's semantics entirely — its label is a content description now,
        // never a `Text` node `hasText`/`onNodeWithText` can find.
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Preview"))
        composeTestRule.onNodeWithContentDescription("Preview").performClick()
        assert(previewTapped) { "expected onPreview to fire" }
    }

    @Test
    @Requirement("R-137")
    fun `R_137_save_writes_zip Save bundle calls back, and a real confirmation names the saved file`() {
        var saveTapped = false
        composeTestRule.setContent {
            OrtTheme {
                SettingsDiagnosticsScreen(
                    state = state(),
                    onBack = {},
                    bundleActions = SettingsDiagnosticsBundleActions(
                        onPreview = {},
                        onSaveBundle = { saveTapped = true },
                    ),
                    saveConfirmationLabel = "Saved diagnostics-2026-09-08.zip",
                )
            }
        }

        composeTestRule.onNodeWithText("Saved diagnostics-2026-09-08.zip").assertExists()
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Save bundle"))
        composeTestRule.onNodeWithContentDescription("Save bundle").performClick()
        assert(saveTapped) { "expected onSaveBundle to fire" }
    }

    // ---------------------------------------------------------------------------------------
    // WPR2 (FR-OBS-6..12, D37/D38): the field-report section and consent screen.
    // ---------------------------------------------------------------------------------------

    @Test
    @Requirement("FR-OBS-6")
    fun `FR_OBS_6 the field-report section is absent when state carries no fieldReport`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsDiagnosticsScreen(
                    state = state(),
                    onBack = {},
                    bundleActions = SettingsDiagnosticsBundleActions(onPreview = {}, onSaveBundle = {}),
                )
            }
        }

        // `SectionHeader` uppercases its own label (guide's own section-label style, per this
        // file's earlier "IN THE BUNDLE" assertions).
        composeTestRule.onNodeWithText("FIELD REPORT (DEBUG BUILDS ONLY)").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Send field report…").assertDoesNotExist()
    }

    @Test
    @Requirement("FR-OBS-10")
    fun `FR_OBS_10 the field-report section renders its guard toggle and entry button when present`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsDiagnosticsScreen(
                    state = state().copy(fieldReport = FieldReportSectionViewState(publicGuardEnabled = true)),
                    onBack = {},
                    bundleActions = SettingsDiagnosticsBundleActions(onPreview = {}, onSaveBundle = {}),
                )
            }
        }

        composeTestRule.onNode(hasScrollAction())
            .performScrollToNode(hasContentDescription("Send field report…"))
        composeTestRule.onNodeWithContentDescription("Send field report…").assertExists()
        // FR-OBS-10: the guard defaults enabled (the safe position) — the switch reads *off*
        // because it is labelled "Allow …", the inverse of "the guard is enabled".
        composeTestRule.onNodeWithTag(FIELD_REPORT_GUARD_TOGGLE_TEST_TAG).assertIsOff()
    }

    @Test
    @Requirement("FR-OBS-10")
    fun `FR_OBS_10 turning the guard toggle on calls back with the guard disabled, not the raw switch value`() {
        var guardEnabledValue: Boolean? = null
        composeTestRule.setContent {
            OrtTheme {
                SettingsDiagnosticsScreen(
                    state = state().copy(fieldReport = FieldReportSectionViewState(publicGuardEnabled = true)),
                    onBack = {},
                    bundleActions = SettingsDiagnosticsBundleActions(onPreview = {}, onSaveBundle = {}),
                    fieldReportActions = FieldReportSectionActions(
                        onSetPublicDestinationGuardEnabled = { guardEnabledValue = it },
                    ),
                )
            }
        }

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(FIELD_REPORT_GUARD_TOGGLE_TEST_TAG))
        composeTestRule.onNodeWithTag(FIELD_REPORT_GUARD_TOGGLE_TEST_TAG).performClick()

        assert(guardEnabledValue == false) { "turning the switch ON (\"Allow…\") must report the guard DISABLED" }
    }

    private fun consentState(
        destinationKnown: Boolean = false,
        destinationPublic: Boolean = false,
        publicGuardEnabled: Boolean = true,
        toggles: FieldReportToggleState = FieldReportToggleState(),
    ) = FieldReportConsentViewState(
        files = listOf(
            FieldReportConsentFileViewState("lifecycle.log", "140 KB", category = null),
            FieldReportConsentFileViewState("session-recorder.log", "4 KB", category = null),
        ),
        totalSizeLabel = "144 KB",
        destinationKnown = destinationKnown,
        destinationLabel = if (destinationKnown) "org/repo" else "No upload destination is configured in this build",
        destinationPublic = destinationPublic,
        publicGuardEnabled = publicGuardEnabled,
        toggles = toggles,
    )

    @Test
    @Requirement("AC-144")
    fun `AC_144 the consent screen's three toggles default off`() {
        composeTestRule.setContent {
            OrtTheme {
                FieldReportConsentScreen(
                    state = consentState(),
                    onToggleRetainedAudio = {},
                    onToggleVoiceprintEmbeddings = {},
                    onToggleScreenFrames = {},
                    onCancel = {},
                    onSend = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(FIELD_REPORT_TOGGLE_RETAINED_AUDIO_TEST_TAG).assertIsOff()
        composeTestRule.onNodeWithTag(FIELD_REPORT_TOGGLE_VOICEPRINTS_TEST_TAG).assertIsOff()
        composeTestRule.onNodeWithTag(FIELD_REPORT_TOGGLE_SCREEN_FRAMES_TEST_TAG).assertIsOff()
    }

    // AC-144's "reappears on a second upload rather than proceeding on a remembered choice" is
    // proven at the level where a remembered choice could actually happen — the stateful
    // `SettingsContent` wiring, one open→toggle→cancel→reopen cycle inside a single composition
    // (`SettingsContentTest`'s own `AC_144 a category toggled on and cancelled is off again…`).
    // This composable itself is stateless (every toggle arrives as a parameter, never as internal
    // `remember`ed state) — a `ComposeContentTestRule` refuses a second `setContent` per test, so
    // "render it twice and compare" cannot be expressed as a single test here without that
    // refusal, and two independent `@Test` functions would prove nothing a single one does not
    // already prove for a composable with no internal state to carry anything between calls.

    @Test
    @Requirement("AC-145")
    fun `AC_145 a public destination with the guard enabled forces the three toggles off and inert`() {
        var toggledOn = false
        composeTestRule.setContent {
            OrtTheme {
                FieldReportConsentScreen(
                    state = consentState(destinationKnown = true, destinationPublic = true, publicGuardEnabled = true),
                    onToggleRetainedAudio = { toggledOn = true },
                    onToggleVoiceprintEmbeddings = { toggledOn = true },
                    onToggleScreenFrames = { toggledOn = true },
                    onCancel = {},
                    onSend = {},
                )
            }
        }

        composeTestRule.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag(FIELD_REPORT_TOGGLE_RETAINED_AUDIO_TEST_TAG))
        composeTestRule.onNodeWithTag(FIELD_REPORT_TOGGLE_RETAINED_AUDIO_TEST_TAG).performClick()
        composeTestRule.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag(FIELD_REPORT_TOGGLE_VOICEPRINTS_TEST_TAG))
        composeTestRule.onNodeWithTag(FIELD_REPORT_TOGGLE_VOICEPRINTS_TEST_TAG).performClick()
        composeTestRule.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag(FIELD_REPORT_TOGGLE_SCREEN_FRAMES_TEST_TAG))
        composeTestRule.onNodeWithTag(FIELD_REPORT_TOGGLE_SCREEN_FRAMES_TEST_TAG).performClick()

        assert(!toggledOn) { "a public destination with the guard enabled must refuse every gated toggle" }
        composeTestRule.onNodeWithTag(FIELD_REPORT_TOGGLE_RETAINED_AUDIO_TEST_TAG).assertIsOff()
        composeTestRule.onNodeWithTag(FIELD_REPORT_TOGGLE_VOICEPRINTS_TEST_TAG).assertIsOff()
        composeTestRule.onNodeWithTag(FIELD_REPORT_TOGGLE_SCREEN_FRAMES_TEST_TAG).assertIsOff()
    }

    @Test
    @Requirement("AC-145")
    fun `AC_145 a public destination with the guard turned off allows the toggles`() {
        var toggledOn = false
        composeTestRule.setContent {
            OrtTheme {
                FieldReportConsentScreen(
                    state = consentState(destinationKnown = true, destinationPublic = true, publicGuardEnabled = false),
                    onToggleRetainedAudio = { toggledOn = true },
                    onToggleVoiceprintEmbeddings = {},
                    onToggleScreenFrames = {},
                    onCancel = {},
                    onSend = {},
                )
            }
        }

        composeTestRule.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag(FIELD_REPORT_TOGGLE_RETAINED_AUDIO_TEST_TAG))
        composeTestRule.onNodeWithTag(FIELD_REPORT_TOGGLE_RETAINED_AUDIO_TEST_TAG).performClick()

        assert(toggledOn) { "turning the FR-OBS-10 switch off must allow the gated toggles again" }
    }

    @Test
    @Requirement("AC-149")
    fun `AC_149 the public-destination warning names the toggled-on categories`() {
        // AC-149's "not only the first time it is seen": this warning is computed fresh from
        // `state` on every recomposition (`FieldReportConsentScreen`'s own doc comment) — there is
        // no persisted "already shown" flag anywhere for it to suppress a repeat with, so a single
        // render already proves what a second one would; a `ComposeContentTestRule` refuses a
        // second `setContent` per test regardless (see the AC-144 note above this test).
        composeTestRule.setContent {
            OrtTheme {
                FieldReportConsentScreen(
                    state = consentState(
                        destinationKnown = true,
                        destinationPublic = true,
                        publicGuardEnabled = false,
                        toggles = FieldReportToggleState(screenFrames = true),
                    ),
                    onToggleRetainedAudio = {},
                    onToggleVoiceprintEmbeddings = {},
                    onToggleScreenFrames = {},
                    onCancel = {},
                    onSend = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("public destination warning")
            .assertExists()
            .assertTextContains("Screen frames", substring = true)
    }

    @Test
    @Requirement("AC-149")
    fun `AC_149 no warning renders against a private destination or with the guard still enabled`() {
        composeTestRule.setContent {
            OrtTheme {
                FieldReportConsentScreen(
                    state = consentState(
                        destinationKnown = true,
                        destinationPublic = false,
                        publicGuardEnabled = false,
                    ),
                    onToggleRetainedAudio = {},
                    onToggleVoiceprintEmbeddings = {},
                    onToggleScreenFrames = {},
                    onCancel = {},
                    onSend = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("public destination warning").assertDoesNotExist()
    }

    @Test
    fun `Send is disabled with an honest message when no upload client is wired`() {
        var sendTapped = false
        composeTestRule.setContent {
            OrtTheme {
                FieldReportConsentScreen(
                    state = consentState(),
                    onToggleRetainedAudio = {},
                    onToggleVoiceprintEmbeddings = {},
                    onToggleScreenFrames = {},
                    onCancel = {},
                    onSend = { sendTapped = true },
                    canSend = false,
                )
            }
        }

        composeTestRule.onNodeWithText("No field-report upload client exists in :net yet", substring = true)
            .assertExists()
        // A disabled `clickable` carries no click semantics action at all — asserting the disabled
        // state (rather than attempting `performClick()`, which would throw on a node with no click
        // action) is the correct, honest proof that `Send` cannot be tapped in this build.
        composeTestRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
        assert(!sendTapped) { "onSend must never fire on its own" }
    }

    @Test
    fun `Cancel fires onCancel without ever calling onSend`() {
        var cancelled = false
        var sendTapped = false
        composeTestRule.setContent {
            OrtTheme {
                FieldReportConsentScreen(
                    state = consentState(),
                    onToggleRetainedAudio = {},
                    onToggleVoiceprintEmbeddings = {},
                    onToggleScreenFrames = {},
                    onCancel = { cancelled = true },
                    onSend = { sendTapped = true },
                )
            }
        }

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Cancel"))
        composeTestRule.onNodeWithContentDescription("Cancel").performClick()

        assert(cancelled)
        assert(!sendTapped)
    }
}
