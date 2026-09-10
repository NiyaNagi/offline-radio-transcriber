package org.ort.pipeline.digest

import java.io.File

/**
 * Where the real, on-device Gemma 3 1B int4 `.task` file is expected once WPG's bundled-asset
 * installer has run — never committed, exactly `org.ort.pipeline.passb.AsrModelLocator`'s own
 * pattern for the Whisper model (see that class's doc comment for the same reasoning). A fixed,
 * documented location so [ProseDigestRunner] and whatever install step populates it agree on
 * where to look without either side inventing a path.
 */
public object LlmModelLocator {
    public const val MODEL_ID: String = "gemma3-1b-it-int4"

    public fun modelsDir(filesDir: File): File = File(filesDir, "models/$MODEL_ID")

    /**
     * Null if the file is missing — a not-yet-installed model is treated exactly like no model
     * at all (constitution I).
     */
    public fun locate(filesDir: File): File? {
        val file = File(modelsDir(filesDir), "$MODEL_ID.task")
        return if (file.isFile) file else null
    }
}
