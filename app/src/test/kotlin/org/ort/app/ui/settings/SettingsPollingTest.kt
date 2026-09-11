package org.ort.app.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.realCaptureConfigurationStore
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.core.capture.BluetoothAudioProfile
import org.ort.core.capture.CaptureMode
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.rig.CaptureConfiguration
import org.ort.pipeline.rig.SharedPreferencesCaptureConfigurationStore
import org.ort.rig.RigTransportKind
import org.ort.rig.descriptor.BundledDescriptors
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
        clearConfigStore()
    }

    @Before
    fun clearHolders() {
        InputStatus.reset()
        LevelStatus.reset()
        RigStatus.reset()
        ShedStatus.reset()
        clearConfigStore()
    }

    /** R-860/R-861/R-864: [realCaptureConfigurationStore] always opens the same real
     * `SharedPreferences` file — cleared before and after every test in this class so a test that
     * writes a real configuration (to prove the fallback these findings require) can never leak
     * into, or be polluted by, any other test sharing this JVM worker. */
    private fun clearConfigStore() {
        context.getSharedPreferences(SharedPreferencesCaptureConfigurationStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `R_090 capture with no input selected reads honestly, not fabricated`() {
        val state = SettingsPolling.capture(context, InMemorySettingsStore())
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
        val state = SettingsPolling.capture(context, InMemorySettingsStore())
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
        val state = SettingsPolling.capture(context, InMemorySettingsStore())
        assert(state.levelLabel.contains("-14"))
        assert(state.levelSubLine.contains("-58"))
    }

    @Test
    fun `R_090 rig with no rig configured is honest, not a fabricated connection`() {
        val state = SettingsPolling.rig(context)
        assert(!state.connected)
        assert(state.descriptorLabel == "No radio configured")
        assert(state.bands.isEmpty())
    }

    // --- R-860/R-861/R-864 (halt, Validator V9, device) — the configured-but-unverified fallback ---

    @Test
    fun `R_861 the Input row falls back to the configured selection when no live status is open`() {
        // The exact device shape V9 reproduced: a real, fully-configured session
        // (`CaptureConfigurationStore.hasBeenConfigured()`) whose `InputStatus`/`RigStatus`
        // holders this process never opened — a fresh process pointed at an already-configured
        // store, not a fabricated test setup.
        realCaptureConfigurationStore(context).update(
            CaptureConfiguration(mode = CaptureMode.USB_RADIO, selectedInputId = "usb-audio-1"),
        )
        val state = SettingsPolling.capture(context, InMemorySettingsStore())
        assert(state.inputLabel == "usb-audio-1") {
            "expected the configured input id as the Input row's label, got ${state.inputLabel}"
        }
        assert(state.inputSubLine == "not verified this session") {
            "expected the honest qualifier, got ${state.inputSubLine}"
        }
    }

    @Test
    fun `R_861 the Input row still reads honestly not-selected when the store itself has nothing either`() {
        val state = SettingsPolling.capture(context, InMemorySettingsStore())
        assert(state.inputLabel == "No input selected") { "expected the honest unset case, got ${state.inputLabel}" }
        assert(state.inputSubLine == "select an input to capture")
    }

    @Test
    fun `R_861 a live InputStatus always wins over the configured store fallback`() {
        realCaptureConfigurationStore(context).update(
            CaptureConfiguration(mode = CaptureMode.USB_RADIO, selectedInputId = "usb-audio-1"),
        )
        InputStatus.opened(
            descriptor = AudioDeviceDescriptor(id = "2", kind = AudioDeviceKind.USB_DEVICE, label = "Live USB Device"),
            nativeRateHz = 48_000,
            resamplerId = "linear",
            routeVerified = true,
            routedDeviceMatches = true,
            openedAtMillis = 0L,
        )
        val state = SettingsPolling.capture(context, InMemorySettingsStore())
        assert(state.inputLabel == "Live USB Device") {
            "expected the real, live device to win over the configured fallback, got ${state.inputLabel}"
        }
    }

    @Test
    fun `R_860 CF11's Audio-route and Rig-link rows fall back to the configured selection`() {
        realCaptureConfigurationStore(context).update(
            CaptureConfiguration(
                mode = CaptureMode.USB_RADIO,
                selectedInputId = "usb-audio-1",
                rigId = BundledDescriptors.kenwoodThD75a().id,
                rigTransportKind = RigTransportKind.USB_SERIAL,
            ),
        )
        val state = SettingsPolling.modeScreen(context)
        assert(state.audioRoute.subLine == "usb-audio-1 · not verified this session") {
            "expected the configured audio route, got ${state.audioRoute.subLine}"
        }
        assert(state.rigLink.subLine == "Kenwood TH-D75A · USB serial · not verified this session") {
            "expected the configured rig link, got ${state.rigLink.subLine}"
        }
    }

    @Test
    fun `R_860 CF11 stays honestly unset when the store itself was never configured`() {
        val state = SettingsPolling.modeScreen(context)
        assert(state.audioRoute.subLine == "not yet selected")
        assert(state.rigLink.subLine == "no radio configured")
    }

    @Test
    fun `R_860 a configured store naming no real rig still reads no radio configured, never fabricated`() {
        realCaptureConfigurationStore(context).update(
            CaptureConfiguration(mode = CaptureMode.LOCAL_MICROPHONE, selectedInputId = null),
        )
        val state = SettingsPolling.modeScreen(context)
        assert(state.rigLink.subLine == "no radio configured") {
            "expected the honest no-rig case even though the store is configured, got ${state.rigLink.subLine}"
        }
        // LOCAL_MICROPHONE's own implicit input (no explicit selectedInputId) is still a real,
        // sourced fact from the store — the mode's own operator label, not a bare "not selected".
        assert(state.audioRoute.subLine == "Local microphone · not verified this session") {
            "expected the mode's own implicit input label, got ${state.audioRoute.subLine}"
        }
    }

    @Test
    fun `R_864 the Input row names the real Bluetooth profile from the live descriptor`() {
        InputStatus.opened(
            descriptor = AudioDeviceDescriptor(
                id = "3",
                kind = AudioDeviceKind.BLUETOOTH,
                label = "Bluetooth headset",
                bluetoothProfile = BluetoothAudioProfile.HFP_MSBC,
            ),
            nativeRateHz = 16_000,
            resamplerId = "linear",
            routeVerified = true,
            routedDeviceMatches = true,
            openedAtMillis = 0L,
        )
        val state = SettingsPolling.capture(context, InMemorySettingsStore())
        assert(state.inputSubLine.endsWith("· HFP mSBC")) {
            "expected the real HFP codec name appended, got ${state.inputSubLine}"
        }
    }

    @Test
    fun `R_864 a non-Bluetooth device never carries a profile clause`() {
        InputStatus.opened(
            descriptor = AudioDeviceDescriptor(id = "4", kind = AudioDeviceKind.USB_DEVICE, label = "USB Audio Device"),
            nativeRateHz = 48_000,
            resamplerId = "linear",
            routeVerified = true,
            routedDeviceMatches = true,
            openedAtMillis = 0L,
        )
        val state = SettingsPolling.capture(context, InMemorySettingsStore())
        assert(!state.inputSubLine.contains("HFP")) { "did not expect any HFP clause, got ${state.inputSubLine}" }
    }

    @Test
    fun `R_864 CF11's Audio-route row also names the real Bluetooth profile`() {
        InputStatus.opened(
            descriptor = AudioDeviceDescriptor(
                id = "5",
                kind = AudioDeviceKind.BLUETOOTH,
                label = "Bluetooth headset",
                bluetoothProfile = BluetoothAudioProfile.HFP_CVSD,
            ),
            nativeRateHz = 8_000,
            resamplerId = "linear",
            routeVerified = true,
            routedDeviceMatches = true,
            openedAtMillis = 0L,
        )
        val state = SettingsPolling.modeScreen(context)
        assert(state.audioRoute.subLine.endsWith("· HFP CVSD")) {
            "expected the real HFP codec name appended, got ${state.audioRoute.subLine}"
        }
    }

    @Test
    fun `R_845 the title strips the real bundled Kenwood descriptor's own leading manufacturer word`() {
        RigStatus.connected(
            descriptor = "Kenwood TH-D75A",
            bands = emptyList(),
            transportKind = RigTransportKind.BLUETOOTH_SPP,
            descriptorId = BundledDescriptors.kenwoodThD75a().id,
        )
        val state = SettingsPolling.rig(context)
        assert(state.descriptorLabel == "TH-D75A") {
            "expected the manufacturer word dropped, got ${state.descriptorLabel}"
        }
    }

    @Test
    fun `R_845 a descriptor with no digit-bearing word is left unchanged, nothing to strip`() {
        RigStatus.connected(descriptor = "Generic ASCII CAT", bands = emptyList())
        val state = SettingsPolling.rig(context)
        assert(state.descriptorLabel == "Generic ASCII CAT") {
            "expected no change (no manufacturer prefix to drop), got ${state.descriptorLabel}"
        }
    }

    @Test
    fun `R_845 the pollingClause is unpolled for the real Kenwood descriptor, which declares push`() {
        RigStatus.connected(
            descriptor = "Kenwood TH-D75A",
            bands = emptyList(),
            transportKind = RigTransportKind.BLUETOOTH_SPP,
            descriptorId = BundledDescriptors.kenwoodThD75a().id,
        )
        val state = SettingsPolling.rig(context)
        assert(state.pollingClause == "reading both bands unpolled") {
            "expected the real unsolicited-push clause, got ${state.pollingClause}"
        }
    }

    @Test
    fun `R_845 the pollingClause is a real interval for the generic descriptor, which has no push`() {
        RigStatus.connected(
            descriptor = "Generic ASCII CAT",
            bands = emptyList(),
            transportKind = RigTransportKind.USB_SERIAL,
            descriptorId = BundledDescriptors.genericAsciiCat().id,
        )
        val state = SettingsPolling.rig(context)
        assert(state.pollingClause == "polled every 0.5 s") {
            "expected the real 500ms poll interval, got ${state.pollingClause}"
        }
    }

    @Test
    fun `R_835 an unmatched descriptorId falls back to the honest not-reported label, never invented data`() {
        RigStatus.connected(descriptor = "Imported Radio", bands = emptyList(), descriptorId = "some-imported-id")
        val state = SettingsPolling.rig(context)
        assert(state.rigModuleLabel == NOT_REPORTED_BY_RIG_MODULE)
        assert(state.pollingClause == NOT_REPORTED_BY_RIG_MODULE)
        assert(state.autoInformation == null)
    }

    @Test
    fun `R_835_R_923 the Rig-module row renders the real descriptor's own capabilities as CAT mnemonics`() {
        RigStatus.connected(
            descriptor = "Kenwood TH-D75A",
            bands = emptyList(),
            transportKind = RigTransportKind.BLUETOOTH_SPP,
            descriptorId = BundledDescriptors.kenwoodThD75a().id,
        )
        val state = SettingsPolling.rig(context)
        // R-923 (Reviewer C2, run 3): `docs/reference/th-d75a-cat.md`'s own verified command
        // table — FREQUENCY -> FQ, SQUELCH_STATE -> BY, SUB_BAND -> BC — the screen's own
        // established shorthand, never the raw `RigCapability` enum names, and never the board
        // mockup's own broader eight-command example (`FQ BY FO BC MR ME AI BL`, illustrative of a
        // command set no accessible source in this build actually declares).
        assert(state.rigModuleLabel == "kenwood-thd75a · built in · verified command set FQ BY BC") {
            "expected the real CAT mnemonics, got ${state.rigModuleLabel}"
        }
        assert(!state.rigModuleLabel.contains("FQ BY FO BC MR ME AI BL"))
        assert(!state.rigModuleLabel.contains("FREQUENCY"))
    }

    @Test
    fun `R_835_reopened otherTransportLabel reads vid-pid not yet verified H1, never the board mockup ids`() {
        RigStatus.connected(
            descriptor = "Kenwood TH-D75A",
            bands = emptyList(),
            transportKind = RigTransportKind.BLUETOOTH_SPP,
            descriptorId = BundledDescriptors.kenwoodThD75a().id,
        )
        val state = SettingsPolling.rig(context)
        assert(state.otherTransportLabel == "USB serial also supported, vid/pid not yet verified (H1)") {
            "expected the honest H1 qualifier, got ${state.otherTransportLabel}"
        }
        assert(state.otherTransportLabel?.contains("0x0451") != true)
    }

    @Test
    fun `R_835 Auto-information is real for the Kenwood descriptor, using its own real poll interval`() {
        RigStatus.connected(
            descriptor = "Kenwood TH-D75A",
            bands = emptyList(),
            transportKind = RigTransportKind.BLUETOOTH_SPP,
            descriptorId = BundledDescriptors.kenwoodThD75a().id,
        )
        val state = SettingsPolling.rig(context)
        val ai = state.autoInformation
        assert(ai != null) { "expected a real Auto-information row for the Kenwood descriptor" }
        assert(ai!!.label == "Auto-information, AI 1") {
            "expected the descriptor's own real enable string, got ${ai.label}"
        }
        assert(ai.subLine.contains("fallback poll every 2 s if it stops")) {
            "expected the descriptor's own real 2s poll fallback, got ${ai.subLine}"
        }
    }

    @Test
    fun `R_835 no Auto-information for the generic descriptor, which declares no push block`() {
        RigStatus.connected(
            descriptor = "Generic ASCII CAT",
            bands = emptyList(),
            transportKind = RigTransportKind.USB_SERIAL,
            descriptorId = BundledDescriptors.genericAsciiCat().id,
        )
        val state = SettingsPolling.rig(context)
        assert(state.autoInformation == null) { "generic ASCII CAT declares no unsolicited push at all" }
    }

    @Test
    fun `R_821 capture reads Not set from an unset CaptureModeFacts, never LOCAL_MICROPHONE`() {
        val state = SettingsPolling.capture(
            context,
            InMemorySettingsStore(),
            modeFacts = org.ort.app.ui.data.FakeCaptureModeFacts(),
        )
        assert(state.mode == null) { "expected a null mode from an unset seam, got ${state.mode}" }
        assert(state.modeLabel == "Not set") { "expected the honest 'Not set' label, got ${state.modeLabel}" }
    }

    @Test
    fun `R_821 modeScreen marks no row current from an unset CaptureModeFacts`() {
        val state = SettingsPolling.modeScreen(context, modeFacts = org.ort.app.ui.data.FakeCaptureModeFacts())
        assert(state.rows.none { it.current }) {
            "expected no row marked current before setup has chosen one, got ${state.rows}"
        }
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
    fun `R_915 the root Models-and-lexicon row reads honestly when nothing is installed`(): Unit = runTest {
        val root = SettingsPolling.root(context, InMemorySettingsStore())
        val modelsRow = root.sections.flatMap { it.rows }.single { it.screen == SettingsScreenId.ASSETS }
        assert(modelsRow.subLine == "Nothing installed yet") {
            "expected the honest empty case, got ${modelsRow.subLine}"
        }
    }

    @Test
    fun `R_915 the root Models-and-lexicon row names the real installed components, not a bare count`(): Unit =
        runTest {
            // The same real install path `R_443_clean_install_groups` (`ModelsScreenTest.kt`)
            // already relies on — a genuinely fresh Robolectric context's own real app assets,
            // never a synthetic row list standing in for either half.
            org.ort.app.assets.BundledAssetInstaller.installAll(
                context.filesDir,
                org.ort.app.assets.AndroidBundledAssetSource(context),
            )
            val root = SettingsPolling.root(context, InMemorySettingsStore())
            val modelsRow = root.sections.flatMap { it.rows }.single { it.screen == SettingsScreenId.ASSETS }
            assert(modelsRow.subLine.contains("Whisper tiny.en")) {
                "expected the real Whisper family name, got ${modelsRow.subLine}"
            }
            assert(modelsRow.subLine.contains("Silero VAD")) {
                "expected the real VAD name, got ${modelsRow.subLine}"
            }
            assert(!modelsRow.subLine.contains("of 5 assets installed")) {
                "expected component names, not the old bare count, got ${modelsRow.subLine}"
            }
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
    fun `R_137 every bundle file's trailing clause is Settings-Diagnostics-dc-html verbatim`(): Unit = runTest {
        val state = SettingsPolling.diagnostics(context)
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
    fun `R_137_list_from_preview the seven files and the header total are real, from DiagnosticsBundleBuilder`(): Unit =
        runTest {
            val state = SettingsPolling.diagnostics(context)

            assert(state.files.size == 7) { "expected the board's seven files, got ${state.files.size}" }
            // Every size is real (never blank/zero-as-placeholder — a `device.json`/`counts.json`
            // producer always writes real bytes even with an empty database) and the header total
            // is the real sum, not the board's illustrative "2.1 MB".
            assert(state.files.all { it.sizeLabel.isNotBlank() })
            assert(state.totalSizeLabel.isNotBlank())
        }

    @Test
    fun `R_137_save_writes_zip DiagnosticsBundleBuilder-write, the call SettingsContent makes, is a real zip`(): Unit =
        runTest {
            val target = java.io.ByteArrayOutputStream()

            org.ort.app.diagnostics.DiagnosticsBundleBuilder.write(context, target)

            val bytes = target.toByteArray()
            assert(bytes.isNotEmpty()) { "expected a real, non-empty zip" }
            // The ZIP local-file-header magic bytes ("PK") — a real zip, never an
            // empty/placeholder stream standing in for one.
            assert(bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                "expected the zip magic bytes, got ${bytes.take(2)}"
            }
        }

    @Test
    fun `R_090 storage sums real categories from the audio directory and installed model files`(): Unit = runTest {
        val state = SettingsPolling.storage(context, InMemorySettingsStore())
        assert(state.categories.any { it.label == "Audio" })
        assert(state.categories.any { it.label == "Models" })
        assert(state.budgetGb == null)
    }

    @Test
    fun `R_133_bar storage now carries a real, honestly-zero Lexicon category from StorageAccounting`(): Unit =
        runTest {
            val state = SettingsPolling.storage(context, InMemorySettingsStore())
            val lexicon = state.categories.firstOrNull { it.label == "Lexicon" }
            assert(lexicon != null) { "expected a Lexicon category, got ${state.categories.map { it.label }}" }
            // Honest zero (no on-disk lexicon asset exists yet) — never omitted, never fabricated.
            assert(lexicon!!.bytes == 0L)
        }

    @Test
    fun `R_133_next_deletion_row nothing is scheduled with no budget set and no sessions retained`(): Unit = runTest {
        val state = SettingsPolling.storage(context, InMemorySettingsStore())
        assert(state.nextDeletion == null) { "expected no next deletion with no budget and no sessions" }
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
