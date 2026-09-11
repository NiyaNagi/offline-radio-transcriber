package org.ort.app.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import kotlinx.coroutines.runBlocking

/**
 * R-873 (register, validator V10): a debug process start (a `force-stop` + relaunch a validator or
 * the screenshot tour driver can trigger at any point mid-session) leaves every process-wide capture
 * holder ([org.ort.pipeline.capture.CaptureState], [org.ort.pipeline.capture.InputStatus],
 * [org.ort.pipeline.capture.RigStatus], [org.ort.pipeline.capture.LevelStatus], the rig-link state
 * where a scenario seeded one) back at its own honest "not started" default — [Scenarios.load]'s own
 * `resetProcessWideFacets` doc comment explains why that reset exists in the first place — while the
 * session row and the fresh heartbeat a live scenario seeds both persist untouched in the real,
 * on-disk database (`OrtDatabase`/`FileHeartbeatStore`, neither process-scoped). V10 root-caused
 * R-853 to exactly this mismatch: a state production genuinely cannot produce on its own (a real
 * capture service is a live, continuously-running process; only a *debug* relaunch can lose these
 * holders while every DB-backed fact survives), so no product-side fix applies — this is this
 * package's own gap to close, not `main`'s.
 *
 * A `ContentProvider`, not a change to the real `OrtApplication` in `main` (outside this package's
 * own file row, and unnecessary besides — see below): AGP merges this manifest into the debug
 * variant only, and a manifest-registered `<provider>` runs its own `onCreate()` at process start,
 * before any `Activity` — the identical mechanism WorkManager's and Firebase's own auto-init
 * providers use for the same reason (library code that cannot insert itself into an app's own
 * `Application.onCreate()`).
 *
 * Re-publishing is nothing more than calling [Scenarios.load] again with whichever name
 * [ActiveScenarioMarker] last recorded: `load`'s own `clearPriorScenarioData` + upsert writes are
 * already idempotent by construction (every tour/validator session this round reloads scenarios
 * repeatedly without a reinstall in between), so doing it once more at process start is safe and
 * produces exactly the same holders a fresh load would.
 *
 * [runBlocking] on the main thread inside [onCreate] is a deliberate, debug-only trade-off: this
 * provider's whole reason to exist is making the *next* screen a validator or the tour opens render
 * correctly, immediately, so the re-seed must be complete before that happens, not merely started —
 * a fire-and-forget coroutine would race the very next `Activity.onCreate()` this restart is for.
 * The one-time cost (the same single scenario load a real one already takes, well under a second in
 * every scenario this round measured) is the honest price of correctness here, not an oversight.
 */
public class ActiveScenarioRepublishProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return true
        val activeScenarioName = ActiveScenarioMarker.read(appContext) ?: return true
        // A marker naming a scenario [Scenarios.NAMES] no longer recognises (a stale marker from a
        // build this device has since moved past) must never crash the app at every single launch —
        // silently doing nothing is the honest response; the marker only ever names something real
        // to begin with because [Scenarios.load] itself validates it before ever writing one.
        if (activeScenarioName !in Scenarios.NAMES) return true
        runBlocking { Scenarios.load(appContext, activeScenarioName) }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
