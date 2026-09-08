package org.ort.app.debug

import android.content.Context
import org.ort.app.ui.data.LexiconImportViewState
import org.ort.app.ui.data.ModelsController
import org.ort.app.ui.data.RoomActiveLexiconStore
import org.ort.core.SystemClock
import org.ort.data.OrtDatabase
import org.ort.data.entity.LexiconVersionEntity
import java.io.File

/**
 * `lexicon-corrupt` — register R-154, `Fail-Lexicon.dc.html`, FR-LEX-12/FR-LEX-30/FR-AST-2.
 *
 * Makes F12's refusal reachable from the debug scenario simulator by running the **real**
 * production call path ([ModelsController.installLexicon], and through it
 * [org.ort.lexicon.import.LexiconImportInstaller]/[org.ort.lexicon.import.LexiconImportValidator])
 * against a deliberately corrupt, bundled lexicon file ([ASSET_PATH]) — not a hand-built
 * [org.ort.app.ui.failures.FailurePresentation] override the way WP11b's seven no-runtime-signal
 * scenarios work (`org.ort.app.ui.failures.DebugFailureOverride`, the `ui/failures` package, outside
 * this package's file ownership). The board's own register row says exactly this: "runs the same import
 * validation on a bundled bad file" only means something if it is the *actual* validator, called
 * the *actual* way a real "Install from a file" gesture would call it — not a second, hand-authored
 * copy of what the board says the outcome should be.
 *
 * [ASSET_PATH]'s manifest declares 1,122,410 records with a checksum that matches neither the two
 * data rows actually present nor their count — the same "partial download" shape
 * `Fail-Lexicon.dc.html` itself depicts, produced by [org.ort.lexicon.import.LexiconImportValidator]
 * genuinely computing a mismatch, not by this scenario asserting one.
 *
 * **Known gap** (`results/ui-audit/README.md`): no screen renders a [LexiconImportViewState] yet —
 * `Fail-Lexicon.dc.html` has no WP10 build (register R-154 was still open when this scenario was
 * written). [lastResult] is where a future screen, or a test, reads what this scenario produced;
 * until a screen exists this scenario proves the *data* path end to end, not the render.
 */
internal object LexiconCorruptScenario {

    /** The debug-only bundled asset staged as the corrupt import file ([app/src/debug/assets]). */
    private const val ASSET_PATH = "lexicon-corrupt/lexicon-2026.09.tsv"

    /** The lexicon this scenario seeds as already active before the corrupt import — the board's "Still active". */
    private const val PREVIOUS_VERSION = "2026.08"
    private const val PREVIOUS_RECORD_COUNT = 1_104_208
    private const val PREVIOUS_CHECKSUM = "4b81000000000000000000000000000000000000000000000000000000000000"

    /** The most recent [LexiconImportViewState] this scenario produced. `null` until [run] has executed once. */
    @Volatile
    var lastResult: LexiconImportViewState? = null
        private set

    suspend fun run(context: Context, db: OrtDatabase): Scenarios.LoadResult {
        val sessionId = ScenarioFixtures.sessionId("lexicon-corrupt")
        db.sessionDao().insert(
            ScenarioFixtures.session(sessionId, startedAt = SystemClock.wallMillis() - 3_600_000L, endedAt = null),
        )

        // The board's "Still active" row: seeded directly, the same "prove the path with real data"
        // approach every other scenario in this file uses for a fact that would otherwise need a
        // second, earlier successful import to produce for real.
        db.catalogDao().insert(
            LexiconVersionEntity(
                assetId = ModelsController.CALLSIGN_LEXICON_ASSET_ID,
                version = PREVIOUS_VERSION,
                importedAt = SystemClock.wallMillis() - PREVIOUS_IMPORT_AGE_MILLIS,
                recordCount = PREVIOUS_RECORD_COUNT,
                checksum = PREVIOUS_CHECKSUM,
            ),
        )

        val corruptFile = stageAssetAsFile(context)
        lastResult = ModelsController.installLexicon(context, corruptFile, RoomActiveLexiconStore(context))

        return Scenarios.LoadResult(transmissionCount = 0, sessionCount = 1, primarySessionId = sessionId)
    }

    private fun stageAssetAsFile(context: Context): File {
        val dest = File(context.cacheDir, "lexicon-corrupt-import.tsv")
        context.assets.open(ASSET_PATH).use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
        return dest
    }

    private const val PREVIOUS_IMPORT_AGE_MILLIS = 30L * 24 * 3_600_000L
}
