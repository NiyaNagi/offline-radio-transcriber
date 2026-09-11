package org.ort.app.ui.setup

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.app.BuildConfig

/**
 * The tour-injection seam for S10b's paired-device states, the same shape
 * [org.ort.app.ui.failures.DebugFailureOverride] already establishes: [DebugRigLinkPortOverride.activeOverride]
 * must read `null` in any non-debug build, no matter what [DebugRigLinkPortOverride.show] set.
 * Robolectric only ever compiles this module's debug variant (`ort.android-app.gradle.kts`'s
 * `testBuildType = "debug"`), so `BuildConfig.DEBUG` is `true` in every unit test regardless —
 * [DebugRigLinkPortOverride.isDebugBuild] is the seam this proves the gating with, restored to its
 * real default after every test (including on failure).
 */
class DebugRigLinkPortOverrideTest {

    @AfterEach
    fun reset() {
        DebugRigLinkPortOverride.clear()
        DebugRigLinkPortOverride.isDebugBuild = { BuildConfig.DEBUG }
    }

    @Test
    fun `the real default reflects BuildConfig DEBUG, true under the Robolectric debug variant`() {
        assertTrue(DebugRigLinkPortOverride.isDebugBuild())
        assertEquals(BuildConfig.DEBUG, DebugRigLinkPortOverride.isDebugBuild())
    }

    @Test
    fun `a shown override is active while isDebugBuild reports true`() {
        val port = InMemoryRigLinkPort()
        DebugRigLinkPortOverride.show(port)

        assertSame(port, DebugRigLinkPortOverride.current)
        assertSame(port, DebugRigLinkPortOverride.activeOverride)
    }

    @Test
    fun `a shown override is ignored the moment isDebugBuild reports false, even though current still holds it`() {
        val port = InMemoryRigLinkPort()
        DebugRigLinkPortOverride.show(port)
        DebugRigLinkPortOverride.isDebugBuild = { false }

        assertSame(port, DebugRigLinkPortOverride.current)
        assertNull(DebugRigLinkPortOverride.activeOverride)
    }

    @Test
    fun `clear removes a shown override`() {
        DebugRigLinkPortOverride.show(InMemoryRigLinkPort())
        DebugRigLinkPortOverride.clear()

        assertNull(DebugRigLinkPortOverride.current)
        assertNull(DebugRigLinkPortOverride.activeOverride)
    }

    @Test
    fun `no override shown reads null regardless of isDebugBuild`() {
        assertNull(DebugRigLinkPortOverride.activeOverride)
        DebugRigLinkPortOverride.isDebugBuild = { false }
        assertNull(DebugRigLinkPortOverride.activeOverride)
    }
}
