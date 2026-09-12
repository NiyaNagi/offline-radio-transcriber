package org.ort.app.fieldreport.bundle

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ort.app.diagnostics.DiagnosticsBundleSpec
import org.ort.app.fieldreport.recorder.FieldReportRecorder

/**
 * FR-OBS-8: the field-report bundle's ungated set is exactly `DiagnosticsBundleSpec`'s seven files
 * plus the session-recorder log — no more, no fewer — a closed compile-time fact, the same
 * discipline `DiagnosticsBundleSpecTest` already proves for `DiagnosticsBundleSpec` itself.
 */
class FieldReportBundleSpecTest {

    @Test
    fun `FR_OBS_8 ungated set is exactly the seven diagnostics files plus the session-recorder log`() {
        val names = FieldReportBundleSpec.ungatedFiles.map { it.fileName }
        assertEquals(
            DiagnosticsBundleSpec.files.map { it.fileName } + FieldReportRecorder.LOG_FILE_NAME,
            names,
        )
    }

    @Test
    fun `FR_OBS_8 every ungated file id is unique`() {
        val ids = FieldReportBundleSpec.ungatedFiles.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `FR_OBS_8 every ungated file name is unique`() {
        val names = FieldReportBundleSpec.ungatedFiles.map { it.fileName }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `FR_OBS_8 the seven diagnostics-shared entries reuse the exact same producer instances`() {
        val diagnosticsByFileName = DiagnosticsBundleSpec.files.associateBy { it.fileName }
        FieldReportBundleSpec.ungatedFiles
            .filter { it.id != FieldReportUngatedFileId.SESSION_RECORDER_LOG }
            .forEach { fieldReportFile ->
                val diagnosticsFile = diagnosticsByFileName.getValue(fieldReportFile.fileName)
                assert(fieldReportFile.producer === diagnosticsFile.producer) {
                    "expected ${fieldReportFile.fileName} to reuse DiagnosticsBundleSpec's own producer instance"
                }
                assertEquals(diagnosticsFile.clause, fieldReportFile.clause)
            }
    }

    @Test
    fun `FR_OBS_8 the session-recorder log entry uses SessionRecorderLogProducer`() {
        val recorderEntry = FieldReportBundleSpec.ungatedFiles.single {
            it.id == FieldReportUngatedFileId.SESSION_RECORDER_LOG
        }
        assert(recorderEntry.producer === SessionRecorderLogProducer)
        assertEquals(FieldReportRecorder.LOG_FILE_NAME, recorderEntry.fileName)
    }

    @Test
    fun `FieldReportGatedCategory is exactly the three FR-OBS-9 categories`() {
        assertEquals(
            listOf(
                FieldReportGatedCategory.RETAINED_AUDIO,
                FieldReportGatedCategory.VOICEPRINT_EMBEDDINGS,
                FieldReportGatedCategory.SCREEN_FRAMES,
            ),
            FieldReportGatedCategory.entries,
        )
    }
}
