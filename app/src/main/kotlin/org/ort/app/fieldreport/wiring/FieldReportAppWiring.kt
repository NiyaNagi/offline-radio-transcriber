package org.ort.app.fieldreport.wiring

import android.view.Window
import org.ort.app.BuildConfig
import org.ort.app.fieldreport.recorder.FieldReportRecorder
import org.ort.app.fieldreport.recorder.RealScreenFrameCapturer
import org.ort.app.fieldreport.recorder.ScreenFrameCapturer
import java.io.File

/**
 * WPR2: wires [FieldReportRecorder] into a running app (FR-OBS-6/FR-OBS-7) — nothing called
 * [FieldReportRecorder.configure] before this file existed, so in a running build no
 * session-recorder log or frame was ever written, regardless of how debug-only-safe the recorder
 * itself already was.
 *
 * **[configureOnce] is called exactly once per process, from [org.ort.app.OrtApplication.onCreate].**
 * Not from an `Activity`: [FieldReportRecorder.configure] clears the ring buffer and restarts the
 * single consumer coroutine every time it runs (that function's own doc comment: "Idempotent-safe
 * to call again: the previous consumer is cancelled first") — calling it a second time from an
 * `Activity.onCreate` (reached on every rotation, not just app cold start) would silently discard
 * every event recorded before that point, for a debug tool whose entire purpose is not losing
 * field evidence. Calling it once, at the process root, means the ring buffer spans the whole
 * process lifetime `Application.onCreate` already anchors everything else in this app to.
 *
 * **The frame capturer is wired the other way round, without a second [FieldReportRecorder.configure]
 * call.** [attachWindow]/[detachWindow] repoint [delegatingCapturer]'s own mutable
 * [DelegatingScreenFrameCapturer.delegate] field; neither ever touches [FieldReportRecorder]'s
 * configuration again after [configureOnce]. [delegatingCapturer] is handed to
 * [FieldReportRecorder.configure] once, up front, before any real `Window` exists — until an
 * activity attaches one, it simply returns `null`, [ScreenFrameCapturer.capture]'s own legitimate
 * "cannot capture right now" contract, never a thrown exception.
 *
 * **Debug builds only** ([BuildConfig.DEBUG]), matching FR-OBS-6's "absent from release builds" —
 * checked here, at the call site, in addition to (never instead of) [FieldReportRecorder.configure]'s
 * own internal debug-build gate, so a release build never even constructs the `field-report`
 * directory [File] this object would otherwise build.
 *
 * **The known leak this round could not close, said plainly rather than left silent.**
 * [RealScreenFrameCapturer] starts its own [android.os.HandlerThread] lazily and never quits it —
 * flagged by WPR1's own report, confirmed again here by reading that class before writing this.
 * Closing it needs a `close()`/`quit()` entry point on `RealScreenFrameCapturer` itself, which
 * lives in the `fieldreport.recorder` package — outside this round's own file-ownership map ("consume
 * WPR1's contract, do not alter it"). [attachWindow] mitigates rather than fixes: it constructs at
 * most **one** [RealScreenFrameCapturer] per `Activity` *instance* (called once from `onCreate`,
 * never once per destination change/navigation, which [FieldReportRecorder.onDestinationChanged]
 * alone would otherwise imply for every screen the operator visits), so the leak's rate is bounded
 * by activity recreations — a device rotation, a process-death restore — not by in-app
 * navigation. Reported to the lead as follow-up work for whichever session next owns
 * the `fieldreport.recorder` package: expose a `close()` on [RealScreenFrameCapturer] and call it
 * from [detachWindow].
 */
public object FieldReportAppWiring {

    /** [org.ort.app.OrtApplication]'s own `filesDir`-shaped seam — a plain [File] rather than a
     * `Context`, so this stays trivially testable with a temp directory. */
    public fun configureOnce(filesDir: File) {
        if (!BuildConfig.DEBUG) return
        FieldReportRecorder.configure(filesDir = filesDir, frameCapturer = delegatingCapturer)
    }

    /** [org.ort.app.ui.ReaderActivity.onCreate]'s own call — see this object's own doc comment for
     * why this never calls [FieldReportRecorder.configure] again. */
    public fun attachWindow(window: Window) {
        if (!BuildConfig.DEBUG) return
        delegatingCapturer.delegate = RealScreenFrameCapturer(window)
    }

    /** [org.ort.app.ui.ReaderActivity]'s own `onDestroy` — stops routing future captures to a
     * `Window` that is about to go away. Does not (cannot; see this object's own doc comment) quit
     * the underlying [RealScreenFrameCapturer]'s [android.os.HandlerThread]. */
    public fun detachWindow() {
        delegatingCapturer.delegate = null
    }

    /** Test/inspection-only seam: never reassigned in production. */
    internal val delegatingCapturer: DelegatingScreenFrameCapturer = DelegatingScreenFrameCapturer()
}

/** A [ScreenFrameCapturer] that forwards to whatever [delegate] is currently attached, or reports
 * "cannot capture right now" ([ScreenFrameCapturer.capture]'s own `null` contract, never a thrown
 * exception) when none is. Lets [FieldReportRecorder.configure] be handed a capturer once, before
 * any real `Window` exists, while [FieldReportAppWiring.attachWindow]/[FieldReportAppWiring.detachWindow]
 * repoint it afterwards without ever reconfiguring the recorder itself. */
public class DelegatingScreenFrameCapturer : ScreenFrameCapturer {
    @Volatile public var delegate: ScreenFrameCapturer? = null

    override suspend fun capture(): ByteArray? = delegate?.capture()
}
