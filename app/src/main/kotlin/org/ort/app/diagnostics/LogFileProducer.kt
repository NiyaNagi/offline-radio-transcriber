package org.ort.app.diagnostics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WP11e (brief step 5, constitution I): renders one of the four board-listed logs. Reads a real
 * file at [DiagnosticsLogPaths.logFile] when a writer has put one there; otherwise produces the
 * honest placeholder — never fabricated log content. Every line, real or placeholder, is run
 * through [CallsignScrubber] before it ever reaches the bundle (FR-OBS-3's own example: `resolved
 * [callsign] at 0.94`).
 */
public class LogFileProducer(private val logicalFileName: String) : DiagnosticsFileProducer {

    override suspend fun produce(context: Context): ByteArray = withContext(Dispatchers.IO) {
        val file = DiagnosticsLogPaths.logFile(context, logicalFileName)
        val raw = if (file.isFile) file.readText(Charsets.UTF_8) else NO_ENTRIES_HEADER
        CallsignScrubber.scrub(raw).toByteArray(Charsets.UTF_8)
    }

    private companion object {
        const val NO_ENTRIES_HEADER = "no entries recorded by this build\n"
    }
}
