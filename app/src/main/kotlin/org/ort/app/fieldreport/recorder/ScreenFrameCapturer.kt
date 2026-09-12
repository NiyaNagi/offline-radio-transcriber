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
