package org.ort.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WP11e (register R-137, `Settings-Diagnostics.dc.html`, FR-OBS-1/3): the closed list of files a
 * bundle may contain must match the board exactly — no more, no fewer — the same seven files
 * `SettingsPolling.kt`'s existing fake already names verbatim (that file is WP10's; this is this
 * package's own, independent copy the real producer is built against).
 */
class DiagnosticsBundleSpecTest {

    @Test
    fun `R_137 the spec names exactly the board's seven files, in the board's order`() {
        val names = DiagnosticsBundleSpec.files.map { it.fileName }
        assertEquals(
            listOf(
                "lifecycle.log",
                "capture.log",
                "pipeline.log",
                "rig.log",
                "assets.json",
                "device.json",
                "counts.json",
            ),
            names,
        )
    }

    @Test
    fun `R_137 every file id is unique`() {
        val ids = DiagnosticsBundleSpec.files.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `R_137 every file name is unique`() {
        val names = DiagnosticsBundleSpec.files.map { it.fileName }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `R_137 clauses match the board verbatim`() {
        val clauses = DiagnosticsBundleSpec.files.associate { it.fileName to it.clause }
        assertEquals(
            "service start, stop, heartbeat gaps, OS kills — the F5 evidence",
            clauses["lifecycle.log"],
        )
        assertEquals(
            "route verifications, input device changes, level warnings, overruns",
            clauses["capture.log"],
        )
        assertEquals(
            "per-pass timings, tier changes with their cause, queue depth over time",
            clauses["pipeline.log"],
        )
        assertEquals(
            "CAT traffic, band changes, disconnects · frequencies included, they are not private",
            clauses["rig.log"],
        )
        assertEquals(
            "every model and lexicon: name, version, checksum, install date",
            clauses["assets.json"],
        )
        assertEquals(
            "SoC, RAM, Android version, OEM, thermal history · no serial, no IMEI, no account",
            clauses["device.json"],
        )
        assertEquals(
            "overs by state, rejections by reason, corrections by tier · numbers only",
            clauses["counts.json"],
        )
    }
}
