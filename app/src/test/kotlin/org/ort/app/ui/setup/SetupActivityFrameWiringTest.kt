package org.ort.app.ui.setup

import android.app.Application
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.fieldreport.wiring.FieldReportAppWiring
import org.robolectric.RobolectricTestRunner

/**
 * WPW (register, WPR2's own report): [FieldReportAppWiring.attachWindow] was called only from
 * `ReaderActivity` — Setup was never wired at all, even though the operator's own motivating
 * incident (four onboarding defects, no evidence but a verbal description and one photograph)
 * happened *during* Setup. Proves the identical attach/detach contract
 * `org.ort.app.fieldreport.wiring.FieldReportAppWiringTest` already proves for `ReaderActivity`'s
 * own wiring, now for [SetupActivity] — this file's own row ("the frame wiring only"), never a
 * second, competing test of anything else `SetupActivityTest` already covers.
 */
@RunWith(RobolectricTestRunner::class)
class SetupActivityFrameWiringTest {

    @After
    fun tearDown() {
        FieldReportAppWiring.detachWindow()
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `WPW onCreate attaches this activity's own window to the field-report frame capturer`() {
        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity {
                assertTrue(
                    "SetupActivity.onCreate must attach its window the same way ReaderActivity's does",
                    FieldReportAppWiring.delegatingCapturer.delegate != null,
                )
            }
        }
    }

    @Test
    fun `WPW onDestroy detaches the window so a destroyed SetupActivity is never captured again`() {
        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity {
                assertTrue(FieldReportAppWiring.delegatingCapturer.delegate != null)
            }
        }
        // `.use { }` has already closed (and so destroyed) the scenario by this point.
        assertNull(
            "SetupActivity.onDestroy must detach the window, the same way ReaderActivity's does",
            FieldReportAppWiring.delegatingCapturer.delegate,
        )
    }

    /**
     * The frame wiring is attached exactly once, in `onCreate` — this activity renders every one of
     * S00..S12 inside one continuous `Activity` instance (`step` is a plain `mutableStateOf`, never
     * a fresh `startActivity`), so the one attached window covers every step the operator can reach,
     * S04/S09b/S10b included, without a second attach call anywhere in this class (this file's own
     * row: "the frame wiring only", so nothing here re-verifies each individual step's own render).
     */
    @Test
    fun `WPW the same attached window persists across a step change, covering every step in one Activity`() {
        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            val delegateAtWelcome = FieldReportAppWiring.delegatingCapturer.delegate
            scenario.onActivity { activity -> activity.onChooseMode(org.ort.core.capture.CaptureMode.USB_RADIO) }
            scenario.onActivity { activity ->
                assertTrue(
                    "changing steps must never re-attach a different window mid-flow",
                    FieldReportAppWiring.delegatingCapturer.delegate === delegateAtWelcome,
                )
            }
        }
    }
}
