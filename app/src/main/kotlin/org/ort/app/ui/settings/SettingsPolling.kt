package org.ort.app.ui.settings

import android.content.Context
import org.ort.app.BuildConfig
import org.ort.app.ui.data.CaptureModeFacts
import org.ort.app.ui.data.RealCaptureModeFacts
import org.ort.app.ui.navigation.StorageFooterViewState
import org.ort.app.ui.navigation.toGigabyteLabel
import org.ort.capture.android.AudioDeviceKind
import org.ort.core.SystemClock
import org.ort.core.capture.CaptureMode
import org.ort.core.capture.CaptureModePresets
import org.ort.core.capture.RigTransportKind
import org.ort.data.OrtDatabase
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.pipeline.capture.collectSessionStorageSummaries
import org.ort.pipeline.capture.computeNextDeletion
import org.ort.pipeline.capture.measureStorageAccounting
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * WP10's read path for the `Settings` root and its nine sub-screens (register R-090), following
 * `ui/data/ReaderPolling.kt`'s own idiom (`OrtDatabase.create(context.applicationContext)` inside
 * each `suspend fun`, process-wide holders read directly, no caching) without editing that file —
 * `ReaderPolling.kt` is WP4's alone.
 *
 * Every fact here comes from a real holder, the real database, or [SettingsStore] — never a
 * literal lifted from an artboard. Where no real source exists (a tier detector, a rig connection,
 * an exporter, a diagnostics-bundle or contribution-upload producer), the corresponding view-state
 * says so plainly rather than showing the artboard's example numbers — see this package's report
 * for the full list of what is therefore honestly unavailable in this build.
 */
public object SettingsPolling {

    private const val MAX_TIER: Int = 3

