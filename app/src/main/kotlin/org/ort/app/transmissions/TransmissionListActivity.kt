package org.ort.app.transmissions

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The minimal transmission-list reader surface (build-plan P11): this is the first point in the
 * project where an [org.ort.core.Attribution] reaches a screen. A full reader — live view,
 * thread grouping, playback, correction, the lattice inspection surface — is M5 (build-plan P11
 * is explicit that a full reader is out of scope here). Deliberately plain Android views, same
 * choice [org.ort.app.status.StatusActivity] made and for the same reason.
 */
public class TransmissionListActivity : Activity() {

    internal lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(listContainer)
    }

    /** Renders [rows] through [TransmissionListViewStateMapper] — no attribution logic here. */
    public fun render(rows: List<TransmissionRow>) {
        listContainer.removeAllViews()
        rows.forEach { row ->
            val state = TransmissionListViewStateMapper.from(row)
            listContainer.addView(
                TextView(this).apply {
                    text = buildString {
                        append(state.attributionLabel)
                        state.stationLabel?.let { append("  ").append(it) }
                        if (state.transcript.isNotBlank()) append("  — ").append(state.transcript)
                    }
                },
            )
        }
    }

    public fun rowCount(): Int = listContainer.childCount

    public fun renderedRowText(): List<String> =
        (0 until listContainer.childCount).map { (listContainer.getChildAt(it) as TextView).text.toString() }
}
