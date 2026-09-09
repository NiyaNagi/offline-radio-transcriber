package org.ort.app.debug.tour

import org.json.JSONObject
import java.io.File
import java.time.Instant

/** One line of `<filesDir>/tour/manifest.json` — this package's brief names this exact field set. */
public data class TourManifestEntry(
    public val id: String,
    public val scenario: String,
    public val fontScale: Float,
    public val width: Int,
    public val height: Int,
    public val capturedAt: String,
    public val ok: Boolean,
    public val errorMessage: String?,
) {
    public fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("scenario", scenario)
        put("fontScale", fontScale.toDouble())
        put("width", width)
        put("height", height)
        put("capturedAt", capturedAt)
        put("ok", ok)
        put("errorMessage", errorMessage ?: JSONObject.NULL)
    }

    public companion object {
        public fun success(
            id: String,
            scenario: String,
            fontScale: Float,
            width: Int,
            height: Int,
        ): TourManifestEntry = TourManifestEntry(
            id,
            scenario,
            fontScale,
            width,
            height,
            Instant.now().toString(),
            ok = true,
            errorMessage = null,
        )

        public fun failure(id: String, scenario: String, fontScale: Float, message: String): TourManifestEntry =
            TourManifestEntry(
                id,
                scenario,
                fontScale,
                width = 0,
                height = 0,
                capturedAt = Instant.now().toString(),
                ok = false,
                errorMessage = message,
            )

        public fun fromJson(obj: JSONObject): TourManifestEntry {
            val hasError = obj.has("errorMessage") && !obj.isNull("errorMessage")
            return TourManifestEntry(
                id = obj.getString("id"),
                scenario = obj.getString("scenario"),
                fontScale = obj.getDouble("fontScale").toFloat(),
                width = obj.getInt("width"),
                height = obj.getInt("height"),
                capturedAt = obj.getString("capturedAt"),
                ok = obj.getBoolean("ok"),
                errorMessage = obj.optString("errorMessage").takeIf { hasError },
            )
        }
    }
}

/**
 * Appends [entry] as one JSON line to [file] (creating it if absent) — durable per step, per this
 * package's brief ("append a line ... for each step"), so a tour that is killed mid-run still
 * leaves every already-completed step's result on disk.
 */
public fun appendManifestEntry(file: File, entry: TourManifestEntry) {
    file.parentFile?.mkdirs()
    file.appendText(entry.toJson().toString() + "\n")
}

/** The final line `tour.ps1` polls for — see that script's own doc comment for why a done marker
 * is needed alongside "the Activity finished". */
public fun appendManifestDone(file: File, total: Int, ok: Int, errors: Int) {
    file.parentFile?.mkdirs()
    val line = JSONObject().apply {
        put("done", true)
        put("steps", total)
        put("ok", ok)
        put("errors", errors)
        put("finishedAt", Instant.now().toString())
    }
    file.appendText(line.toString() + "\n")
}

/** Reads every step-result line back (skips the trailing `done` summary line) — used by
 * [TourRunner]'s own tests and available to any later reader that wants the parsed form rather
 * than raw JSONL. */
public fun readManifestEntries(file: File): List<TourManifestEntry> = file.readLines()
    .filter { it.isNotBlank() }
    .map { JSONObject(it) }
    .filterNot { it.optBoolean("done", false) }
    .map { TourManifestEntry.fromJson(it) }
