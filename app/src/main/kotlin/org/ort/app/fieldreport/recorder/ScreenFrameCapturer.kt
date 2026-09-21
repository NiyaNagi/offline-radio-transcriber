package org.ort.app.fieldreport.recorder

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.Window
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * FR-OBS-7: captures one downscaled screen frame, pixels only — "no OCR pass, no separate
 * transcript of what it shows". [capture] never throws; a failed or unsupported capture is `null`,
 * a legitimate outcome (constitution I: uncertainty is content, not an exception to route around).
 */
public interface ScreenFrameCapturer {
    /** Returns already-downscaled, already-encoded image bytes, or `null` if capture failed or is
     * unsupported on this device/window state. */
    public suspend fun capture(): ByteArray?
}

/**
 * D55/R-1128/FR-OBS-7: which [RecorderDestination]s are **cleared** for field-report frame capture.
 *
 * **R-1128: the first version of this predicate named only [RecorderDestination.STATION_DETAIL],
 * and it missed the stations *list*** — `StationsAndFrequencies.kt`'s `StationViewMapper.listEntry`
 * copies `StationEntity.userName` onto `StationListEntryViewState.givenName`, and `StationScreen
 * .kt`'s `StationRow` (`:172`, `:198`, `:220`) draws it as visible text and semantics on every row
 * of the `STATIONS` destination. That is exactly the failure a destination-level allowlist can
 * produce when the allowlist is a guess rather than a derivation: this file's own prior version
 * said so honestly ("a destination-level allowlist cannot see a name rendered somewhere it does not
 * name") and the lead found the somewhere-else the very next read. **The fix is not adding
 * `STATIONS` to a list of one** — there are 14 destinations and no reason to believe a second guess
 * fares better than the first. It is deriving the list from where the data actually flows, and
 * writing the derivation down so the next screen can be checked against it, not guessed at.
 *
 * **THE DERIVATION.** Constitution V names three things that leave the device in no channel and no
 * tier at all: user-supplied names (FR-SPK-25), station knowledge (FR-DIG-13), and location finer
 * than a grid square (FR-LEX-24). Working forward from [org.ort.data.entity.StationEntity]'s own
 * fields (the one place these are declared, `data/src/main/kotlin/org/ort/data/entity
 * /CatalogEntities.kt`) to every view state that copies one, and from each view state to every
 * `Content`/`Screen` composable that renders it — one line per field below, read as
 * *field (constitution clause) -> the view state it is carried into -> where that view state is
 * rendered*:
 *
 * - `StationEntity.userName` (FR-SPK-25) -> `StationListEntryViewState.givenName` -> `STATIONS`
 *   (`StationScreen.StationRow` draws it as visible text and in its `contentDescription`, at
 *   `:172`, `:198` and `:220`).
 * - `StationEntity.userName` / `.notes` (FR-SPK-25 / FR-DIG-13) -> `StationGivenByYouViewState`,
 *   `StationIdentityViewState.givenByYou` -> `STATION_DETAIL` (`StationIdentityScreen`'s own
 *   "Given by you" fields).
 * - `StationEntity.userName` (FR-SPK-25) -> `StationSearchRow.userName` -> `TRANSMISSION_DETAIL`
 *   (`CorrectionSheet`'s station-search rows — see the note on this destination below).
 * - `StationEntity.ituRegionFromPrefix` (FR-DIG-7, FR-DIG-13) ->
 *   `StationIdentityViewState.lexiconLabel` -> `STATION_DETAIL`.
 * - `StationEntity.{frequenciesHeard, activityByHourDow, potaRefs, spokenGrids,
 *   overCountsByAttributionState}` (FR-DIG-7, FR-DIG-13) -> **no view state found that carries
 *   any of them** -> not rendered on any screen today. Verified by searching every consumer of
 *   `StationEntity` and every `StationPolling`/`StationsAndFrequencies` view-state mapper;
 *   `ShareCoordinator`'s own doc comment independently confirms these five are excluded from the
 *   *contribution* payload for the identical reason.
 *
 * That derivation makes exactly three destinations unsafe: `STATIONS`, `STATION_DETAIL`, and
 * `TRANSMISSION_DETAIL`. Every other destination was checked against the same table and carries
 * none of these fields in any view state it renders — `NOW`'s own `NowStationRow` carries only
 * `stationId`/`callsign`, never `userName`; `LOG`/`TRANSMISSION_DETAIL`'s (base screen)
 * `TransmissionListViewState.stationLabel` is `"Station: $stationId"`, the public identifier, not
 * the nickname; `THREAD_DETAIL`'s `ThreadViewData` reasons about attribution and callsigns only.
 *
 * **A note on `TRANSMISSION_DETAIL`.** `CorrectionSheet` is composed *inside*
 * `TransmissionDetailContent.kt`, not a separate nav route — opening it does not fire a new
 * `onDestinationChanged` (capture is keyed on [RecorderDestination], not on in-screen sheet state),
 * so today's one capture-per-arrival happens to land before the sheet can be open. **This predicate
 * does not rely on that timing as the safety mechanism.** The derivation's own question is "can this
 * destination ever render the field while it is current", not "does today's one capture call happen
 * to race ahead of it" — a later change that captures more than once per destination (on back, on a
 * timer, on any other trigger) must not silently start leaking a sheet's contents just because the
 * original implementation never gave it the chance. `TRANSMISSION_DETAIL` is excluded on the
 * derivation's own terms.
 *
 * **A note on a corrected callsign.** Deliberately **not** treated as station knowledge here.
 * FR-DIG-13 scopes "station knowledge" to FR-DIG-7's own closed field list (the five aggregate,
 * behavioural facts in the table above, plus first/last-heard) — an accumulating dossier, which is
 * the constitution's own stated reason for the rule. A callsign is the opposite of a dossier entry:
 * it is the public identifier this entire product exists to record, already rendered on every
 * reading screen the app has (`NOW`, `LOG`, `SEARCH`, every list and detail screen), and the
 * resolver would have written the identical value from a candidate the operator merely confirmed.
 * Treating a corrected callsign as forbidden would make almost no screen capturable, defeating the
 * channel D55 exists to restore. This holds even where the operator types a callsign the resolver
 * never offered as a candidate — the *act* of correcting is the operator's own judgement, but the
 * *value* recorded is the same public identifier a resolved candidate would also have carried, not
 * a private fact about the station. Argued on the record because the alternative reading is not
 * absurd — revisit this note first if a future correction UI ever attaches free-text operator
 * commentary to a correction, which it does not today.
 *
 * **The direction is fail-closed, inverted from this predicate's first version (R-1128's second
 * finding).** [CLEARED_FOR_CAPTURE] is an allowlist, not a deny-list: a [RecorderDestination] this
 * file does not name is **not capturable**, the same shape
 * [org.ort.net.fieldreport.real.RealFieldReportUploadClient]'s own visibility guard already uses
 * (an unreadable destination is `UNKNOWN`, refused, never assumed `PRIVATE`). Before this fix, an
 * unnamed destination was capturable by default, which is exactly how `STATIONS` leaked — the
 * predicate was right about every destination it had considered and silent about the rest, and
 * silence read as permission. The real cost of the inversion: **a brand-new destination captures
 * nothing at all until a person adds it to [CLEARED_FOR_CAPTURE] and updates the table above** —
 * this is a genuine reduction in the field-report channel's day-one usefulness for a new screen,
 * not a free fix. It is accepted deliberately: constitution V is absolute here, a breach is
 * unrecoverable the moment a frame is captured (there is no "delete it from the upload" once a
 * bundle has shipped), and the alternative direction has now produced one proven leak. A screen a
 * builder forgets to clear is a missing feature; a screen nobody remembered to forbid is a breach.
 */
