package org.ort.app.diagnostics.localsave

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ort.app.diagnostics.DiagnosticsBundleBuilder
import org.ort.app.export.DebugDumpBuilder
import org.ort.app.fieldreport.bundle.FieldReportBundleBuilder
import org.ort.app.fieldreport.bundle.FieldReportBundleSpec
import org.ort.app.fieldreport.bundle.VoiceprintEmbeddingsProducer
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One resolved row of a local save — real caption (the stated "why empty" reason already appended
 * when [available] is `false`), real size, and every real file this category would write, in the
 * order [write] writes them. [fileNames] is a list because two categories ([LocalSaveCategoryId.RETAINED_AUDIO],
 * [LocalSaveCategoryId.SCREEN_FRAMES]) are backed by many on-disk files, not one. */
public data class LocalSaveCategoryEntry(
    val id: LocalSaveCategoryId,
    val label: String,
    val caption: String,
    val fileNames: List<String>,
    val sizeBytes: Long,
    val available: Boolean,
)

public data class LocalSavePreview(val entries: List<LocalSaveCategoryEntry>) {

    /** The real total for exactly the categories [selected] names — computed from the sizes
     * [preview] already resolved, no further disk/database read. An id in [selected] that this
     * preview never resolved (should not happen — [LocalSaveCategoryId] is a closed enum this
     * preview always resolves every entry of) contributes nothing, never a crash. */
    public fun totalBytes(selected: Set<LocalSaveCategoryId>): Long =
        entries.filter { it.id in selected }.sumOf { it.sizeBytes }
}

/**
 * WPDUMP (operator: *"just allow a single save dump with checkboxes for EVERYTHING that can be
 * saved"*). The one producer behind the unified local-save checklist — [preview] is every
 * checkbox's real label/caption/size/availability, [write] is `Save`, and both render every
 * category through the exact same [FieldReportBundleSpec] entry/[VoiceprintEmbeddingsProducer]/
 * [DiagnosticsBundleBuilder]/[DebugDumpBuilder] call the field-report bundle and the diagnostics
 * export already use — never a second, independently-authored render of the same logical file (the
 * `DiagnosticsBundleBuilder`/`FieldReportBundleBuilder` doc comments' own "preview and write can
 * never drift" discipline, one level up, applied to a third bundle that shares their producers
 * rather than reinventing them).
 *
 * **Why a local save is never gated the way an upload is (FR-OBS-10 does not apply here).**
 * FR-OBS-10's guard exists because an upload has a *destination* whose visibility (public/private)
 * changes what leaving the device means. A local save has no destination at all — the operator
 * picks where on their own device (or a location their device otherwise reaches, e.g. removable
 * storage) the file goes, via the Storage Access Framework, and nothing is transmitted anywhere.
 * FR-OBS-3 ("export a diagnostic bundle... excluding audio unless explicitly included") already
 * anticipated operator-directed inclusion of a category the ungated set does not carry; this
 * builder applies that same "shown its real size, ticked deliberately" shape to every category that
 * exists, not only audio. [LocalSaveBundleSpec.defaultSelected]/[LocalSaveBundleSpec.thirdPartyContent]
 * carry the actual safety discipline (default-on only for what is safe by construction) — there is
 * no runtime refusal to add on top of that, because there is no public/private destination for one
 * to key off.
 *
 * **Availability.** Eight categories (the seven scrubbed files plus [LocalSaveCategoryId.DEBUG_DUMP_NDJSON])
 * are never disabled — each underlying producer always returns real, non-fabricated bytes (a real
 * log, a "no entries recorded" placeholder, or a debug dump that is at minimum one real `meta`
 * line). [LocalSaveCategoryId.SESSION_RECORDER_LOG] is disabled exactly in a release build
 * (FR-OBS-6: the recorder is absent from release builds entirely, a compile-time fact, not "has
 * logged something yet"). [LocalSaveCategoryId.RETAINED_AUDIO]/[LocalSaveCategoryId.VOICEPRINT_EMBEDDINGS]/
 * [LocalSaveCategoryId.SCREEN_FRAMES] are disabled exactly when there is nothing real on disk for
 * them yet — constitution I: a disabled checkbox with a stated reason is the truth; a missing
 * checkbox would read as "this does not exist".
 */
public object LocalSaveBundleBuilder {

    public suspend fun preview(
        context: Context,
        debugBuild: Boolean = org.ort.app.BuildConfig.DEBUG,
    ): LocalSavePreview = withContext(Dispatchers.IO) {
        LocalSavePreview(LocalSaveBundleSpec.order.map { id -> resolve(context, id, debugBuild) })
    }

