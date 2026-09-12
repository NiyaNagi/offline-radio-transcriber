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

    private val handlerThread by lazy {
        HandlerThread("field-report-frame-capture").also { it.start() }
    }
    private val handler by lazy { Handler(handlerThread.looper) }

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
