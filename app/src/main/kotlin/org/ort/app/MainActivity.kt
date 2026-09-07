package org.ort.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Placeholder launcher activity. Replaced by the Compose navigation host in build-plan P8.
 * It renders one line of text so an emulator/device smoke test (AC-93) can assert the app
 * actually started at the minimum supported API level.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply { text = getString(R.string.placeholder_running) },
        )
    }
}
