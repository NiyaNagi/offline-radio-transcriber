package org.ort.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * R-129 (halt — audit V3 @3e2d4ee): `LogContent`/`ThreadContent` kept non-Bundle-saveable types
 * (`LogQuickFilterId`, `LogFilterSelection`) in `rememberSaveable` with no `Saver`. Every
 * `LogScreenTest`/`LogFilterSheetTest` case stayed green because `createComposeRule()` installs no
 * `SaveableStateRegistry` at all — under a real one (any genuine `Activity`), the very first
 * composition throws `IllegalArgumentException: MutableState containing All cannot be saved…`,
 * before Log or Threads render anything. `createAndroidComposeRule<ComponentActivity>()` hosts
 * content inside a real, `ActivityScenario`-backed `Activity` (its own `ActivityScenarioRule`
 * launches one) with a genuine `SaveableStateRegistry` wired up, so a future missing `Saver` here
 * fails this test in CI rather than only on a device.
 *
 * [sessionId] is deliberately `null` for both: a non-null session starts the session-tied polling
 * `LaunchedEffect(sessionId) { while (true) { ...; delay(2000) } }` loop in each composable, which
 * a Robolectric-driven test never gets a chance to cleanly cancel and can poison a later test's
 * idle-check in the same suite run (`ReaderActivity.kt`'s own doc comment records this exact
 * failure mode from `ReaderActivityTest`). The crash this test guards against happens on first
 * composition, before that loop would ever start, so `sessionId = null` still exercises it fully.
 */
@RunWith(RobolectricTestRunner::class)
class LogAndThreadContentActivityTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun R_129_log_and_threads_compose_inside_a_real_activity() {
        // Both in one `setContent` (`AndroidComposeTestRule` refuses a second call per test) --
        // sibling, independent subtrees, exactly as a screen swap would recompose one out and the
        // other in; either one throwing on its own first composition still fails this test.
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    LogContent(context = composeTestRule.activity, sessionId = null, onOpen = {})
                    ThreadContent(context = composeTestRule.activity, sessionId = null, onOpen = {})
                }
            }
        }

        composeTestRule.waitForIdle()
    }
}
