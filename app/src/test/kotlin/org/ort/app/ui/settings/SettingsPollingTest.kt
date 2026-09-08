package org.ort.app.ui.settings

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.ShedStatus
import org.robolectric.RobolectricTestRunner

/**
 * R-090: [SettingsPolling]'s pure mappers over the real `:pipeline` holders and [SettingsStore] —
 * never a fabricated fact where a holder has not measured anything yet (constitution I).
 */
@RunWith(RobolectricTestRunner::class)
class SettingsPollingTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun resetHolders() {
        InputStatus.reset()
        LevelStatus.reset()
        RigStatus.reset()
        ShedStatus.reset()
    }

    @Before
    fun clearHolders() {
        InputStatus.reset()
        LevelStatus.reset()
        RigStatus.reset()
        ShedStatus.reset()
    }

    @Test
    fun `R_090 capture with no input selected reads honestly, not fabricated`() {
        val state = SettingsPolling.capture(InMemorySettingsStore())
        assert(state.inputLabel == "No input selected")
        assert(state.levelLabel == "Not measured")
    }

    @Test
    fun `R_090 capture reflects a verified InputStatus device`() {
        InputStatus.opened(
            descriptor = AudioDeviceDescriptor(id = "1", kind = AudioDeviceKind.USB_DEVICE, label = "USB Audio Device"),
            nativeRateHz = 48_000,
            resamplerId = "linear",
            routeVerified = true,
            routedDeviceMatches = true,
            openedAtMillis = 0L,
        )
        val state = SettingsPolling.capture(InMemorySettingsStore())
        assert(state.inputLabel == "USB Audio Device")
        assert(state.inputSubLine.contains("verified"))
        assert(state.inputSubLine.contains("48000 Hz"))
    }

    @Test
    fun `R_090 capture reflects a measured level`() {
        LevelStatus.update(
            LevelStatus.State.Measured(
                peakDbfs = -14f,
                rmsDbfs = -20f,
                noiseFloorDbfs = -58f,
                clipped = false,
                clipCountLastSecond = 0,
                sampleRateHz = 48_000,
                updatedAtMillis = 0L,
            ),
            peakHistoryDbfs = emptyList(),
        )
        val state = SettingsPolling.capture(InMemorySettingsStore())
        assert(state.levelLabel.contains("-14"))
        assert(state.levelSubLine.contains("-58"))
    }

    @Test
    fun `R_090 rig with no rig configured is honest, not a fabricated connection`() {
        val state = SettingsPolling.rig()
        assert(!state.connected)
        assert(state.descriptorLabel == "No radio configured")
        assert(state.bands.isEmpty())
    }

    @Test
    fun `R_090 tier reads from the shed level placeholder and honours an override`() {
        ShedStatus.update(level = 0, backlog = 0)
        val notOverridden = SettingsPolling.tier(InMemorySettingsStore())
        assert(notOverridden.currentTierLabel == "3")
        assert(!notOverridden.isOverridden)

        val overridden = SettingsPolling.tier(InMemorySettingsStore(tierOverrideName = "T2"))
        assert(overridden.isOverridden)
        assert(overridden.overrideLabel == "Held at T2")
    }

    @Test
    fun `R_131_R_253 the root Tier row is Settings-dc-html verbatim at the real max tier, no override`(): Unit =
        runTest {
            ShedStatus.update(level = 0, backlog = 0)
            val root = SettingsPolling.root(context, InMemorySettingsStore())

            val tierRow = root.sections.flatMap { it.rows }.first { it.screen == SettingsScreenId.TIER }
            assert(tierRow.subLine == "Tier 3 of 3 · this phone's best · what it does not know") {
                "expected the board's verbatim copy at the real max tier, got ${tierRow.subLine}"
            }
        }

    @Test
    fun `R_131_R_253 the root Tier row never claims this phone's best while an override is held`(): Unit = runTest {
        ShedStatus.update(level = 0, backlog = 0)
        val root = SettingsPolling.root(context, InMemorySettingsStore(tierOverrideName = "T2"))

        val tierRow = root.sections.flatMap { it.rows }.first { it.screen == SettingsScreenId.TIER }
        assert(!tierRow.subLine.contains("this phone's best")) {
            "held at a lower tier is not this phone's best — must not claim it, got ${tierRow.subLine}"
        }
        assert(tierRow.subLine.contains("what it does not know"))
        assert(tierRow.subLine.contains("held at T2"))
    }

    @Test
    fun `R_090 contribute reports every category off when the store is off`() {
        val state = SettingsPolling.contribute(InMemorySettingsStore())
        assert(!state.contributionEnabled)
        assert(state.categories.all { !it.enabled })
        assert(state.neverIncluded == NEVER_LEAVES_DEVICE)
    }

    @Test
    fun `R_136 the never-included list is Settings-Contribute-dc-html verbatim, title and sub-line real fields`() {
        val state = SettingsPolling.contribute(InMemorySettingsStore())

        val voiceprints = state.neverIncluded.first { it.title == "Voiceprints" }
        assert(voiceprints.subLine == "a voice is a biometric · it is used here and only here") {
            "expected the board's own sub-line, got ${voiceprints.subLine}"
        }
        val stationKnowledge = state.neverIncluded.first { it.title == "Station knowledge" }
        assert(stationKnowledge.subLine != null && stationKnowledge.subLine!!.contains("patterns this phone")) {
            "expected the real per-item sub-line, not a title+description merge"
        }
        assert(state.neverIncluded.none { it.title.contains("and embeddings") }) {
            "R-136: title must not be the old merged 'Voiceprints and embeddings' string"
        }
    }

    @Test
    fun `R_090 about reads the real app version from the package manager, not a literal`() {
        val state = SettingsPolling.about(context)
        assert(state.appVersionLabel.isNotBlank())
        assert(state.minSdkLabel == "8.0")
    }

    @Test
    fun `R_138 about's version label carries a real build number from the package manager`() {
        val state = SettingsPolling.about(context)
        assert(state.appVersionLabel.contains("· build ")) {
            "expected 'version · build N' (R-138), got ${state.appVersionLabel}"
        }
    }

    @Test
    fun `R_138 round 7 the version label's commit hash and the Models row version are BuildConfig's real values`() {
        val state = SettingsPolling.about(context)
        // BuildConfig.GIT_SHORT_COMMIT is injected by app/build.gradle.kts from a real
        // `git rev-parse` — this asserts the view-state carries that exact value (appended only
        // when it is a real hash), never a second, independently-computed one.
        val commit = org.ort.app.BuildConfig.GIT_SHORT_COMMIT
        if (commit.isNotBlank() && commit != "unknown") {
            assert(state.appVersionLabel.endsWith("· $commit")) {
                "expected the version label to end with the real commit hash, got ${state.appVersionLabel}"
            }
        }
        assert(state.sherpaOnnxVersionLabel == org.ort.app.BuildConfig.SHERPA_ONNX_VERSION)
        assert(state.sherpaOnnxVersionLabel.isNotBlank())
    }

    @Test
    fun `R_137 every bundle file's trailing clause is Settings-Diagnostics-dc-html verbatim`() {
        val state = SettingsPolling.diagnostics()
        val descriptionByName = state.files.associate { it.name to it.description }

        assert(descriptionByName["lifecycle.log"] == "service start, stop, heartbeat gaps, OS kills — the F5 evidence")
        assert(
            descriptionByName["capture.log"] ==
                "route verifications, input device changes, level warnings, overruns",
        )
        assert(
            descriptionByName["pipeline.log"] ==
                "per-pass timings, tier changes with their cause, queue depth over time",
        )
        assert(
            descriptionByName["rig.log"] ==
                "CAT traffic, band changes, disconnects · frequencies included, they are not private",
        )
        assert(
            descriptionByName["device.json"] ==
                "SoC, RAM, Android version, OEM, thermal history · no serial, no IMEI, no account",
        )
        assert(
            descriptionByName["counts.json"] ==
                "overs by state, rejections by reason, corrections by tier · numbers only",
        )
    }

    @Test
    fun `R_090 storage sums real categories from the audio directory and installed model files`(): Unit = runTest {
        val state = SettingsPolling.storage(context, InMemorySettingsStore())
        assert(state.categories.any { it.label == "Audio" })
        assert(state.categories.any { it.label == "Models" })
        assert(state.budgetGb == null)
    }

    @Test
    fun `R_133 storage warnAtNightsLeft reads the real StorageForecast threshold, not a board literal`(): Unit =
        runTest {
            val state = SettingsPolling.storage(context, InMemorySettingsStore())
            assert(state.warnAtNightsLeft == org.ort.pipeline.capture.StorageForecast.THREE_NIGHTS_THRESHOLD.toInt())
        }

    @Test
    fun `R_090 export counts real sessions and overs from the database`(): Unit = runTest {
        val state = SettingsPolling.export(context)
        assert(state.allSessionCount == 0)
        assert(state.allOverCount == 0)
    }
}
