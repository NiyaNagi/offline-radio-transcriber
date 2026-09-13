package org.ort.pipeline.archive

/**
 * `Recording-Session.dc.html`'s (RC02) "Export" action, for the session's **audio** specifically
 * — not the transcript/attribution export `org.ort.app.export.ExportCoordinator` already builds
 * (its four formats, ADIF/CSV/JSON/TEXT, are all text; none of them carries audio bytes, and
 * building a fifth, audio-carrying format is out of this change's scope — "do not build a new
 * file format").
 *
 * **No writer for a session's raw audio exists anywhere in this codebase today.** The only place
 * audio ever leaves the device already is `org.ort.app.fieldreport.bundle.FieldReportBundleBuilder`
 * (D38's field-report channel) — a narrow, gated, per-category, default-off upload to one fixed
 * destination, not a general "share this session's audio" export, and reusing it here would
 * silently widen a channel the constitution treats as an exception, not a general mechanism
 * (constitution V). RC02's Export button therefore has no real implementation to call yet; this
 * is a typed stub naming exactly that, for whichever package eventually builds the real writer (a
 * zip of every FLAC file under `audio/<sessionId>/` plus enough metadata to be useful) to replace.
 */
public data class SessionAudioExportUnavailable(public val reason: String)

public object SessionAudioExport {

    /** Always [SessionAudioExportUnavailable] today — see the object's own doc comment. Takes
     * [sessionId] (unused) so a future real implementation's signature does not have to change. */
    @Suppress("UnusedParameter")
    public fun unavailable(sessionId: String): SessionAudioExportUnavailable = SessionAudioExportUnavailable(
        reason = "session audio export is not built -- no writer for a session's raw audio exists " +
            "anywhere in this codebase (ExportCoordinator's own formats are text-only)",
    )
}
