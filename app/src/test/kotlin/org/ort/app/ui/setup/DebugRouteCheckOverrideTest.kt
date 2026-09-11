package org.ort.app.ui.setup

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.app.BuildConfig

/**
 * R-943's own tour-injection seam for S05, the exact same shape [DebugRigLinkPortOverride] already
 * establishes for S10b (that class's own test has the fuller account of why the gating needs a
 * settable [DebugRouteCheckOverride.isDebugBuild] seam rather than the bare `BuildConfig.DEBUG`
 * constant): [DebugRouteCheckOverride.activeOverride] must read `null` in any non-debug build, no
 * matter what [DebugRouteCheckOverride.show] set. Restored to its real default after every test,
 * including on failure.
 */
class DebugRouteCheckOverrideTest {

    private val sampleState = RouteCheckState.InProgress(
        passed = setOf(RouteCheckStage.NATIVE_RATE, RouteCheckStage.ROUTE_MATCH),
        nativeRateHz = 48_000,
        elapsedListeningMillis = 4_000L,
        routedDeviceLabel = "USB Audio Device",
        levelBars = listOf(0.2f, 0.4f, 0.6f),
        noiseFloorDbfs = -58.0,
    )

    @AfterEach
    fun reset() {
        DebugRouteCheckOverride.clear()
        DebugRouteCheckOverride.isDebugBuild = { BuildConfig.DEBUG }
    }

    @Test
    fun `the real default reflects BuildConfig DEBUG, true under the Robolectric debug variant`() {
        assertTrue(DebugRouteCheckOverride.isDebugBuild())
        assertEquals(BuildConfig.DEBUG, DebugRouteCheckOverride.isDebugBuild())
    }

    @Test
    fun `a shown override is active while isDebugBuild reports true`() {
        DebugRouteCheckOverride.show(sampleState)

        assertSame(sampleState, DebugRouteCheckOverride.current)
        assertSame(sampleState, DebugRouteCheckOverride.activeOverride)
    }

    @Test
    fun `a shown override is ignored the moment isDebugBuild reports false, even though current still holds it`() {
        DebugRouteCheckOverride.show(sampleState)
        DebugRouteCheckOverride.isDebugBuild = { false }

        assertSame(sampleState, DebugRouteCheckOverride.current)
        assertNull(DebugRouteCheckOverride.activeOverride)
    }

    @Test
    fun `clear removes a shown override`() {
        DebugRouteCheckOverride.show(sampleState)
        DebugRouteCheckOverride.clear()

        assertNull(DebugRouteCheckOverride.current)
        assertNull(DebugRouteCheckOverride.activeOverride)
    }

    @Test
    fun `no override shown reads null regardless of isDebugBuild`() {
        assertNull(DebugRouteCheckOverride.activeOverride)
        DebugRouteCheckOverride.isDebugBuild = { false }
        assertNull(DebugRouteCheckOverride.activeOverride)
    }
}
