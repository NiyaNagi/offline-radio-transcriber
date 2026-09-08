package org.ort.app.transmissions

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.runBlocking
import org.ort.core.Attribution
import org.ort.data.OrtDatabase

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

        // v0 smoke-test wiring only — see RealCaptureService's doc comment. Rows show
        // "not yet transcribed" for every captured transmission because no ASR pass is wired
        // into production capture yet (P10/P11 built :asr-*/PassB, neither is constructed here);
        // this at least proves real audio is really reaching :data as real rows. Not exercised
        // by TransmissionListActivityTest, which never sets this extra.
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        if (sessionId != null) {
            val db = OrtDatabase.create(applicationContext)
            val rows = runBlocking {
                db.transmissionDao().listBySession(sessionId).map { entity ->
                    TransmissionRow(
                        id = entity.id,
                        transcript = "(captured, not yet transcribed)",
                        attribution = Attribution.unknown(),
                    )
                }
            }
            render(rows)
        }
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

    public companion object {
        public const val EXTRA_SESSION_ID: String = "session_id"
    }
}
