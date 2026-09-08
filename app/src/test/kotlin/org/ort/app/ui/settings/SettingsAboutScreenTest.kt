package org.ort.app.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * R-138 (register, round 7 System validator pass 3): `Settings-About.dc.html`'s "Build" section
 * row order is Models, Runtime, Radio, Android, Licences — this screen used to lead with Android.
 * The Models row now carries the real sherpa-onnx version (`SettingsPolling.about`'s own
 * `BuildConfig.SHERPA_ONNX_VERSION`, read from `gradle/libs.versions.toml`, never hardcoded here);
 * Runtime/Radio stay the honest, version-free statements they already were, since neither ONNX
 * Runtime nor usb-serial-for-android is an actual Gradle dependency of this build yet.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsAboutScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun state() = SettingsAboutViewState(
        appVersionLabel = "0.1.0 · build 1 · abc1234",
        androidVersionLabel = "14",
        minSdkLabel = "8.0",
        sherpaOnnxVersionLabel = "1.13.7",
    )

    @Test
    @Requirement("R-138")
    fun `R_138 the Models row shows the real sherpa-onnx version, never a bare label`() {
        composeTestRule.setContent { OrtTheme { SettingsAboutScreen(state = state(), onBack = {}) } }

        composeTestRule.onNodeWithText("sherpa-onnx 1.13.7 · whisper · Silero").assertExists()
    }

    @Test
    @Requirement("R-138")
    fun `R_138 the Build section rows read Models, Runtime, Radio, Android in that order`() {
        composeTestRule.setContent { OrtTheme { SettingsAboutScreen(state = state(), onBack = {}) } }

        val modelsY = composeTestRule.onNodeWithText("sherpa-onnx 1.13.7 · whisper · Silero")
            .fetchSemanticsNode().positionInRoot.y
        val runtimeY = composeTestRule.onNodeWithText("ONNX Runtime · NNAPI where this device offers it")
            .fetchSemanticsNode().positionInRoot.y
        val radioY = composeTestRule.onNodeWithText("usb-serial-for-android").fetchSemanticsNode().positionInRoot.y
        val androidY = composeTestRule.onNodeWithText("14").fetchSemanticsNode().positionInRoot.y

        assert(modelsY < runtimeY) { "expected Models above Runtime, got $modelsY vs $runtimeY" }
        assert(runtimeY < radioY) { "expected Runtime above Radio, got $runtimeY vs $radioY" }
        assert(radioY < androidY) { "expected Radio above Android, got $radioY vs $androidY" }
    }
}
