package org.ort.app.fieldreport.bundle

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ort.app.diagnostics.DiagnosticsBundleBuilder
import org.ort.app.diagnostics.DiagnosticsFileProducer
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One resolved entry in a field-report bundle. [category] is `null` for one of
 * [FieldReportBundleSpec.ungatedFiles] (FR-OBS-8's ungated set); non-`null` names which FR-OBS-9
 * opt-in category the file belongs to. */
public data class FieldReportBundleEntry(
    val fileName: String,
    val sizeBytes: Long,
    val category: FieldReportGatedCategory?,
)

public data class FieldReportBundlePreview(val entries: List<FieldReportBundleEntry>, val totalBytes: Long)

/** One file a [FieldReportGatedCategory] contributes — either bytes a [DiagnosticsFileProducer]
 * generates fresh, or an existing on-disk file (retained audio, a stored screen frame). Both
 * [FieldReportBundleBuilder.preview] and [FieldReportBundleBuilder.write] resolve the *same* list
 * of these (via [FieldReportBundleBuilder.gatedFiles]) before rendering either one, so which files
 * are included can never drift between the two — the same discipline
 * [org.ort.app.diagnostics.DiagnosticsBundleBuilder]'s own doc comment documents for its bundle. */
private sealed interface GatedFile {
    val entryName: String
    val category: FieldReportGatedCategory

    data class Generated(
        override val entryName: String,
        override val category: FieldReportGatedCategory,
        val producer: DiagnosticsFileProducer,
    ) : GatedFile

    data class OnDisk(
        override val entryName: String,
        override val category: FieldReportGatedCategory,
        val source: File,
    ) : GatedFile
}

/**
 * FR-OBS-8/FR-OBS-9: the field-report bundle's real producer. [preview] and [write] both resolve
 * [FieldReportBundleSpec.ungatedFiles] (always included) plus [gatedFiles] (only the categories
 * [categories] names) before rendering — never a separate estimate for one and a render for the
 * other, R-137's own discipline restated for this bundle.
 *
 * [categories] is the operator's current, per-upload toggle state (FR-OBS-9) — empty by default,
 * matching "each defaulting off". Nothing outside [FieldReportBundleSpec.ungatedFiles] is ever
 * included beyond what [categories] names.
 */
public object FieldReportBundleBuilder {

    public suspend fun preview(
        context: Context,
        categories: Set<FieldReportGatedCategory> = emptySet(),
    ): FieldReportBundlePreview = withContext(Dispatchers.IO) {
        val ungated = FieldReportBundleSpec.ungatedFiles.map { file ->
            FieldReportBundleEntry(file.fileName, file.producer.produce(context).size.toLong(), category = null)
        }
        val gated = gatedFiles(context, categories).map { gated ->
            val size = when (gated) {
                is GatedFile.Generated -> gated.producer.produce(context).size.toLong()
                is GatedFile.OnDisk -> gated.source.length()
            }
            FieldReportBundleEntry(gated.entryName, size, gated.category)
        }
        val entries = ungated + gated
        FieldReportBundlePreview(entries, entries.sumOf { it.sizeBytes })
    }

    public suspend fun write(
        context: Context,
        target: OutputStream,
        categories: Set<FieldReportGatedCategory> = emptySet(),
    ): Unit = withContext(Dispatchers.IO) {
        ZipOutputStream(target).use { zip ->
            for (file in FieldReportBundleSpec.ungatedFiles) {
                writeEntry(zip, file.fileName, file.producer.produce(context))
            }
            for (gated in gatedFiles(context, categories)) {
                when (gated) {
                    is GatedFile.Generated -> writeEntry(zip, gated.entryName, gated.producer.produce(context))
                    is GatedFile.OnDisk -> writeFileEntry(zip, gated.entryName, gated.source)
                }
            }
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeFileEntry(zip: ZipOutputStream, name: String, source: File) {
        zip.putNextEntry(ZipEntry(name))
        source.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    /** [categories]' real files, resolved once and shared by [preview] and [write] — see this
     * file's own [GatedFile] doc comment for why. */
    private suspend fun gatedFiles(context: Context, categories: Set<FieldReportGatedCategory>): List<GatedFile> {
        val result = mutableListOf<GatedFile>()
        if (FieldReportGatedCategory.VOICEPRINT_EMBEDDINGS in categories) {
            result += GatedFile.Generated(
                VoiceprintEmbeddingsProducer.FILE_NAME,
                FieldReportGatedCategory.VOICEPRINT_EMBEDDINGS,
                VoiceprintEmbeddingsProducer,
            )
        }
        if (FieldReportGatedCategory.RETAINED_AUDIO in categories) {
            for ((entryName, source) in DiagnosticsBundleBuilder.retainedAudioFiles(context)) {
                result += GatedFile.OnDisk(entryName, FieldReportGatedCategory.RETAINED_AUDIO, source)
            }
        }
        if (FieldReportGatedCategory.SCREEN_FRAMES in categories) {
            for (frame in screenFrameFiles(context)) {
                result += GatedFile.OnDisk("frames/${frame.name}", FieldReportGatedCategory.SCREEN_FRAMES, frame)
            }
        }
        return result
    }

    /** Every FR-OBS-7 frame [org.ort.app.fieldreport.recorder.FrameStore] currently holds on disk,
     * oldest first — read directly off the documented `<filesDir>/field-report/frames/` path (this
     * round's own brief), since [org.ort.app.fieldreport.recorder.FrameStore] itself lives in
     * the `fieldreport.recorder` package, not constructed here. */
    private fun screenFrameFiles(context: Context): List<File> {
        val dir = File(File(context.filesDir, FIELD_REPORT_DIR_NAME), "frames")
        return (dir.listFiles()?.toList() ?: emptyList()).sortedBy { it.name }
    }
}
