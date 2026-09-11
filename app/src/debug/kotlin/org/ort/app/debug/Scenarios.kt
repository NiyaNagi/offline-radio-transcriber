package org.ort.app.debug

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.ort.app.assets.AndroidBundledAssetSource
import org.ort.app.assets.BundledAssetInstaller
import org.ort.app.assets.BundledAssetSource
import org.ort.app.assets.BundledAssetState
import org.ort.app.ui.data.DebugLexiconImportOverride
import org.ort.app.ui.data.DebugSearchOverride
import org.ort.app.ui.data.ModelCatalog
import org.ort.app.ui.data.ModelId
import org.ort.app.ui.failures.AssetSwapOption
import org.ort.app.ui.failures.AssetSwapViewState
import org.ort.app.ui.failures.CalibrationViewState
import org.ort.app.ui.failures.ClockLogRow
import org.ort.app.ui.failures.ClockViewState
import org.ort.app.ui.failures.DebugFailureOverride
import org.ort.app.ui.failures.FailurePresentation
import org.ort.app.ui.failures.FailureSignalsPolling
import org.ort.app.ui.failures.InterruptedOverRow
import org.ort.app.ui.failures.InterruptedViewState
import org.ort.app.ui.failures.MigrationStep
import org.ort.app.ui.failures.MigrationViewState
import org.ort.app.ui.failures.ReconcileFile
import org.ort.app.ui.failures.ReconcileRecord
import org.ort.app.ui.failures.ReconcileViewState
import org.ort.app.ui.failures.UsbViewState
import org.ort.app.ui.settings.SharedPreferencesSettingsStore
import org.ort.app.ui.setup.DebugRigLinkPortOverride
import org.ort.app.ui.setup.DebugRouteCheckOverride
import org.ort.app.ui.setup.InMemoryRigLinkPort
import org.ort.app.ui.setup.PairedDevice
import org.ort.app.ui.setup.RadioChoice
import org.ort.app.ui.setup.RouteCheckStage
import org.ort.app.ui.setup.RouteCheckState
import org.ort.app.ui.setup.SharedPreferencesSetupStore
import org.ort.app.ui.setup.levelBarFraction
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.capture.android.heartbeat.FileHeartbeatStore
import org.ort.capture.android.heartbeat.HeartbeatRecord
import org.ort.core.AttributionState
import org.ort.core.PassId
import org.ort.core.SystemClock
import org.ort.core.TransmissionState
import org.ort.core.capture.AudioRouteKind
import org.ort.core.capture.BluetoothAudioProfile
import org.ort.core.capture.CaptureMode
import org.ort.core.capture.RigTransportKind
import org.ort.data.OrtDatabase
import org.ort.data.dao.CorrectionDao
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity
import org.ort.data.entity.CorrectionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TerminationReason
import org.ort.data.entity.ThreadEntity
import org.ort.data.entity.ThreadKind
import org.ort.data.entity.ThreadKindSource
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.WorkAttemptEntity
import org.ort.data.entity.WorkAttemptOutcome
import org.ort.data.entity.WorkQueueItemEntity
import org.ort.data.entity.WorkQueueState
import org.ort.data.execRaw
import org.ort.data.inWriteTransaction
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.pipeline.capture.VadAvailability
import org.ort.pipeline.digest.ProseSummary
import org.ort.pipeline.digest.RoomProseSummaryStore
import org.ort.pipeline.digest.SharedPreferencesProseDigestSettingsStore
import org.ort.pipeline.reprocess.ReprocessStatus
import org.ort.pipeline.rig.CaptureConfiguration
import org.ort.pipeline.rig.DefaultRigTransportFactory
import org.ort.pipeline.rig.SharedPreferencesCaptureConfigurationStore
import org.ort.rig.descriptor.BundledDescriptors
import java.io.File
import java.io.InputStream
import org.ort.rig.RigTransportKind as RigModuleTransportKind

/**
 * spec/ui-conformance-plan.md WP0, register R-110 — the debug scenario simulator's fixture
 * registry. [load] deletes every row a previous scenario wrote (see [clearPriorScenarioData]),
 * resets the process-wide capture-facet singletons ([resetProcessWideFacets]) and inserts one
 * named scenario's fixtures through `:data`'s real DAOs and entities, exactly as the app's own
 * read path ([org.ort.app.ui.data.ReaderPolling]) reads them back — no fake, no shortcut schema.
 *
 * **Fictional data only** (this package's brief, verbatim): every callsign is drawn from
 * [ScenarioFixtures.CALLSIGNS] (the set the design canvas artboards themselves use), nothing is
 * read from `corpus/` or the `eval` fold, and no voiceprint, real name or precise location is ever
 * written (constitution V — those four categories never leave the device, and a debug scenario is
 * not an exception to that).
 *
 * **Clearing prior scenario data.** None of `:data`'s DAOs expose a delete query (build-plan P5
 * never needed one), so [clearPriorScenarioData] reaches through [org.ort.data.execRaw] — `:data`'s
 * own driver-native raw-statement helper (register R-204; `OrtDatabase.openHelper` throws once
 * `BundledSQLiteDriver` is installed) — and runs plain `DELETE` statements scoped to the
 * `scenario-`-prefixed session/transmission ids every scenario here writes
 * ([ScenarioFixtures.SESSION_PREFIX]). `station`/`voiceprint` rows are cleared unconditionally
 * rather than by prefix: [org.ort.data.entity.TransmissionEntity.stationId] *is* the callsign
 * shown on screen (confirmed against `ReaderPollingTest`'s own fixtures — there is no separate
 * numeric station id to prefix without also changing what the artboards' rows would show), and
 * nothing in this codebase writes the `station` table outside this simulator today, so wiping it
 * on every scenario load is the same "start from a known, empty state" guarantee the plan asks
 * for, not a narrower one.
 */
public object Scenarios {

    public data class LoadResult(val transmissionCount: Int, val sessionCount: Int, val primarySessionId: String?)

    /** R-143: how many overs [fieldTier1] seeds — see that function's own doc comment. */
    private const val FIELD_TIER1_OVER_COUNT: Int = 12

    /**
     * Every scenario name `spec/ui-conformance-plan.md` §E and `design/design-intent.md` need
     * reachable. WP11a (register R-104/R-105/R-106) closed the three gaps this list used to note
     * as unreachable from `:app` — `thermal`/`rig-lost`/`os-stopped` are new; `storage-warn` now
     * uses [StorageForecast] instead of reusing F6's exhaustion string. WP11c (register R-112/
     * R-113) adds `level-low`/`level-clip`/`input-verified`/`input-mismatch`. WP11b (register
     * R-100) adds `clock-dst`/`usb-permission`/`interrupted-pass`/`reconcile`/`migration-failed`/
     * `asset-swap`/`calibration` — the seven ids with no runtime signal today, driven through
     * `org.ort.app.ui.failures.DebugFailureOverride` (that object's own kdoc says exactly why for
     * each). `setup-verified`/`setup-level`/`setup-radio` (register R-227/R-264/R-285) are the
     * three scenarios this registry seeds outside `:data` and the process-wide capture facets — see
     * [setupVerified]'s, [setupLevel]'s and [setupRadio]'s own doc comments for why (S05's
     * `SetupStore` gates, not a runtime signal, are what block S07/S09/S12 from ever being reached
     * on an emulator with no real signal to hear).
     *
     * **P19/WPI (`spec/e2e-capture-modes-plan.md`, E2-J01) adds seventeen names** for D33–D36's
     * capture modes, Bluetooth and bundled/LLM assets: `setup-mode`/`setup-bt-permission`/
     * `setup-rig-transport`/`setup-rig-bluetooth` (the four new resumable `SetupStore` gates —
     * S00/S02c/S09b/S10b — reached the same "seed the real preferences file, no override" way
     * [setupVerified] already established); `mode-local-mic`/`mode-usb`/`mode-bluetooth` (a live
     * session per [org.ort.core.capture.CaptureMode], the v7 session columns FR-CAP-13 added, plus
     * — since a `SetupStore` and a `:data` session row are two independent stores — that same
     * mode's own S04 preset chip left mid-way, for the "S04 in a lane per mode" tour coverage);
     * `bt-audio-session` (an ended Bluetooth-audio night, the `bt audio` row mark) and
     * `bt-audio-dropped` (F23: a live `InputStatus.Lost` with a Bluetooth `lastKnown`, an open gap);
     * `rig-bt-connected`/`rig-bt-lost` (CF06/F9 naming the Bluetooth SPP transport); `mode-change-pending`
     * (CF11's amber banner, a real pending [org.ort.pipeline.rig.CaptureConfigurationStore] write);
     * `assets-bundled`/`asset-corrupt` (the real [org.ort.app.assets.BundledAssetInstaller], driven
     * against a synthetic manifest so AC-137's corruption case is genuine, never hand-set); `llm-enabled-prose`/
     * `llm-disabled` (real, stored [org.ort.pipeline.digest.ProseSummary] rows, the real prose-digest
     * settings toggle); `tier0-llm-stored` (the LLM asset installed but this device's own tier below
     * T3, so [org.ort.app.ui.data.ModelRowViewState.tierEligible] genuinely reads `false`). See each
     * function's own doc comment below for the exact facts it seeds and, for `setup-rig-bluetooth`,
     * the one real gap this package found and reported rather than working around (no `:app`-reachable
     * seam exists to script [org.ort.app.ui.setup.SetupActivity]'s own paired-device list).
     */
    public val NAMES: List<String> = listOf(
        "empty",
        "first-session",
        "overnight",
        "overnight-live",
        "unclean-end",
        "os-stopped",
        "gap-call",
        "pass-a-partial",
        "pass-failed",
        "corrected",
        "no-audio",
        "revisions",
        "stations-14-nights",
        "frequency-change",
        "field-tier1",
        "search-corpus",
        "search-unavailable",
        "backlog",
        "model-missing",
        "storage-warn",
        "storage-fine",
        "thermal",
        "rig-lost",
        "rig-reconnected",
        "level-low",
        "level-clip",
        "input-verified",
        "input-mismatch",
        "setup-verified",
        "setup-level",
        "setup-radio",
        // E2-J04 (checklist row, coordinator round): the one local-mic-mode setup base — see
        // [setupVerifiedLocalMic]'s own doc comment.
        "setup-verified-local-mic",
        "clock-dst",
        "usb-permission",
        "interrupted-pass",
        "reconcile",
        "migration-failed",
        "asset-swap",
        "calibration",
        // R-154, FR-LEX-12/FR-LEX-30/FR-AST-2: the one scenario in this list that runs the real
        // production validator (LexiconCorruptScenario.run) rather than hand-setting a
        // FailurePresentation override — see that file's own kdoc for why.
        "lexicon-corrupt",
        // P19/WPI (spec/e2e-capture-modes-plan.md, E2-J01) — see this list's own doc comment above.
        "setup-mode",
        "setup-bt-permission",
        "setup-rig-transport",
        // E2-J04: same base as `setup-rig-transport` — see [setupRigTransportPreset]'s own doc
        // comment for the known, honest gap this scenario reports rather than works around.
        "setup-rig-transport-preset",
        "setup-rig-bluetooth",
        "setup-rig-bluetooth-connecting",
        "setup-rig-bluetooth-identified",
        "setup-rig-bluetooth-dropped",
        "mode-local-mic",
        "mode-usb",
        "mode-bluetooth",
        "bt-audio-session",
        "bt-audio-dropped",
        "rig-bt-connected",
        "rig-bt-lost",
        "mode-change-pending",
        "assets-bundled",
        "asset-corrupt",
        "llm-enabled-prose",
        "llm-disabled",
        "tier0-llm-stored",
    )

