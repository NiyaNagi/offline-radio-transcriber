package org.ort.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.ort.app.ui.navigation.OrtNavHost
import org.ort.app.ui.theme.OrtTheme

/**
 * Hosts the Compose navigation graph (build-plan P13, D15). Not yet the app's launcher — see
 * `AndroidManifest.xml`'s comment and the build-plan's own "done when": this prompt lands the
 * foundation reachable and tested, and the switchover that makes it the app's actual entry point
 * is a deliberate follow-up once P12's capture wiring lands (they touch adjacent but disjoint
 * files this wave, per the standing "do not touch" scoping).
 *
 * [EXTRA_SESSION_ID] mirrors [org.ort.app.status.StatusActivity] and
 * [org.ort.app.transmissions.TransmissionListActivity]'s own extra, so this activity can be
 * launched the same way they are for the v0 smoke-test path (`ui/data/ReaderPolling.kt`).
 */
public class ReaderActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        setContent {
            OrtTheme {
                OrtNavHost(sessionId = sessionId)
            }
        }
    }

    public companion object {
        public const val EXTRA_SESSION_ID: String = "session_id"
    }
}
