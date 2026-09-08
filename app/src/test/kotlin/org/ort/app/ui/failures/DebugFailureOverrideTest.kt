package org.ort.app.ui.failures

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.app.BuildConfig

/**
 * WP11b follow-up: [DebugFailureOverride.activeOverride] must read `null` in any non-debug build,
 * no matter what [DebugFailureOverride.show] set. Robolectric only ever compiles this module's
 * debug variant (`ort.android-app.gradle.kts`'s `testBuildType = "debug"`), so `BuildConfig.DEBUG`
 * is `true` in every unit test regardless — [DebugFailureOverride.isDebugBuild] is the seam this
 * proves the gating with, restored to its real default after every test (including on failure).
 */
class DebugFailureOverrideTest {

    @AfterEach
    fun reset() {
        DebugFailureOverride.clear()
        DebugFailureOverride.isDebugBuild = { BuildConfig.DEBUG }
    }

    @Test
    fun `the real default reflects BuildConfig DEBUG, true under the Robolectric debug variant`() {
        assertTrue(DebugFailureOverride.isDebugBuild())
        assertEquals(BuildConfig.DEBUG, DebugFailureOverride.isDebugBuild())
    }

    @Test
    fun `a shown override is active while isDebugBuild reports true`() {
        val presentation = FailurePresentation.Clock(ClockViewState("PDT → PST", "8 h 30 m", "23:10", "06:40"))
        DebugFailureOverride.show(presentation)

        assertEquals(presentation, DebugFailureOverride.current)
        assertEquals(presentation, DebugFailureOverride.activeOverride)
    }

    @Test
    fun `a shown override is ignored the moment isDebugBuild reports false, even though current still holds it`() {
        val presentation = FailurePresentation.Clock(ClockViewState("PDT → PST", "8 h 30 m", "23:10", "06:40"))
        DebugFailureOverride.show(presentation)
        DebugFailureOverride.isDebugBuild = { false }

        assertEquals(presentation, DebugFailureOverride.current)
        assertNull(DebugFailureOverride.activeOverride)
    }

    @Test
    fun `FailureMapper never sees a debug override when isDebugBuild reports false`() {
        DebugFailureOverride.show(FailurePresentation.Clock(ClockViewState("PDT → PST", "8 h 30 m", "23:10", "06:40")))
        DebugFailureOverride.isDebugBuild = { false }

        val signals = org.ort.pipeline.capture.CaptureState.State.Capturing.let {
            FailureSignals(
                captureState = it,
                inputStatus = org.ort.pipeline.capture.InputStatus.State.None,
                levelStatus = org.ort.pipeline.capture.LevelStatus.State.NotMeasured,
                thermalStatus = org.ort.pipeline.capture.ThermalStatus.State.Nominal(0, null),
                rigStatus = org.ort.pipeline.capture.RigStatus.State.Absent,
                storageForecast = org.ort.pipeline.capture.StorageForecast.State.NotYetMeasured(0L, 0L),
                shedLevel = 0,
                shedBacklog = 0,
                newestGap = null,
                nowMillis = 0L,
                debugOverride = DebugFailureOverride.activeOverride,
            )
        }

        assertEquals(FailurePresentation.None, FailureMapper.map(signals))
    }
}
