package org.ort.app.diagnostics

import android.content.Context

/**
 * WP11e (register R-137, `Settings-Diagnostics.dc.html`, FR-OBS-1/FR-OBS-3): the closed list of
 * files a diagnostics bundle may contain — exactly the board's seven, no more, no fewer. This is
 * this package's own copy, independent of `ui/settings/SettingsPolling.kt`'s existing fake (WP10's
 * file, not touched here) — that screen is expected to switch to this producer afterwards.
 *
 * Never a stored, in-memory or database list: a closed `enum class` plus a `List` built once from
 * it is what makes "the board's seven files, no more" a compile-time fact rather than a runtime
 * convention someone could append to unnoticed.
 */
public enum class DiagnosticsFileId {
    LIFECYCLE_LOG,
    CAPTURE_LOG,
    PIPELINE_LOG,
    RIG_LOG,
    ASSETS_JSON,
    DEVICE_JSON,
    COUNTS_JSON,
}

/**
 * Renders one bundle file's real bytes. Every implementation is a pure read of already-existing
 * state (a log file on disk, the catalog, the database) — none writes anything, and none ever
 * queries a voiceprint embedding, a station's user-supplied name/notes, an operator location or
 * the corpus contribution queue (constitution V) — see each implementation's own doc comment for
 * exactly what it reads.
 */
public fun interface DiagnosticsFileProducer {
    public suspend fun produce(context: Context): ByteArray
}

/** One entry in [DiagnosticsBundleSpec.files] — the board's own file name and trailing clause,
 * verbatim, paired with the [DiagnosticsFileProducer] that renders its real bytes. */
public data class DiagnosticsFileSpec(
    val id: DiagnosticsFileId,
    val fileName: String,
    val clause: String,
    val producer: DiagnosticsFileProducer,
)

public object DiagnosticsBundleSpec {

    /** Board order, `Settings-Diagnostics.dc.html` top to bottom. */
    public val files: List<DiagnosticsFileSpec> = listOf(
        DiagnosticsFileSpec(
            id = DiagnosticsFileId.LIFECYCLE_LOG,
            fileName = "lifecycle.log",
            clause = "service start, stop, heartbeat gaps, OS kills — the F5 evidence",
            producer = LogFileProducer("lifecycle.log"),
        ),
        DiagnosticsFileSpec(
            id = DiagnosticsFileId.CAPTURE_LOG,
            fileName = "capture.log",
            clause = "route verifications, input device changes, level warnings, overruns",
            producer = LogFileProducer("capture.log"),
        ),
        DiagnosticsFileSpec(
            id = DiagnosticsFileId.PIPELINE_LOG,
            fileName = "pipeline.log",
            clause = "per-pass timings, tier changes with their cause, queue depth over time",
            producer = LogFileProducer("pipeline.log"),
        ),
        DiagnosticsFileSpec(
            id = DiagnosticsFileId.RIG_LOG,
            fileName = "rig.log",
            clause = "CAT traffic, band changes, disconnects · frequencies included, they are not private",
            producer = LogFileProducer("rig.log"),
        ),
        DiagnosticsFileSpec(
            id = DiagnosticsFileId.ASSETS_JSON,
            fileName = "assets.json",
            clause = "every model and lexicon: name, version, checksum, install date",
            producer = AssetsJsonProducer,
        ),
        DiagnosticsFileSpec(
            id = DiagnosticsFileId.DEVICE_JSON,
            fileName = "device.json",
            clause = "SoC, RAM, Android version, OEM, thermal history · no serial, no IMEI, no account",
            producer = DeviceJsonProducer,
        ),
        DiagnosticsFileSpec(
            id = DiagnosticsFileId.COUNTS_JSON,
            fileName = "counts.json",
            clause = "overs by state, rejections by reason, corrections by tier · numbers only",
            producer = CountsJsonProducer,
        ),
    )
}
