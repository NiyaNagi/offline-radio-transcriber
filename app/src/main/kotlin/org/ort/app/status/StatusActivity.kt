package org.ort.app.status

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.runBlocking
import org.ort.capture.android.heartbeat.FileHeartbeatStore
import org.ort.core.SystemClock
import org.ort.data.OrtDatabase
import org.ort.pipeline.CaptureStatusRepository
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.VadAvailability
import org.ort.pipeline.shed.ShedController
import org.ort.pipeline.shed.ShedSignals
import java.io.File

/**
 * The capture status surface (FR-UI-7, FR-PLT-1 — build-plan P8): capture state, elapsed time,
 * transmission count, gap count, shed level and liveness always one tap away, plus the
 * unclean-end banner (AC-5). Deliberately plain Android views, not Compose — see
 * `app/build.gradle.kts` for why. [render] is separated from [onCreate] so the mapping from a
 * [StatusViewState] to on-screen text is testable without an Activity at all.
 */
public class StatusActivity : Activity() {

    // internal, not private: StatusActivityTest asserts on rendered content directly rather than
    // walking the decor view hierarchy, which Robolectric wraps differently release to release.
    internal lateinit var bannerView: TextView
    internal lateinit var stateView: TextView
    internal lateinit var elapsedView: TextView
    internal lateinit var countView: TextView
    internal lateinit var gapView: TextView
    internal lateinit var shedView: TextView
    internal lateinit var livenessView: TextView
    internal lateinit var asrView: TextView
    internal lateinit var vadView: TextView

    private val pollHandler = Handler(Looper.getMainLooper())
    private var polling = false
    private var startedAtWallMillis = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bannerView = TextView(this)
        stateView = TextView(this)
        elapsedView = TextView(this)
        countView = TextView(this)
        gapView = TextView(this)
        shedView = TextView(this)
        livenessView = TextView(this)
        asrView = TextView(this)
        vadView = TextView(this)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(bannerView)
                addView(stateView)
                addView(elapsedView)
                addView(countView)
                addView(gapView)
                addView(shedView)
                addView(livenessView)
                addView(asrView)
                addView(vadView)
            },
        )

        // v0 smoke-test wiring only (see RealCaptureService's doc comment) — a real reader UI
        // (M5) would observe this via Flow, not poll. Not exercised by StatusActivityTest, which
        // never sets this extra, so existing Robolectric coverage of render()/onCreate is
        // untouched (Robolectric.buildActivity(...).create() gets a plain, extra-less Intent).
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        if (sessionId != null) {
            startedAtWallMillis = SystemClock.wallMillis()
            polling = true
            startPolling(sessionId)
            (bannerView.parent as LinearLayout).addView(
                Button(this).apply {
                    text = "View transmissions"
                    setOnClickListener {
                        startActivity(
                            Intent(this@StatusActivity, org.ort.app.transmissions.TransmissionListActivity::class.java)
                                .putExtra(
                                    org.ort.app.transmissions.TransmissionListActivity.EXTRA_SESSION_ID,
                                    sessionId,
                                ),
                        )
                    }
                },
            )
        }
    }

    override fun onDestroy() {
        polling = false
        super.onDestroy()
    }

    private fun startPolling(sessionId: String) {
        val db = OrtDatabase.create(applicationContext)
        val heartbeatStore = FileHeartbeatStore(File(filesDir, "heartbeat.txt"))
        // Neutral, always-nominal signals: no real shed telemetry is wired for this smoke test,
        // so the level always reads 0 rather than fabricating a number for one of ShedController's
        // real inputs (battery/backlog).
        val shedController = ShedController(
            object : ShedSignals {
                override fun batteryPercent(): Int = 100
                override fun isCharging(): Boolean = true
                override fun queueBacklog(): Int = 0
                override fun freeStorageBytes(): Long = Long.MAX_VALUE
            },
            SystemClock,
        )
        val repository = CaptureStatusRepository(heartbeatStore, shedController, SystemClock)
        val uncleanEnd = repository.uncleanEndFromPreviousLaunch()

        val poll = object : Runnable {
            override fun run() {
                if (!polling) return
                val (count, gaps) = runBlocking {
                    db.transmissionDao().listBySession(sessionId).size to
                        db.captureGapDao().listBySession(sessionId).size
                }
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                // Real state, never an optimistic constant: the v0 version hardcoded `true` here,
                // so a halted capture still read "Capturing" — the silent failure constitution IV
                // forbids, in the screen whose job is to report it. Found on a real device.
                val status = repository.current(
                    sessionId = sessionId,
                    isCapturing = CaptureState.isCapturing,
                    elapsedMillis = SystemClock.wallMillis() - startedAtWallMillis,
                    transmissionCount = count,
                    gapCount = gaps,
                    isIgnoringBatteryOptimizationsDiagnosticOnly = pm.isIgnoringBatteryOptimizations(packageName),
                ).let { if (uncleanEnd != null) it.copy(uncleanEndFromPreviousLaunch = uncleanEnd) else it }
                render(StatusViewStateMapper.from(status))
                // A stopped capture must say *why*, not just stop saying "Capturing".
                CaptureState.failureReason?.let { stateView.text = "${stateView.text} — $it" }
                // Capture succeeding and ASR being available are independent facts (build-plan
                // P12) -- never let this screen look like transcription is working when it isn't.
                asrView.text = AsrAvailability.statusLabel
                vadView.text = VadAvailability.statusLabel
                pollHandler.postDelayed(this, POLL_INTERVAL_MILLIS)
            }
        }
        pollHandler.post(poll)
    }

    /** Renders [state] into the views. Pure with respect to [StatusViewStateMapper] — no logic here. */
    public fun render(state: StatusViewState) {
        bannerView.text = state.uncleanEndBanner ?: ""
        bannerView.visibility =
            if (state.uncleanEndBanner == null) android.view.View.GONE else android.view.View.VISIBLE
        stateView.text = state.stateLabel
        elapsedView.text = state.elapsedLabel
        countView.text = "${state.transmissionCount} transmissions"
        gapView.text = "${state.gapCount} gaps"
        shedView.text = state.shedLevelLabel
        livenessView.text = state.livenessLabel
    }

    public companion object {
        public const val EXTRA_SESSION_ID: String = "session_id"
        private const val POLL_INTERVAL_MILLIS: Long = 2_000
    }
}