    public suspend fun write(
        context: Context,
        target: OutputStream,
        selected: Set<LocalSaveCategoryId>,
        debugBuild: Boolean = org.ort.app.BuildConfig.DEBUG,
    ): Unit = withContext(Dispatchers.IO) {
        // Defensive filter (this object's own doc comment): an unavailable category can never be
        // written even if a caller passes its id — resolved fresh here, never trusted from a
        // caller-supplied flag, so a stale `selected` set computed before data appeared/disappeared
        // cannot silently include or exclude the wrong thing.
        val resolved = LocalSaveBundleSpec.order.associateWith { id -> resolve(context, id, debugBuild) }
        ZipOutputStream(target).use { zip ->
            LocalSaveBundleSpec.order
                .filter { id -> id in selected && resolved.getValue(id).available }
                .forEach { id -> writeCategory(zip, context, id, resolved.getValue(id)) }
        }
    }

    private suspend fun writeCategory(
        zip: ZipOutputStream,
        context: Context,
        id: LocalSaveCategoryId,
        entry: LocalSaveCategoryEntry,
    ) {
        when (id) {
            LocalSaveCategoryId.RETAINED_AUDIO ->
                for ((entryName, source) in DiagnosticsBundleBuilder.retainedAudioFiles(context)) {
                    writeFileEntry(zip, entryName, source)
                }
            LocalSaveCategoryId.SCREEN_FRAMES ->
                for (frame in FieldReportBundleBuilder.screenFrameFiles(context)) {
                    writeFileEntry(zip, "frames/${frame.name}", frame)
                }
            LocalSaveCategoryId.VOICEPRINT_EMBEDDINGS ->
                writeBytesEntry(zip, entry.fileNames.single(), VoiceprintEmbeddingsProducer.produce(context))
            LocalSaveCategoryId.DEBUG_DUMP_NDJSON ->
                writeBytesEntry(zip, entry.fileNames.single(), DebugDumpBuilder.build(context))
            else -> {
                val ungatedId = LocalSaveBundleSpec.ungatedIdFor(id)
                    ?: error("no producer for $id — every LocalSaveCategoryId must be handled here")
                val spec = FieldReportBundleSpec.ungatedFiles.single { it.id == ungatedId }
                writeBytesEntry(zip, spec.fileName, spec.producer.produce(context))
            }
        }
    }

