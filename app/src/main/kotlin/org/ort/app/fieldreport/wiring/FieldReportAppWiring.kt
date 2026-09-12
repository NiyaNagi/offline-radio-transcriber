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
 * **The `HandlerThread` leak WPR1 flagged and WPR2 could only bound — now actually closed.**
 * [RealScreenFrameCapturer] started its own [android.os.HandlerThread] lazily and never quit it;
 * WPR2's own [attachWindow] mitigated rather than fixed it, since closing the thread needed a
 * `close()`/`quit()` entry point on `RealScreenFrameCapturer` itself, in the `fieldreport.recorder`
 * package, outside that round's own file-ownership map. This round owns both packages: that class
 * now exposes [RealScreenFrameCapturer.close], and [detachWindow] below calls it on whichever
 * capturer was actually attached, *before* discarding the reference — the same "close the resource
 * you are about to drop, not just stop pointing at it" fix `Closeable.use` exists to make
 * automatic elsewhere in this codebase. [attachWindow] still constructs at most **one**
 * [RealScreenFrameCapturer] per `Activity` *instance* (unchanged from WPR2 — called once from
 * `onCreate`, never once per destination change/navigation), so even the *rate* of construction
 * stays bounded the way WPR2 left it; what changes here is that each one constructed is now also
 * genuinely torn down.
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

    /**
     * Called from every `Activity` that [attachWindow] attached — [org.ort.app.ui.ReaderActivity
     * .onDestroy] and, this round, [org.ort.app.ui.setup.SetupActivity]'s own frame wiring — stops
     * routing future captures to a `Window` that is about to go away, and now (see this object's
     * own doc comment) actually quits the underlying [RealScreenFrameCapturer]'s
     * [android.os.HandlerThread] first, rather than merely discarding the reference to it. The
     * `as?` is a plain type check, never a cast that can throw: [delegatingCapturer]'s own
     * `delegate` is `null` for a capturer never attached at all (this function's own no-op-safe
     * case) and, in every real caller, always a [RealScreenFrameCapturer] — the check exists so a
     * future, differently-shaped [org.ort.app.fieldreport.recorder.ScreenFrameCapturer] (a test
     * fake, say) is never assumed to carry a [RealScreenFrameCapturer.close] it does not have.
     */
    public fun detachWindow() {
        (delegatingCapturer.delegate as? RealScreenFrameCapturer)?.close()
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
