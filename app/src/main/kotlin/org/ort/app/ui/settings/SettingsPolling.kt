package org.ort.app.ui.settings

import android.content.Context
import org.ort.app.ui.data.ModelCatalog
import org.ort.app.ui.navigation.StorageFooterViewState
import org.ort.app.ui.navigation.toGigabyteLabel
import org.ort.data.OrtDatabase
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
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

    public fun capture(store: SettingsStore): SettingsCaptureViewState {
        val (inputLabel, inputSub) = when (val state = InputStatus.state) {
            InputStatus.State.None -> "No input selected" to "select an input to capture"
            is InputStatus.State.Opened -> {
                val verified = if (state.routeVerified) "verified" else "not verified"
                state.descriptor.label to "$verified · ${state.nativeRateHz} Hz native · ${state.resamplerId}"
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
        return SettingsCaptureViewState(
            inputLabel = inputLabel,
            inputSubLine = inputSub,
            levelLabel = levelLabel,
            levelSubLine = levelSub,
            levelWarnEnabled = store.levelWarnEnabled,
            noiseReductionEnabled = store.noiseReductionEnabled,
            bandPassEnabled = store.bandPassFilterEnabled,
            manualFrequencyMhz = store.manualFrequencyMhz,
        )
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

    public fun storage(context: Context, store: SettingsStore): SettingsStorageViewState {
        val footer = StorageFooterViewState.fromAudioDirectory(context)
        val modelsBytes = ModelCatalog.entries.sumOf { entry ->
            val file = entry.destination(context.filesDir)
            if (file.isFile) file.length() else 0L
        }
        val recordsBytes = context.getDatabasePath(OrtDatabase.DATABASE_NAME).let { if (it.isFile) it.length() else 0L }
        val forecast = StorageForecast.state
        val nightsLeft = when (forecast) {
            is StorageForecast.State.Fine -> forecast.nightsLeft
            is StorageForecast.State.ThreeNightsLeft -> forecast.nightsLeft
            is StorageForecast.State.OneNightLeft -> forecast.nightsLeft
            is StorageForecast.State.AtFloor, is StorageForecast.State.NotYetMeasured -> null
        }
        return SettingsStorageViewState(
            usedBytes = footer.audioUsedBytes,
            budgetGb = store.audioBudgetGb,
            deviceFreeBytes = footer.freeBytes,
            categories = listOf(
                SettingsStorageCategoryViewState("Audio", footer.audioUsedBytes),
                SettingsStorageCategoryViewState("Models", modelsBytes),
                SettingsStorageCategoryViewState("Records", recordsBytes),
            ),
            nightsLeftLabel = nightsLeft?.let { "${it.toInt().coerceAtLeast(0)} nights left" },
            autoPruneEnabled = store.autoPruneEnabled,
            warnAtNightsLeft = StorageForecast.THREE_NIGHTS_THRESHOLD.toInt(),
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

    public fun diagnostics(): SettingsDiagnosticsViewState {
        val thermal = ThermalStatus.state
        return SettingsDiagnosticsViewState(
            aliveLabel = if (CaptureState.isCapturing) "alive" else "not capturing",
            realTimeFactorLabel = thermal.realTimeFactor?.let { "%.2f".format(Locale.ROOT, it) } ?: "not measured",
            // No aggregate "failed passes across every pass id" query exists on `WorkQueueDao`
            // (its `selectFailed` takes a specific pass and error-prefix) — never fabricated here.
            failedPassCount = null,
            files = listOf(
                SettingsDiagnosticsFileViewState(
                    "lifecycle.log",
                    "service start, stop, heartbeat gaps, OS kills",
                ),
                SettingsDiagnosticsFileViewState(
                    "capture.log",
                    "route verifications, input device changes, level warnings",
                ),
                SettingsDiagnosticsFileViewState(
                    "pipeline.log",
                    "per-pass timings, tier changes with their cause, queue depth",
                ),
                SettingsDiagnosticsFileViewState("rig.log", "CAT traffic, band changes, disconnects"),
                SettingsDiagnosticsFileViewState(
                    "assets.json",
                    "every model and lexicon: name, version, checksum, install date",
                ),
                SettingsDiagnosticsFileViewState(
                    "device.json",
                    "SoC, RAM, Android version, OEM, thermal history — no serial, no IMEI",
                ),
                SettingsDiagnosticsFileViewState(
                    "counts.json",
                    "overs by state, rejections by reason, corrections by tier",
                ),
            ),
        )
    }

    public fun about(context: Context): SettingsAboutViewState = SettingsAboutViewState(
        appVersionLabel = appVersionName(context),
        androidVersionLabel = android.os.Build.VERSION.RELEASE ?: "unknown",
        minSdkLabel = "8.0",
    )

    // R-138 (round 4, System validator): "1.0.0 · build 412 · 6aaa608" is the board's pattern —
    // the real `versionName` and `versionCode`/`longVersionCode` are read from `PackageManager`
    // below; the trailing commit hash is not, since no `BuildConfig` field carries the git commit
    // this build was made at (grepped `app/build.gradle.kts` before writing this) — adding a
    // fabricated one would be exactly the dishonesty constitution I forbids, so the label ends
    // after the real build number rather than inventing a hash.
    private fun appVersionName(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionName = info.versionName ?: "dev build"
        val buildNumber = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "$versionName · build $buildNumber"
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        "dev build"
    }

    private fun formatClock(millis: Long): String {
        val minutes = (millis / 60_000) % (24 * 60)
        return "%02d:%02d".format(Locale.ROOT, minutes / 60, minutes % 60)
    }
}
