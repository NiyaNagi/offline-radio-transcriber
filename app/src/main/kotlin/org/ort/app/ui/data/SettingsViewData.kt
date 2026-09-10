package org.ort.app.ui.data

import android.content.Context
import org.ort.core.capture.CaptureMode
import org.ort.data.OrtDatabase
import org.ort.pipeline.capture.CaptureState

/**
 * WPE (`spec/e2e-capture-modes-plan.md`, FR-CAP-12, FR-CAP-13, AC-131): the seam CF02/CF11 read the
 * capture mode through, so this package never depends on `WPC2`'s still-in-flight
 * `CaptureConfigurationStore`/`pendingConfiguration` directly. Until the lead messages this
 * package's exact shape, [RealCaptureModeFacts] reads the best currently-available facts from the
 * **one source that exists on main today**: the most recent [org.ort.data.entity.SessionEntity]'s
 * own `captureMode` column (WPC1, schema v7, closed at `50bfc07`) — the running session's own
 * recorded mode while [CaptureState.isCapturing], or the last-completed session's mode otherwise
 * (the best honest guess at what the *next* session will start with, absent a real "pending mode"
 * store) — plus [CaptureState.isCapturing] itself for the live-session banner.
 *
 * A fresh install with no session yet reports [currentMode] `null` — never a fabricated default
 * (constitution I). Once WPC2's `CaptureConfigurationStore` lands, swapping [RealCaptureModeFacts]'s
 * body for a read of its real `pendingConfiguration` is a one-file change: every caller in this
 * package depends on the [CaptureModeFacts] interface, never on how it is implemented.
 */
public interface CaptureModeFacts {
    /** The mode governing the live session, or — while idle — the most recently recorded mode
     * (the best honest information available about what the *next* session will start with).
     * `null` only when no session has ever recorded one at all. */
    public suspend fun currentMode(): CaptureMode?

    /** True exactly while a session is live — drives CF11's "applies when it ends" banner. */
    public fun isSessionLive(): Boolean
}

/** The real [CaptureModeFacts] — see the interface's own doc comment for exactly what it reads and why. */
public class RealCaptureModeFacts(private val context: Context) : CaptureModeFacts {

    override suspend fun currentMode(): CaptureMode? {
        val db = OrtDatabase.create(context.applicationContext)
        val liveSessionId = CaptureState.sessionId.takeIf { CaptureState.isCapturing }
        val info = liveSessionId?.let { db.sessionDao().getCaptureInfo(it) }
            ?: db.sessionDao().listAll().firstOrNull()?.let { db.sessionDao().getCaptureInfo(it.id) }
        val name = info?.captureMode ?: return null
        return CaptureMode.entries.firstOrNull { it.name == name }
    }

    override fun isSessionLive(): Boolean = CaptureState.isCapturing
}

/** The behavioural fake (constitution II) — a plain, settable [CaptureModeFacts] for tests. */
public class FakeCaptureModeFacts(
    private var mode: CaptureMode? = null,
    private var sessionLive: Boolean = false,
) : CaptureModeFacts {
    override suspend fun currentMode(): CaptureMode? = mode
    override fun isSessionLive(): Boolean = sessionLive

    public fun setMode(value: CaptureMode?) {
        mode = value
    }

    public fun setSessionLive(value: Boolean) {
        sessionLive = value
    }
}
