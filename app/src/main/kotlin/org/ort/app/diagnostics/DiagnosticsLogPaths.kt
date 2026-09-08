package org.ort.app.diagnostics

import android.content.Context
import java.io.File

/**
 * WP11e (brief step 5): the documented convention a future `lifecycle.log`/`capture.log`/
 * `pipeline.log`/`rig.log` writer should follow so [LogFileProducer] picks its real output up with
 * no change to this package. Grepped before writing this producer (`lifecycle\.log|capture\.log|
 * pipeline\.log|rig\.log`, whole tree, main source sets only) — no writer exists yet for any of
 * the four, so this path is never populated today and every log in the bundle takes the honest
 * placeholder in [LogFileProducer].
 */
public object DiagnosticsLogPaths {

    private const val LOG_DIR_NAME = "diagnostics-logs"

    public fun logDir(context: Context): File = File(context.filesDir, LOG_DIR_NAME)

    public fun logFile(context: Context, fileName: String): File = File(logDir(context), fileName)
}
