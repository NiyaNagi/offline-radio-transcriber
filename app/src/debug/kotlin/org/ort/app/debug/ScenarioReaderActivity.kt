package org.ort.app.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import org.ort.app.ui.ReaderActivity
import org.ort.app.ui.navigation.NavSeed

/**
 * spec/ui-conformance-plan.md WP0, register R-111 — a debug-only launch alias for
 * [org.ort.app.ui.ReaderActivity], which is `android:exported="false"` in the shipped manifest
 * (register R-007) and therefore cannot be started directly by `adb shell am start` — the exact
 * command this package's own brief documents for launching the reader against a scenario's
 * session:
 *
 * ```
 * adb shell am start -n org.ort.app/.ui.ReaderActivity --es session_id <id>
 * ```
 *
 * That command fails with `SecurityException: ... not exported` against the real activity (found
 * running it, not by inspection). `ReaderActivity`'s manifest entry is `app/src/main/AndroidManifest.xml`
 * — outside this package's file ownership — so rather than widen it, this adds one debug-only
 * forwarding activity, registered `exported="true"` **only** in `app/src/debug/AndroidManifest.xml`
 * (never present in a release build, and no file under `app/src/main` is touched):
 *
 * ```
 * adb shell am start -n org.ort.app/.debug.ScenarioReaderActivity --es session_id <id>
 * ```
 *
 * Forwards the `session_id` extra unchanged and finishes immediately — this activity never
 * appears in the back stack or on screen itself.
 *
 * Round 13 (ui-conformance-plan WP3, WP12's screenshot-tour seam — a minimal, disclosed edit to
 * this otherwise WP4-owned file): also parses [NavSeed.fromIntent] from this activity's own
 * intent and, when non-null, forwards its extras onto [ReaderActivity]'s launch intent too
 * ([NavSeed.putExtras] — the exact keys [NavSeed.fromIntent] itself reads back), so
 *
 * ```
 * adb shell am start -n org.ort.app/.debug.ScenarioReaderActivity --es nav_open_transmission_id <id>
 * ```
 *
 * opens that transmission's own detail directly, without a real tap through `Log` first. Parsing
 * into a real [NavSeed] first (rather than blindly copying every extra across) means a malformed
 * or unrecognised extra value falls back to that field's own default — the same "honest until
 * wired" treatment [NavSeed.fromIntent] itself documents — instead of forwarding raw, unvalidated
 * strings `ReaderActivity` would have to re-validate itself.
 */
public class ScenarioReaderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val readerIntent = Intent(this, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_SESSION_ID, intent?.getStringExtra(ReaderActivity.EXTRA_SESSION_ID))
        intent?.let { NavSeed.fromIntent(it) }?.putExtras(readerIntent)
        startActivity(readerIntent)
        finish()
    }
}
