package org.ort.app.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * FR-EXP-7's `FileProvider`/`ACTION_SEND` wiring — the one place it lives, so every real share
 * entry point ([org.ort.app.ui.digest.DigestContent]'s digest share,
 * [org.ort.app.ui.navigation.OrtNavHost]'s thread-transcript share, and
 * [org.ort.app.ui.screens.TransmissionDetailContent]'s over-audio share) launches through the
 * identical intent, rather than each hand-rolling its own copy of it — the exact
 * two-implementations-of-one-rule shape register R-1099 names elsewhere, applied here before a
 * second copy could ever exist. Extracted from `SettingsContent.kt` (P30's original home for this,
 * back when every share action was wired from Settings > Export — see [ShareCoordinator]'s own
 * kdoc for why register R-1096 moved the *selection* out of that screen; this is the one piece of
 * the original wiring that stays shared).
 *
 * [launchShare] is a no-op, logged rather than silently swallowed, exactly when [share] is `null`
 * (constitution I: a share action is user-initiated and discretionary, not a data-integrity claim
 * a missing result could misstate — the screen that called this already answered honestly if it
 * had nothing yet).
 */
public object ShareIntentLauncher {

    /** Test-only seam for [FileProvider.getUriForFile] — see this file's own real caller's former
     * home (`SettingsContentBackupAndShareTest`'s doc comment) for why the *real* `FileProvider`
     * cannot be exercised reliably under this Windows/Robolectric combination; a test supplies a
     * fake here that still returns a `content://<authority>/...`-shaped [Uri]. */
    internal var uriResolverForTest: ((Context, String, File) -> Uri)? = null

    public fun launchShare(context: Context, share: ShareFile?) {
        if (share == null) {
            android.util.Log.i("ShareIntentLauncher", "share tapped but nothing was found to share yet")
            return
        }
        val authority = "${context.packageName}.fileprovider"
        val resolveUri = uriResolverForTest ?: FileProvider::getUriForFile
        val uri = resolveUri(context, authority, share.file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = share.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, share.suggestedName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
