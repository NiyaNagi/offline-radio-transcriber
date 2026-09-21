package org.ort.app.export

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ort.core.AttributionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.TransmissionEntity
import java.io.File
import java.time.Instant

/** One file [ShareCoordinator] built, ready for a `FileProvider`/`ACTION_SEND` caller — the
 * caller ([ShareIntentLauncher]) owns turning this into a content [android.net.Uri] and an
 * [android.content.Intent], never this package (which has no Android UI dependency to build one
 * from). */
public data class ShareFile(val file: File, val mimeType: String, val suggestedName: String)

/**
 * FR-EXP-7 (M): "share a digest, a thread transcript, or a single over's audio clip through the
 * platform share sheet ... user-initiated only." This object builds the *content* — the actual
 * tap-to-share wiring (the `FileProvider` URI, the `ACTION_SEND` intent, the chooser) lives in
 * [ShareIntentLauncher], the one place every real screen this file's own kdoc lists below shares
 * it from.
 *
 * **The closed field list, stated once.** Every text file here is built directly from
 * [TransmissionEntity]/[org.ort.data.entity.TranscriptEntity]/[org.ort.data.entity.SessionEntity]
 * fields this function itself names — never from [org.ort.data.entity.StationEntity.userName],
 * `.notes`, `.spokenGrids`, `.frequenciesHeard`, `.activityByHourDow`, never from
 * [org.ort.data.entity.OperatorLocationEntity], never from a voiceprint. A callsign is included
 * (the transmission's own resolved `stationId`, the public over-the-air identity every export
 * writer in this package already includes — [ExportCoordinator]'s own precedent) but nothing about
 * *the station as a pattern* (who they are, where they are, when they are usually heard) is —
 * exactly [ExportOverRecord][org.ort.pipeline.export.ExportOverRecord]'s own closed shape, the
 * identical discipline this file's own AC-171 test proves by seeding a station with every
 * forbidden field set and asserting none of it reaches the shared bytes.
 *
 * Every function here answers `null`, honestly, when there is nothing to share yet (constitution
 * I: never a fabricated empty digest/transcript/clip) — the named session/thread/transmission does
 * not exist, or (for the audio clip) nothing is retained on disk for it.
 *
 * **Register R-1096, closed.** P30 originally wired the share sheet from Settings > Export with no
 * open screen to take a subject from, so each function below resolved "the most recent" digest/
 * thread/over by real-time — an operator reading one particular over and tapping share did not get
 * that over. Every function now takes the real id of the thing actually open
 * ([sessionId]/[threadId]/[transmissionId]) — [org.ort.app.ui.digest.DigestContent],
 * [org.ort.app.ui.screens.ThreadDetailContent][org.ort.app.ui.navigation.OrtNavHost] and
 * [org.ort.app.ui.screens.TransmissionDetailContent] each already hold their own subject's id for
 * their own poll, and now pass that same id here rather than this object guessing at one. The
 * closed field list and the attribution vocabulary below are unchanged by this — only the
 * *selection* was ever wrong.
 */
public object ShareCoordinator {

    public suspend fun buildDigestShareFile(context: Context, sessionId: String): ShareFile? =
        withContext(Dispatchers.IO) {
            val db = OrtDatabase.create(context.applicationContext)
            val session = db.sessionDao().getById(sessionId) ?: return@withContext null
            val transmissions = db.transmissionDao().listBySession(session.id).sortedBy { it.startedAtUtc }
            val stationCount = transmissions.mapNotNull { it.stationId }.distinct().size
            val text = buildString {
                appendLine("Digest -- session ${session.id}")
                appendLine(
                    "${formatInstant(session.startedAt)} to " +
                        "${session.endedAt?.let(::formatInstant) ?: "(ongoing)"}",
                )
                appendLine("${transmissions.size} overs, $stationCount stations heard")
                appendLine()
                for (transmission in transmissions) {
                    appendLine("[${formatInstant(transmission.startedAtUtc)}] ${attributionLabel(transmission)}")
                    val transcript = db.transcriptDao().getCurrent(transmission.id)
                    if (transcript != null) appendLine("  ${transcript.text}")
                }
            }
            val file = shareCacheFile(context, "digest-${session.id}.txt")
            file.writeText(text, Charsets.UTF_8)
            ShareFile(file, "text/plain", "ort-digest-${session.id}.txt")
        }

    public suspend fun buildThreadTranscriptShareFile(context: Context, threadId: String): ShareFile? =
        withContext(Dispatchers.IO) {
            val db = OrtDatabase.create(context.applicationContext)
            val threadTransmissions = db.transmissionDao().listAll()
                .filter { it.threadId == threadId }
                .sortedBy { it.startedAtUtc }
            if (threadTransmissions.isEmpty()) return@withContext null
            val text = buildString {
                appendLine("Thread transcript -- thread $threadId")
                appendLine()
                for (transmission in threadTransmissions) {
                    appendLine("[${formatInstant(transmission.startedAtUtc)}] ${attributionLabel(transmission)}")
                    val transcript = db.transcriptDao().getCurrent(transmission.id)
                    if (transcript != null) appendLine("  ${transcript.text}")
                }
            }
            val file = shareCacheFile(context, "thread-$threadId.txt")
            file.writeText(text, Charsets.UTF_8)
            ShareFile(file, "text/plain", "ort-thread-$threadId.txt")
        }

    public suspend fun resolveOverAudioShareFile(context: Context, transmissionId: String): ShareFile? =
        withContext(Dispatchers.IO) {
            val transmission = OrtDatabase.create(context.applicationContext)
                .transmissionDao()
                .getById(transmissionId)
                ?: return@withContext null
            val file = File(context.filesDir, transmission.audioPath())
            if (!file.isFile) return@withContext null
            ShareFile(file, "audio/flac", "ort-over-${transmission.id}.flac")
        }

    /** Callsign if this over resolved one, its honest state name otherwise — the same closed
     * vocabulary [org.ort.pipeline.export.ExportAttribution] already gives every format writer in
     * this package, restated locally here since this text is not itself one of those four formats. */
    private fun attributionLabel(transmission: TransmissionEntity): String = when (transmission.attributionState) {
        AttributionState.CONFIRMED -> transmission.stationId?.takeIf { it.isNotBlank() } ?: "UNRESOLVED (CONFIRMED)"
        AttributionState.INFERRED -> "${transmission.stationId?.takeIf { it.isNotBlank() } ?: "UNRESOLVED"} (inferred)"
        AttributionState.AMBIGUOUS -> "AMBIGUOUS"
        AttributionState.UNKNOWN -> "UNKNOWN"
    }

    private fun formatInstant(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

    /** `cacheDir/share/` — the one directory `res/xml/file_paths.xml` declares a `cache-path` for
     * (this file's own share-only text output; over audio is shared straight from its own real
     * `filesDir/audio/...` location, never copied). */
    private fun shareCacheFile(context: Context, name: String): File {
        val dir = File(context.cacheDir, "share")
        dir.mkdirs()
        return File(dir, name)
    }
}
