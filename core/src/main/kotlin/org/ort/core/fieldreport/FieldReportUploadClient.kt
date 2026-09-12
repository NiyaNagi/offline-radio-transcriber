package org.ort.core.fieldreport

/**
 * FR-OBS-9's "destination and destination repository's visibility" (D37/D38), FR-OBS-10's "checked
 * at upload time, not at setup time". [FieldReportDestinationVisibility.UNKNOWN] exists because
 * constitution I forbids collapsing "the API could not be reached" into "confirmed private" — the
 * two facts must stay distinguishable so a caller can refuse the gated categories on `UNKNOWN`
 * exactly as it would on `PUBLIC` (see [org.ort.net.fieldreport.real.RealFieldReportUploadClient]'s
 * own `destination()` for where this is produced, and WPR3's report for why guessing "private" here
 * is exactly the failure this product exists to avoid).
 */
public enum class FieldReportDestinationVisibility {
    PUBLIC,
    PRIVATE,
    UNKNOWN,
}

/** [label] is the destination repository's `owner/repo` name; [visibility] is fetched fresh on
 * every call to [FieldReportUploadClient.destination] — never cached, matching FR-OBS-9's
 * "per-upload, never inferred from a previous upload's choice". */
public data class FieldReportDestination(val label: String, val visibility: FieldReportDestinationVisibility)

/**
 * FR-OBS-9's three opt-in categories (D37/D38), mirrored here from `:app`'s own
 * `org.ort.app.fieldreport.bundle.FieldReportGatedCategory` — `:core` may not depend on `:app`
 * (`ModuleGraph`; constitution VII), and that enum lives in a package this round's file-ownership
 * map puts out of reach in any case, so the two closed, three-valued enums are kept in exact 1:1
 * correspondence by the mapping `:app`'s own field-report wiring performs at its one call site
 * (`SettingsContent.kt`), rather than by a shared type across a forbidden edge.
 */
public enum class FieldReportUploadCategory {
    RETAINED_AUDIO,
    VOICEPRINT_EMBEDDINGS,
    SCREEN_FRAMES,
}

/**
 * One field-report upload attempt (FR-OBS-9, FR-OBS-11).
 *
 * [captureActive] is FR-OBS-11's "never during capture", carried across the module boundary as
 * plain data: `:net` cannot see `:pipeline`'s `CaptureState` (`ModuleGraph` forbids that edge — only
 * `:app` may depend on both), so the caller states the fact and [FieldReportUploadClient.upload]
 * refuses whenever it is `true`. This is enforced **by type** only in the narrow sense that the
 * parameter has no default, so every call site must explicitly decide and state a value — it is
 * enforced **by discipline** in the sense that nothing here stops a caller from passing a stale or
 * wrong answer. WPR3's report names the one real caller in this codebase
 * (`SettingsContent.kt`'s field-report wiring) and how it reads
 * `org.ort.pipeline.capture.CaptureState.isCapturing` fresh at the moment of send.
 *
 * [deviceLabel], [buildLabel] and [commitLabel] are plain, caller-supplied strings so the real
 * `:net` client never has to reach into `android.os.Build`/`BuildConfig` itself — the issue body's
 * "names the device, the build, the commit" (WPR3 brief) is composed entirely from what the caller
 * already knows, never gathered independently by the module that is not supposed to know what an
 * Android device is (constitution VII: `:core`/`:net` boundaries are structural).
 */
public data class FieldReportUploadRequest(
    val bundle: ByteArray,
    val fileName: String,
    val categoriesIncluded: Set<FieldReportUploadCategory>,
    val captureActive: Boolean,
    val deviceLabel: String,
    val buildLabel: String,
    val commitLabel: String,
)

/**
 * A closed failure vocabulary (constitution II: "assertions MUST NOT depend on prose") — a test or
 * caller reads [FieldReportUploadResult.Failure.reason], never
 * [FieldReportUploadResult.Failure.detail], which is operator-facing text only and MUST NOT be
 * asserted against (it may echo the destination's own HTTP status text, which — like an exception
 * message — differs by server and by day).
 */
public enum class FieldReportUploadFailureReason {
    /** No token configured in this build (FR-OBS-12) — an honest "not configured", never a silent no-op. */
    NOT_CONFIGURED,

    /** FR-OBS-11: refused because capture was active at the moment of send. */
    CAPTURE_ACTIVE,

    /** The destination could not be reached or prepared (its visibility, or its release). */
    DESTINATION_UNREACHABLE,

    /** The destination reached, but rejected the bundle upload outright (a non-2xx response). */
    UPLOAD_FAILED,

    /** The bundle upload did not complete — the connection failed while writing. */
    PARTIAL_WRITE,

    /** The bundle uploaded, but the destination rejected the issue that should have linked it. */
    ISSUE_CREATE_FAILED,
}

public sealed interface FieldReportUploadResult {
    public data class Success(val issueUrl: String) : FieldReportUploadResult
    public data class Failure(val reason: FieldReportUploadFailureReason, val detail: String) : FieldReportUploadResult
}

/**
 * FR-OBS-11: "The field-report client SHALL live entirely in the network module" — this contract
 * itself lives in `:core` (no Android dependency, constitution VII), the one place both `:net` (the
 * real implementation — the only module permitted an HTTP client, constitution V) and `:app` (the
 * one caller) can see without either gaining a forbidden edge on the other. WPR2 declared this
 * interface in `:app`'s own package and flagged the contradiction rather than guessing past it
 * (that file's own doc comment named two resolutions); this is resolution (b).
 *
 * [destination] is asked fresh before every upload — never cached, matching FR-OBS-9's "per-
 * upload, never inferred from a previous upload's choice" — and returns `null` exactly when no real
 * destination is configured in this build (FR-OBS-12: no token), the same honest "not available"
 * state `SettingsContributeScreen.kt` already renders for its own not-yet-built upload client,
 * never a fabricated placeholder repository.
 */
public interface FieldReportUploadClient {
    public suspend fun destination(): FieldReportDestination?
    public suspend fun upload(request: FieldReportUploadRequest): FieldReportUploadResult
}