    private fun writeBytesEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeFileEntry(zip: ZipOutputStream, name: String, source: File) {
        zip.putNextEntry(ZipEntry(name))
        source.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private suspend fun resolve(
        context: Context,
        id: LocalSaveCategoryId,
        debugBuild: Boolean,
    ): LocalSaveCategoryEntry {
        val ungatedId = LocalSaveBundleSpec.ungatedIdFor(id)
        if (ungatedId != null) {
            val spec = FieldReportBundleSpec.ungatedFiles.single { it.id == ungatedId }
            val available = id != LocalSaveCategoryId.SESSION_RECORDER_LOG || debugBuild
            val bytes = if (available) spec.producer.produce(context) else ByteArray(0)
            return LocalSaveCategoryEntry(
                id = id,
                label = spec.fileName,
                caption = caption(id, available),
                fileNames = listOf(spec.fileName),
                sizeBytes = bytes.size.toLong(),
                available = available,
            )
        }
        return when (id) {
            LocalSaveCategoryId.RETAINED_AUDIO -> {
                val files = DiagnosticsBundleBuilder.retainedAudioFiles(context)
                LocalSaveCategoryEntry(
                    id = id,
                    label = "Retained over audio",
                    caption = caption(id, files.isNotEmpty()),
                    fileNames = files.map { it.first },
                    sizeBytes = files.sumOf { it.second.length() },
                    available = files.isNotEmpty(),
                )
            }
            LocalSaveCategoryId.SCREEN_FRAMES -> {
                val frames = FieldReportBundleBuilder.screenFrameFiles(context)
                LocalSaveCategoryEntry(
                    id = id,
                    label = "Screen frames",
                    caption = caption(id, frames.isNotEmpty()),
                    fileNames = frames.map { "frames/${it.name}" },
                    sizeBytes = frames.sumOf { it.length() },
                    available = frames.isNotEmpty(),
                )
            }
            LocalSaveCategoryId.VOICEPRINT_EMBEDDINGS -> {
                val count = VoiceprintEmbeddingsProducer.count(context)
                val bytes = if (count > 0) VoiceprintEmbeddingsProducer.produce(context) else ByteArray(0)
                LocalSaveCategoryEntry(
                    id = id,
                    label = VoiceprintEmbeddingsProducer.FILE_NAME,
                    caption = caption(id, count > 0),
                    fileNames = listOf(VoiceprintEmbeddingsProducer.FILE_NAME),
                    sizeBytes = bytes.size.toLong(),
                    available = count > 0,
                )
            }
            LocalSaveCategoryId.DEBUG_DUMP_NDJSON -> {
                val bytes = DebugDumpBuilder.build(context)
                LocalSaveCategoryEntry(
                    id = id,
                    label = "debug-dump.ndjson",
                    caption = caption(id, available = true),
                    fileNames = listOf("debug-dump.ndjson"),
                    sizeBytes = bytes.size.toLong(),
                    available = true,
                )
            }
            else -> error("unreachable — $id is one of the eight ungated ids handled above")
        }
    }

    /** WPDUMP: the operator's own diagnostics zip already contained the seven scrubbed files with
     * real content and *not* the recorder log, the frames or the NDJSON — three separate saves were
     * needed to get everything. These captions are written for a reader choosing what goes in
     * **one** file, not re-explaining what a fresh screen would need to introduce from scratch. */
    private fun caption(id: LocalSaveCategoryId, available: Boolean): String {
        val base = when (id) {
            LocalSaveCategoryId.LIFECYCLE_LOG ->
                "service start, stop, heartbeat gaps, OS kills — the F5 evidence"
            LocalSaveCategoryId.CAPTURE_LOG ->
                // R-1034: this log is session/fault-level, not per-transmission — a 12-minute, "
                // 8-transmission session produces two short lines, not eight. Never promise
                // per-transmission detail it does not carry.
                "route verifications, input device changes, level warnings, overruns — one line " +
                    "per event, not per transmission"
            LocalSaveCategoryId.PIPELINE_LOG ->
                "per-pass timings, tier changes with their cause, queue depth over time"
            LocalSaveCategoryId.RIG_LOG ->
                "CAT traffic, band changes, disconnects · frequencies included, they are not private"
            LocalSaveCategoryId.ASSETS_JSON ->
                "every model and lexicon: name, version, checksum, install date"
            LocalSaveCategoryId.DEVICE_JSON ->
                "SoC, RAM, Android version, OEM, thermal history · no serial, no IMEI, no account"
            LocalSaveCategoryId.COUNTS_JSON ->
                "overs by state, rejections by reason, corrections by tier · numbers only"
            LocalSaveCategoryId.SESSION_RECORDER_LOG ->
                "destination changes, tapped controls, permission results, capture-state and " +
                    "setup-step transitions, audio-device enumeration — a closed vocabulary, no " +
                    "free text (FR-OBS-6)"
            LocalSaveCategoryId.RETAINED_AUDIO ->
                "the full-fidelity recording of every retained transmission on this device — the " +
                    "actual voices of every station heard, not a transcript of them. Recordings of " +
                    "identifiable people."
            LocalSaveCategoryId.VOICEPRINT_EMBEDDINGS ->
                "the numeric voice signatures used for speaker matching, one per enrolled voice, " +
                    "derived from a real operator's voice. Tied to an identifiable person's speech, " +
                    "not audio itself, but still theirs."
            LocalSaveCategoryId.SCREEN_FRAMES ->
                "downscaled screenshots captured on each destination change — pixels only, no OCR, " +
                    "no separate transcript (FR-OBS-7). Can show a callsign or transcript fragment " +
                    "that happened to be on screen at that moment."
            LocalSaveCategoryId.DEBUG_DUMP_NDJSON ->
                "every session, over, attribution and gap, plus every failed processing item's " +
                    "error and full retry history, one JSON object per line — the workstation " +
                    "format for analysing a failed field session"
        }
        if (available) return base
        return "$base — ${unavailableReason(id)}."
    }

    private fun unavailableReason(id: LocalSaveCategoryId): String = when (id) {
        LocalSaveCategoryId.SESSION_RECORDER_LOG -> "the session recorder only runs in debug builds (FR-OBS-6)"
        LocalSaveCategoryId.RETAINED_AUDIO -> "no retained audio exists on this device yet"
        LocalSaveCategoryId.VOICEPRINT_EMBEDDINGS -> "no voiceprints have been resolved yet"
        LocalSaveCategoryId.SCREEN_FRAMES -> "no screen frames have been captured yet"
        else -> error("$id is never unavailable — see LocalSaveBundleBuilder's own doc comment")
    }
}