    public suspend fun root(context: Context, store: SettingsStore): SettingsRootViewState {
        val footer = StorageFooterViewState.fromAudioDirectory(context)
        val modelsState = org.ort.app.ui.data.ModelsController.currentState(context)
        val installedCount = modelsState.rows.count { it.status != org.ort.app.ui.data.ModelRowStatus.NOT_INSTALLED }

        return SettingsRootViewState(
            sections = listOf(
                SettingsSectionViewState(
                    label = "Capture",
                    rows = listOf(
                        SettingsRowViewState("Input and level", inputSummaryLine(), SettingsScreenId.CAPTURE),
                        SettingsRowViewState("Radio", rigSummaryLine(), SettingsScreenId.RIG),
                        SettingsRowViewState("Tier and capability", tierSummaryLine(store), SettingsScreenId.TIER),
                    ),
                ),
                SettingsSectionViewState(
                    label = "Records",
                    rows = listOf(
                        SettingsRowViewState(
                            "Storage and retention",
                            "${footer.audioUsedBytes.toGigabyteLabel()} audio" +
                                (store.audioBudgetGb?.let { " of $it GB" } ?: " · no budget set"),
                            SettingsScreenId.STORAGE,
                        ),
                        SettingsRowViewState(
                            "Models and lexicon",
                            "$installedCount of ${modelsState.rows.size} assets installed",
                            SettingsScreenId.ASSETS,
                        ),
                        SettingsRowViewState(
                            "Export",
                            "Log, transcripts and digests · never voiceprints, names or location",
                            SettingsScreenId.EXPORT,
                        ),
                    ),
                ),
                SettingsSectionViewState(
                    label = "Privacy",
                    rows = listOf(
                        SettingsRowViewState(
                            "Contribute to the corpus",
                            if (store.contributionEnabled) {
                                "On · categories chosen individually"
                            } else {
                                "Off · nothing is shared unless you choose each category"
                            },
                            SettingsScreenId.CONTRIBUTE,
                        ),
                        SettingsRowViewState(
                            "Diagnostics",
                            "A bundle you can read before you save it",
                            SettingsScreenId.DIAGNOSTICS,
                        ),
                    ),
                ),
                SettingsSectionViewState(
                    label = "About",
                    rows = listOf(
                        SettingsRowViewState(
                            "Offline radio transcriber",
                            aboutSummaryLine(context),
                            SettingsScreenId.ABOUT,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun inputSummaryLine(): String = when (val state = InputStatus.state) {
        InputStatus.State.None -> "No input selected"
        is InputStatus.State.Opened ->
            "${state.descriptor.label} · " + (if (state.routeVerified) "verified" else "not verified")
        is InputStatus.State.Lost -> "${state.lastKnown.descriptor.label} · input lost"
        is InputStatus.State.Mismatch -> "Route mismatch"
    }

    private fun rigSummaryLine(): String = when (val state = RigStatus.state) {
        RigStatus.State.Absent -> "No radio configured"
        is RigStatus.State.Connected -> "${state.descriptor} · connected · ${state.bands.size} band(s)"
        is RigStatus.State.Stale -> "${state.lastKnown.descriptor} · disconnected"
    }

    /** R-131/R-253 (register, round 6 System validator pass 2): `Settings.dc.html`'s own copy is
     * "Tier 3 of 3 · this phone's best · what it does not know" — verbatim at the common case (the
     * current tier is this phone's real maximum, no override held), but "this phone's best" would
     * be a false claim at a lower tier (a thermal shed, or a held override), so that clause is
     * shown only then; "what it does not know" is always true — every tier below the top one misses
     * something `Settings-Tier` explains, which this row's own tap opens. */
    private fun tierSummaryLine(store: SettingsStore): String {
        val current = currentTierNumber()
        val overrideName = store.tierOverrideName
        val overrideNote = overrideName?.let { " · held at $it" } ?: ""
        val bestNote = if (current == MAX_TIER && overrideName == null) " · this phone's best" else ""
        return "Tier $current of $MAX_TIER$bestNote$overrideNote · what it does not know"
    }

    private fun aboutSummaryLine(context: Context): String {
        val version = appVersionName(context)
        return "$version · licences · the offline promise"
    }

    private fun currentTierNumber(): Int = (MAX_TIER - ShedStatus.currentLevel).coerceIn(0, MAX_TIER)

    /**
     * CF02 (amended 2026-09-10, FR-CAP-12): `suspend` now — the leading Capture-mode row's
     * [SettingsCaptureViewState.modeLabel]/[modeSubLine] read through [modeFacts]
     * ([org.ort.app.ui.data.CaptureModeFacts]), which queries `:data` for the most recent session's
     * recorded mode (see that interface's own doc comment for exactly what it reads and why).
     * [context] is new for the same reason. The Input row's own sub-line now names "room audio" or
     * "radio audio" from the routed device's real [AudioDeviceKind] (FR-CAP-10, FR-CAP-3a) — the
     * built-in mic is the one device kind that is ever the room, never the radio.
     */
    public suspend fun capture(
        context: Context,
        store: SettingsStore,
        modeFacts: CaptureModeFacts = RealCaptureModeFacts(context),
    ): SettingsCaptureViewState {
        val (inputLabel, inputSub) = when (val state = InputStatus.state) {
            InputStatus.State.None -> "No input selected" to "select an input to capture"
            is InputStatus.State.Opened -> {
                val verified = if (state.routeVerified) "verified" else "not verified"
                val audioKindNote = if (state.descriptor.kind == AudioDeviceKind.BUILT_IN_MIC) {
                    "room audio"
                } else {
                    "radio audio"
                }
                state.descriptor.label to
                    "$verified · ${state.nativeRateHz} Hz native · ${state.resamplerId} · $audioKindNote"
            }
            is InputStatus.State.Lost -> state.lastKnown.descriptor.label to "input lost since ${state.sinceMillis}"
            is InputStatus.State.Mismatch -> (state.actual?.label ?: "unknown device") to
                "expected ${state.expected.label} — route mismatch"
        }
        val (levelLabel, levelSub) = when (val state = LevelStatus.state) {
            LevelStatus.State.NotMeasured -> "Not measured" to "no level signal published yet this session"
            is LevelStatus.State.Measured -> "Speech peaks %.0f dBFS".format(Locale.ROOT, state.peakDbfs) to
                "noise floor ${state.noiseFloorDbfs?.let { "%.0f".format(Locale.ROOT, it) } ?: "not measured"} dBFS" +
                if (state.clipped) " · clipping" else ""
        }
        val mode = modeFacts.currentMode()
        return SettingsCaptureViewState(
            inputLabel = inputLabel,
            inputSubLine = inputSub,
            levelLabel = levelLabel,
            levelSubLine = levelSub,
            levelWarnEnabled = store.levelWarnEnabled,
            noiseReductionEnabled = store.noiseReductionEnabled,
            bandPassEnabled = store.bandPassFilterEnabled,
            manualFrequencyMhz = store.manualFrequencyMhz,
            mode = mode,
            modeLabel = mode?.operatorLabel ?: "Not yet set",
            modeSubLine = mode?.let { modeSubLine(it) } ?: "complete setup to record a mode",
        )
    }

    /**
     * CF02/CF11's per-mode sub-line, e.g. "audio by cable · rig link over Bluetooth · a change
     * applies at the next session" (`Settings-Capture.dc.html`'s own Bluetooth-mode example,
     * matched verbatim by this formula) — derived from [CaptureModePresets.presetsFor], never a
     * second, hand-written copy of the pairing FR-CAP-8's table already states once.
     */
    internal fun modeSubLine(mode: CaptureMode): String {
        val preset = CaptureModePresets.presetsFor(mode)
        val audioClause = when (preset.preferredRouteKind) {
            org.ort.core.capture.AudioRouteKind.BUILT_IN_MIC -> "room audio"
            org.ort.core.capture.AudioRouteKind.BLUETOOTH_SCO -> "audio over Bluetooth"
            else -> "audio by cable"
        }
        val rigClause = when (preset.preferredRigTransportKind) {
            null -> "frequency by hand"
            RigTransportKind.USB_SERIAL -> "rig link on the same cable"
            RigTransportKind.BLUETOOTH_SPP -> "rig link over Bluetooth"
        }
        return "$audioClause · $rigClause · a change applies at the next session"
    }

    /**
     * CF11 (`Settings-Mode.dc.html`): the three-mode picker, the current one marked from
     * [modeFacts], the live-session banner from [CaptureModeFacts.isSessionLive], and "what the
     * mode set" from the same real [InputStatus]/[RigStatus] facts CF02/CF06 read — never a second,
     * differently-sourced copy of either fact.
     */
    public suspend fun modeScreen(
        context: Context,
        store: SettingsStore,
        modeFacts: CaptureModeFacts = RealCaptureModeFacts(context),
    ): SettingsModeViewState {
        val current = modeFacts.currentMode()
        val pending = store.pendingCaptureModeName?.let { name -> CaptureMode.entries.firstOrNull { it.name == name } }
        val rows = CaptureMode.entries.map { mode ->
            SettingsModeRowViewState(
                mode = mode,
                descriptionLabel = modeRowDescription(mode),
                current = mode == current,
                pending = mode == pending,
            )
        }
        val audioRoute = when (val state = InputStatus.state) {
            InputStatus.State.None -> SettingsModeSetRowViewState("Audio route", "not yet selected")
            is InputStatus.State.Opened -> {
                val verified = if (state.routeVerified) "verified" else "not verified"
                val kindNote = if (state.descriptor.kind == AudioDeviceKind.BUILT_IN_MIC) {
                    "room audio, not the radio"
                } else {
                    "radio audio, not the room"
                }
                SettingsModeSetRowViewState("Audio route", "${state.descriptor.label} · $verified · $kindNote")
            }
            is InputStatus.State.Lost ->
                SettingsModeSetRowViewState("Audio route", "${state.lastKnown.descriptor.label} · input lost")
            is InputStatus.State.Mismatch -> SettingsModeSetRowViewState("Audio route", "route mismatch")
        }
        val rigLink = when (val state = RigStatus.state) {
            RigStatus.State.Absent -> SettingsModeSetRowViewState("Rig link", "no radio configured")
            is RigStatus.State.Connected ->
                SettingsModeSetRowViewState("Rig link", "${state.descriptor} · connected")
            is RigStatus.State.Stale ->
                SettingsModeSetRowViewState("Rig link", "${state.lastKnown.descriptor} · stale since ${state.sinceMillis}")
        }
        return SettingsModeViewState(
            rows = rows,
            sessionLive = modeFacts.isSessionLive(),
            audioRoute = audioRoute,
            rigLink = rigLink,
        )
    }

    /** CF11's top-list per-mode description (`Settings-Mode.dc.html` verbatim). */
    private fun modeRowDescription(mode: CaptureMode): String = when (mode) {
        CaptureMode.LOCAL_MICROPHONE -> "room audio · frequency by hand"
        CaptureMode.USB_RADIO -> "audio adapter and CAT on the cable"
        CaptureMode.BLUETOOTH_RADIO -> "CAT over Bluetooth · audio by cable or Bluetooth"
    }

    public fun rig(): SettingsRigViewState = when (val state = RigStatus.state) {
        RigStatus.State.Absent -> SettingsRigViewState(
            descriptorLabel = "No radio configured",
            connected = false,
            staleSinceLabel = null,
            bands = emptyList(),
        )

        is RigStatus.State.Connected -> SettingsRigViewState(
            descriptorLabel = state.descriptor,
            connected = true,
            staleSinceLabel = null,
            bands = state.bands.map { band ->
                SettingsRigBandViewState(
                    label = band.band,
                    frequencyLabel = band.frequencyHz?.let { "%.3f".format(Locale.ROOT, it / 1_000_000.0) } ?: "—",
                    statusLabel = (band.mode ?: "—") + " · " + if (band.squelchOpen) "squelch open" else "closed",
                    squelchOpen = band.squelchOpen,
                )
            },
        )

        is RigStatus.State.Stale -> SettingsRigViewState(
            descriptorLabel = state.lastKnown.descriptor,
            connected = false,
            staleSinceLabel = "since ${state.sinceMillis}",
            bands = state.lastKnown.bands.map { band ->
                SettingsRigBandViewState(
                    label = band.band,
                    frequencyLabel = (
                        band.frequencyHz?.let { "%.3f".format(Locale.ROOT, it / 1_000_000.0) } ?: "—"
                        ) + "?",
                    statusLabel = "stale",
                    squelchOpen = false,
                )
            },
        )
    }

    public fun tier(store: SettingsStore): SettingsTierViewState {
        val current = currentTierNumber()
        val overrideLabel = store.tierOverrideName?.let { "Held at $it" } ?: "Let the phone choose"
        return SettingsTierViewState(
            currentTierLabel = "$current",
            maxTierLabel = "$MAX_TIER",
            overrideLabel = overrideLabel,
            isOverridden = store.tierOverrideName != null,
        )
    }

    // R-170 (ReaderPolling.kt's own established reasoning, mirrored here rather than imported —
    // that file is WP4's alone): Locale.ROOT has no real month-name data ("Sep" degrades to
    // "M09"); guide §9's dates are prose, read in the device's own locale.
    private val nightDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
        .withZone(ZoneId.systemDefault())

    /** R-133 (round 8): mirrors `RealCaptureService.STORAGE_FLOOR_BYTES` — `internal` to
     * `:pipeline`, not importable here, same limitation [SettingsStorageViewState.hardFloorLabel]'s
     * own doc comment already names for the "100 MB" label this same real value backs. */
    private const val HARD_FLOOR_BYTES: Long = 100L * 1024 * 1024

    /**
     * R-133 (round 8, register): now sourced from `:pipeline`'s real
     * [org.ort.pipeline.capture.measureStorageAccounting]/[org.ort.pipeline.capture.collectSessionStorageSummaries]/
     * [org.ort.pipeline.capture.computeNextDeletion] (WP11c) rather than this package's own ad-hoc
     * directory sums — the four-segment usage bar (Audio/Models/Records/Lexicon, `Lexicon` honestly
     * `0` today — see `StorageAccounting`'s own doc comment for why) and the "Next deletion" row
     * are both real facts, never board literals. `suspend` (was not, before this round) because
     * every one of those three calls is real file/database I/O.
     */
    public suspend fun storage(context: Context, store: SettingsStore): SettingsStorageViewState {
        val footer = StorageFooterViewState.fromAudioDirectory(context)
        val dbFile = context.getDatabasePath(OrtDatabase.DATABASE_NAME)
        val databaseFiles = listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm"))
        val accounting = measureStorageAccounting(context.filesDir, databaseFiles)

        val forecast = StorageForecast.state
        val nightsLeft = when (forecast) {
            is StorageForecast.State.Fine -> forecast.nightsLeft
            is StorageForecast.State.ThreeNightsLeft -> forecast.nightsLeft
            is StorageForecast.State.OneNightLeft -> forecast.nightsLeft
            is StorageForecast.State.AtFloor, is StorageForecast.State.NotYetMeasured -> null
        }

        val db = OrtDatabase.create(context.applicationContext)
        val sessions = collectSessionStorageSummaries(db, context.filesDir)
        val budgetBytes = store.audioBudgetGb?.let { it * 1_000_000_000L }
        val nextDeletion = computeNextDeletion(
            sessionsOldestFirst = sessions,
            audioBytesUsed = accounting.audioBytes,
            budgetBytes = budgetBytes,
            floorBytes = HARD_FLOOR_BYTES,
            freeBytes = footer.freeBytes,
            nowMillis = SystemClock.wallMillis(),
        )

        return SettingsStorageViewState(
            usedBytes = accounting.audioBytes,
            budgetGb = store.audioBudgetGb,
            deviceFreeBytes = footer.freeBytes,
            categories = listOf(
                SettingsStorageCategoryViewState("Audio", accounting.audioBytes),
                SettingsStorageCategoryViewState("Models", accounting.modelBytes),
                SettingsStorageCategoryViewState("Records", accounting.recordBytes),
                SettingsStorageCategoryViewState("Lexicon", accounting.lexiconBytes),
            ),
            nightsLeftLabel = nightsLeft?.let { "${it.toInt().coerceAtLeast(0)} nights left" },
            autoPruneEnabled = store.autoPruneEnabled,
            warnAtNightsLeft = StorageForecast.THREE_NIGHTS_THRESHOLD.toInt(),
            nextDeletion = nextDeletion?.let {
                SettingsNextDeletionViewState(
                    sessionId = it.sessionId,
                    predictedDateLabel = nightDateFormat.format(Instant.ofEpochMilli(it.predictedAtMillis)),
                    sessionDateLabel = nightDateFormat.format(Instant.ofEpochMilli(it.startedAtMillis)),
                    overCount = it.overCount,
                    sizeLabel = it.bytes.toGigabyteLabel(),
                )
            },
        )
    }

    public suspend fun export(context: Context): SettingsExportViewState {
        val db = OrtDatabase.create(context.applicationContext)
        val sessions = db.sessionDao().listAll()
        val latest = sessions.firstOrNull()
        val tonightCount = latest?.let { db.transmissionDao().listBySession(it.id).size } ?: 0
        val tonightSpan = latest?.let {
            val end = it.endedAt?.let { e -> formatClock(e) } ?: "now"
            "${formatClock(it.startedAt)} – $end"
        } ?: "no session yet"
        var allOvers = 0
        sessions.forEach { allOvers += db.transmissionDao().listBySession(it.id).size }
        return SettingsExportViewState(
            tonightOverCount = tonightCount,
            tonightSpanLabel = tonightSpan,
            allSessionCount = sessions.size,
            allOverCount = allOvers,
        )
    }

    public fun contribute(store: SettingsStore): SettingsContributeViewState = SettingsContributeViewState(
        contributionEnabled = store.contributionEnabled,
        categories = listOf(
            SettingsContributeCategoryViewState(
                "Audio of overs you have labelled",
                "only overs you marked with a callsign and a certainty",
                store.contributeAudioOfLabelled,
            ),
            SettingsContributeCategoryViewState(
                "Transcripts and your corrections",
                "the machine's text, the text you fixed it to, and the attribution state",
                store.contributeTranscriptsAndCorrections,
            ),
            SettingsContributeCategoryViewState(
                "Rejected segments",
                "squelch tails and hallucinations, so the next model learns to refuse them too",
                store.contributeRejectedSegments,
            ),
            SettingsContributeCategoryViewState(
                "Resolver statistics",
                "counts and scores only — no callsigns, no audio",
                store.contributeResolverStatistics,
            ),
        ),
        neverIncluded = NEVER_LEAVES_DEVICE,
    )

    /**
     * R-137 (round 9, register): sourced from WP11e's real `DiagnosticsBundleBuilder.preview` —
     * every file's real, current byte size (rendered through the exact same producer `write` uses,
     * never a separate stat, so a preview number can never drift from what a save actually writes)
     * and the board's own "In the bundle · N files · X.X MB" running total. `suspend` (was not,
     * before this round) because `preview` is real file/database/asset I/O.
     */
    public suspend fun diagnostics(context: Context): SettingsDiagnosticsViewState {
        val thermal = ThermalStatus.state
        val preview = org.ort.app.diagnostics.DiagnosticsBundleBuilder.preview(context)
        return SettingsDiagnosticsViewState(
            aliveLabel = if (CaptureState.isCapturing) "alive" else "not capturing",
            realTimeFactorLabel = thermal.realTimeFactor?.let { "%.2f".format(Locale.ROOT, it) } ?: "not measured",
            // No aggregate "failed passes across every pass id" query exists on `WorkQueueDao`
            // (its `selectFailed` takes a specific pass and error-prefix) — never fabricated here.
            failedPassCount = null,
            files = preview.entries.map { entry ->
                SettingsDiagnosticsFileViewState(entry.fileName, entry.clause, formatDiagnosticsSize(entry.sizeBytes))
            },
            totalSizeLabel = formatDiagnosticsSize(preview.totalBytes),
        )
    }

    /** `140 KB`/`1.6 MB`-style tiered size — the board's own precision for a bundle file/total (a
     * plain `toGigabyteLabel()` would round every one of these real, small files to `0.0 GB`). */
    private fun formatDiagnosticsSize(bytes: Long): String {
        val mb = bytes / 1_000_000.0
        val kb = bytes / 1_000.0
        return when {
            mb >= 1.0 -> "%.1f MB".format(Locale.ROOT, mb)
            else -> "%.0f KB".format(Locale.ROOT, kb)
        }
    }

    public fun about(context: Context): SettingsAboutViewState = SettingsAboutViewState(
        appVersionLabel = appVersionName(context),
        androidVersionLabel = android.os.Build.VERSION.RELEASE ?: "unknown",
        minSdkLabel = "8.0",
        sherpaOnnxVersionLabel = BuildConfig.SHERPA_ONNX_VERSION,
    )

    // R-138 (round 7, register): "1.0.0 · build 412 · 6aaa608" is the board's pattern — the real
    // `versionName` and `versionCode`/`longVersionCode` are read from `PackageManager` below; the
    // trailing commit hash is now real too, `BuildConfig.GIT_SHORT_COMMIT` (injected at build time
    // by `app/build.gradle.kts` from a real `git rev-parse`, see that file's own comment) — appended
    // only when it is a real hash, never the honest "unknown" fallback that field carries when this
    // checkout has no git available, so an unbuildable-git environment still reads as an honest
    // shorter label rather than a fabricated one.
    private fun appVersionName(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionName = info.versionName ?: "dev build"
        val buildNumber = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        val commit = BuildConfig.GIT_SHORT_COMMIT
        if (commit.isNotBlank() && commit != "unknown") {
            "$versionName · build $buildNumber · $commit"
        } else {
            "$versionName · build $buildNumber"
        }
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        "dev build"
    }

    private fun formatClock(millis: Long): String {
        val minutes = (millis / 60_000) % (24 * 60)
        return "%02d:%02d".format(Locale.ROOT, minutes / 60, minutes % 60)
    }
}