public fun RecorderDestination.mayRenderUserSuppliedContent(): Boolean = this !in CLEARED_FOR_CAPTURE

/** See [RecorderDestination.mayRenderUserSuppliedContent]'s own doc comment for the derivation this
 * set is built from, and for why its *absence* — not presence — is what makes a destination unsafe. */
private val CLEARED_FOR_CAPTURE: Set<RecorderDestination> = setOf(
    RecorderDestination.NOW,
    RecorderDestination.LOG,
    RecorderDestination.SEARCH,
    RecorderDestination.THREADS,
    RecorderDestination.THREAD_DETAIL,
    RecorderDestination.FREQUENCIES,
    RecorderDestination.FREQUENCY_DETAIL,
    RecorderDestination.EARLIER_NIGHTS,
    RecorderDestination.CAPTURE,
    RecorderDestination.IMPROVE_RECORDS,
    RecorderDestination.SETTINGS,
)

/**
 * The real, device-touching [ScreenFrameCapturer]: [PixelCopy] against [window], downscaled to
 * roughly the artboard's own width (~390 px, `design/design-guide.md` — [targetWidthPx]), encoded
 * as PNG.
 *
 * **Why `PixelCopy` and not `View.draw(Canvas)` into a `Bitmap`.** The latter is the more common
 * Compose-testing shortcut, but it only ever replays what that `View`'s own drawing code would
 * paint into an arbitrary `Canvas` — it does not read the actual composited framebuffer, so it
 * misses anything the platform composites on top outside that draw call (most importantly, this
 * app's own inset/status-bar treatment and any hardware-accelerated overlay). `PixelCopy` copies
 * the real, already-composited window surface, which is what actually answers "what did the
 * operator's eye see" — the same question a comparison against an artboard (constitution VIII)
 * exists to answer, and the reason FR-OBS-7 names frames as a device-truth capture rather than a
 * re-render. It also matches the technique `tools/ui-audit/`'s own capture tooling already uses
 * for exactly the same reason.
 *
 * A dedicated [HandlerThread] backs the callback because `PixelCopy.request` requires a [Handler]
 * whose `Looper` is not the one about to be blocked awaiting the result — this class owns and
 * lazily starts that thread rather than assuming the caller's own looper is free, since
 * [ScreenFrameCapturer.capture] is called from [FieldReportRecorder]'s own capture scope, not from
 * the main thread.
 */