    public suspend fun load(context: Context, name: String): LoadResult {
        require(name in NAMES) { "unknown scenario '$name' — known scenarios: $NAMES" }
        // R-873: recorded before the load's own work below so a scenario whose builder throws
        // partway still names itself as "active" for a later debug-process-start re-publish — the
        // same honest position `clearPriorScenarioData`/`resetProcessWideFacets` already take (a
        // half-applied load is this call's own caller's problem, not a reason to leave the marker
        // pointing at whatever loaded before it).
        ActiveScenarioMarker.write(context, name)
        val db = OrtDatabase.create(context.applicationContext)
        clearPriorScenarioData(context, db)
        resetProcessWideFacets()
        // Every scenario builder below is a single, sequential suspend chain of awaited DAO calls
        // (confirmed: no `.launch`/`CoroutineScope`/`async` anywhere in this package's scenario
        // builders — `ScenarioReceiver`'s own `CoroutineScope(Dispatchers.IO)` is a different,
        // broadcast-only entry point this function never goes through). `load` itself is a plain
        // suspend function with no `launch` of its own, so its caller (this object's own contract,
        // and `ScenariosTest`'s `runTest`) genuinely does not return until every write below has
        // completed — see `clearPriorScenarioData`'s own doc comment for the half of this that
        // *was* broken.
        return when (name) {
            "empty" -> empty(context, db)
            "first-session" -> firstSession(context, db)
            // R-440 (register, WP12's own tour finding): `seedConfiguredDeviceState` — a chosen and
            // verified input, a radio choice, every model asset "installed", and a storage budget
            // — so `overnight`/`overnight-live`/`stations-14-nights`' own Settings boards
            // (CF01/CF02/CF03/CF06) render as configured on a clean install, not the unconfigured
            // defaults the tour's own receiver-parity check found. `gap-call` deliberately excluded
            // — the register names only these three.
            "overnight" -> OvernightScenario.overnight(context, db).also { seedConfiguredDeviceState(context) }
            "overnight-live" ->
                OvernightScenario.overnightLive(context, db).also { seedConfiguredDeviceState(context) }
            "gap-call" -> OvernightScenario.gapCall(context, db)
            "unclean-end" -> uncleanEnd(context, db)
            "os-stopped" -> osStopped(context, db)
            "pass-a-partial" -> passAPartial(db)
            "pass-failed" -> passFailed(context, db)
            "corrected" -> corrected(db)
            "no-audio" -> noAudio(db)
            "revisions" -> revisions(context, db)
            "stations-14-nights" ->
                StationsFixtures.stations14Nights(db).also { seedConfiguredDeviceState(context) }
            "frequency-change" -> FrequencyChangeFixtures.frequencyChange(db)
            "field-tier1" -> fieldTier1(context, db)
            "search-corpus" -> searchCorpus(db)
            "search-unavailable" -> searchUnavailable(db)
            "backlog" -> backlog(context, db)
            "model-missing" -> modelMissing(context, db)
            "storage-warn" -> storageWarn(context, db)
            "storage-fine" -> storageFine(context, db)
            "thermal" -> thermal(context, db)
            "rig-lost" -> rigLost(context, db)
            "rig-reconnected" -> rigReconnected(context, db)
            "level-low" -> levelLow(context, db)
            "level-clip" -> levelClip(context, db)
            "input-verified" -> inputVerified(context, db)
            "input-mismatch" -> inputMismatch(db)
            "setup-verified" -> setupVerified(context)
            "setup-level" -> setupLevel(context)
            "setup-radio" -> setupRadio(context)
            "setup-verified-local-mic" -> setupVerifiedLocalMic(context)
            "clock-dst" -> clockDst(context, db)
            "usb-permission" -> usbPermission(context, db)
            "interrupted-pass" -> interruptedPass(context, db)
            "reconcile" -> reconcile(context, db)
            "migration-failed" -> migrationFailed(context, db)
            "asset-swap" -> assetSwap(context, db)
            "calibration" -> calibration(context, db)
            "lexicon-corrupt" -> LexiconCorruptScenario.run(context, db)
            "setup-mode" -> setupMode(context)
            "setup-bt-permission" -> setupBtPermission(context)
            "setup-rig-transport" -> setupRigTransport(context)
            "setup-rig-transport-preset" -> setupRigTransportPreset(context)
            "setup-rig-bluetooth" -> setupRigBluetooth(context)
            "setup-rig-bluetooth-connecting" -> setupRigBluetoothConnecting(context)
            "setup-rig-bluetooth-identified" -> setupRigBluetoothIdentified(context)
            "setup-rig-bluetooth-dropped" -> setupRigBluetoothDropped(context)
            "mode-local-mic" -> modeLocalMic(context, db)
            "mode-usb" -> modeUsb(context, db)
            "mode-bluetooth" -> modeBluetooth(context, db)
            "bt-audio-session" -> btAudioSession(context, db)
            "bt-audio-dropped" -> btAudioDropped(context, db)
            "rig-bt-connected" -> rigBtConnected(context, db)
            "rig-bt-lost" -> rigBtLost(context, db)
            "mode-change-pending" -> modeChangePending(context, db)
            "assets-bundled" -> assetsBundled(context)
            "asset-corrupt" -> assetCorrupt(context)
            "llm-enabled-prose" -> llmEnabledProse(context, db)
            "llm-disabled" -> llmDisabled(context, db)
            "tier0-llm-stored" -> tier0LlmStored(context)
            else -> error("unreachable — guarded by the require() above")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Clearing
    // ---------------------------------------------------------------------------------------

    /**
     * **Root cause of the intermittent `SQLiteBusyException` this function used to throw**
     * (register R-110, `ScenariosTest :: R_110 every declared scenario name loads without
     * throwing`, flaky on main's gate): this ran as nine-plus separate `execSQL` calls against
     * [OrtDatabase.openHelper]'s raw `writableDatabase` — each its own implicit transaction,
     * *outside* Room's own transaction/connection bookkeeping — so two writers this database's own
     * bookkeeping did not know about each other could race for the single-writer lock. Checked
     * directly and ruled out first: no scenario builder (nor [Scenarios.load] itself) launches a
     * writer of its own — no `.launch`, `CoroutineScope(`, or `async` anywhere in this package
     * other than [ScenarioReceiver]'s own broadcast-only entry point, which [Scenarios.load] never
     * goes through. Every scenario's own writes are a single, sequential, awaited suspend chain
     * already; the "writer left alive across the clear step" was this function's own un-transacted
     * `execSQL` sequence, not a stray coroutine.
     *
     * **Fixed in three layers, verified by repeatedly running `ScenariosTest` with
     * `--rerun-tasks`** (this package's report to the lead has the exact run counts and failure
     * rates at each stage):
     *
     * 1. **One Room-coordinated transaction**, not nine separate ones — [org.ort.data.inWriteTransaction]
     *    runs the whole clear on Room's *own* pooled writer connection, atomic besides (every
     *    `DELETE` below commits together or not at all). This alone cut the failure rate roughly in
     *    half but did not eliminate it. (Originally built on
     *    `androidx.room.RoomDatabase.withTransaction`; register R-204 replaced every use of that
     *    KTX helper repo-wide once `:data` started installing `BundledSQLiteDriver` — it silently
     *    stopped working the moment a driver is set, throwing `Cannot return a
     *    SupportSQLiteOpenHelper` — with the driver-native `inWriteTransaction`, alongside
     *    [org.ort.data.execRaw] replacing the raw `OrtDatabase.openHelper.writableDatabase.execSQL`
     *    calls below for the identical reason.)
     * 2. **`ScenariosTest` now closes its own [OrtDatabase] in `@After`** — previously every test
     *    method opened a fresh `RoomDatabase` (its own connection pool, its own
     *    `InvalidationTracker`) against the *same* on-disk `ort.db` and never closed the previous
     *    one, so a three-dozen-test run ended with dozens of still-warm, never-released instances
     *    all pointed at one file. This narrowed the failure further but a locked-database error
     *    still reproduced *within a single test method* driving many back-to-back loads against
     *    *one* already-open `OrtDatabase` instance (this file's own regression test, below,
     *    reproduced it directly) — meaning something below the application layer (most likely
     *    Room's `InvalidationTracker` background version-refresh, which Robolectric's `sqlite4java`
     *    driver is known to occasionally still be settling when the next writer arrives — compounded
     *    by, but distinct from, the `no such module: fts5` noise Robolectric's SQLite build logs for
     *    this project's FTS5 tables, itself expected and harmless) can still transiently hold the
     *    single-writer lock for a moment even with everything above fixed.
     * 3. **A bounded, backed-off retry on a transient lock**, entirely within this function — the
     *    standard, SQLite-documented response to `SQLITE_BUSY` ("the application should... retry
     *    after a short delay"), scoped narrowly to messages that actually say "busy"/"locked" so a
     *    genuine, non-transient failure still surfaces immediately rather than retrying blindly.
     *    [OrtDatabase.create] now also sets a `PRAGMA busy_timeout` (register R-204's follow-up) so
     *    a transient lock waits before it ever reaches SQLite's `SQLITE_BUSY` at all — this retry
     *    stays as defence in depth for whatever a bounded busy-timeout does not itself absorb.
     */
    private suspend fun clearPriorScenarioData(context: Context, db: OrtDatabase) {
        var attempt = 0
        while (true) {
            try {
                clearScenarioRowsInOneTransaction(db)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                if (attempt >= MAX_CLEAR_ATTEMPTS || !isTransientlyLocked(e)) throw e
                delay(CLEAR_RETRY_BACKOFF_MILLIS * attempt)
            }
        }

        // Filesystem side effects, deliberately outside the DB transaction above (a rollback of
        // the SQL has nothing to do with these, and `File` I/O does not participate in SQLite's
        // locking at all — keeping them separate keeps the transaction itself minimal).
        File(context.filesDir, "audio").listFiles { f -> f.name.startsWith(ScenarioFixtures.SESSION_PREFIX) }
            ?.forEach { it.deleteRecursively() }
        File(context.filesDir, "heartbeat.txt").delete()

        // P19/WPI: two more `SharedPreferences` files a P19 scenario can write outside :data —
        // cleared unconditionally (a no-op if nothing was ever written), the same "start from a
        // known, honest default" guarantee this function already gives every other process-wide
        // facet. `mode-change-pending` is the only writer of the first (a stale pending
        // configuration must never leak into a scenario that never asked for one); `llm-enabled-prose`/
        // `llm-disabled` are the only writers of the second (D36's own default is enabled — clearing
        // resets to that default, never to a leaked "disabled" from whichever LLM scenario loaded
        // previously in this process).
        context.applicationContext.getSharedPreferences(
            SharedPreferencesCaptureConfigurationStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        ).edit().clear().apply()
        context.applicationContext.getSharedPreferences(
            SharedPreferencesProseDigestSettingsStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        ).edit().clear().apply()
    }

    private suspend fun clearScenarioRowsInOneTransaction(db: OrtDatabase) {
        db.inWriteTransaction {
            val likeScenario = "${ScenarioFixtures.SESSION_PREFIX}%"
            db.execRaw(
                "DELETE FROM correction WHERE transmissionId IN " +
                    "(SELECT id FROM transmission WHERE sessionId LIKE ?)",
                likeScenario,
            )
            db.execRaw(
                "DELETE FROM callsign_candidate WHERE transmissionId IN " +
                    "(SELECT id FROM transmission WHERE sessionId LIKE ?)",
                likeScenario,
            )
            db.execRaw(
                "DELETE FROM phonetic_lattice WHERE transmissionId IN " +
                    "(SELECT id FROM transmission WHERE sessionId LIKE ?)",
                likeScenario,
            )
            // R-421 (schema v5): `lattice_slot` was never cleared here — a scenario whose
            // candidate/slot ids are deterministic (not a fresh ULID per load, e.g. `overnight`'s
            // own `$sessionId-tx03-c1-slot0`) collided with its own prior load's rows on a second
            // reload (`UNIQUE constraint failed: lattice_slot.id`), caught by this file's own
            // `loading every scenario back to back five times never hits a database-locked error`
            // test before this fix.
            db.execRaw(
                "DELETE FROM lattice_slot WHERE transmissionId IN " +
                    "(SELECT id FROM transmission WHERE sessionId LIKE ?)",
                likeScenario,
            )
            db.execRaw(
                "DELETE FROM transcript WHERE transmissionId IN " +
                    "(SELECT id FROM transmission WHERE sessionId LIKE ?)",
                likeScenario,
            )
            // R-564: `pass-failed` is now also the first scenario to write `work_attempt` rows
            // (schema v6) — cleared *before* `work_queue_item` below, via the same
            // itemId → work_queue_item.transmissionId → transmission.sessionId join, since
            // `WorkAttemptEntity` carries no direct `transmissionId`/`sessionId` of its own (it is
            // deliberately outlived by no `@ForeignKey` — see the entity's own doc comment) and the
            // next line's delete would otherwise leave these rows orphaned on every reload.
            db.execRaw(
                "DELETE FROM work_attempt WHERE itemId IN " +
                    "(SELECT id FROM work_queue_item WHERE transmissionId IN " +
                    "(SELECT id FROM transmission WHERE sessionId LIKE ?))",
                likeScenario,
            )
            // R-153: `pass-failed` is the first scenario to write a work_queue_item row — cleared
            // by the same transmissionId-through-sessionId join every other per-transmission table
            // uses.
            db.execRaw(
                "DELETE FROM work_queue_item WHERE transmissionId IN " +
                    "(SELECT id FROM transmission WHERE sessionId LIKE ?)",
                likeScenario,
            )
            db.execRaw("DELETE FROM thread WHERE sessionId LIKE ?", likeScenario)
            db.execRaw("DELETE FROM capture_gap WHERE sessionId LIKE ?", likeScenario)
            db.execRaw("DELETE FROM transmission WHERE sessionId LIKE ?", likeScenario)
            db.execRaw("DELETE FROM session WHERE id LIKE ?", likeScenario)
            // Unconditional — see this object's own doc comment for why station/voiceprint rows
            // cannot be tagged by the same session-id-prefix convention.
            db.execRaw("DELETE FROM station")
            db.execRaw("DELETE FROM voiceprint")
            // `lexicon-corrupt` (R-154) is the only scenario that writes `lexicon_version` — scoped
            // by asset id, the same reason station/voiceprint above are cleared unconditionally rather
            // than by the `scenario-` session-id prefix (a LexiconVersionEntity carries neither).
            db.execRaw(
                "DELETE FROM lexicon_version WHERE assetId = ?",
                org.ort.app.ui.data.ModelsController.CALLSIGN_LEXICON_ASSET_ID,
            )
        }
    }

    /** True if [e], or anything in its cause chain, is SQLite's own transient "busy"/"locked" — never a blind retry. */
    private fun isTransientlyLocked(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            val message = cause.message?.lowercase(java.util.Locale.ROOT).orEmpty()
            if (message.contains("locked") || message.contains("busy")) return true
            cause = cause.cause
        }
        return false
    }

    private const val MAX_CLEAR_ATTEMPTS = 5
    private const val CLEAR_RETRY_BACKOFF_MILLIS = 25L

    /** R-822: the one, consistent paired-device address every Bluetooth-rig scenario below uses
     * for `CaptureConfiguration.rigParams[DefaultRigTransportFactory.ParamKeys.BLUETOOTH_ADDRESS]`
     * — a real-shaped MAC address, never a placeholder string, since CF06/CF02's own Link row reads
     * this value directly. */
    private const val BLUETOOTH_RIG_ADDRESS = "AA:BB:CC:11:22:33"

    /** WPD's seam: the headset-class-only paired device `setup-rig-bluetooth`'s own list also names
     * (S10b's own dim, unselectable row) — a distinct address from [BLUETOOTH_RIG_ADDRESS]. */
    private const val HANDHELD_BT_ADDRESS = "11:22:33:AA:BB:CC"

    /** R-832: the coordinator's own specified reconnect-ladder position for both `bt-audio-dropped`
     * (F23) and `rig-bt-lost` (F9) — a mid-ladder attempt, not the first or the terminal one, so
     * both boards' ladder sentences have a genuinely non-trivial position to render. */
    private const val RECONNECT_LADDER_ATTEMPT = 3
    private const val RECONNECT_LADDER_OF_TOTAL = 8
    private const val RECONNECT_LADDER_NEXT_RETRY_MILLIS = 20_000L

    /**
     * Every process-wide capture-facet singleton [org.ort.app.ui.data.ReaderPolling] reads
     * ([CaptureState], [AsrAvailability], [VadAvailability], [ShedStatus], and — WP11a, register
     * R-104/R-105 — [ThermalStatus]/[RigStatus]/[StorageForecast], and — WP11c, register R-112/
     * R-113 — [LevelStatus]/[InputStatus]) reset to their own honest "not started" default before a
     * scenario applies its own facts — otherwise a facet a previous scenario set (e.g.
     * `model-missing`'s [AsrAvailability.unavailable]) would leak into the next scenario loaded in
     * the same process, which is exactly the kind of silent cross-contamination this simulator
     * exists to prevent.
     */
    private fun resetProcessWideFacets() {
        CaptureState.idle(clearSession = true)
        AsrAvailability.reset()
        VadAvailability.reset()
        ShedStatus.reset()
        ThermalStatus.reset()
        RigStatus.reset()
        StorageForecast.reset()
        LevelStatus.reset()
        InputStatus.reset()
        DebugFailureOverride.clear()
        FailureSignalsPolling.reset()
        // R-154 (round 5): a rejection from a previous `lexicon-corrupt` load must not leak into
        // the next scenario's Assets screenshot — the same cross-contamination concern this
        // function already exists to prevent for every other process-wide facet.
        DebugLexiconImportOverride.clear()
        ReprocessStatus.reset()
        // round-eleven tooling: a pending search-unavailable override must not leak into whatever
        // scenario loads next, the same cross-contamination concern every process-wide facet reset
        // here already exists to prevent.
        DebugSearchOverride.clear()
        // WPD's seam (coordinator-assigned, this round): a scripted RigLinkPort from a prior
        // `setup-rig-bluetooth` load must not leak into a later scenario's own S10b render.
        DebugRigLinkPortOverride.clear()
        // R-943 (WPD's own seam): a RouteCheckState published for a prior `setup-verified` load's
        // own S05 render must not leak into a later scenario's own S05/route-mismatch board.
        DebugRouteCheckOverride.clear()
        // R-807 (register, coordinator round): [DebugBundledAssetSourceOverride] is deliberately
        // NOT cleared here, unlike every override above — it exists to survive across a whole
        // *sequence* of [load] calls within one test (`ScenariosTest`'s own R_110 stress-repeat
        // loop), not to be scoped to a single scenario's own render the way `DebugRigLinkPortOverride`
        // et al. are; clearing it here on every single load would silently defeat it on the very
        // first iteration, since this function runs before `installRealBundledAssets` ever reads
        // it (reproduced directly: exactly this bug, before this comment was added). The one test
        // that sets it is responsible for its own `try`/`finally` clear, backstopped by that test
        // class's own `@After`.
    }

    // ---------------------------------------------------------------------------------------
    // Scenarios
    // ---------------------------------------------------------------------------------------

    /** `empty` — no sessions at all: the true first-launch / freshly-reset state. */
    /**
     * `empty` — the true first-launch / freshly-reset state: no sessions at all. R-461 (register,
     * Reviewer A round 2): `SharedPreferences` persist across scenario loads (unlike `:data`, this
     * file's own established rule elsewhere) — a prior `overnight`/`overnight-live`/
     * `stations-14-nights` load in the same process would otherwise leave its own configured input
     * behind, making `empty` render `Now-Idle.dc.html`'s meta row as if Setup had already run,
     * which contradicts this scenario's own name and doc. Explicitly clears the same input/radio
     * fields [seedConfiguredDeviceState] writes, so `empty` always renders the honest, genuinely
     * unconfigured row ("No input · No radio · tier —") regardless of load order.
     */
    private fun empty(context: Context, db: OrtDatabase): LoadResult {
        val prefs = context.applicationContext.getSharedPreferences(
            SharedPreferencesSetupStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val store = SharedPreferencesSetupStore(prefs)
        store.selectedInputId = null
        store.selectedInputLabel = null
        store.inputVerified = false
        RigStatus.reset()
        return LoadResult(0, 0, null)
    }

    /** `first-session` — one session started "now" minus 3 minutes, no transmissions yet (`Now-First`). */
    private suspend fun firstSession(context: Context, db: OrtDatabase): LoadResult {
        val id = ScenarioFixtures.sessionId("first-session")
        val startedAt = SystemClock.wallMillis() - 3 * 60_000L
        db.sessionDao().insert(ScenarioFixtures.session(id, startedAt = startedAt, endedAt = null))
        ScenarioFixtures.markCapturing(context, id)
        // R-174: `Now-First.dc.html`'s subtitle reads "listening on 145.230 and 146.960" —
        // `NowViewStateMapper.active` only ever says that when `RigStatus.state` is actually
        // `Connected` (`ReaderPolling.activeNowViewState`'s own `listeningOnLabel`), so a scenario
        // that never sets it renders an honestly-blank subtitle instead, not this artboard's text.
        RigStatus.connected(
            descriptor = "TH-D75A",
            bands = listOf(
                RigStatus.BandState(band = "A", frequencyHz = 145_230_000L, mode = "FM", squelchOpen = false),
                RigStatus.BandState(band = "B", frequencyHz = 146_960_000L, mode = "FM", squelchOpen = false),
            ),
        )
        return LoadResult(0, 1, id)
    }

    /**
     * `unclean-end` — a prior session whose heartbeat stopped without a clean shutdown
     * ([org.ort.capture.android.heartbeat.FileHeartbeatStore.markCleanShutdown] is deliberately
     * never called — that omission alone is what
     * [org.ort.capture.android.heartbeat.FileHeartbeatStore.hadUncleanEnd] reads as unclean, per
     * that store's own `write()` always stamping the "clean" marker `false`). This process is
     * *not* capturing — the finding is about the *previous* launch.
     */
    private suspend fun uncleanEnd(context: Context, db: OrtDatabase): LoadResult {
        val id = ScenarioFixtures.sessionId("unclean-end")
        val staleWallMillis = SystemClock.wallMillis() - 6 * 3_600_000L
        db.sessionDao().insert(
            ScenarioFixtures.session(
                id,
                startedAt = staleWallMillis - 3_600_000L,
                endedAt = null,
                terminationReason = TerminationReason.UNKNOWN,
            ),
        )
        FileHeartbeatStore(File(context.filesDir, "heartbeat.txt")).write(
            HeartbeatRecord(
                sessionId = id,
                monotonicNanos = 0L,
                wallMillis = staleWallMillis,
                samplePosition = 118_000L,
            ),
        )
        return LoadResult(0, 1, id)
    }

    /**
     * `os-stopped` — F5, register R-106: the same unclean-end heartbeat [uncleanEnd] writes, plus
     * the [CaptureGapCause.OS_STOPPED] gap [RealCaptureService][org.ort.pipeline.capture.RealCaptureService]
     * itself now persists on relaunch (from the last heartbeat to the moment it is detected — never
     * "reopening" the previous session, a policy the lead has not decided; see this package's
     * report). Written directly, the same way `overnight`'s own gap rows are, rather than driving
     * the real service end to end — this scenario proves the *rendering* path (`Fail-Killed`'s gap
     * list, the log's gap row) is fed real data shaped exactly like the real fix produces.
     */
    private suspend fun osStopped(context: Context, db: OrtDatabase): LoadResult {
        val id = ScenarioFixtures.sessionId("os-stopped")
        val staleWallMillis = SystemClock.wallMillis() - 6 * 3_600_000L
        val detectedAtWallMillis = SystemClock.wallMillis()
        db.sessionDao().insert(
            ScenarioFixtures.session(
                id,
                startedAt = staleWallMillis - 3_600_000L,
                endedAt = null,
                terminationReason = TerminationReason.UNKNOWN,
            ),
        )
        FileHeartbeatStore(File(context.filesDir, "heartbeat.txt")).write(
            HeartbeatRecord(
                sessionId = id,
                monotonicNanos = 0L,
                wallMillis = staleWallMillis,
                samplePosition = 118_000L,
            ),
        )
        db.captureGapDao().insert(
            CaptureGapEntity(
                id = "$id-gap-os-stopped",
                sessionId = id,
                startedAt = staleWallMillis,
                endedAt = detectedAtWallMillis,
                cause = CaptureGapCause.OS_STOPPED,
                recoveredAutomatically = false,
            ),
        )
        return LoadResult(0, 1, id)
    }

    /**
     * `pass-a-partial` — a transmission whose only transcript row is a Pass A partial
     * ([TranscriptPass.A], `isCurrent = true`, `processingState = PROCESSING`). **Representable**:
     * `:data`'s schema has no separate "is a partial" flag, but a pass=A current transcript with no
     * pass=B row yet *is* exactly what a real in-flight Pass A produces — see this package's report
     * for what is and is not built to *render* that state (register R-041 — the rendering, not the
     * data, is what is missing; `spec/ui-conformance-plan.md`'s WP0 brief asked this to be checked
     * and reported, not fixed here).
     */
    private suspend fun passAPartial(db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("pass-a-partial")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 5 * 60_000L, endedAt = null),
        )
        val txId = "$sessionId-tx1"
        val startedAt = SystemClock.wallMillis() - 20_000L
        val tx = ScenarioFixtures.transmission(
            id = txId,
            sessionId = sessionId,
            startedAtUtc = startedAt,
            samplePosition = 1L,
            frequencyHz = 146_960_000L,
            attributionState = AttributionState.UNKNOWN,
            processingState = TransmissionState.PROCESSING,
        )
        db.transmissionDao().insert(tx)
        db.transcriptDao().insert(
            ScenarioFixtures.transcript(
                id = "$txId-t1",
                transmissionId = txId,
                text = "and we're clear on the repeater, seven th",
                pass = TranscriptPass.A,
                isCurrent = true,
                createdAt = startedAt + 1_000L,
                confidence = null,
            ),
        )
        return LoadResult(1, 1, sessionId)
    }

