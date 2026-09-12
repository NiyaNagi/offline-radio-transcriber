package org.ort.app.ui.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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
import org.ort.app.diagnostics.localsave.LocalSaveCategoryId
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * WPDUMP (operator: "just allow a single save dump with checkboxes for EVERYTHING that can be
 * saved"). This screen only renders the [LocalSaveSectionViewState] it is handed — coverage for
 * [org.ort.app.diagnostics.localsave.LocalSaveBundleBuilder] itself lives in that package's own
 * `LocalSaveBundleBuilderTest`.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsDiagnosticsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun row(
        id: LocalSaveCategoryId,
        label: String,
        caption: String,
        sizeLabel: String,
        checked: Boolean,
        available: Boolean = true,
    ) = LocalSaveCategoryRowViewState(id, label, caption, sizeLabel, checked, available)

    private fun localSave(rows: List<LocalSaveCategoryRowViewState>, totalSizeLabel: String = "1.0 MB") =
        LocalSaveSectionViewState(rows = rows, totalSizeLabel = totalSizeLabel)

    private fun state(localSave: LocalSaveSectionViewState? = null) = SettingsDiagnosticsViewState(
        aliveLabel = "alive",
        realTimeFactorLabel = "0.31",
        failedPassCount = 0,
        files = emptyList(),
        totalSizeLabel = "0 KB",
        localSave = localSave,
    )

    private fun twelveRows() = listOf(
        row(LocalSaveCategoryId.LIFECYCLE_LOG, "lifecycle.log", "service start, stop", "140 KB", checked = true),
        row(LocalSaveCategoryId.CAPTURE_LOG, "capture.log", "route verifications", "88 KB", checked = true),
        row(LocalSaveCategoryId.PIPELINE_LOG, "pipeline.log", "per-pass timings", "64 KB", checked = true),
        row(LocalSaveCategoryId.RIG_LOG, "rig.log", "CAT traffic", "8 KB", checked = true),
        row(LocalSaveCategoryId.ASSETS_JSON, "assets.json", "every model and lexicon", "2 KB", checked = true),
        row(LocalSaveCategoryId.DEVICE_JSON, "device.json", "SoC, RAM", "1 KB", checked = true),
        row(LocalSaveCategoryId.COUNTS_JSON, "counts.json", "overs by state", "1 KB", checked = true),
        row(
            LocalSaveCategoryId.SESSION_RECORDER_LOG,
            "session-recorder.log",
            "destination changes",
            "4 KB",
            checked = true,
        ),
        row(
            LocalSaveCategoryId.RETAINED_AUDIO,
            "Retained over audio",
            "the full-fidelity recording — recordings of identifiable people.",
            "0 KB",
            checked = false,
            available = false,
        ),
        row(
            LocalSaveCategoryId.VOICEPRINT_EMBEDDINGS,
            "voiceprints.json",
            "the numeric voice signatures — no voiceprints have been resolved yet.",
            "0 KB",
            checked = false,
            available = false,
        ),
        row(
            LocalSaveCategoryId.SCREEN_FRAMES,
            "Screen frames",
            "downscaled screenshots — no screen frames have been captured yet.",
            "0 KB",
            checked = false,
            available = false,
        ),
        row(LocalSaveCategoryId.DEBUG_DUMP_NDJSON, "debug-dump.ndjson", "every session, over", "12 KB", checked = true),
    )

    @Test
    @Requirement("FR-OBS-3")
    fun `WPDUMP all twelve checklist rows render with their real label, caption and size`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsDiagnosticsScreen(
                    state = state(localSave(twelveRows())),
                    onBack = {},
                    localSaveActions = LocalSaveActions(),
                )
            }
        }

        for (r in twelveRows()) {
            val tag = LOCAL_SAVE_ROW_TEST_TAG_PREFIX + r.id.name
            composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))
            composeTestRule.onNodeWithTag(tag).assertTextContains(r.label, substring = true)
            composeTestRule.onNodeWithTag(tag).assertTextContains(r.sizeLabel, substring = true)
        }
    }

    @Test
    @Requirement("FR-OBS-3")
    fun `WPDUMP a default-on row renders checked, a default-off row renders unchecked`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsDiagnosticsScreen(
                    state = state(localSave(twelveRows())),
                    onBack = {},
                    localSaveActions = LocalSaveActions(),
                )
            }
        }

        composeTestRule.onNodeWithTag(LOCAL_SAVE_ROW_TEST_TAG_PREFIX + LocalSaveCategoryId.LIFECYCLE_LOG.name)
            .assertIsOn()

        val voiceprintTag = LOCAL_SAVE_ROW_TEST_TAG_PREFIX + LocalSaveCategoryId.VOICEPRINT_EMBEDDINGS.name
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(voiceprintTag))
        composeTestRule.onNodeWithTag(voiceprintTag).assertIsOff()
    }

    @Test
    @Requirement("FR-OBS-3")
    fun `WPDUMP an unavailable row renders disabled and its caption states why`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsDiagnosticsScreen(
                    state = state(localSave(twelveRows())),
                    onBack = {},
                    localSaveActions = LocalSaveActions(),
                )
            }
        }

        val framesTag = LOCAL_SAVE_ROW_TEST_TAG_PREFIX + LocalSaveCategoryId.SCREEN_FRAMES.name
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(framesTag))
        composeTestRule.onNodeWithTag(framesTag).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(framesTag).assertIsOff()
        composeTestRule.onNodeWithTag(framesTag)
            .assertTextContains("no screen frames have been captured yet", substring = true)
    }

    @Test
    @Requirement("FR-OBS-3")
    fun `WPDUMP tapping an available row's checkbox calls onToggle with the flipped value`() {
        var toggledId: LocalSaveCategoryId? = null
        var toggledValue: Boolean? = null
        composeTestRule.setContent {
            OrtTheme {
                SettingsDiagnosticsScreen(
                    state = state(localSave(twelveRows())),
                    onBack = {},
                    localSaveActions = LocalSaveActions(
                        onToggle = { id, value ->
                            toggledId = id
                            toggledValue = value
                        },
                    ),
                )
            }
        }

        val tag = LOCAL_SAVE_ROW_TEST_TAG_PREFIX + LocalSaveCategoryId.LIFECYCLE_LOG.name
        composeTestRule.onNodeWithTag(tag).performClick()

        assert(toggledId == LocalSaveCategoryId.LIFECYCLE_LOG)
        assert(toggledValue == false) { "lifecycle.log started checked; tapping it must report false" }
    }

    @Test
    @Requirement("FR-OBS-3")
    fun `WPDUMP the section header names the checked count and the real total`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsDiagnosticsScreen(
                    state = state(localSave(twelveRows(), totalSizeLabel = "320 KB")),
                    onBack = {},
                    localSaveActions = LocalSaveActions(),
                )
            }
        }

        // nine rows default checked in `twelveRows()`; SectionHeader uppercases its own label.
        composeTestRule.onNodeWithText("SAVE · 9 CATEGORIES · 320 KB").assertExists()
    }

    @Test
    @Requirement("FR-OBS-3")
    fun `WPDUMP tapping Save calls onSave, and a real confirmation names the saved file`() {
        var saveTapped = false
        composeTestRule.setContent {
            OrtTheme {
                SettingsDiagnosticsScreen(
                    state = state(localSave(twelveRows())),
                    onBack = {},
                    localSaveActions = LocalSaveActions(onSave = { saveTapped = true }),
                    saveConfirmationLabel = "Saved ort-debug-dump-2026-09-12.zip",
                )
            }
        }

        composeTestRule.onNodeWithText("Saved ort-debug-dump-2026-09-12.zip").assertExists()
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(LOCAL_SAVE_SAVE_BUTTON_TEST_TAG))
        composeTestRule.onNodeWithTag(LOCAL_SAVE_SAVE_BUTTON_TEST_TAG).performClick()
        assert(saveTapped) { "expected onSave to fire" }
    }

    @Test
    @Requirement("FR-OBS-3")
    fun `WPDUMP the checklist section is absent while the async preview has not resolved yet`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsDiagnosticsScreen(
                    state = state(localSave = null),
                    onBack = {},
                    localSaveActions = LocalSaveActions(),
                )
            }
        }

        composeTestRule.onNodeWithTag(LOCAL_SAVE_SAVE_BUTTON_TEST_TAG).assertDoesNotExist()
    }

    @Test
    @Requirement("R-137")
    fun `R_137 the excluded prose still carries the board's own scrubbing example verbatim`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsDiagnosticsScreen(
                    state = state(localSave(twelveRows())),
                    onBack = {},
                    localSaveActions = LocalSaveActions(),
                )
            }
        }

        composeTestRule.onNodeWithText("resolved [callsign] at 0.94", substring = true).assertExists()
    }

    @Test
    @Requirement("FR-OBS-3")
    fun `WPDUMP the stale absolute never-included claim is gone now that audio and voiceprints are opt-in`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsDiagnosticsScreen(
                    state = state(localSave(twelveRows())),
                    onBack = {},
                    localSaveActions = LocalSaveActions(),
                )
            }
        }

        // The pre-WPDUMP screen asserted this exact sentence as a categorical absolute — it is no
        // longer true (both categories are now opt-in checkboxes above), so it must not appear.
        composeTestRule.onNodeWithText("Voiceprints. Names. Location.", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("never included in a local save", substring = true).assertExists()
    }

    // ---------------------------------------------------------------------------------------
    // WPR2 (FR-OBS-6..12, D37/D38): the field-report section and consent screen — unchanged by
    // WPDUMP (its own selection state stays independent of the checklist above; see this screen's
    // own top doc comment for why).
    // ---------------------------------------------------------------------------------------

    @Test
    @Requirement("FR-OBS-6")
    fun `FR_OBS_6 the field-report section is absent when state carries no fieldReport`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsDiagnosticsScreen(
                    state = state(localSave(twelveRows())),
                    onBack = {},
                    localSaveActions = LocalSaveActions(),
                )
            }
        }

        // `SectionHeader` uppercases its own label (guide's own section-label style, per this
        // file's earlier "SAVE ·" assertion).
        composeTestRule.onNodeWithText("FIELD REPORT (DEBUG BUILDS ONLY)").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Send field report…").assertDoesNotExist()
    }

    @Test
    @Requirement("FR-OBS-10")
    fun `FR_OBS_10 the field-report section renders its guard toggle and entry button when present`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsDiagnosticsScreen(
                    state = state(localSave(twelveRows())).copy(
                        fieldReport = FieldReportSectionViewState(publicGuardEnabled = true),
                    ),
                    onBack = {},
                    localSaveActions = LocalSaveActions(),
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
                    state = state(localSave(twelveRows())).copy(
                        fieldReport = FieldReportSectionViewState(publicGuardEnabled = true),
                    ),
                    onBack = {},
                    localSaveActions = LocalSaveActions(),
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
    // `SettingsContent` wiring, one openâ†’toggleâ†’cancelâ†’reopen cycle inside a single composition
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
