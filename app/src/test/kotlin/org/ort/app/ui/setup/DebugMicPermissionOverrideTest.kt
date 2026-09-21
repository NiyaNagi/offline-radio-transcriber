package org.ort.app.ui.setup

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.app.BuildConfig

/**
 * R-1127/R-1107/R-085 (register): the same shape [DebugRouteCheckOverrideTest] already establishes
 * for [DebugRouteCheckOverride] — [DebugMicPermissionOverride.active] must read `false` in any
 * non-debug build, no matter what [DebugMicPermissionOverride.show] set. Restored to its real
 * default after every test, including on failure.
 */
class DebugMicPermissionOverrideTest {

    @AfterEach
    fun reset() {
        DebugMicPermissionOverride.clear()
        DebugMicPermissionOverride.isDebugBuild = { BuildConfig.DEBUG }
    }

    @Test
    fun `the real default reflects BuildConfig DEBUG, true under the Robolectric debug variant`() {
        assertTrue(DebugMicPermissionOverride.isDebugBuild())
    }

    @Test
    fun `a shown override is active while isDebugBuild reports true`() {
        DebugMicPermissionOverride.show()

        assertTrue(DebugMicPermissionOverride.current)
        assertTrue(DebugMicPermissionOverride.active)
    }

    @Test
    fun `a shown override is ignored the moment isDebugBuild reports false, even though current still holds it`() {
        DebugMicPermissionOverride.show()
        DebugMicPermissionOverride.isDebugBuild = { false }

        assertTrue(DebugMicPermissionOverride.current)
        assertFalse(DebugMicPermissionOverride.active)
    }

    @Test
    fun `clear removes a shown override`() {
        DebugMicPermissionOverride.show()
        DebugMicPermissionOverride.clear()

        assertFalse(DebugMicPermissionOverride.current)
        assertFalse(DebugMicPermissionOverride.active)
    }

    @Test
    fun `no override shown reads false regardless of isDebugBuild`() {
        assertFalse(DebugMicPermissionOverride.active)
        DebugMicPermissionOverride.isDebugBuild = { false }
        assertFalse(DebugMicPermissionOverride.active)
    }
}