    /**
     * `pass-failed` — R-153, F18 `Fail-Pass.dc.html`, FR-RUN-9: a transmission whose Pass B
     * ([PassId.B_OFFLINE]) errored out under [org.ort.data.WorkQueue.failPass]'s bound and is now
     * terminally [WorkQueueState.FAILED] (3 attempts, the real `lastError` text), while its Pass A
     * partial ([TranscriptPass.A], `isCurrent = true`) is the honest text
     * [org.ort.app.ui.data.ReaderTransmissionViewStateMapper.transcriptLabel] shows in place of a
     * final transcript — this scenario writes the [WorkQueueItemEntity] row directly (the same
     * shape [org.ort.data.WorkQueue.failPass] itself writes via
     * [org.ort.data.dao.WorkQueueDao.markFailed]) rather than driving a real pass through failure,
     * the same "prove the rendering path, not the pipeline" approach [osStopped] already uses for
     * its gap row. Retained audio is written so `Retry this pass`/the waveform have a real over to
     * act on, matching `Fail-Pass.dc.html`'s own "the audio is here" reading.
     *
     * Register R-564: this also writes the three [WorkAttemptEntity] rows the terminal
     * [WorkQueueItemEntity.attemptCount] of `3` implies — the same durable per-attempt audit
     * [org.ort.data.WorkQueue.failPass] itself writes on every real failure (schema v6). Without
     * these rows, `CorrectionPolling.passFailure`'s own `attemptLog` is honestly empty (a real,
     * disclosed fallback for a record that predates schema v6 — see
     * `TransmissionDetailScreen`'s `FailedPassWhatWentWrongSection`), which is what round 4's device
     * review actually found: not a mapper bug, a fixture gap — this scenario never had per-attempt
     * rows to read. Timestamps mirror `Fail-Pass.dc.html`'s own spacing (a 45s then a 90s backoff
     * between attempts); the reason repeats the item's own `lastError` verbatim, exactly as three
     * retries of the same decoder OOM genuinely would.
     */
    private suspend fun passFailed(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("pass-failed")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 90 * 60_000L, endedAt = null),
        )
        val txId = "$sessionId-tx1"
        val startedAt = SystemClock.wallMillis() - 5 * 60_000L
        val tx = ScenarioFixtures.transmission(
            id = txId,
            sessionId = sessionId,
            startedAtUtc = startedAt,
            durationMs = 28_400L,
            samplePosition = 1L,
            frequencyHz = 145_230_000L,
            signalStrength = 8.0,
            attributionState = AttributionState.UNKNOWN,
            processingState = TransmissionState.FAILED,
        )
        db.transmissionDao().insert(tx)
        ScenarioFixtures.writeAudioFixture(context, tx)
        db.transcriptDao().insert(
            ScenarioFixtures.transcript(
                id = "$txId-t1",
                transmissionId = txId,
                text = "okay so for the net tonight we've got the following announcements first the club " +
                    "meeting has moved to the second thursday and second the",
                pass = TranscriptPass.A,
                isCurrent = true,
                createdAt = startedAt + 1_000L,
                confidence = null,
            ),
        )
        val itemId = db.workQueueDao().insert(
            WorkQueueItemEntity(
                transmissionId = txId,
                pass = PassId.B_OFFLINE,
                state = WorkQueueState.FAILED,
                priority = 0,
                attemptCount = 3,
                lastError = "out of memory in the decoder",
                enqueuedAt = startedAt,
            ),
        )
        // R-564: the real per-attempt audit trail `Fail-Pass.dc.html`'s "What went wrong · 3
        // attempts" block reads — see this function's own doc comment above.
        val attempt1FinishedAt = startedAt + 30_000L
        val attempt2FinishedAt = attempt1FinishedAt + 45_000L
        val attempt3FinishedAt = attempt2FinishedAt + 90_000L
        db.workQueueDao().insert(
            WorkAttemptEntity(
                itemId = itemId,
                attemptNo = 1,
                startedAtMillis = startedAt,
                finishedAtMillis = attempt1FinishedAt,
                outcome = WorkAttemptOutcome.FAILED,
                reason = "out of memory in the decoder",
            ),
        )
        db.workQueueDao().insert(
            WorkAttemptEntity(
                itemId = itemId,
                attemptNo = 2,
                startedAtMillis = attempt1FinishedAt,
                finishedAtMillis = attempt2FinishedAt,
                outcome = WorkAttemptOutcome.FAILED,
                reason = "out of memory in the decoder",
            ),
        )
        db.workQueueDao().insert(
            WorkAttemptEntity(
                itemId = itemId,
                attemptNo = 3,
                startedAtMillis = attempt2FinishedAt,
                finishedAtMillis = attempt3FinishedAt,
                outcome = WorkAttemptOutcome.FAILED,
                reason = "out of memory in the decoder",
            ),
        )
        return LoadResult(1, 1, sessionId)
    }

    /**
     * `corrected` — a single transmission carrying the exact shape a one-tap correction produces
     * ([org.ort.core.Attribution.withCorrection]: `INFERRED`, no confidence, no propagation
     * source, `corrected = true`), plus the [CorrectionEntity] audit row
     * [CorrectionDao.recordCorrection] would have written, for the detail screen's `corrected`
     * variant.
     */
    private suspend fun corrected(db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("corrected")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 3_600_000L, endedAt = null),
        )
        val txId = "$sessionId-tx1"
        val startedAt = SystemClock.wallMillis() - 600_000L
        val tx = ScenarioFixtures.transmission(
            id = txId,
            sessionId = sessionId,
            startedAtUtc = startedAt,
            samplePosition = 1L,
            frequencyHz = 146_960_000L,
            attributionState = AttributionState.INFERRED,
            stationId = "KJ7ABC",
            attributionConfidence = null,
            corrected = true,
        )
        db.transmissionDao().insert(tx)
        db.transcriptDao().insert(
            ScenarioFixtures.transcript(
                id = "$txId-t1",
                transmissionId = txId,
                text = "kilo juliet seven alpha bravo charlie, back to you",
                isCurrent = true,
                createdAt = startedAt + 1_000L,
            ),
        )
        db.catalogDao().insert(
            CorrectionEntity(
                id = "$txId-correction1",
                transmissionId = txId,
                field = CorrectionDao.FIELD_STATION,
                previousValue = "K7LWH",
                newValue = "KJ7ABC",
                correctedAt = startedAt + 30_000L,
                propagatedToCount = 3,
            ),
        )
        return LoadResult(1, 1, sessionId)
    }

    /** `no-audio` — a confirmed transmission with no retained-audio file at all (`Detail-Playback`'s no-audio variant). */
    private suspend fun noAudio(db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("no-audio")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 3_600_000L, endedAt = null),
        )
        val txId = "$sessionId-tx1"
        val startedAt = SystemClock.wallMillis() - 500_000L
        db.transmissionDao().insert(
            ScenarioFixtures.transmission(
                id = txId,
                sessionId = sessionId,
                startedAtUtc = startedAt,
                samplePosition = 1L,
                frequencyHz = 145_230_000L,
                attributionState = AttributionState.CONFIRMED,
                stationId = "W7NPC",
                attributionConfidence = 0.95,
            ),
        )
        db.transcriptDao().insert(
            ScenarioFixtures.transcript(
                id = "$txId-t1",
                transmissionId = txId,
                text = "this is whiskey seven november papa charlie, monitoring",
                isCurrent = true,
                createdAt = startedAt + 1_000L,
            ),
        )
        // Deliberately no ScenarioFixtures.writeAudioFixture(...) call — hasAudio must read false.
        return LoadResult(1, 1, sessionId)
    }

    /** `revisions` — a single transmission with two transcript versions, the older superseded (`Detail-Revisions`). */
    private suspend fun revisions(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("revisions")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 3_600_000L, endedAt = null),
        )
        val txId = "$sessionId-tx1"
        val startedAt = SystemClock.wallMillis() - 700_000L
        val tx = ScenarioFixtures.transmission(
            id = txId,
            sessionId = sessionId,
            startedAtUtc = startedAt,
            samplePosition = 1L,
            frequencyHz = 146_960_000L,
            attributionState = AttributionState.CONFIRMED,
            stationId = "W7NPC",
            attributionConfidence = 0.9,
        )
        db.transmissionDao().insert(tx)
        ScenarioFixtures.writeAudioFixture(context, tx)
        db.transcriptDao().supersede(
            ScenarioFixtures.transcript(
                id = "$txId-t1",
                transmissionId = txId,
                text = "and we're clear on the repeater, seven th",
                pass = TranscriptPass.A,
                isCurrent = true,
                createdAt = startedAt + 1_000L,
                confidence = null,
            ),
        )
        db.transcriptDao().supersede(
            ScenarioFixtures.transcript(
                id = "$txId-t2",
                transmissionId = txId,
                text = "and we're clear on the repeater, seven three",
                isCurrent = true,
                createdAt = startedAt + 4_000L,
            ),
        )
        return LoadResult(1, 1, sessionId)
    }

    /**
     * `field-tier1` — a session flagged as captured at tier 1 ([org.ort.data.entity.SessionEntity.deviceTier]
     * is the only tier field `:data` carries, and it is a plain, freely-settable `String?`). Now
     * wired end to end by WP10 (register R-090/R-091, round 3): `Settings-Tier`/CF05 and
     * `Improve`/`Improve-Select`/`Improve-Running` all read it.
     *
     * R-143 (round 4, System validator): a single over made `Improve-Running` (R03) unreachable on
     * the emulator — [FakeImproveRunner]'s per-item delay times the *whole* run, so one over
     * finished before a screenshot script's own settle wait could ever catch it running. Twelve
     * overs (`FIELD_TIER1_OVER_COUNT`), still a real, honest number [ImprovePolling] counts for
     * real, gives the run a visible multi-second span at the runner's default per-item delay.
     *
     * R-290 (halt): the *real* `RealImproveRunner`/[org.ort.pipeline.reprocess.ReprocessRunner]
     * reaches this session's overs through [org.ort.pipeline.passb.FlacSegmentAudioProvider], which
     * `check`s that a real file exists at [org.ort.data.entity.TransmissionEntity.audioPath] and
     * throws if not — this fixture used to seed only the database rows, so R03/R04
     * (`Improve-Running`/`Improve-Done`) crashed the app on the first item instead of ever
     * completing. Every over here is, by definition, "improvable" (that is what a non-null
     * `deviceTier` below the current one means — see [ImprovePolling.root]'s own kdoc), so every one
     * of them now gets [ScenarioFixtures.writeAudioFixture]'s real, decodable file — the same
     * synthetic-tone fixture `OvernightScenario`'s own `writeAudio = true` overs already write, and
     * the exact container [FlacSegmentAudioProvider]'s default codec
     * ([org.ort.capture.android.codec.DeflatePredictiveCodec]) decodes (its own class name aside,
     * that is the real codec production reprocessing reads with — confirmed by reading
     * [org.ort.pipeline.passb.FlacSegmentAudioProvider]'s own constructor default before writing
     * this, not assumed from the file extension `TransmissionEntity.audioPath()` happens to use).
     */
    private suspend fun fieldTier1(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("field-tier1")
        db.sessionDao().insert(
            ScenarioFixtures.session(
                sessionId,
                startedAt = SystemClock.wallMillis() - 3_600_000L,
                endedAt = null,
                deviceTier = org.ort.core.Tier.T1.name,
            ),
        )
        val baseStartedAt = SystemClock.wallMillis() - 400_000L
        repeat(FIELD_TIER1_OVER_COUNT) { i ->
            val txId = "$sessionId-tx${i + 1}"
            val startedAt = baseStartedAt + i * 20_000L
            val tx = ScenarioFixtures.transmission(
                id = txId,
                sessionId = sessionId,
                startedAtUtc = startedAt,
                samplePosition = (i + 1).toLong(),
                frequencyHz = 146_960_000L,
                attributionState = AttributionState.INFERRED,
                stationId = ScenarioFixtures.CALLSIGNS[i % ScenarioFixtures.CALLSIGNS.size],
                attributionConfidence = 0.68,
            )
            db.transmissionDao().insert(tx)
            // R-290: real, decodable retained audio at the exact path the reprocess engine
            // requires — not seeded before this fix (see this function's own kdoc).
            ScenarioFixtures.writeAudioFixture(context, tx)
            db.transcriptDao().insert(
                ScenarioFixtures.transcript(
                    id = "$txId-t1",
                    transmissionId = txId,
                    text = "roger that, good copy on the repeater this morning",
                    isCurrent = true,
                    createdAt = startedAt + 1_000L,
                ),
            )
        }
        return LoadResult(FIELD_TIER1_OVER_COUNT, 1, sessionId)
    }

    /**
     * `search-corpus` — enough transcripts containing "park activation" for ~14 hits across 3
     * nights.
     *
     * **R-502's own root cause, fixed here.** Every transmission below sets `stationId = callsign`
     * (the callsign string used directly as the id — [Attribution.confirmed]'s own `stationId` is
     * exactly this value, no separate lookup, which is why every screenshot already renders the
     * right callsign) — but until this fix, no [StationEntity] row with that id ever existed.
     * `SearchDao.filterOnly`/`searchText`'s callsign filter is `LEFT JOIN station ON station.id =
     * transmission.stationId` then `UPPER(station.callsign) = UPPER(:callsign)` — a real,
     * normalized-catalog design, correctly reflecting how `:data` models this everywhere else
     * (`SearchPollingTest.kt`'s/`StationsFixtures.kt`'s own fixtures both insert a matching
     * [StationEntity] alongside every transmission) — this scenario alone never had. With no
     * `station` row, that join always produced `NULL`, so a callsign-shaped query (R-371's own
     * `TextQueryRouter`, routed correctly) always matched zero rows: not a routing bug, not the
     * screenshot-tour seam failing to reach the search, but this fixture's own incomplete catalog.
     */
    private suspend fun searchCorpus(db: OrtDatabase): LoadResult {
        var transmissionCount = 0
        val nights = 3
        val perNight = listOf(5, 5, 4) // sums to 14, matching this package's brief
        var primary: String? = null
        val stationsSeeded = mutableSetOf<String>()
        for (night in 0 until nights) {
            val sessionId = ScenarioFixtures.sessionId("search-corpus", "night$night")
            val nightStart = SystemClock.wallMillis() - (nights - night) * 24 * 3_600_000L
            db.sessionDao().insert(
                ScenarioFixtures.session(
                    sessionId,
                    startedAt = nightStart,
                    endedAt =
                    nightStart + 3_600_000L,
                ),
            )
            if (primary == null) primary = sessionId
            repeat(perNight[night]) { i ->
                val txId = "$sessionId-tx$i"
                val startedAt = nightStart + i * 60_000L
                val callsign = ScenarioFixtures.CALLSIGNS[i % ScenarioFixtures.CALLSIGNS.size]
                if (stationsSeeded.add(callsign)) {
                    db.catalogDao().insert(
                        StationEntity(
                            id = callsign,
                            callsign = callsign,
                            firstHeardAt = startedAt,
                            lastHeardAt = startedAt,
                            notes = null,
                            userName = null,
                            frequenciesHeard = null,
                            activityByHourDow = null,
                            potaRefs = null,
                            spokenGrids = null,
                            ituRegionFromPrefix = null,
                            overCountsByAttributionState = null,
                        ),
                    )
                }
                db.transmissionDao().insert(
                    ScenarioFixtures.transmission(
                        id = txId,
                        sessionId = sessionId,
                        startedAtUtc = startedAt,
                        samplePosition = i.toLong() + 1L,
                        frequencyHz = if (i % 2 == 0) 146_960_000L else 145_230_000L,
                        attributionState = AttributionState.CONFIRMED,
                        stationId = callsign,
                        attributionConfidence = 0.9,
                    ),
                )
                db.transcriptDao().insert(
                    ScenarioFixtures.transcript(
                        id = "$txId-t1",
                        transmissionId = txId,
                        text = "this is $callsign, doing a park activation at K-${4400 + i}, any hunters listening",
                        isCurrent = true,
                        createdAt = startedAt + 1_000L,
                    ),
                )
                transmissionCount++
            }
        }
        return LoadResult(transmissionCount, nights, primary)
    }

    /**
     * `search-unavailable` — coordinator-reported (round-eleven tooling): the amber
     * `Search-Unavailable.dc.html` board (`search-unavailable-banner`, the field's own "Not
     * applied" cue, `Retry`) has never been reachable — no scenario ever put
     * [org.ort.app.ui.data.SearchPolling.search]'s real fts5-unavailable degrade path
     * ([org.ort.app.ui.data.SearchResult.textSearchUnavailable]) into its `true` branch.
     *
     * A **real SQL-level break of `transcript_fts` was tried first and rejected** — read and
     * proved unsafe by running it, not assumed: [org.ort.data.OrtDatabase.create]'s own
     * `ensureFtsIndex` touches `transcript_fts` unconditionally on every single call (register
     * R-204's own `'rebuild'` step), so corrupting its shadow tables survives past that self-heal
     * and breaks the fts5 virtual table's own *construction* — every later `OrtDatabase.create()`
     * call anywhere in the app, not just Search, then fails outright
     * (`vtable constructor failed: transcript_fts`, the real exception a throwaway Robolectric test
     * against real `BundledSQLiteDriver` SQLite produced before this scenario took its current
     * shape). That is catastrophic, not a Search-only degrade, so this scenario instead uses
     * [org.ort.app.ui.data.DebugSearchOverride] — the exact same debug-override-receiver pattern
     * [org.ort.app.ui.failures.DebugFailureOverride] already established for six failure boards
     * with no real signal (WP11b's own file) and [DebugLexiconImportOverride] established for F12 —
     * see that object's own kdoc (`app/src/main/kotlin/org/ort/app/ui/data/SearchViewData.kt`,
     * WP7's file — the one, minimal, coordinator-anticipated touch outside this package's own row
     * this scenario needed; flagged in this package's own report for WP7 to review) for exactly why
     * a flag, not real corruption. One-shot, not sticky: the *first* free-text search after this
     * scenario loads genuinely degrades; `Retry` (which only ever calls `search()` again) genuinely
     * recovers to real results on its second call — the "index was rebuilding, now it is ready"
     * story `Search-Unavailable.dc.html`'s own `Retry` action tells, proven end to end rather than
     * left permanently broken.
     */
    private suspend fun searchUnavailable(db: OrtDatabase): LoadResult {
        val result = searchCorpus(db)
        DebugSearchOverride.forceNextTextSearchUnavailable()
        return result
    }

    /**
     * `backlog` — F8, the shed level 3 the plan's own artboard reading maps to (register R-038:
     * "Backlog (3 overs waiting · not growing)"). [ShedStatus] is settable from `:app` today
     * ([org.ort.app.ui.data.ReaderPolling] already reads it directly) — no `:pipeline` change
     * needed.
     */
    private suspend fun backlog(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("backlog")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 40 * 60_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        ShedStatus.update(level = 3, backlog = 112)
        return LoadResult(0, 1, sessionId)
    }

    /**
     * `model-missing` — F13, no ASR model installed. [AsrAvailability] is settable from `:app`
     * today for the same reason [ShedStatus] is. Transmissions are captured but never transcribed
     * (`processingState = CAPTURED`, no transcript row) — the honest state a real device with no
     * model shows.
     */
    private suspend fun modelMissing(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("model-missing")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 20 * 60_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        AsrAvailability.unavailable("no ASR model installed — see Settings › Models")
        // R-440 (register): "model-missing keeps nothing installed" — real files on disk, outside
        // this file's own DB-row clearing, so a prior overnight/overnight-live/stations-14-nights
        // load's own seeded models must be removed here explicitly.
        ScenarioFixtures.uninstallEveryModelFixture(context)
        val startedAt = SystemClock.wallMillis() - 60_000L
        db.transmissionDao().insert(
            ScenarioFixtures.transmission(
                id = "$sessionId-tx1",
                sessionId = sessionId,
                startedAtUtc = startedAt,
                samplePosition = 1L,
                frequencyHz = 146_960_000L,
                attributionState = AttributionState.UNKNOWN,
                processingState = TransmissionState.CAPTURED,
            ),
        )
        return LoadResult(1, 1, sessionId)
    }

    /**
     * `storage-warn` — F6/FR-STO-3, register R-105. **Replaces this scenario's earlier misuse of
     * F6's exhaustion failure string** (`CaptureState.failed(...)`) — capture is genuinely still
     * running (never `Failed`); the early warning is now [StorageForecast.ThreeNightsLeft], the
     * real "getting low" state WP11a added, set directly the way [ShedStatus]/[AsrAvailability]
     * are for the same reason a scenario asserts an exact state rather than reverse-engineering the
     * byte/time inputs that would produce it.
     */
    private suspend fun storageWarn(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("storage-warn")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 90 * 60_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        StorageForecast.set(
            StorageForecast.State.ThreeNightsLeft(
                freeBytes = 6L * 1024 * 1024 * 1024, // ~6 GB free
                audioDirectoryBytes = 38L * 1024 * 1024 * 1024 + (200L * 1024 * 1024), // ~38.2 GB, matching the board
                nightsLeft = 2.4,
            ),
        )
        return LoadResult(0, 1, sessionId)
    }

    /**
     * `storage-fine` — register R-231. `RecoveryAnnouncer.announce`'s own "Storage back above the
     * floor" toast fires only on a transition *into* [StorageForecast.State.Fine] from
     * [StorageForecast.State.ThreeNightsLeft]/`OneNightLeft`/`AtFloor` — no scenario published
     * `Fine` at all before this one, so that toast was unreachable no matter what preceded it. Load
     * with `-NoRestart` (`tools/ui-audit/scenario.ps1`) straight after `storage-warn` so
     * `RecoveryAnnouncer`'s own poll tick observes the real transition, the same recipe `rig-lost` →
     * `rig-reconnected` uses for R-230.
     */
    private suspend fun storageFine(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("storage-fine")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 95 * 60_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        StorageForecast.set(
            StorageForecast.State.Fine(
                freeBytes = 40L * 1024 * 1024 * 1024, // ~40 GB free, well clear of the warning stage
                audioDirectoryBytes = 12L * 1024 * 1024 * 1024,
                nightsLeft = 9.0,
            ),
        )
        return LoadResult(0, 1, sessionId)
    }

    /**
     * `thermal` — F7, register R-104. [ThermalStatus] warm with a measured RTF of 0.9
     * (`Fail-Thermal.dc.html`'s own figure), the same shed level/backlog `backlog` already sets
     * (F8's board and this one show the same degraded tier together in practice).
     */
    private suspend fun thermal(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("thermal")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 70 * 60_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        ShedStatus.update(level = 3, backlog = 112)
        // R-177: a real, fixed "minutes ago" transition moment -- not "now" -- so the banner's
        // "dropped to tier N at HH:MM:SS" reads the same real clock time on every poll instead of
        // advancing each time the mapper re-renders it.
        ThermalStatus.update(
            osThermalStatus = ThermalStatus.THERMAL_STATUS_MODERATE,
            realTimeFactor = 0.9,
            sinceMillis = SystemClock.wallMillis() - 12 * 60_000L,
        )
        return LoadResult(0, 1, sessionId)
    }

    /**
     * `rig-lost` — F9, register R-104. [RigStatus] stale since 30 minutes ago, last known on
     * 145.230 (`Fail-Rig.dc.html`'s own figures) — the rig module itself (FR-RIG) is unbuilt
     * (register R-084), so this scenario is the only producer of a `Connected`/`Stale` [RigStatus]
     * today; [org.ort.pipeline.capture.RealCaptureService]'s own real production call is
     * [RigStatus.absent] (see that class's kdoc).
     */
    private suspend fun rigLost(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("rig-lost")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 5 * 3_600_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        val lastKnown = RigStatus.State.Connected(
            descriptor = "TH-D75A",
            bands = listOf(
                RigStatus.BandState(band = "A", frequencyHz = 145_230_000L, mode = "FM", squelchOpen = true),
                RigStatus.BandState(band = "B", frequencyHz = 146_960_000L, mode = "FM", squelchOpen = false),
            ),
        )
        RigStatus.stale(lastKnown, sinceMillis = SystemClock.wallMillis() - 30 * 60_000L)
        return LoadResult(0, 1, sessionId)
    }

    /**
     * `rig-reconnected` — register R-230. `RecoveryAnnouncer.announce`'s own "Radio reconnected"
     * toast fires only on a transition from [RigStatus.State.Stale] to
     * [RigStatus.State.Connected] — `rig-lost` → `empty` never reaches it (`empty` never publishes
     * a `RigStatus` at all, so the process-wide holder just resets to
     * [RigStatus.State.Absent] and stays there), so this scenario exists specifically to be the
     * *second half* of that transition. Same descriptor and bands `rig-lost` itself uses
     * (`TH-D75A`, 145.230/146.960), reconnected rather than stale, so the "same radio came back"
     * story is honest, not a different device appearing. Load with `-NoRestart`
     * (`tools/ui-audit/scenario.ps1`) straight after `rig-lost` so `RecoveryAnnouncer`'s own poll
     * tick observes the real transition.
     */
    private suspend fun rigReconnected(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("rig-reconnected")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 5 * 3_600_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        RigStatus.connected(
            descriptor = "TH-D75A",
            bands = listOf(
                RigStatus.BandState(band = "A", frequencyHz = 145_230_000L, mode = "FM", squelchOpen = true),
                RigStatus.BandState(band = "B", frequencyHz = 146_960_000L, mode = "FM", squelchOpen = false),
            ),
        )
        return LoadResult(0, 1, sessionId)
    }

    /**
     * `level-low` — F3, register R-112. Peak −38 dBFS, floor −60 dBFS, no clipping
     * (`Fail-Level.dc.html`'s own figures) — the radio's volume has drifted quiet, but capture is
     * still genuinely running.
     */
    private suspend fun levelLow(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("level-low")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 90 * 60_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        // R-462 (register, Reviewer A round 2): `Level-Meter.dc.html`'s own subtitle is "USB Audio
        // Device · last 60 s" — `CaptureStatusContent.levelInputLabel` already prepends the real
        // device name when `InputStatus` carries one (confirmed by reading it before this fix);
        // this scenario never seeded one, so the subtitle honestly read only "last 60 s".
        InputStatus.opened(
            descriptor = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Device"),
            nativeRateHz = 48_000,
            resamplerId = "polyphase/v1 48000->16000 (L=1 M=3 taps=64 8f2c91a4d310)",
            routeVerified = true,
            routedDeviceMatches = true,
            openedAtMillis = SystemClock.wallMillis(),
        )
        // R-804 (halt): CF02/CF11 read CaptureConfigurationStore.current(), not InputStatus — a USB
        // session with nothing written here left them reading the store's own honest DEFAULT
        // (LOCAL_MICROPHONE), inconsistent with the USB descriptor just published above.
        // `markCapturing` above already flipped `CaptureState.isCapturing`, so this must be the
        // force-current variant, not the ordinary store — see that function's own kdoc.
        forceCurrentCaptureConfiguration(
            context,
            CaptureConfiguration(mode = CaptureMode.USB_RADIO, selectedInputId = "usb-1"),
        )
        LevelStatus.update(
            LevelStatus.State.Measured(
                peakDbfs = -38f,
                rmsDbfs = -44f,
                noiseFloorDbfs = -60f,
                clipped = false,
                clipCountLastSecond = 0,
                sampleRateHz = 48_000,
                updatedAtMillis = SystemClock.wallMillis(),
            ),
            peakHistoryDbfs = List(60) { -40f + (it % 5) },
        )
        // R-419: `LevelStatus.clippedSamplesThisSession` (WP11c's own follow-up) — quiet audio
        // genuinely never clips, so this stays honestly zero.
        LevelStatus.recordClippedSamplesThisSession(0L)
        // R-419: `ReaderPolling.weakestOverLabel` reads this session's own transmissions' real
        // `signalStrength` (the weakest one) — this scenario used to seed none at all, so
        // `Level-Meter.dc.html`'s own "Weakest over resolved tonight" row was honestly absent, not
        // exercised. One quiet over, genuinely weak, gives the row a real minimum to report.
        val txId = "$sessionId-tx1"
        db.transmissionDao().insert(
            ScenarioFixtures.transmission(
                id = txId,
                sessionId = sessionId,
                startedAtUtc = SystemClock.wallMillis() - 5 * 60_000L,
                samplePosition = 1L,
                frequencyHz = 146_960_000L,
                signalStrength = 2.0,
                attributionState = AttributionState.CONFIRMED,
                stationId = "W7NPC",
                attributionConfidence = 0.7,
            ),
        )
        db.transcriptDao().insert(
            ScenarioFixtures.transcript(
                id = "$txId-t1",
                transmissionId = txId,
                text = "this is whiskey seven november papa charlie, weak signal",
                isCurrent = true,
                createdAt = SystemClock.wallMillis() - 5 * 60_000L + 500L,
            ),
        )
        return LoadResult(1, 1, sessionId)
    }

    /**
     * `level-clip` — F3, register R-112. Peak 0 dBFS, clipped, 12 clips in the last second
     * (`Level-Meter.dc.html`'s own clip-count row) — the radio's volume is too hot.
     */
    private suspend fun levelClip(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("level-clip")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 20 * 60_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        // R-462: same real device name `level-low` now seeds, for the same subtitle reason.
        InputStatus.opened(
            descriptor = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Device"),
            nativeRateHz = 48_000,
            resamplerId = "polyphase/v1 48000->16000 (L=1 M=3 taps=64 8f2c91a4d310)",
            routeVerified = true,
            routedDeviceMatches = true,
            openedAtMillis = SystemClock.wallMillis(),
        )
        // R-804 (halt): see `levelLow`'s own identical comment — the force-current variant, since
        // `markCapturing` above already flipped `CaptureState.isCapturing`.
        forceCurrentCaptureConfiguration(
            context,
            CaptureConfiguration(mode = CaptureMode.USB_RADIO, selectedInputId = "usb-1"),
        )
        LevelStatus.update(
            LevelStatus.State.Measured(
                peakDbfs = 0f,
                rmsDbfs = -6f,
                noiseFloorDbfs = -55f,
                clipped = true,
                clipCountLastSecond = 12,
                sampleRateHz = 48_000,
                updatedAtMillis = SystemClock.wallMillis(),
            ),
            peakHistoryDbfs = List(60) { if (it % 4 == 0) 0f else -8f },
        )
        // R-419: `LevelStatus.clippedSamplesThisSession` (WP11c's own follow-up) — a real,
        // plausible running total for a 20-minute session that has been clipping at 12/s,
        // distinctly larger than `clipCountLastSecond` so a test can tell them apart.
        LevelStatus.recordClippedSamplesThisSession(340L)
        return LoadResult(0, 1, sessionId)
    }

    /**
     * `input-verified` — R-113. [InputStatus.State.Opened] with a USB descriptor, native 48 kHz, a
     * recorded resampler identity, and a verified matching route (`Setup-Verify.dc.html`'s four
     * checks all satisfied).
     */
    private suspend fun inputVerified(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("input-verified")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 10 * 60_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        InputStatus.opened(
            descriptor = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Device"),
            nativeRateHz = 48_000,
            resamplerId = "polyphase/v1 48000->16000 (L=1 M=3 taps=64 8f2c91a4d310)",
            routeVerified = true,
            routedDeviceMatches = true,
            openedAtMillis = SystemClock.wallMillis(),
        )
        // R-804 (halt): see `levelLow`'s own identical comment — the force-current variant, since
        // `markCapturing` above already flipped `CaptureState.isCapturing`.
        forceCurrentCaptureConfiguration(
            context,
            CaptureConfiguration(mode = CaptureMode.USB_RADIO, selectedInputId = "usb-1"),
        )
        return LoadResult(0, 1, sessionId)
    }

    /**
     * `input-mismatch` — R-113, F1. [InputStatus.State.Mismatch]: the built-in mic routed instead
     * of the chosen USB device (`Setup-Route-Mismatch.dc.html`/`Fail-Route.dc.html`'s own pairing).
     * Capture is deliberately **not** marked running — a mismatch halts capture in the same second
     * (`Fail-Route.dc.html`: "Capture stopped in the same second"; see this package's report for
     * exactly where `RealCaptureService`/`AudioRecordSource` already do that), so a scenario naming
     * this state must not also claim capture is still live.
     */
    private suspend fun inputMismatch(db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("input-mismatch")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 5 * 60_000L, endedAt = null),
        )
        InputStatus.mismatch(
            expected = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Device"),
            actual = AudioDeviceDescriptor("mic-0", AudioDeviceKind.BUILT_IN_MIC, "Built-in microphone"),
        )
        return LoadResult(0, 1, sessionId)
    }

    /**
     * `setup-verified` — R-227 (validator pass 2). Before this, S07 (`Setup-Level.dc.html`) and
     * S12 (`Setup-Done.dc.html`) had no sanctioned path on an emulator: [SetupStateMachine.stepFor]
     * resumes at [SetupStep.INPUT] until [SetupStore.inputVerified] is real, and that can only
     * become real through S05's own 30 s raw-signal listen ([RealRouteCheck]) actually hearing
     * something — which a silent emulator mic never does. The validator's own workaround (editing
     * `SharedPreferences` via `run-as`) is exactly what a debug scenario exists to make unnecessary
     * (register R-110's own brief) — this seeds the *same* `org.ort.app.setup` preferences file
     * [SharedPreferencesSetupStore] itself reads and writes, through that real class (never
     * duplicated key names), landing the natural resume point at [SetupStep.READY] (S12) with the
     * Level row already green.
     *
     * **R-264 (V7 accessibility pass) found the S07 path this row's own doc comment above named
     * (`Fix`/`Install`, `EXTRA_STEP=LEVEL`) still unreachable from a cold launch**: `SetupActivity`
     * is `android:exported="false"` (confirmed by reading `AndroidManifest.xml` before writing
     * this), so the README's old direct-launch recipe threw a `SecurityException` on a real device,
     * and `MainActivity` (the actually-exported entry point) routes a fully-`setup-verified` state
     * straight to `READY` — S07 was only reachable by *also* tapping the Level row's `Fix` action
     * once already on S12, not from a cold launch on its own. [setupLevel] is the fix: the same
     * verified-input base as this scenario, but [SetupStore.levelInBand] genuinely left unset
     * (`false`, the honest default — never claiming a level that was never measured), so
     * `stepFor`'s own next check (`!snapshot.levelInBand -> SetupStep.LEVEL`) resumes there
     * directly, through `MainActivity`, on a cold launch, no extra tap needed.
     *
     * **Not a substitute for granting the two OS permissions.** `RECORD_AUDIO`/`POST_NOTIFICATIONS`
     * are live [android.content.pm.PackageManager] state, not a preference this scenario can seed —
     * confirmed by reading [SetupStateMachine.stepFor] before writing this: both gates are read from
     * a live `PermissionsState`, never from [SetupSnapshot]. The validator (or `results/ui-audit/README.md`,
     * which documents this) must still grant both separately, e.g.
     * `adb shell pm grant org.ort.app android.permission.RECORD_AUDIO` and the `POST_NOTIFICATIONS`
     * equivalent, exactly as every other scenario that exercises a real screen already assumes.
     */
    private fun setupVerified(context: Context): LoadResult {
        val store = verifiedInputStore(context)
        store.levelInBand = true
        store.levelPeakDbfs = -14.0
        store.overnightStepSeen = true
        store.radioChoice = RadioChoice.NONE
        store.rigTransport = RigTransportKind.USB_SERIAL
        store.manualFrequencyHz = 145_230_000L
        store.setupComplete = false
        // R-943 (register, reviewer A4 run 5, halt): S05 (`Setup-Verify.dc.html`) is reached by a
        // cold `SetupActivity.EXTRA_STEP` launch straight at `VERIFY` — no S04 selection ever ran,
        // so `SetupActivity.selectedDescriptor` is `null` and `RenderVerify` never starts
        // `RealRouteCheck.run`'s own listen loop. Every fact this board needs (the routed-device
        // line, the native-rate line, the elapsed counter, the Input waveform card, the noise-floor
        // text) comes only from a live `RouteCheckState` the real check would otherwise have to
        // genuinely run and finish to produce — hardware the tour's AVD does not have and 30s the
        // tour cannot spend per step (`DebugRouteCheckOverride`'s own doc comment, WPD's seam this
        // round). `passed = {NATIVE_RATE, ROUTE_MATCH}`: those two facts are already known by the
        // moment a real device label/rate exist; `SIGNAL` is what `levelBars`/`noiseFloorDbfs`
        // themselves represent, still running (never in `passed` — it has not passed yet); `RESAMPLER`
        // not reached. **Corrects this function's own prior-round doc comment**, which assumed S05
        // read the process-wide `LevelStatus` holder S07's meter does — reading
        // `VerifyScreen.kt`/`DebugRouteCheckOverride.kt` once they actually landed on this branch
        // shows S05 reads `RouteCheckState.InProgress.levelBars` instead, a genuinely different
        // holder; the `LevelStatus.update` call this replaced never did anything this board reads.
        DebugRouteCheckOverride.show(
            RouteCheckState.InProgress(
                passed = setOf(RouteCheckStage.NATIVE_RATE, RouteCheckStage.ROUTE_MATCH),
                nativeRateHz = 48_000,
                elapsedListeningMillis = 12_000L,
                routedDeviceLabel = "USB Audio Device",
                levelBars = speechShapedLevelBarFractions(),
                noiseFloorDbfs = -58.0,
            ),
        )
        return LoadResult(0, 0, null)
    }

    /**
     * R-943: a genuine speech-shaped envelope for [org.ort.app.ui.setup.RouteCheckState.InProgress
     * .levelBars] — [org.ort.app.ui.setup.VerifyScreen]'s own `InputWaveformCard` draws every
     * element of this list directly (no `takeLast` truncation the way S07's own meter has —
     * confirmed by reading `VerifyScreen.kt` before writing this, the exact class of bug R-944
     * found there), so a shorter list sized to what a waveform card actually shows is honest here,
     * not a 60-sample history built for a different display. Two raised-cosine lobes, the same
     * shape [speechShapedPeakHistoryDbfs] uses, mapped through the real
     * [org.ort.app.ui.setup.levelBarFraction] so this fraction scale agrees with S07's own meter
     * (`RouteCheckState.InProgress`'s own doc comment).
     */
    private fun speechShapedLevelBarFractions(): List<Float> {
        val floorDbfs = -58.0
        val peakDbfs = -14.0
        val sampleCount = 24
        fun lobe(index: Int, center: Int, halfWidth: Int): Double {
            val distance = kotlin.math.abs(index - center)
            if (distance > halfWidth) return 0.0
            return 0.5 * (1.0 + kotlin.math.cos(Math.PI * distance / halfWidth))
        }
        return List(sampleCount) { i ->
            val envelope = maxOf(lobe(i, center = 8, halfWidth = 5), lobe(i, center = 17, halfWidth = 4) * 0.75)
            levelBarFraction(floorDbfs + envelope * (peakDbfs - floorDbfs))
        }
    }

    /**
     * `setup-level` — R-264 (V7 accessibility pass, register R-260..R-267): the same verified-input
     * base [setupVerified] seeds, but [SetupStore.levelInBand]/[SetupStore.levelPeakDbfs] are left
     * at their honest, unset defaults (`false`/`null` — never a fabricated measurement), so
     * `SetupStateMachine.stepFor` resumes at [SetupStep.LEVEL] (S07) directly, reachable from a
     * cold `MainActivity` launch with no extra tap — see [setupVerified]'s own doc comment for the
     * full account of why S07 needed its own scenario rather than only `setup-verified`'s `Fix` row.
     */
    private fun setupLevel(context: Context): LoadResult {
        verifiedInputStore(context)
        // R-411 (register, WP12's own tour finding): S07's meter (`Setup-Level.dc.html`) reads the
        // same live `LevelStatus` holder `Level-Meter.dc.html`/`level-low`/`level-clip` already do
        // — this scenario never published one, so a clean install (no prior real capture in this
        // process) left the meter genuinely empty (no bars, "noise —", Continue disabled). This is
        // independent of `SetupStore.levelInBand` (left unset above, on purpose, so `stepFor` still
        // resumes at S07 rather than skipping past it) — a real reading the operator has not yet
        // confirmed is exactly the state this step exists to show.
        //
        // R-944 (validator/reviewer, this round): the flat `-20f + (it % 5)` cycle this used to pass
        // produced alternating full-height amber/green blocks once WPD proved S07's meter is a real
        // proportional envelope (`levelBarRects`) — a shape no real signal ever draws. Replaced with
        // [speechShapedPeakHistoryDbfs]'s genuine rise-and-fall between the noise floor and a real
        // peak, the same envelope [setupVerified]'s own S05 listen now seeds too.
        LevelStatus.update(
            LevelStatus.State.Measured(
                peakDbfs = -14f,
                rmsDbfs = -20f,
                noiseFloorDbfs = -58f,
                clipped = false,
                clipCountLastSecond = 0,
                sampleRateHz = 48_000,
                updatedAtMillis = SystemClock.wallMillis(),
            ),
            peakHistoryDbfs = speechShapedPeakHistoryDbfs(),
        )
        return LoadResult(0, 0, null)
    }

    /**
     * R-944: a genuine speech-shaped envelope for [LevelStatus.update]'s own `peakHistoryDbfs` —
     * two syllable-like rises from the noise floor (-58 dBFS) toward a real peak (-14 dBFS) and back
     * down, a few seconds' worth of samples (60, matching every other caller's own history length),
     * never a flat or mechanically-alternating cycle (the original bug this fixed: a repeating
     * `-20f + (it % 5)` pattern drew as alternating full-height amber/green blocks once WPD's
     * `levelBarRects` made S07's meter a real proportional envelope, not the honest waveform this
     * board is meant to show).
     *
     * **Both lobes sit inside the last 15 samples, not spread across the full 60** — found only
     * after A4's own device report ("a decaying spike," not a rise-and-fall) sent this back through
     * `LevelCheck.kt`: `levelReadingFrom` never reads the whole history, only
     * `history.takeLast(RealLevelCheck.DEFAULT_BAR_COUNT)` (15) — this function's own first version
     * centered its two lobes at index 14 and 38, both entirely *outside* that 15-sample tail window
     * (indices 45..59), so S07's board only ever rendered that second lobe's own trailing decay
     * into the noise floor, never its rise. Centering both lobes inside 45..59 instead makes the
     * one window this board (and every other reader of this same history) ever actually displays
     * show the genuine rise-and-fall this register asks for; the untouched leading samples
     * (0..44) stay at the honest floor — never displayed by [RealLevelCheck.DEFAULT_BAR_COUNT]'s
     * own window, so their own value carries no risk of misleading anything that does read more of
     * the history than S07 does.
     */
    private fun speechShapedPeakHistoryDbfs(): List<Float> {
        val floorDbfs = -58f
        val peakDbfs = -14f
        val sampleCount = 60
        fun lobe(index: Int, center: Int, halfWidth: Int): Float {
            val distance = kotlin.math.abs(index - center)
            if (distance > halfWidth) return 0f
            return 0.5f * (1f + kotlin.math.cos(Math.PI.toFloat() * distance / halfWidth))
        }
        return List(sampleCount) { i ->
            val envelope = maxOf(lobe(i, center = 50, halfWidth = 5), lobe(i, center = 57, halfWidth = 3) * 0.75f)
            floorDbfs + envelope * (peakDbfs - floorDbfs)
        }
    }

    /**
     * `setup-radio` — R-285 (V1 pass 3). No sanctioned path reached S09..S11 (`Setup-Rig*.dc.html`)
     * on an emulator before this: the linear flow stalls at S05's own 30 s raw-signal listen (a
     * silent emulator mic never hears anything, [setupVerified]'s own doc comment), and
     * `setupVerified` itself resolves straight through [SetupStep.RADIO] to `READY` by setting
     * [SetupStore.radioChoice] up front — so even the `Fix` action on S12's own Radio row landed
     * back on `READY` immediately rather than ever showing S09, since [SetupStateMachine.stepFor]
     * only stops at [SetupStep.RADIO] while [SetupSnapshot.radioChoice] is still unset. This
     * scenario is the same verified-input/level/overnight base [setupVerified] seeds, but leaves
     * [SetupStore.radioChoice] at its honest, unset default (`null` — never a fabricated choice), so
     * `stepFor` resumes at [SetupStep.RADIO] directly from a cold `MainActivity` launch, the same
     * "leave the one gate this screen exists to test unset" recipe [setupLevel] already uses for S07.
     *
     * `radioChoice`/`manualFrequencyHz` are set to `null` explicitly, not merely left untouched —
     * [SharedPreferencesSetupStore] persists across scenario loads (unlike the `:data` tables
     * [clearPriorScenarioData] wipes), so a `setup-verified` run immediately before this one would
     * otherwise leave `radioChoice = NONE` behind and this scenario would resume at `READY`, not
     * `RADIO`, defeating its own purpose.
     */
    private fun setupRadio(context: Context): LoadResult {
        val store = verifiedInputStore(context)
        store.levelInBand = true
        store.levelPeakDbfs = -14.0
        store.overnightStepSeen = true
        store.radioChoice = null
        store.manualFrequencyHz = null
        return LoadResult(0, 0, null)
    }

    /**
     * `setup-verified-local-mic` — E2-J04 (checklist row, coordinator round): [setupVerified] and its
     * two siblings all share [verifiedInputStore]'s `USB_RADIO`/`usb-1` base, so no scenario ever
     * reached S07..S12 under `LOCAL_MICROPHONE` — the register's own finding. A verified, fully-
     * configured local-mic setup: `SetupStep.READY` (S12) shows the Mode row as "Local microphone ·
     * frequency by hand" (`radioChoice = NONE` + a real `manualFrequencyHz`, the same honest
     * no-rig-module-built-yet pairing [assetsBundled]/[seedConfiguredDeviceState] already establish
     * for a USB session — FR-CAP-2b's own fact that local-mic mode has no rig at all does not change
     * how the Mode row itself reports "no radio chosen", the identical fact any other no-rig mode
     * would), and — since [SetupStateMachine.needsRigTransport] gates on `radioChoice` alone, never
     * on `captureMode` — no S09b/S10b Rig rows at all, confirmed by reading that function before
     * writing this.
     */
    private fun setupVerifiedLocalMic(context: Context): LoadResult {
        val prefs = context.applicationContext.getSharedPreferences(
            SharedPreferencesSetupStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val store = SharedPreferencesSetupStore(prefs)
        store.welcomeSeen = true
        store.captureMode = CaptureMode.LOCAL_MICROPHONE
        store.notificationsSkipped = true
        store.selectedInputId = "mic-0"
        store.selectedInputLabel = "Built-in microphone"
        store.inputVerified = true
        store.verifiedNativeRateHz = 48_000
        store.verifiedResamplerIdentity = "polyphase/v1 48000->16000 (L=1 M=3 taps=64 8f2c91a4d310)"
        // R-804's own class: CF02/CF11 read CaptureConfigurationStore.current(), not SetupStore.
        realCaptureConfigurationStore(context).update(
            CaptureConfiguration(mode = CaptureMode.LOCAL_MICROPHONE, selectedInputId = "mic-0"),
        )
        store.levelInBand = true
        store.levelPeakDbfs = -14.0
        store.overnightStepSeen = true
        store.radioChoice = RadioChoice.NONE
        store.manualFrequencyHz = 145_230_000L
        store.setupComplete = false
        return LoadResult(0, 0, null)
    }

    /**
     * `setup-rig-transport-preset` — E2-J04 (checklist row, coordinator round): [setupRigTransport]
     * already makes S09b's own preset label (`RigTransportOption.isPreset`, computed automatically
     * from `SetupStore.captureMode` via `CaptureModePresets.presetsFor` — confirmed by reading
     * `SetupActivity.onSelectRadio`/`RenderRigTransport` before writing this) show "preset by your
     * mode" against Bluetooth SPP, since it already sets `captureMode = BLUETOOTH_RADIO`. **What no
     * scenario reaches**: the row actually *pre-selected* (its own radio marker filled), because
     * `SetupActivity.selectedRigTransportKind` — the field `RenderRigTransport` reads for which row
     * shows selected — is a transient, `SetupStore`-independent `mutableStateOf` the real Activity
     * only ever seeds from the preset on the *forward* navigation path (`onSelectRadio`, S09→S09b);
     * a scenario landing cold on S09b via `EXTRA_STEP` (this scenario's own base, same as
     * [setupRigTransport]) never runs that path, so `selectedRigTransportKind` stays its own honest
     * `null` default regardless of what this scenario seeds — **a known, honest gap, reported here
     * rather than worked around**: closing it needs the same class of fix R-802 gave S10b's own
     * paired-device list (refresh/seed a transient Activity field on cold entry to a step, not only
     * on the forward-nav path that already sets it) — a `SetupActivity.kt` change this round's own
     * coordinator message did not pre-approve, unlike R-900's `rigLinkStateForTest` seam. This
     * scenario is therefore functionally identical to [setupRigTransport] today; it exists so the
     * tour has a stably-named step to re-point at once that fix lands, and so this gap is visible in
     * the tour's own step list rather than silently absent.
     */
    private fun setupRigTransportPreset(context: Context): LoadResult = setupRigTransport(context)

    /** The one `SetupStore` state [setupVerified] and [setupLevel] share — a real, verified input
     * selection, welcome already seen, notifications skipped (diagnostic-only — constitution: R-002
     * never gates capture on it). Neither level, overnight, radio nor `setupComplete` is touched
     * here; each caller decides those for itself. */
    /**
     * R-440 (register, WP12's own tour finding): `ScreenshotTourActivity` and the receiver both run
     * only [Scenarios.load] — the manual review passes that showed a configured `Settings*` board
     * on `overnight`/`overnight-live`/`stations-14-nights` were seeing state an *earlier Setup run*
     * had left on those AVDs, not anything this fixture itself wrote. On a clean install (the
     * tour's own environment) the same scenarios rendered "No input selected"/"No radio
     * configured"/"0.0 GB · no budget set"/"0 of 4 assets" — this is the real fix: every fact
     * CF01/CF02/CF03/CF06 read, seeded here exactly once, by the same real stores/files those
     * screens themselves read (never a shortcut UI-only flag). Radio stays [RadioChoice.NONE] +
     * a manual frequency (145.230 MHz) — honest, since FR-RIG's own module is unbuilt (register
     * R-084) and there is no real radio choice to seed instead.
     */
    private fun seedConfiguredDeviceState(context: Context) {
        val store = verifiedInputStore(context)
        store.overnightStepSeen = true
        store.radioChoice = RadioChoice.NONE
        store.rigTransport = RigTransportKind.USB_SERIAL
        store.manualFrequencyHz = 145_230_000L
        store.setupComplete = true
        val settingsPrefs = context.applicationContext.getSharedPreferences(
            SharedPreferencesSettingsStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        SharedPreferencesSettingsStore(settingsPrefs).audioBudgetGb = 60
        ScenarioFixtures.installEveryModelFixture(context)
        // R-804 (halt): the real fix for "overnight/CF02 shows Local microphone above a USB Audio
        // Device input row" — CF02/CF11 read CaptureConfigurationStore.current(), never SetupStore
        // or InputStatus, so a scenario naming a USB session must write it here too. Every caller of
        // this shared base (overnight/overnight-live/stations-14-nights) selects the same usb-1
        // input this function already publishes to InputStatus below. `overnight-live` has already
        // marked `CaptureState` capturing by the time this runs — [forceCurrentCaptureConfiguration],
        // not the ordinary store, is what keeps this landing as `current` regardless (see that
        // function's own kdoc for the regression this fixes).
        forceCurrentCaptureConfiguration(
            context,
            CaptureConfiguration(mode = CaptureMode.USB_RADIO, selectedInputId = "usb-1"),
        )
        // R-494 (register, Reviewer D round 2): `SettingsPolling`'s own Input rows (CF01/CF02 —
        // `inputSummaryLine`/`capture()`) never read `SetupStore` at all — confirmed by reading
        // that file before assuming the register's own guess — they read the live `InputStatus`
        // holder, which nothing here published, so it stayed `None` ("No input selected") despite
        // `SetupStore`'s own fields (above) already being verified. The same descriptor/rate/
        // resampler `verifiedInputStore` already records, opened for real — matching exactly what
        // `SetupActivity` itself writes once S04/S05 complete (see `input-verified`'s own
        // identical values, this file's established precedent).
        InputStatus.opened(
            descriptor = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Device"),
            nativeRateHz = 48_000,
            resamplerId = "polyphase/v1 48000->16000 (L=1 M=3 taps=64 8f2c91a4d310)",
            routeVerified = true,
            routedDeviceMatches = true,
            openedAtMillis = SystemClock.wallMillis(),
        )
        // R-494: CF06 (`SettingsRigScreen.kt`, WP10's own file) already renders "No rig module is
        // connected" / "No radio support in this build yet..." (R-444) for any non-Connected
        // `RigStatus` — `RigStatus.Absent` (this object's own honest default, left untouched here)
        // is not a bug to fix by fixture: FR-RIG's own module is genuinely unbuilt (register
        // R-084), and `radioChoice = RadioChoice.NONE` + a manual frequency (above) is precisely
        // the "no rig, 145.230 MHz by hand" configuration that state describes — CF06's existing
        // copy is already the honest, correct rendering for it, confirmed by `R_494`'s own test
        // below rather than assumed.
    }

    private fun verifiedInputStore(context: Context): SharedPreferencesSetupStore {
        val prefs = context.applicationContext.getSharedPreferences(
            SharedPreferencesSetupStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val store = SharedPreferencesSetupStore(prefs)
        store.welcomeSeen = true
        // D33/P19 (WPD): SetupStateMachine.stepFor now gates on captureMode before anything else
        // -- USB_RADIO matches the "usb-1" input fixture every caller of this shared base seeds
        // below, so each scenario's own documented resume point is reached again.
        store.captureMode = CaptureMode.USB_RADIO
        store.notificationsSkipped = true
        store.selectedInputId = "usb-1"
        store.selectedInputLabel = "USB Audio Device"
        store.inputVerified = true
        store.verifiedNativeRateHz = 48_000
        store.verifiedResamplerIdentity = "polyphase/v1 48000->16000 (L=1 M=3 taps=64 8f2c91a4d310)"
        // R-804 (halt): CF02/CF11 read CaptureConfigurationStore.current(), not SetupStore — every
        // caller of this shared base (setup-verified/setup-level/setup-radio) selects the same
        // usb-1 input above, so each must agree with the real store those two boards actually read.
        realCaptureConfigurationStore(context).update(
            CaptureConfiguration(mode = CaptureMode.USB_RADIO, selectedInputId = "usb-1"),
        )
        return store
    }

    // ---------------------------------------------------------------------------------------
    // WP11b (register R-100): the seven ids with no runtime signal today —
    // `DebugFailureOverride.kt`'s kdoc says exactly why for each. Each scenario below sets
    // [DebugFailureOverride.show] with the exact [FailurePresentation] its board needs, on top of
    // a minimal session so the reader beneath the takeover is not a bare crash surface.
    // ---------------------------------------------------------------------------------------

    /** `clock-dst` — F14, `Fail-Clock.dc.html`. */
    private suspend fun clockDst(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("clock-dst")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 8 * 3_600_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        DebugFailureOverride.show(
            FailurePresentation.Clock(
                ClockViewState(
                    offsetChangeLabel = "PDT → PST",
                    ranForLabel = "8 h 30 m",
                    startedLabel = "23:10",
                    endedLabel = "06:40",
                    nightLabel = "Overnight, Sat 31 Oct",
                    windowLabel = "23:10 – 06:40 · 8 h 30 m · the clock went back at 02:00",
                    logRows = listOf(
                        ClockLogRow("01:58:40", "before", "W7NPC", "copy on the repeater, seven three"),
                        ClockLogRow("01:01:12", "after (was 02:01:12)", "KJ7ABC", "back to you, seven three"),
                        ClockLogRow("01:04:03", "after (was 02:04:03)", null, "break, break — anyone on frequency"),
                    ),
                ),
            ),
        )
        return LoadResult(0, 1, sessionId)
    }

    /** `usb-permission` — F16, `Fail-Usb.dc.html`. */
    private suspend fun usbPermission(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("usb-permission")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 3 * 3_600_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        DebugFailureOverride.show(
            FailurePresentation.Usb(
                UsbViewState(detachedAtLabel = "03:44", reattachedAtLabel = "03:47", staleOversCount = 4),
            ),
        )
        return LoadResult(0, 1, sessionId)
    }

    /** `interrupted-pass` — F17, `Fail-Interrupted.dc.html`. */
    private suspend fun interruptedPass(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("interrupted-pass")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 10 * 60_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        DebugFailureOverride.show(
            FailurePresentation.Interrupted(
                InterruptedViewState(
                    overCount = 3,
                    gapLabel = "03:12 – 06:48",
                    backlogLabel = "3 overs waiting",
                    overs = listOf(
                        InterruptedOverRow("03:12:31", "audio only", "no transcript — the pass never started"),
                        InterruptedOverRow("03:12:08", "partial", "a Pass A partial, superseded by nothing yet"),
                        InterruptedOverRow("03:11:40", "audio only", "no transcript — the pass never started"),
                    ),
                ),
            ),
        )
        return LoadResult(0, 1, sessionId)
    }

    /** `reconcile` — F19, `Fail-Reconcile.dc.html`. */
    private suspend fun reconcile(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("reconcile")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 8 * 3_600_000L, endedAt = null),
        )
        DebugFailureOverride.show(
            FailurePresentation.Reconcile(
                ReconcileViewState(
                    recordsNoFile = listOf(
                        ReconcileRecord(
                            "W7NPC",
                            "Fri 4 Sep 01:22 · 28.4 s",
                            "transcript, attribution and lattice intact",
                        ),
                        ReconcileRecord(
                            "KJ7ABC",
                            "Fri 4 Sep 01:23 · 4.1 s",
                            "same session, same minute — likely the same cause",
                        ),
                        ReconcileRecord("unknown station", "Fri 4 Sep 01:23 · 2.0 s", null),
                    ),
                    filesNoRecord = listOf(
                        ReconcileFile(
                            "a/2026-09-04/03-12-31.flac",
                            "6.2 s",
                            "written while the app was stopped mid-write",
                        ),
                        ReconcileFile("a/2026-09-04/03-12-40.flac", "1.8 s", "same"),
                    ),
                    causeText = "The OS stopped the app at 03:12 on Fri 4 Sep with writes in flight. The three " +
                        "records lost their files to an interrupted retention pass the next morning; the two " +
                        "files are overs whose records never landed.",
                ),
            ),
        )
        return LoadResult(0, 1, sessionId)
    }

    /** `migration-failed` — F20, `Fail-Migration.dc.html`. */
    private suspend fun migrationFailed(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("migration-failed")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 60_000L, endedAt = null),
        )
        DebugFailureOverride.show(
            FailurePresentation.Migration(
                MigrationViewState(
                    versionLabel = "Updated to 1.1.0",
                    headline = "The records did not fully carry over",
                    steps = listOf(
                        MigrationStep("Audio untouched", "38.2 GB · 4,318 files · checksums match", ok = true),
                        MigrationStep(
                            "Transcripts, all versions, untouched",
                            "current and superseded · 6,904 rows",
                            ok = true,
                        ),
                        MigrationStep(
                            "Attributions, corrections, stations untouched",
                            "every state, every correction",
                            ok = true,
                        ),
                        MigrationStep(
                            "Activity patterns need rebuilding",
                            "the hour-bucket table changed shape · 4,318 overs marked · runs in the background",
                            ok = false,
                        ),
                    ),
                ),
            ),
        )
        return LoadResult(0, 1, sessionId)
    }

    /** `asset-swap` — F21, `Fail-Asset-Swap.dc.html`. */
    private suspend fun assetSwap(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("asset-swap")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 3_600_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        DebugFailureOverride.show(
            FailurePresentation.AssetSwap(
                AssetSwapViewState(
                    activeLabel = "2026.08 · active · this session · 1,104,208 records",
                    stagedLabel = "2026.09 · staged · next session · 1,122,410 records",
                    options = listOf(
                        AssetSwapOption("Wait for the session to end", "the default · nothing else to do"),
                        AssetSwapOption(
                            "Stop capture, swap, start a new session",
                            "tonight's log ends here · a second session begins on 2026.09 · both stay in " +
                                "Earlier nights",
                        ),
                        AssetSwapOption(
                            "Afterwards, re-run tonight on 2026.09",
                            "Improve records will offer it · 12 ambiguous overs might resolve with the new " +
                                "prefixes",
                        ),
                    ),
                    selectedOption = 0,
                ),
            ),
        )
        return LoadResult(0, 1, sessionId)
    }

    /** `calibration` — F22, `Fail-Calibration.dc.html`. */
    private suspend fun calibration(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("calibration")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 3_600_000L, endedAt = null),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        DebugFailureOverride.show(
            FailurePresentation.Calibration(
                CalibrationViewState(
                    sinceLabel = "1 Sep",
                    scoreLabel = "0.90",
                    accuracyLabel = "78%",
                    points = listOf(0.30f to 0.22f, 0.50f to 0.38f, 0.70f to 0.52f, 0.86f to 0.66f, 0.94f to 0.74f),
                    calibrationVersion = "2026.09-a",
                    correctionsCount = 22,
                    correctionsNeeded = 100,
                ),
            ),
        )
        return LoadResult(0, 1, sessionId)
    }

    // ---------------------------------------------------------------------------------------
    // P19/WPI (spec/e2e-capture-modes-plan.md, E2-J01) — capture modes, Bluetooth, bundled
    // assets and the LLM. See NAMES's own doc comment above for the one-paragraph summary of
    // every scenario below.
    // ---------------------------------------------------------------------------------------

    /** The one `SetupStore` state every P19 setup scenario below starts from: the real
     * `SharedPreferences` file wiped outright, not merely overwritten field by field — persistence
     * across scenario loads ([empty]'s own established caveat) means a partial overwrite would
     * leave whatever an *earlier* scenario in this process wrote (e.g. `setupVerified`'s `radioChoice`)
     * behind for a field this function's own caller never mentions. */
    private fun freshSetupStore(context: Context): SharedPreferencesSetupStore {
        val prefs = context.applicationContext.getSharedPreferences(
            SharedPreferencesSetupStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        prefs.edit().clear().apply()
        return SharedPreferencesSetupStore(prefs)
    }

    /**
     * R-821/R-822 (halt): the real [SharedPreferencesCaptureConfigurationStore] CF02/CF11 both read
     * — every live-session scenario below writes its own session's mode/input/rig facts here too, not
     * just into the `:data` session row, since a `SharedPreferences` file and a Room table share
     * nothing and CF02/CF11 read only this store, never the session entity directly.
     */
    private fun realCaptureConfigurationStore(context: Context): SharedPreferencesCaptureConfigurationStore =
        SharedPreferencesCaptureConfigurationStore(
            context.applicationContext.getSharedPreferences(
                SharedPreferencesCaptureConfigurationStore.PREFS_NAME,
                Context.MODE_PRIVATE,
            ),
        )

    /**
     * R-804 (halt, regression found by this fix's own gate run): [SharedPreferencesCaptureConfigurationStore.update]'s
     * freeze rule (FR-CAP-12) reads the real, live [CaptureState.isCapturing] — correct for a caller
     * asking to *change* a running session's configuration mid-flight, wrong for a scenario simply
     * *establishing the baseline* a fixture session already reflects, called *after*
     * [ScenarioFixtures.markCapturing] has already flipped that flag (`overnight-live`'s own
     * `seedConfiguredDeviceState` call site, `levelLow`/`levelClip`/`inputVerified`'s own). Using the
     * ordinary store there landed the write as `pendingConfiguration` instead of `current` — CF02/CF11
     * would have read the store's own honest `DEFAULT` (`LOCAL_MICROPHONE`) forever, since nothing in
     * these debug-only scenarios ever calls `activateForNewSession` to promote it. This variant's own
     * `isCapturing` seam always reports `false`, so [SharedPreferencesCaptureConfigurationStore.update]
     * always lands the write as `current`, regardless of what the real, process-wide [CaptureState]
     * happens to read at the moment this runs.
     */
    private fun forceCurrentCaptureConfiguration(context: Context, configuration: CaptureConfiguration) {
        SharedPreferencesCaptureConfigurationStore(
            context.applicationContext.getSharedPreferences(
                SharedPreferencesCaptureConfigurationStore.PREFS_NAME,
                Context.MODE_PRIVATE,
            ),
            isCapturing = { false },
        ).update(configuration)
    }

    /** `setup-mode` — D33/FR-CAP-8, S00: a completely fresh install, so
     * [SetupStateMachine.stepFor] resumes at [SetupStep.MODE] directly from a cold `MainActivity`
     * launch — no mode has ever been chosen. */
    private fun setupMode(context: Context): LoadResult {
        val store = freshSetupStore(context)
        store.welcomeSeen = true
        return LoadResult(0, 0, null)
    }

    /**
     * `setup-bt-permission` — D33, S02c: Bluetooth mode chosen, mic already granted (the tour/
     * validator grants `RECORD_AUDIO` before every scenario, `results/ui-audit/README.md`'s
     * established recipe), `BLUETOOTH_CONNECT` genuinely not yet decided — `stepFor` resumes at
     * [SetupStep.BLUETOOTH_PERMISSION] as long as the OS permission itself is not already granted.
     * **The OS permission is live [android.content.pm.PackageManager] state, not a preference this
     * scenario can seed** (`SetupStateMachine`'s own `PermissionsState` gate, the same reason
     * [setupVerified]'s doc comment gives for `RECORD_AUDIO`/`POST_NOTIFICATIONS`) — see
     * `results/ui-audit/README.md` for the exact `pm grant`/`pm revoke` recipe this state needs.
     */
    private fun setupBtPermission(context: Context): LoadResult {
        val store = freshSetupStore(context)
        store.welcomeSeen = true
        store.captureMode = CaptureMode.BLUETOOTH_RADIO
        store.bluetoothPermissionDeclined = false
        return LoadResult(0, 0, null)
    }

    /**
     * `setup-rig-transport` — D33/FR-RIG-13, S09b: Bluetooth mode, a verified wired-headset input
     * (AC-130's own "Bluetooth control with wired audio" pairing — the preset
     * [org.ort.core.capture.CaptureModePresets.presetsFor] proposes for this mode), the TH-D75A
     * chosen at S09 ([RadioChoice.TH_D75A], [org.ort.rig.NullRigModule.ID] untouched — this is
     * `:rig`'s own catalogue id, [org.ort.rig.descriptor.BundledDescriptors.kenwoodThD75a]), but
     * [SetupStore.rigTransport] genuinely left unset — `stepFor`'s `needsRigTransport` gate resumes
     * here directly from a cold launch, the same "leave the one gate this screen exists to test
     * unset" recipe [setupLevel]/[setupRadio] already establish. **Also needs `BLUETOOTH_CONNECT`
     * already granted** (see [setupBtPermission]'s own doc comment) — without it, `stepFor` stops
     * one step earlier, at S02c, not here.
     */
    private fun setupRigTransport(context: Context): LoadResult {
        val store = freshSetupStore(context)
        store.welcomeSeen = true
        store.captureMode = CaptureMode.BLUETOOTH_RADIO
        store.notificationsSkipped = true
        store.selectedInputId = "wired-1"
        store.selectedInputLabel = "Wired headset"
        store.inputVerified = true
        store.verifiedNativeRateHz = 48_000
        store.verifiedResamplerIdentity = "polyphase/v1 48000->16000 (L=1 M=3 taps=64 8f2c91a4d310)"
        store.levelInBand = true
        store.levelPeakDbfs = -14.0
        store.overnightStepSeen = true
        store.radioChoice = RadioChoice.TH_D75A
        store.rigId = BundledDescriptors.kenwoodThD75a().id
        store.rigTransport = null
        return LoadResult(0, 0, null)
    }

    /**
     * `setup-rig-bluetooth` — D33/D34, S10b: the same base [setupRigTransport] seeds, but
     * [SetupStore.rigTransport] is now [RigTransportKind.BLUETOOTH_SPP] and
     * [SetupStore.rigBluetoothVerified] genuinely left `false` — `stepFor`'s `needsRigBluetoothLink`
     * gate resumes at [SetupStep.RIG_BLUETOOTH] directly from a cold launch.
     *
     * **WPD's seam closes the paired-device-list half of the gap this scenario used to report.**
     * `SetupActivity.onCreate` now reads `DebugRigLinkPortOverride.activeOverride` ahead of
     * constructing the real `BridgeRigLinkPort` — a debug-build-only holder shaped exactly like
     * `org.ort.app.ui.failures.DebugFailureOverride`. [DebugRigLinkPortOverride.show] below publishes
     * a real, scripted [InMemoryRigLinkPort] naming two paired devices — the TH-D75A (SPP-capable,
     * selectable) and a `Handheld BT` (headset-class only, listed dim and unselectable) — so S10b's
     * own paired-device list renders real rows.
     *
     * **WPD's second seam (`SetupActivity.EXTRA_DEBUG_RIG_BLUETOOTH_ADDRESS`) closes the checklist
     * half too.** A tap on a paired-device row is normally what drives the connect → identify →
     * verify checklist; the tour cannot tap, but this extra reaches
     * `SetupActivity.onSelectRigBluetoothDevice` directly from a cold launch — see
     * [setupRigBluetoothConnecting]/[setupRigBluetoothIdentified]/[setupRigBluetoothDropped] for the
     * three other checklist states this same mechanism reaches, each on its own differently-scripted
     * [InMemoryRigLinkPort] (this scenario's own port stays scripted for the *verified* end state —
     * [InMemoryRigLinkPort.useDefaultBehaviour]'s default script completes in milliseconds, not real
     * Bluetooth latency, so a device scripted this way is only ever usefully captured at its own
     * terminal, [org.ort.app.ui.setup.RigLinkState.Verified] state).
     */
    private fun setupRigBluetooth(context: Context): LoadResult {
        seedRigBluetoothLinkStore(context)
        DebugRigLinkPortOverride.show(
            InMemoryRigLinkPort(
                devices = listOf(
                    PairedDevice(name = "TH-D75A", address = BLUETOOTH_RIG_ADDRESS, sppCapable = true),
                    PairedDevice(name = "Handheld BT", address = HANDHELD_BT_ADDRESS, sppCapable = false),
                ),
            ).apply { useDefaultBehaviour(BLUETOOTH_RIG_ADDRESS) },
        )
        return LoadResult(0, 0, null)
    }

    /** The `SetupStore` state every `setup-rig-bluetooth*` scenario shares — only the
     * [DebugRigLinkPortOverride] script differs between them. */
    private fun seedRigBluetoothLinkStore(context: Context) {
        val store = freshSetupStore(context)
        store.welcomeSeen = true
        store.captureMode = CaptureMode.BLUETOOTH_RADIO
        store.notificationsSkipped = true
        store.selectedInputId = "wired-1"
        store.selectedInputLabel = "Wired headset"
        store.inputVerified = true
        store.verifiedNativeRateHz = 48_000
        store.verifiedResamplerIdentity = "polyphase/v1 48000->16000 (L=1 M=3 taps=64 8f2c91a4d310)"
        store.levelInBand = true
        store.levelPeakDbfs = -14.0
        store.overnightStepSeen = true
        store.radioChoice = RadioChoice.TH_D75A
        store.rigId = BundledDescriptors.kenwoodThD75a().id
        store.rigTransport = RigTransportKind.BLUETOOTH_SPP
        store.rigBluetoothVerified = false
    }

    /** `setup-rig-bluetooth-connecting` — the checklist's own first row, held there on purpose:
     * [InMemoryRigLinkPort.hang] never emits past [org.ort.app.ui.setup.RigLinkState.Opening]. */
    private fun setupRigBluetoothConnecting(context: Context): LoadResult {
        seedRigBluetoothLinkStore(context)
        DebugRigLinkPortOverride.show(
            InMemoryRigLinkPort(
                devices = listOf(PairedDevice(name = "TH-D75A", address = BLUETOOTH_RIG_ADDRESS, sppCapable = true)),
            ).apply { hang(BLUETOOTH_RIG_ADDRESS) },
        )
        return LoadResult(0, 0, null)
    }

    /** `setup-rig-bluetooth-identified` — the checklist's own second row done, third not yet:
     * [InMemoryRigLinkPort.hangAfterIdentify] (this round's own new script — see its own kdoc for
     * why [useDefaultBehaviour] cannot be captured at this intermediate state at all). */
    private fun setupRigBluetoothIdentified(context: Context): LoadResult {
        seedRigBluetoothLinkStore(context)
        DebugRigLinkPortOverride.show(
            InMemoryRigLinkPort(
                devices = listOf(PairedDevice(name = "TH-D75A", address = BLUETOOTH_RIG_ADDRESS, sppCapable = true)),
            ).apply { hangAfterIdentify(BLUETOOTH_RIG_ADDRESS) },
        )
        return LoadResult(0, 0, null)
    }

    /** `setup-rig-bluetooth-dropped` — E2-E11/FR-RIG-15's own dropped-link banner:
     * [InMemoryRigLinkPort.dropAfterOpen] opens then reports [org.ort.app.ui.setup.RigLinkState.Lost]. */
    private fun setupRigBluetoothDropped(context: Context): LoadResult {
        seedRigBluetoothLinkStore(context)
        DebugRigLinkPortOverride.show(
            InMemoryRigLinkPort(
                devices = listOf(PairedDevice(name = "TH-D75A", address = BLUETOOTH_RIG_ADDRESS, sppCapable = true)),
            ).apply { dropAfterOpen(BLUETOOTH_RIG_ADDRESS) },
        )
        return LoadResult(0, 0, null)
    }

    /**
     * `mode-local-mic` — FR-CAP-3a/10, AC-128/129: a live session whose v7 columns genuinely read
     * `captureMode = LOCAL_MICROPHONE`/`audioRouteKind = BUILT_IN_MIC` (`SessionRouteFacts`'
     * `isLocalMicrophone`, N01b's chip and every live-bar `room` mark). Also leaves the real
     * `SetupStore` mid-way at S04 with the Local-microphone preset chip showing (independent of the
     * `:data` session row above — a `SharedPreferences` file and a Room table share nothing) for the
     * "S04 in a lane per mode" tour coverage. `frequencyHz` is honestly `null` — local-mic mode has
     * no rig at all (FR-CAP-2b).
     *
     * R-821/R-823 (halt): also seeds the real [org.ort.pipeline.rig.CaptureConfigurationStore]
     * (`mode = LOCAL_MICROPHONE`, `selectedInputId = "mic-0"`, matching the session/`InputStatus`
     * above — CF02/CF11 previously read the store's own honest `DEFAULT`, LOCAL_MICROPHONE with a
     * `null` input, which happened to *look* right for mode but wrong for "not yet selected") and a
     * real [StationEntity] for `W7NPC` (ST01 previously showed zero stations against N01b's one,
     * since a `stationId` on the transmission row is not itself a station-catalog entry — see
     * [Scenarios]'s own class kdoc on why `station` rows are not implied by a transmission's own
     * `stationId`).
     */
    private suspend fun modeLocalMic(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("mode-local-mic")
        db.sessionDao().insert(
            ScenarioFixtures.session(
                sessionId,
                startedAt = SystemClock.wallMillis() - 20 * 60_000L,
                endedAt = null,
                captureMode = CaptureMode.LOCAL_MICROPHONE.name,
                audioRouteKind = AudioRouteKind.BUILT_IN_MIC.name,
                audioRouteLabel = "Built-in microphone",
                rigDescriptorId = null,
                audioRouteVerified = true,
                audioNativeRateHz = 48_000,
            ),
        )
        realCaptureConfigurationStore(context).update(
            CaptureConfiguration(mode = CaptureMode.LOCAL_MICROPHONE, selectedInputId = "mic-0"),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        InputStatus.opened(
            descriptor = AudioDeviceDescriptor("mic-0", AudioDeviceKind.BUILT_IN_MIC, "Built-in microphone"),
            nativeRateHz = 48_000,
            resamplerId = "polyphase/v1 48000->16000 (L=1 M=3 taps=64 8f2c91a4d310)",
            routeVerified = true,
            routedDeviceMatches = true,
            openedAtMillis = SystemClock.wallMillis(),
        )
        val txId = "$sessionId-tx1"
        val startedAt = SystemClock.wallMillis() - 5 * 60_000L
        db.transmissionDao().insert(
            ScenarioFixtures.transmission(
                id = txId,
                sessionId = sessionId,
                startedAtUtc = startedAt,
                samplePosition = 1L,
                frequencyHz = null,
                attributionState = AttributionState.CONFIRMED,
                stationId = "W7NPC",
                attributionConfidence = 0.9,
            ),
        )
        db.catalogDao().insert(
            StationEntity(
                id = "W7NPC",
                callsign = "W7NPC",
                firstHeardAt = startedAt,
                lastHeardAt = startedAt,
                notes = null,
                userName = null,
                frequenciesHeard = null,
                activityByHourDow = null,
                potaRefs = null,
                spokenGrids = null,
                ituRegionFromPrefix = null,
                overCountsByAttributionState = null,
            ),
        )
        db.transcriptDao().insert(
            ScenarioFixtures.transcript(
                id = "$txId-t1",
                transmissionId = txId,
                text = "this is whiskey seven november papa charlie, from the kitchen table",
                isCurrent = true,
                createdAt = startedAt + 1_000L,
            ),
        )
        val store = freshSetupStore(context)
        store.welcomeSeen = true
        store.captureMode = CaptureMode.LOCAL_MICROPHONE
        store.notificationsSkipped = true
        return LoadResult(1, 1, sessionId)
    }

    /** `mode-usb` — FR-CAP-13, AC-129: a live session over USB, `RigStatus.Connected` naming the
     * USB-serial transport and the TH-D75A descriptor (F9/CF06). See [modeLocalMic]'s own doc
     * comment for why the S04 preset-chip seeding alongside it is not a conflict, and for why the
     * real [org.ort.pipeline.rig.CaptureConfigurationStore] write below (R-821) is not either. */
    private suspend fun modeUsb(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("mode-usb")
        db.sessionDao().insert(
            ScenarioFixtures.session(
                sessionId,
                startedAt = SystemClock.wallMillis() - 20 * 60_000L,
                endedAt = null,
                captureMode = CaptureMode.USB_RADIO.name,
                audioRouteKind = AudioRouteKind.USB.name,
                audioRouteLabel = "USB Audio Device",
                rigTransport = RigTransportKind.USB_SERIAL.name,
                rigDescriptorId = BundledDescriptors.kenwoodThD75a().id,
                audioRouteVerified = true,
                audioNativeRateHz = 48_000,
            ),
        )
        realCaptureConfigurationStore(context).update(
            CaptureConfiguration(
                mode = CaptureMode.USB_RADIO,
                selectedInputId = "usb-1",
                rigId = BundledDescriptors.kenwoodThD75a().id,
                rigTransportKind = RigModuleTransportKind.USB_SERIAL,
            ),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        InputStatus.opened(
            descriptor = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Device"),
            nativeRateHz = 48_000,
            resamplerId = "polyphase/v1 48000->16000 (L=1 M=3 taps=64 8f2c91a4d310)",
            routeVerified = true,
            routedDeviceMatches = true,
            openedAtMillis = SystemClock.wallMillis(),
        )
        RigStatus.connected(
            descriptor = "Kenwood TH-D75A",
            bands = listOf(
                RigStatus.BandState(band = "A", frequencyHz = 145_230_000L, mode = "FM", squelchOpen = true),
                RigStatus.BandState(band = "B", frequencyHz = 146_960_000L, mode = "FM", squelchOpen = false),
            ),
            transportKind = RigModuleTransportKind.USB_SERIAL,
            descriptorId = BundledDescriptors.kenwoodThD75a().id,
        )
        val txId = "$sessionId-tx1"
        val startedAt = SystemClock.wallMillis() - 5 * 60_000L
        db.transmissionDao().insert(
            ScenarioFixtures.transmission(
                id = txId,
                sessionId = sessionId,
                startedAtUtc = startedAt,
                samplePosition = 1L,
                frequencyHz = 145_230_000L,
                attributionState = AttributionState.CONFIRMED,
                stationId = "K7LWH",
                attributionConfidence = 0.92,
            ),
        )
        db.transcriptDao().insert(
            ScenarioFixtures.transcript(
                id = "$txId-t1",
                transmissionId = txId,
                text = "kilo seven lima whiskey hotel, copy on the repeater",
                isCurrent = true,
                createdAt = startedAt + 1_000L,
            ),
        )
        val store = freshSetupStore(context)
        store.welcomeSeen = true
        store.captureMode = CaptureMode.USB_RADIO
        store.notificationsSkipped = true
        return LoadResult(1, 1, sessionId)
    }

    /** `mode-bluetooth` — D34/FR-CAP-11/13: a live session over Bluetooth audio (SCO, mSBC) *and*
     * Bluetooth rig control (SPP) at once — the combination H6's own hardware row is honest about
     * stock Android never offering from a single peer, but perfectly real as two independent
     * Bluetooth links (a headset-class audio source, the TH-D75A's own SPP control link). See
     * [modeLocalMic]'s own doc comment for why the S04 preset-chip seeding alongside it is not a
     * conflict, and for why the real [org.ort.pipeline.rig.CaptureConfigurationStore] write below
     * (R-821) — with the Bluetooth address in `rigParams`, R-822's own explicit ask — is not either. */
    private suspend fun modeBluetooth(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("mode-bluetooth")
        db.sessionDao().insert(
            ScenarioFixtures.session(
                sessionId,
                startedAt = SystemClock.wallMillis() - 20 * 60_000L,
                endedAt = null,
                captureMode = CaptureMode.BLUETOOTH_RADIO.name,
                audioRouteKind = AudioRouteKind.BLUETOOTH_SCO.name,
                audioRouteLabel = "Bluetooth headset",
                bluetoothProfile = BluetoothAudioProfile.HFP_MSBC.name,
                rigTransport = RigTransportKind.BLUETOOTH_SPP.name,
                rigDescriptorId = BundledDescriptors.kenwoodThD75a().id,
                audioRouteVerified = true,
                audioNativeRateHz = 48_000,
            ),
        )
        realCaptureConfigurationStore(context).update(
            CaptureConfiguration(
                mode = CaptureMode.BLUETOOTH_RADIO,
                selectedInputId = "bt-1",
                rigId = BundledDescriptors.kenwoodThD75a().id,
                rigTransportKind = RigModuleTransportKind.BLUETOOTH_SPP,
                rigParams = mapOf(
                    DefaultRigTransportFactory.ParamKeys.BLUETOOTH_ADDRESS to BLUETOOTH_RIG_ADDRESS,
                ),
            ),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        InputStatus.opened(
            descriptor = AudioDeviceDescriptor(
                "bt-1",
                AudioDeviceKind.BLUETOOTH,
                "Bluetooth headset",
                bluetoothProfile = BluetoothAudioProfile.HFP_MSBC,
            ),
            nativeRateHz = 16_000,
            resamplerId = "identity/16000",
            routeVerified = true,
            routedDeviceMatches = true,
            openedAtMillis = SystemClock.wallMillis(),
        )
        RigStatus.connected(
            descriptor = "Kenwood TH-D75A",
            bands = listOf(
                RigStatus.BandState(band = "A", frequencyHz = 145_230_000L, mode = "FM", squelchOpen = false),
                RigStatus.BandState(band = "B", frequencyHz = 146_960_000L, mode = "FM", squelchOpen = true),
            ),
            transportKind = RigModuleTransportKind.BLUETOOTH_SPP,
            descriptorId = BundledDescriptors.kenwoodThD75a().id,
        )
        val txId = "$sessionId-tx1"
        val startedAt = SystemClock.wallMillis() - 5 * 60_000L
        db.transmissionDao().insert(
            ScenarioFixtures.transmission(
                id = txId,
                sessionId = sessionId,
                startedAtUtc = startedAt,
                samplePosition = 1L,
                frequencyHz = 146_960_000L,
                attributionState = AttributionState.CONFIRMED,
                stationId = "WA7HJR",
                attributionConfidence = 0.88,
            ),
        )
        db.transcriptDao().insert(
            ScenarioFixtures.transcript(
                id = "$txId-t1",
                transmissionId = txId,
                text = "whiskey alpha seven hotel juliet romeo, over the bluetooth link",
                isCurrent = true,
                createdAt = startedAt + 1_000L,
            ),
        )
        val store = freshSetupStore(context)
        store.welcomeSeen = true
        store.captureMode = CaptureMode.BLUETOOTH_RADIO
        store.notificationsSkipped = true
        return LoadResult(1, 1, sessionId)
    }

    /** `bt-audio-session` — FR-CAP-13: an *ended* session captured over Bluetooth audio, two overs,
     * for the Log's `bt audio` row mark (E2-G04) and DG04's session-review facts. */
    private suspend fun btAudioSession(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("bt-audio-session")
        val start = SystemClock.wallMillis() - 3 * 3_600_000L
        val end = SystemClock.wallMillis() - 2 * 3_600_000L
        db.sessionDao().insert(
            ScenarioFixtures.session(
                sessionId,
                startedAt = start,
                endedAt = end,
                captureMode = CaptureMode.BLUETOOTH_RADIO.name,
                audioRouteKind = AudioRouteKind.BLUETOOTH_SCO.name,
                audioRouteLabel = "Bluetooth headset",
                bluetoothProfile = BluetoothAudioProfile.HFP_MSBC.name,
                rigTransport = RigTransportKind.BLUETOOTH_SPP.name,
                rigDescriptorId = BundledDescriptors.kenwoodThD75a().id,
                audioRouteVerified = true,
                audioNativeRateHz = 48_000,
            ),
        )
        val tx1 = "$sessionId-tx1"
        val tx2 = "$sessionId-tx2"
        db.transmissionDao().insert(
            ScenarioFixtures.transmission(
                id = tx1,
                sessionId = sessionId,
                startedAtUtc = start + 5 * 60_000L,
                samplePosition = 1L,
                frequencyHz = 146_960_000L,
                attributionState = AttributionState.CONFIRMED,
                stationId = "WA7HJR",
                attributionConfidence = 0.9,
            ),
        )
        db.transcriptDao().insert(
            ScenarioFixtures.transcript(
                id = "$tx1-t1",
                transmissionId = tx1,
                text = "whiskey alpha seven hotel juliet romeo, monitoring",
                isCurrent = true,
                createdAt = start + 5 * 60_000L + 1_000L,
            ),
        )
        db.transmissionDao().insert(
            ScenarioFixtures.transmission(
                id = tx2,
                sessionId = sessionId,
                startedAtUtc = start + 8 * 60_000L,
                samplePosition = 2L,
                frequencyHz = 146_960_000L,
                attributionState = AttributionState.CONFIRMED,
                stationId = "KJ7ABC",
                attributionConfidence = 0.87,
            ),
        )
        db.transcriptDao().insert(
            ScenarioFixtures.transcript(
                id = "$tx2-t1",
                transmissionId = tx2,
                text = "kilo juliet seven alpha bravo charlie, copy",
                isCurrent = true,
                createdAt = start + 8 * 60_000L + 1_000L,
            ),
        )
        return LoadResult(2, 1, sessionId)
    }

    /**
     * `bt-audio-dropped` — F23, FR-CAP-5: a *live* Bluetooth session whose audio just stopped —
     * [InputStatus.State.Lost] with a genuine Bluetooth `lastKnown` (via [InputStatus.opened] then
     * [InputStatus.lost], the only way [InputStatus.lost] ever transitions — see that function's own
     * doc comment), plus a real, open [CaptureGapEntity]. The rig's own *control* link is left
     * `Connected` (still Bluetooth SPP) — this is an audio-only drop, distinct from [rigBtLost]'s
     * control-only one (FR-RIG-15's own distinction).
     *
     * **R-838 (reopened, reviewer C2 on run 3)**: cause is [CaptureGapCause.BLUETOOTH_AUDIO_LOST] —
     * this schema has carried that value since v9 (E2-A06); the doc comment here previously claimed
     * otherwise (an honest stand-in that outlived the schema change it was written against) and
     * `results/ui-audit/README.md` repeated the same now-stale claim, fixed alongside this. `startedAt`
     * stays [lostSinceMillis] and `endedAt` stays `null` (still genuinely open — a live drop, not one
     * already recovered), so the Log row's own "ongoing" duration is computed from that real elapsed
     * time exactly as before; only the cause itself, and therefore the row's own icon/copy
     * (`LogListItem.Gap`, WPF's own half of this fix), changes.
     */
    private suspend fun btAudioDropped(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("bt-audio-dropped")
        db.sessionDao().insert(
            ScenarioFixtures.session(
                sessionId,
                startedAt = SystemClock.wallMillis() - 40 * 60_000L,
                endedAt = null,
                captureMode = CaptureMode.BLUETOOTH_RADIO.name,
                audioRouteKind = AudioRouteKind.BLUETOOTH_SCO.name,
                audioRouteLabel = "Bluetooth headset",
                bluetoothProfile = BluetoothAudioProfile.HFP_MSBC.name,
                rigTransport = RigTransportKind.BLUETOOTH_SPP.name,
                rigDescriptorId = BundledDescriptors.kenwoodThD75a().id,
                audioRouteVerified = true,
                audioNativeRateHz = 48_000,
            ),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        val descriptor = AudioDeviceDescriptor(
            "bt-1",
            AudioDeviceKind.BLUETOOTH,
            "Bluetooth headset",
            bluetoothProfile = BluetoothAudioProfile.HFP_MSBC,
        )
        InputStatus.opened(
            descriptor = descriptor,
            nativeRateHz = 16_000,
            resamplerId = "identity/16000",
            routeVerified = true,
            routedDeviceMatches = true,
            openedAtMillis = SystemClock.wallMillis() - 40 * 60_000L,
        )
        val lostSinceMillis = SystemClock.wallMillis() - 45_000L
        // R-832 (halt): the reconnect-ladder position, so F23's own ladder sentence ("retry 3 of 8,
        // next attempt in 20s") has real numbers to render, not the honest-but-blank "not computed"
        // state InputStatus.Lost's own defaulted-null fields would otherwise leave it in.
        InputStatus.lost(
            lostSinceMillis,
            attempt = RECONNECT_LADDER_ATTEMPT,
            ofTotal = RECONNECT_LADDER_OF_TOTAL,
            nextRetryInMillis = RECONNECT_LADDER_NEXT_RETRY_MILLIS,
        )
        RigStatus.connected(
            descriptor = "Kenwood TH-D75A",
            bands = listOf(
                RigStatus.BandState(band = "A", frequencyHz = 146_960_000L, mode = "FM", squelchOpen = false),
            ),
            transportKind = RigModuleTransportKind.BLUETOOTH_SPP,
            descriptorId = BundledDescriptors.kenwoodThD75a().id,
        )
        db.captureGapDao().insert(
            CaptureGapEntity(
                id = "$sessionId-gap-bt-audio",
                sessionId = sessionId,
                startedAt = lostSinceMillis,
                endedAt = null,
                cause = CaptureGapCause.BLUETOOTH_AUDIO_LOST,
                recoveredAutomatically = false,
            ),
        )
        return LoadResult(0, 1, sessionId)
    }

    /**
     * `rig-bt-connected` — CF06: a live session whose rig link is `RigStatus.Connected` over
     * Bluetooth SPP, transport and descriptor both named. Also leaves the real `SetupStore` at
     * S11/`SetupStep.RADIO_VERIFIED` — a genuinely resumable state to land on directly (`stepFor`'s
     * own natural resume point is [SetupStep.READY], strictly after [SetupStep.RADIO_VERIFIED] in
     * its declared order, so [org.ort.app.ui.setup.SetupActivity.EXTRA_STEP]'s own "at or before the
     * natural resume point" rule honors the request — `SetupActivity.kt`'s own doc comment) — with
     * the global [RigStatus] this function already publishes above read once, honestly, at
     * `SetupActivity` construction (E2-E12: S11's own transport subtitle, "Bluetooth SPP").
     */
    private suspend fun rigBtConnected(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("rig-bt-connected")
        db.sessionDao().insert(
            ScenarioFixtures.session(
                sessionId,
                startedAt = SystemClock.wallMillis() - 5 * 3_600_000L,
                endedAt = null,
                captureMode = CaptureMode.BLUETOOTH_RADIO.name,
                audioRouteKind = AudioRouteKind.WIRED_HEADSET.name,
                audioRouteLabel = "Wired headset",
                rigTransport = RigTransportKind.BLUETOOTH_SPP.name,
                rigDescriptorId = BundledDescriptors.kenwoodThD75a().id,
                audioRouteVerified = true,
                audioNativeRateHz = 48_000,
            ),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        RigStatus.connected(
            descriptor = "Kenwood TH-D75A",
            bands = listOf(
                RigStatus.BandState(band = "A", frequencyHz = 145_230_000L, mode = "FM", squelchOpen = true),
                RigStatus.BandState(band = "B", frequencyHz = 146_960_000L, mode = "FM", squelchOpen = false),
            ),
            transportKind = RigModuleTransportKind.BLUETOOTH_SPP,
            descriptorId = BundledDescriptors.kenwoodThD75a().id,
        )
        val store = freshSetupStore(context)
        store.welcomeSeen = true
        store.captureMode = CaptureMode.BLUETOOTH_RADIO
        store.notificationsSkipped = true
        store.selectedInputId = "wired-1"
        store.selectedInputLabel = "Wired headset"
        store.inputVerified = true
        store.verifiedNativeRateHz = 48_000
        store.verifiedResamplerIdentity = "polyphase/v1 48000->16000 (L=1 M=3 taps=64 8f2c91a4d310)"
        store.levelInBand = true
        store.levelPeakDbfs = -14.0
        store.overnightStepSeen = true
        store.radioChoice = RadioChoice.TH_D75A
        store.rigId = BundledDescriptors.kenwoodThD75a().id
        store.rigTransport = RigTransportKind.BLUETOOTH_SPP
        store.rigBluetoothVerified = true
        store.setupComplete = false
        return LoadResult(0, 1, sessionId)
    }

    /** `rig-bt-lost` — F9, FR-RIG-15: [RigStatus.State.Stale] whose own `lastKnown` names the
     * Bluetooth SPP transport and the TH-D75A descriptor — unlike [rigLost] (the pre-P19 scenario,
     * transport/descriptor both `null`), F9's board can now name what dropped. */
    private suspend fun rigBtLost(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("rig-bt-lost")
        db.sessionDao().insert(
            ScenarioFixtures.session(
                sessionId,
                startedAt = SystemClock.wallMillis() - 5 * 3_600_000L,
                endedAt = null,
                captureMode = CaptureMode.BLUETOOTH_RADIO.name,
                audioRouteKind = AudioRouteKind.WIRED_HEADSET.name,
                audioRouteLabel = "Wired headset",
                rigTransport = RigTransportKind.BLUETOOTH_SPP.name,
                rigDescriptorId = BundledDescriptors.kenwoodThD75a().id,
                audioRouteVerified = true,
                audioNativeRateHz = 48_000,
            ),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        // R-872 (register, halt): FR-RIG-15's own distinction is audio-only-fine, rig-control-only
        // dropped — this session's own real `WIRED_HEADSET` v7 columns already say the audio route
        // is verified, but nothing here ever opened `InputStatus` or published a `LevelStatus`
        // reading, so the live bar's own honest "nothing measured yet" floor read identically to a
        // genuine input-lost drop on a real device (validator V10's own capture). `InputStatus`
        // stays open and `LevelStatus` stays real throughout this drop — only `RigStatus` goes
        // stale — the same "audio is fine" shape `LiveBarPollingTest.R_836`'s own rig-only-drop
        // test already proves the live bar renders correctly, given a real reading to show.
        InputStatus.opened(
            descriptor = AudioDeviceDescriptor("wired-1", AudioDeviceKind.WIRED_HEADSET, "Wired headset"),
            nativeRateHz = 48_000,
            resamplerId = "polyphase/v1 48000->16000 (L=1 M=3 taps=64 8f2c91a4d310)",
            routeVerified = true,
            routedDeviceMatches = true,
            openedAtMillis = SystemClock.wallMillis() - 5 * 3_600_000L,
        )
        LevelStatus.update(
            LevelStatus.State.Measured(
                peakDbfs = -16f,
                rmsDbfs = -22f,
                noiseFloorDbfs = -52f,
                clipped = false,
                clipCountLastSecond = 0,
                sampleRateHz = 48_000,
                updatedAtMillis = SystemClock.wallMillis(),
            ),
            peakHistoryDbfs = List(60) { -20f + (it % 5) },
        )
        val lastKnown = RigStatus.State.Connected(
            descriptor = "Kenwood TH-D75A",
            bands = listOf(
                RigStatus.BandState(band = "A", frequencyHz = 145_230_000L, mode = "FM", squelchOpen = true),
                RigStatus.BandState(band = "B", frequencyHz = 146_960_000L, mode = "FM", squelchOpen = false),
            ),
            transportKind = RigModuleTransportKind.BLUETOOTH_SPP,
            descriptorId = BundledDescriptors.kenwoodThD75a().id,
        )
        // R-832 (halt): the same reconnect-ladder position bt-audio-dropped seeds for F23, here for
        // F9's own ladder sentence.
        RigStatus.stale(
            lastKnown,
            sinceMillis = SystemClock.wallMillis() - 12 * 60_000L,
            attempt = RECONNECT_LADDER_ATTEMPT,
            ofTotal = RECONNECT_LADDER_OF_TOTAL,
            nextRetryInMillis = RECONNECT_LADDER_NEXT_RETRY_MILLIS,
        )
        return LoadResult(0, 1, sessionId)
    }

    /**
     * `mode-change-pending` — FR-CAP-12, AC-131: a live USB-radio session, plus a real
     * [org.ort.pipeline.rig.CaptureConfigurationStore] write requesting Bluetooth for the *next*
     * session — written after [ScenarioFixtures.markCapturing] so [CaptureState.isCapturing] is
     * already true and the store's own freeze rule genuinely lands it as
     * [org.ort.pipeline.rig.CaptureConfigurationStore.pendingConfiguration], never touching
     * [org.ort.pipeline.rig.CaptureConfigurationStore.current] (CF11's amber banner).
     */
    private suspend fun modeChangePending(context: Context, db: OrtDatabase): LoadResult {
        val sessionId = ScenarioFixtures.sessionId("mode-change-pending")
        db.sessionDao().insert(
            ScenarioFixtures.session(
                sessionId,
                startedAt = SystemClock.wallMillis() - 15 * 60_000L,
                endedAt = null,
                captureMode = CaptureMode.USB_RADIO.name,
                audioRouteKind = AudioRouteKind.USB.name,
                audioRouteLabel = "USB Audio Device",
                rigTransport = RigTransportKind.USB_SERIAL.name,
                rigDescriptorId = BundledDescriptors.kenwoodThD75a().id,
                audioRouteVerified = true,
                audioNativeRateHz = 48_000,
            ),
        )
        val configStore = realCaptureConfigurationStore(context)
        configStore.update(
            CaptureConfiguration(
                mode = CaptureMode.USB_RADIO,
                selectedInputId = "usb-1",
                rigId = BundledDescriptors.kenwoodThD75a().id,
                rigTransportKind = RigModuleTransportKind.USB_SERIAL,
            ),
        )
        ScenarioFixtures.markCapturing(context, sessionId)
        // R-860/R-861 (halt): this is a live USB session, exactly like `mode-usb`'s own — CF02/CF11
        // read the process-wide `InputStatus`/`RigStatus` holders, never the session row directly, so
        // leaving them at their default idle state (as this scenario did before this fix) rendered the
        // *current* USB session as if nothing were open at all, only the pending Bluetooth change
        // showing anything real.
        InputStatus.opened(
            descriptor = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Device"),
            nativeRateHz = 48_000,
            resamplerId = "polyphase/v1 48000->16000 (L=1 M=3 taps=64 8f2c91a4d310)",
            routeVerified = true,
            routedDeviceMatches = true,
            openedAtMillis = SystemClock.wallMillis(),
        )
        RigStatus.connected(
            descriptor = "Kenwood TH-D75A",
            bands = listOf(
                RigStatus.BandState(band = "A", frequencyHz = 145_230_000L, mode = "FM", squelchOpen = true),
                RigStatus.BandState(band = "B", frequencyHz = 146_960_000L, mode = "FM", squelchOpen = false),
            ),
            transportKind = RigModuleTransportKind.USB_SERIAL,
            descriptorId = BundledDescriptors.kenwoodThD75a().id,
        )
        configStore.update(
            CaptureConfiguration(
                mode = CaptureMode.BLUETOOTH_RADIO,
                selectedInputId = "wired-1",
                rigId = BundledDescriptors.kenwoodThD75a().id,
                rigTransportKind = RigModuleTransportKind.BLUETOOTH_SPP,
                rigParams = mapOf(
                    DefaultRigTransportFactory.ParamKeys.BLUETOOTH_ADDRESS to BLUETOOTH_RIG_ADDRESS,
                ),
            ),
        )
        return LoadResult(0, 1, sessionId)
    }

    /**
     * R-841/R-843 (halt, reviewer diagnosis): the previous implementation drove
     * [BundledAssetInstaller] through a fake, hand-computed manifest source —
     * genuinely exercising the installer, but against sha256 values this fixture invented from
     * placeholder bytes, never the ones [ModelCatalog] (generated at build time from the *same* root
     * `bundled-assets.json` [org.ort.app.assets.AndroidBundledAssetSource]'s own real
     * `bundled/manifest.json` was resolved from — confirmed by reading `ort.android-app.gradle.kts`'s
     * `generateBundledAssetCatalog`/`fetchBundledAssets` tasks before writing this fix, not assumed)
     * pins as each entry's own production checksum. [org.ort.app.ui.data.ModelsController.currentState]
     * verifies a marker against exactly that pinned value (`ModelCatalog.specFor`), so a fixture-installed
     * marker computed from different bytes could never read back as `INSTALLED` there, no matter how
     * real [BundledAssetInstaller] itself was underneath — the bug reviewers actually saw on CF04/S12.
     *
     * **The fix: install through the REAL [org.ort.app.assets.AndroidBundledAssetSource]**, the exact
     * source `OrtApplication.onCreate()` itself installs from on every launch — so a genuinely
     * installed marker is, by construction, the same real checksum `ModelsController.currentState`
     * checks against. [ScenarioFixtures.uninstallEveryModelFixture] runs first, both to clear a prior
     * scenario's own installed-fixture markers and to force a fresh copy regardless of whether
     * [org.ort.app.OrtApplication]'s own one-shot background install (fired once, at process start,
     * racing this scenario the same way — see this function's own report) already got there first;
     * `BundledAssetInstaller.installOne`'s own idempotency check (`marker already matches → skip`)
     * would otherwise silently defeat [corruptId] by never even reading from [source].
     *
     * [corruptId], when given, is served genuinely corrupted bytes for exactly that one entry (one
     * byte flipped, via [CorruptingBundledAssetSource]) while every other entry — manifest.json
     * included — reads through untouched, so [BundledAssetInstaller.installOne]'s own real,
     * unmodified digest comparison genuinely fails for that one entry (AC-137), never a fabricated
     * mismatch. In this build (the local `ORT_ALLOW_MISSING_BUNDLED_ASSETS`/`-PortAllowMissingBundledAssets`
     * escape hatch, no `HF_TOKEN`), [ModelId.LLM_GEMMA3_1B] is genuinely absent from
     * `bundled/manifest.json` (`missing: true`) and reports [BundledAssetState.NotBundledInThisBuild]
     * — real and honest for this build, not a defect in this fixture.
     */
    private fun installRealBundledAssets(context: Context, corruptId: ModelId? = null): List<BundledAssetState> {
        ScenarioFixtures.uninstallEveryModelFixture(context)
        val filesDir = context.filesDir
        File(filesDir, "bundled_assets.manifest").delete()
        val realSource = DebugBundledAssetSourceOverride.override ?: AndroidBundledAssetSource(context)
        val source: BundledAssetSource = if (corruptId == null) {
            realSource
        } else {
            val relativeDestination =
                ModelCatalog.entry(corruptId).destination(filesDir).relativeTo(filesDir).invariantSeparatorsPath
            CorruptingBundledAssetSource(realSource, corruptAssetPath = "bundled/$relativeDestination")
        }
        return BundledAssetInstaller.installAll(filesDir, source)
    }

    /**
     * Delegates every read to [delegate] unchanged except [corruptAssetPath], which it corrupts by
     * flipping one byte — genuine corruption, not a fabricated digest, so [BundledAssetInstaller]'s
     * own real post-copy sha256 comparison is what actually fails (AC-137). Reads the full byte array
     * only for the one path being corrupted; every other asset (including the large ASR decoder and
     * the manifest itself) streams straight through [delegate], never buffered here.
     */
    private class CorruptingBundledAssetSource(
        private val delegate: BundledAssetSource,
        private val corruptAssetPath: String,
    ) : BundledAssetSource {
        override fun open(assetPath: String): InputStream {
            val stream = delegate.open(assetPath)
            if (assetPath != corruptAssetPath) return stream
            val bytes = stream.use { it.readBytes() }
            val corrupted = bytes.copyOf()
            if (corrupted.isNotEmpty()) corrupted[corrupted.size - 1] = corrupted[corrupted.size - 1].inc()
            return corrupted.inputStream()
        }
    }

    /**
     * `assets-bundled` — FR-AST-3/3b, AC-137: every non-gated real [BundledAssetInstaller] result is
     * [BundledAssetState.Installed] — the four ASR/VAD entries this build's own `fetchBundledAssets`
     * task genuinely fetched and packaged. [ModelId.LLM_GEMMA3_1B] reports
     * [BundledAssetState.NotBundledInThisBuild] in this dev/escape-hatch build (no `HF_TOKEN`) —
     * real and honest, not "every entry Installed" (R-841's fix corrected this doc comment's own
     * earlier, pre-fix claim to match). Also leaves the real `SetupStore` at S12/[SetupStep.READY]
     * (a verified USB input, no rig — the same honest "no rig module built yet" pairing
     * [seedConfiguredDeviceState] already establishes) so S12's own Mode row and Models row render
     * against this scenario's real, just-installed bundled assets (E2-E13's tour coverage) — never
     * `setup-verified`'s own unrelated, unbundled state.
     */
    private fun assetsBundled(context: Context): LoadResult {
        installRealBundledAssets(context)
        val store = freshSetupStore(context)
        store.welcomeSeen = true
        store.captureMode = CaptureMode.USB_RADIO
        store.notificationsSkipped = true
        store.selectedInputId = "usb-1"
        store.selectedInputLabel = "USB Audio Device"
        store.inputVerified = true
        store.verifiedNativeRateHz = 48_000
        store.verifiedResamplerIdentity = "polyphase/v1 48000->16000 (L=1 M=3 taps=64 8f2c91a4d310)"
        store.levelInBand = true
        store.levelPeakDbfs = -14.0
        store.overnightStepSeen = true
        store.radioChoice = RadioChoice.NONE
        store.rigTransport = null
        store.manualFrequencyHz = 145_230_000L
        store.setupComplete = false
        return LoadResult(0, 0, null)
    }

    /** `asset-corrupt` — AC-137: one entry ([ModelId.ASR_ENCODER]) genuinely fails its post-copy
     * digest check ([BundledAssetState.Failed]); every other real, non-gated entry still installs for
     * real (R-841's fix — see [installRealBundledAssets]'s own kdoc for why the real source, not a
     * fabricated manifest, is what makes [org.ort.app.ui.data.ModelsController.currentState] agree).
     *
     * R-866 (register, reviewer D3): a real, resumable `SetupStore` — the same verified-input/level/
     * overnight base [assetsBundled] seeds, USB radio never a genuine conflict with the asset-install
     * facts above — so `SetupStateMachine.stepFor` resumes at [SetupStep.READY] (S12) directly, the
     * one place this scenario's own amber `Install` action (the failed [ModelId.ASR_ENCODER] entry)
     * can actually be captured: `assets-bundled/S12` went green once R-862 landed, so it can no longer
     * measure the amber form at all. Left unseeded before this round because no tour step reached S12
     * under this scenario yet, not because seeding it would have been wrong.
     */
    private fun assetCorrupt(context: Context): LoadResult {
        installRealBundledAssets(context, corruptId = ModelId.ASR_ENCODER)
        val store = freshSetupStore(context)
        store.welcomeSeen = true
        store.captureMode = CaptureMode.USB_RADIO
        store.notificationsSkipped = true
        store.selectedInputId = "usb-1"
        store.selectedInputLabel = "USB Audio Device"
        store.inputVerified = true
        store.verifiedNativeRateHz = 48_000
        store.verifiedResamplerIdentity = "polyphase/v1 48000->16000 (L=1 M=3 taps=64 8f2c91a4d310)"
        store.levelInBand = true
        store.levelPeakDbfs = -14.0
        store.overnightStepSeen = true
        store.radioChoice = RadioChoice.NONE
        store.rigTransport = null
        store.manualFrequencyHz = 145_230_000L
        store.setupComplete = false
        return LoadResult(0, 0, null)
    }

    /**
     * `tier0-llm-stored` — FR-AST-3a, AC-138: [ModelId.LLM_GEMMA3_1B] reads `INSTALLED`, but this
     * device's own tier is forced below T3 ([ShedStatus] is `ModelsController.realCurrentTierLabel`'s
     * own real input — that function's own kdoc), so [org.ort.app.ui.data.ModelRowViewState.tierEligible]
     * genuinely reads `false` for it — stored, never loaded (AC-138's own distinction). `backlog`
     * stays `0`: this is a tier fact, not F8's backlog failure, which gates on the queue depth alone.
     *
     * **R-865 (halt, coordinator spot-check): the four non-gated entries now install through the
     * REAL [installRealBundledAssets]**, exactly like `assets-bundled` — this scenario's own earlier
     * shape (every entry, Gemma included, via [ScenarioFixtures.installEveryModelFixture]'s fabricated
     * placeholder-plus-real-checksum marker) made S12's Models row read every one of the four
     * non-gated entries as "verified" without the real installer ever having run, an avoidable
     * dishonesty this round closes. **Only [ModelId.LLM_GEMMA3_1B] still gets the placeholder**
     * ([ScenarioFixtures.installModelFixture] for that one id alone): this build's own escape hatch
     * (no `HF_TOKEN`) leaves it genuinely absent from `bundled/manifest.json`, so the real installer
     * can never report it `Installed` here regardless of any fix — there is no real, ~550 MB gated
     * file for this fixture to install, and a placeholder plus the *real*, pinned [ModelCatalog]
     * checksum as the marker is real enough for that one entry's own purpose here (the tier
     * distinction), unlike `assets-bundled`/`asset-corrupt`, whose whole point is exercising the
     * installer's real digest comparison on entries a real build genuinely can fetch.
     */
    private fun tier0LlmStored(context: Context): LoadResult {
        installRealBundledAssets(context)
        ScenarioFixtures.installModelFixture(context, ModelId.LLM_GEMMA3_1B)
        ShedStatus.update(level = 1, backlog = 0)
        return LoadResult(0, 0, null)
    }

    /**
     * The shared body of [llmEnabledProse] and [llmDisabled] (FR-DIG-3/6/11, D36): the real
     * `overnight` fixture (so the QSO thread's own four real over ids exist to attribute a summary
     * to — FR-DIG-11), one more small thread of two overs on the same session (the "one other"
     * thread DG05's own board draws a second card for), a real prose-settings toggle
     * ([SharedPreferencesProseDigestSettingsStore]), and two real, stored [ProseSummary] rows via
     * the real [RoomProseSummaryStore] — never a UI stand-in for any of the three.
     */
    private suspend fun proseDigestScenario(context: Context, db: OrtDatabase, enabled: Boolean): LoadResult {
        val base = OvernightScenario.overnight(context, db)
        val sessionId = ScenarioFixtures.sessionId("overnight")
        SharedPreferencesProseDigestSettingsStore(context).setEnabled(enabled)

        val otherThreadId = "$sessionId-thread-other"
        val other1 = "$sessionId-llm-tx1"
        val other2 = "$sessionId-llm-tx2"
        val otherStart = SystemClock.wallMillis() - 5 * 3_600_000L
        db.transmissionDao().insert(
            ScenarioFixtures.transmission(
                id = other1,
                sessionId = sessionId,
                threadId = otherThreadId,
                startedAtUtc = otherStart,
                samplePosition = 9_001L,
                frequencyHz = 146_960_000L,
                attributionState = AttributionState.CONFIRMED,
                stationId = "KJ7ABC",
                attributionConfidence = 0.9,
            ),
        )
        db.transcriptDao().insert(
            ScenarioFixtures.transcript(
                id = "$other1-t1",
                transmissionId = other1,
                text = "kilo juliet seven alpha bravo charlie, activating the summit for an hour",
                isCurrent = true,
                createdAt = otherStart + 1_000L,
            ),
        )
        db.transmissionDao().insert(
            ScenarioFixtures.transmission(
                id = other2,
                sessionId = sessionId,
                threadId = otherThreadId,
                startedAtUtc = otherStart + 60_000L,
                samplePosition = 9_002L,
                frequencyHz = 146_960_000L,
                attributionState = AttributionState.CONFIRMED,
                stationId = "N7XYZ",
                attributionConfidence = 0.86,
            ),
        )
        db.transcriptDao().insert(
            ScenarioFixtures.transcript(
                id = "$other2-t1",
                transmissionId = other2,
                text = "november seven x-ray yankee zulu, copy, logging you for the activation",
                isCurrent = true,
                createdAt = otherStart + 60_000L + 1_000L,
            ),
        )
        db.catalogDao().insert(
            ThreadEntity(
                id = otherThreadId,
                sessionId = sessionId,
                startedAt = otherStart,
                endedAt = otherStart + 65_000L,
                frequencyHz = 146_960_000L,
                transmissionCount = 2,
                participantStationIds = listOf("KJ7ABC", "N7XYZ"),
                digestText = null,
                kind = ThreadKind.QSO,
                kindSource = ThreadKindSource.DETECTED,
                participantOrder = listOf("KJ7ABC", "N7XYZ"),
            ),
        )

        val summaryStore = RoomProseSummaryStore(db)
        summaryStore.store(
            ProseSummary(
                threadId = "$sessionId-thread1",
                text = "Whiskey Seven November Papa Charlie and Kilo Seven Lima Whiskey Hotel traded " +
                    "signal reports and closed out the repeater for the night.",
                sourceTransmissionIds = listOf(
                    "$sessionId-qso1",
                    "$sessionId-qso2",
                    "$sessionId-qso3",
                    "$sessionId-qso4",
                ),
                generatedAtMillis = SystemClock.wallMillis(),
                modelId = "gemma3-1b-it-int4",
            ),
        )
        summaryStore.store(
            ProseSummary(
                threadId = otherThreadId,
                text = "Kilo Juliet Seven Alpha Bravo Charlie activated a summit for an hour; November " +
                    "Seven X-ray Yankee Zulu logged the contact.",
                sourceTransmissionIds = listOf(other1, other2),
                generatedAtMillis = SystemClock.wallMillis(),
                modelId = "gemma3-1b-it-int4",
            ),
        )
        return LoadResult(base.transmissionCount + 2, base.sessionCount, base.primarySessionId)
    }

    /** `llm-enabled-prose` — DG05, FR-DIG-3/6/11: prose enabled, two real stored summaries. */
    private suspend fun llmEnabledProse(context: Context, db: OrtDatabase): LoadResult =
        proseDigestScenario(context, db, enabled = true)

    /** `llm-disabled` — DG01, FR-DIG-3b, AC-140: the same two summaries are genuinely stored, but
     * prose is disabled — DG05's "In their words" section must render absent entirely (E2-G07's own
     * discriminating test), not merely empty. */
    private suspend fun llmDisabled(context: Context, db: OrtDatabase): LoadResult =
        proseDigestScenario(context, db, enabled = false)
}
