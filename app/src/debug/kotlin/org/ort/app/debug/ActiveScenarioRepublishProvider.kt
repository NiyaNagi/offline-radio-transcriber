package org.ort.app.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

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
 * **R-1076 (register, validator V10 follow-up)**: re-publishing used to mean calling
 * [Scenarios.load] again — but `load`'s own `clearPriorScenarioData` + every scenario builder's own
 * `:data`/file/`SharedPreferences` writes are *seeding*, not republishing: calling it a second time
 * at a debug process restart deleted and re-inserted every row a previous incarnation of this same
 * process (or the operator, or a validator) had already changed since the first load, silently
 * discarding it — although this class's own kdoc claimed all along that only in-memory facets were
 * being republished. [Scenarios.republish] is the real fix: it re-applies exactly the process-wide,
 * in-memory holders [Scenarios.load] itself sets (see that function's own kdoc for the full
 * catalogue) and nothing else — never a database write, a file write, or a `SharedPreferences` edit.
 * It needs no `Context` at all, unlike `load`, because every one of those holders is a plain,
 * context-free singleton — the strongest available proof that nothing here can reach `:data` or a
 * file by accident.
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
        Scenarios.republish(activeScenarioName)
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
