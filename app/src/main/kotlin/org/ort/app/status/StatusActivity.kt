package org.ort.app.status

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bannerView = TextView(this)
        stateView = TextView(this)
        elapsedView = TextView(this)
        countView = TextView(this)
        gapView = TextView(this)
        shedView = TextView(this)
        livenessView = TextView(this)
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
            },
        )
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
}
