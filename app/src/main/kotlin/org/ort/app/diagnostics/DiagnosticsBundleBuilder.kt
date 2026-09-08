package org.ort.app.diagnostics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ort.data.OrtDatabase
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One [DiagnosticsBundleSpec] entry, resolved to its real, current byte size. */
public data class DiagnosticsBundleEntry(
    val id: DiagnosticsFileId,
    val fileName: String,
    val clause: String,
    val sizeBytes: Long,
)

public data class BundlePreview(val entries: List<DiagnosticsBundleEntry>, val totalBytes: Long)

/**
 * WP11e (register R-137, `Settings-Diagnostics.dc.html`, FR-OBS-1/FR-OBS-3, constitution V). The
 * real producer WP10's `SettingsDiagnosticsScreen` names as missing — [preview] is the "IN THE
 * BUNDLE" section's numbers (a real per-file size and a real total, not the board's illustrative
 * `2.1 MB`); [write] is `Save bundle`.
 *
 * **R-137**: [preview] and [write] render every file's bytes through the exact same
 * [DiagnosticsFileSpec.producer] call — never a separate `File.length()` stat for one and a
 * render for the other — so a preview size can never drift from what actually lands in the zip.
 * This matters specifically because the log producers *scrub* their content (`CallsignScrubber`),
 * which changes byte length; a stat of the raw on-disk file would not match the written, scrubbed
 * entry.
 *
 * **FR-OBS-3's `includeAudio` gate**: retained audio is not one of the board's seven files, but
 * FR-OBS-3 requires it be excluded "unless explicitly included" — enforced here structurally:
 * [includeAudio] defaults to `false`, and the audio-attaching code path
 * ([retainedAudioFiles]) is never reached at all unless a caller passes `true` explicitly.
 *
 * Every producer in [DiagnosticsBundleSpec.files] reads only what its own doc comment says it
 * reads — none queries [org.ort.data.entity.VoiceprintEntity.embedding],
 * [org.ort.data.entity.StationEntity.userName]/`notes`,
 * [org.ort.data.entity.OperatorLocationEntity] or [org.ort.data.entity.ContributionItemEntity] —
 * so AC-109 (no voiceprint) and AC-120 (no station knowledge) hold structurally, proven with
 * seeded rows in `DiagnosticsBundleBuilderTest`.
 */
public object DiagnosticsBundleBuilder {

    public suspend fun preview(
        context: Context,
        includeAudio: Boolean = false,
        spec: List<DiagnosticsFileSpec> = DiagnosticsBundleSpec.files,
    ): BundlePreview = withContext(Dispatchers.IO) {
        val entries = spec.map { file ->
            val bytes = file.producer.produce(context)
            DiagnosticsBundleEntry(file.id, file.fileName, file.clause, bytes.size.toLong())
        }
        val audioBytes = if (includeAudio) retainedAudioFiles(context).sumOf { it.second.length() } else 0L
        BundlePreview(entries, entries.sumOf { it.sizeBytes } + audioBytes)
    }

    public suspend fun write(
        context: Context,
        target: OutputStream,
        includeAudio: Boolean = false,
        spec: List<DiagnosticsFileSpec> = DiagnosticsBundleSpec.files,
    ): Unit = withContext(Dispatchers.IO) {
        ZipOutputStream(target).use { zip ->
            for (file in spec) {
                val bytes = file.producer.produce(context)
                zip.putNextEntry(ZipEntry(file.fileName))
                zip.write(bytes)
                zip.closeEntry()
            }
            if (includeAudio) {
                for ((entryName, source) in retainedAudioFiles(context)) {
                    zip.putNextEntry(ZipEntry(entryName))
                    source.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * Every retained transmission audio file that actually exists on disk, as
     * `(zip entry name, source file)` pairs — reached only when the caller passed `includeAudio =
     * true` (FR-OBS-3). Uses the same `File(context.filesDir, entity.audioPath())` layout every
     * other real reader of retained audio in this module does
     * ([org.ort.app.ui.audio.RealTransmissionAudioPlayer], `ReaderPolling.kt`, `LogViewData.kt`).
     */
    private suspend fun retainedAudioFiles(context: Context): List<Pair<String, File>> {
        val db = OrtDatabase.create(context.applicationContext)
        return db.transmissionDao().listAll().mapNotNull { entity ->
            val source = File(context.filesDir, entity.audioPath())
            if (source.isFile) "audio/${entity.sessionId}/${entity.id}.flac" to source else null
        }
    }
}
