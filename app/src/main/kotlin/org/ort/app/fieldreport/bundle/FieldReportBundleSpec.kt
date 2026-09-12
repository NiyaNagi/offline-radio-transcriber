package org.ort.app.fieldreport.bundle

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ort.app.diagnostics.DiagnosticsBundleSpec
import org.ort.app.diagnostics.DiagnosticsFileId
import org.ort.app.diagnostics.DiagnosticsFileProducer
import org.ort.app.fieldreport.recorder.FieldReportRecorder
import java.io.File

/**
 * WPR2 (FR-OBS-8): the field-report bundle's **ungated set** — the part that uploads with no gate
 * at all, regardless of the FR-OBS-10 switch or any FR-OBS-9 per-category toggle. A closed `enum
 * class` plus a `List` built once from it, exactly [org.ort.app.diagnostics.DiagnosticsBundleSpec]'s
 * own discipline (that object's own doc comment, lines 10-13): "these and no more" as a
 * compile-time fact, never a runtime convention a future change could append to unnoticed.
 *
 * Deliberately a sibling of [DiagnosticsFileId], not that enum widened by one entry:
 * `DiagnosticsBundleSpec` is WP11e's file, scoped to the FR-OBS-3 exported bundle; this is a
 * different bundle (the field-report upload) that happens to share seven of its eight entries with
 * that one, by direct reuse of the exact same [DiagnosticsFileSpec][org.ort.app.diagnostics.DiagnosticsFileSpec]
 * entries (`fileName`, `clause`, and — most importantly — `producer`, per [FieldReportBundleSpec]'s
 * own construction below) — FR-OBS-9's "rendered through the same producer" discipline, one level
 * up: the diagnostics export and the field-report upload never render the same logical file two
 * different ways.
 */
public enum class FieldReportUngatedFileId {
    LIFECYCLE_LOG,
    CAPTURE_LOG,
    PIPELINE_LOG,
    RIG_LOG,
    ASSETS_JSON,
    DEVICE_JSON,
    COUNTS_JSON,
    SESSION_RECORDER_LOG,
}

/** One entry in [FieldReportBundleSpec.ungatedFiles]. */
public data class FieldReportUngatedFileSpec(
    val id: FieldReportUngatedFileId,
    val fileName: String,
    val clause: String,
    val producer: DiagnosticsFileProducer,
)

/**
 * FR-OBS-9: the three categories a field-report upload MAY additionally carry beyond
 * [FieldReportBundleSpec.ungatedFiles], each opt-in, each defaulting off, gated identically by
 * FR-OBS-10. A closed `enum class` — never a `Set<String>` or other open-ended shape — so "exactly
 * these three, no more" is a compile-time fact the same way [FieldReportUngatedFileId] is.
 */
public enum class FieldReportGatedCategory {
    RETAINED_AUDIO,
    VOICEPRINT_EMBEDDINGS,
    SCREEN_FRAMES,
}

/** The literal directory name [FieldReportRecorder.configure] creates under `filesDir` — that
 * object's own `LOG_DIR_NAME` constant is `private`, so this package's own readers of the same
 * directory ([SessionRecorderLogProducer]; [FieldReportBundleBuilder]'s screen-frame reader) name
 * the identical literal directly, exactly as this round's own brief documents the contract:
 * "`<filesDir>/field-report/session-recorder.log` — constant `FieldReportRecorder.LOG_FILE_NAME`"
 * and "`<filesDir>/field-report/frames/`". */
public const val FIELD_REPORT_DIR_NAME: String = "field-report"

/**
 * FR-OBS-6/FR-OBS-8: reads the session-recorder log [FieldReportRecorder] writes under
 * `<filesDir>/field-report/session-recorder.log`. **Never scrubbed by
 * [org.ort.app.diagnostics.CallsignScrubber]** — unlike the seven log files above, this one needs
 * no scrubbing: FR-OBS-8's own text is explicit that the recorder log is safe *by construction*
 * (FR-OBS-6's closed vocabulary has no `message: String` parameter anywhere in it, proven by
 * `RecorderEventVocabularyTest`'s own reflection test), so running a callsign-shaped-token scrubber
 * over it would only ever match nothing.
 */
public object SessionRecorderLogProducer : DiagnosticsFileProducer {

    override suspend fun produce(context: Context): ByteArray = withContext(Dispatchers.IO) {
        val file = File(File(context.filesDir, FIELD_REPORT_DIR_NAME), FieldReportRecorder.LOG_FILE_NAME)
        if (file.isFile) file.readBytes() else NO_ENTRIES_HEADER.toByteArray(Charsets.UTF_8)
    }

    private const val NO_ENTRIES_HEADER = "no session-recorder events recorded this run\n"
}

public object FieldReportBundleSpec {

    /** Maps each of [DiagnosticsBundleSpec.files]' seven ids onto this bundle's own id for the
     * identical logical file — see this file's own top doc comment for why this is a mapping over
     * reused entries, not a second, independently-authored list of the same seven files. */
    private val DIAGNOSTICS_TO_FIELD_REPORT_ID: Map<DiagnosticsFileId, FieldReportUngatedFileId> = mapOf(
        DiagnosticsFileId.LIFECYCLE_LOG to FieldReportUngatedFileId.LIFECYCLE_LOG,
        DiagnosticsFileId.CAPTURE_LOG to FieldReportUngatedFileId.CAPTURE_LOG,
        DiagnosticsFileId.PIPELINE_LOG to FieldReportUngatedFileId.PIPELINE_LOG,
        DiagnosticsFileId.RIG_LOG to FieldReportUngatedFileId.RIG_LOG,
        DiagnosticsFileId.ASSETS_JSON to FieldReportUngatedFileId.ASSETS_JSON,
        DiagnosticsFileId.DEVICE_JSON to FieldReportUngatedFileId.DEVICE_JSON,
        DiagnosticsFileId.COUNTS_JSON to FieldReportUngatedFileId.COUNTS_JSON,
    )

    /** FR-OBS-8's ungated set, board order matching [DiagnosticsBundleSpec.files], the recorder log
     * last. `getValue` on [DIAGNOSTICS_TO_FIELD_REPORT_ID] fails loudly, at class-load, the moment
     * `DiagnosticsBundleSpec.files` ever carries an id this map does not know about — a deliberate
     * fail-closed guard against silent drift between the two closed lists. */
    public val ungatedFiles: List<FieldReportUngatedFileSpec> =
        DiagnosticsBundleSpec.files.map { diagnosticsFile ->
            FieldReportUngatedFileSpec(
                id = DIAGNOSTICS_TO_FIELD_REPORT_ID.getValue(diagnosticsFile.id),
                fileName = diagnosticsFile.fileName,
                clause = diagnosticsFile.clause,
                producer = diagnosticsFile.producer,
            )
        } + FieldReportUngatedFileSpec(
            id = FieldReportUngatedFileId.SESSION_RECORDER_LOG,
            fileName = FieldReportRecorder.LOG_FILE_NAME,
            clause = "destination changes, tapped controls, permission results, capture-state and " +
                "setup-step transitions, audio-device enumeration — a closed vocabulary, no free " +
                "text (FR-OBS-6)",
            producer = SessionRecorderLogProducer,
        )
}