public class RealScreenFrameCapturer(private val window: Window, private val targetWidthPx: Int = TARGET_WIDTH_PX) :
    ScreenFrameCapturer {

    // WPW (register, WPR1/WPR2's own follow-up): a plain nullable field, not `by lazy` — `close()`
    // must be able to tell "never started" (a legitimate no-op: a capturer constructed but never
    // asked to capture) apart from "started, now stopping" without forcing the thread to start just
    // to quit it. `by lazy` has no public "was this ever initialized" check reachable without
    // reflection; this get-and-cache accessor does the identical lazy-start `capture()` already
    // relied on, while leaving [close] a simple, honest null-check.
    private var handlerThreadOrNull: HandlerThread? = null
    private val handlerThread: HandlerThread
        get() = handlerThreadOrNull ?: HandlerThread("field-report-frame-capture").also {
            it.start()
            handlerThreadOrNull = it
        }
    private val handler by lazy { Handler(handlerThread.looper) }

    /**
     * WPW (register, WPR1 flagged the leak, WPR2 bounded but could not fix it — see
     * `FieldReportAppWiring`'s own doc comment for why this needed a seam in this class, not a
     * workaround in that one). Quits the [HandlerThread] this instance started, if any — a legitimate
     * no-op when [capture] was never called (nothing was ever started). [HandlerThread.quitSafely]
     * (not `quit()`): lets any [PixelCopy] callback already queued on this thread's `Looper` finish
     * delivering its result to the `suspendCancellableCoroutine` awaiting it in [capture], rather
     * than dropping a callback mid-flight — this instance is being discarded either way, but a
     * dropped `PixelCopy` callback would otherwise leak that coroutine, suspended forever.
     */
    public fun close() {
        handlerThreadOrNull?.quitSafely()
        handlerThreadOrNull = null
    }

    /** Test-only window into [handlerThreadOrNull] — lets a test see whether [close] actually has
     * something to quit without needing a real, laid-out [Window] whose decor view reports
     * non-zero dimensions (which [capture] itself requires before it ever touches [handlerThread]).
     * Production code never reads this. */
    internal val handlerThreadForTest: HandlerThread? get() = handlerThreadOrNull

    /** Test-only seam: forces [handlerThread] to actually start, through the identical lazy-start
     * accessor [capture] itself uses — see [handlerThreadForTest]'s own doc comment for why a test
     * needs this rather than driving a real [capture] call. Production code never calls this. */
    internal fun startHandlerThreadForTest(): HandlerThread = handlerThread

    override suspend fun capture(): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val decorView = window.decorView
        val width = decorView.width
        val height = decorView.height
        if (width <= 0 || height <= 0) return null

        val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val copied = suspendCancellableCoroutine { continuation ->
            try {
                PixelCopy.request(
                    window,
                    full,
                    { result ->
                        continuation.resume(result == PixelCopy.SUCCESS)
                    },
                    handler,
                )
            } catch (_: IllegalArgumentException) {
                // Window not attached / not visible — a legitimate "cannot capture right now".
                continuation.resume(false)
            }
        }
        if (!copied) return null

        val scaledHeight = (height.toLong() * targetWidthPx / width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(full, targetWidthPx, scaledHeight, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        return out.toByteArray()
    }

    private companion object {
        /** FR-OBS-7: "roughly the artboard's own width (~390 px, design/design-guide.md)". */
        const val TARGET_WIDTH_PX = 390
        const val PNG_QUALITY = 100
    }
}

/**
 * Constitution II's behavioural fake: "a fake that cannot be told to fail, hang or return a
 * hallucination is a stub, and stubs test nothing that matters". This one can do all three —
 * [FakeScreenFrameCapturer.Mode.Success] returns fixed bytes instantly, [Mode.Failure] returns
 * `null` instantly (a legitimate capture failure, not an exception), and [Mode.Hang] suspends
 * forever so a caller's own timeout/cancellation handling can be exercised.
 */
public class FakeScreenFrameCapturer(@Volatile public var mode: Mode = Mode.Success(byteArrayOf(1, 2, 3))) :
    ScreenFrameCapturer {

    public sealed interface Mode {
        public data class Success(val bytes: ByteArray) : Mode
        public data object Failure : Mode
        public data object Hang : Mode
    }

    /** How many times [capture] has been called — lets a test assert a capture was attempted
     * without needing to inspect [FrameStore] at all. */
    public var callCount: Int = 0
        private set

    override suspend fun capture(): ByteArray? {
        callCount++
        return when (val current = mode) {
            is Mode.Success -> current.bytes
            is Mode.Failure -> null
            is Mode.Hang -> suspendCancellableCoroutine { }
        }
    }
}
