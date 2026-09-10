package org.ort.core.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** FR-CAP-2, FR-CAP-8: the closed set of audio route kinds `:core` reasons about, mirroring `:capture-android`'s. */
class AudioRouteKindTest {

    @Test
    fun `the audio route kind set is exactly the five closed values`() {
        assertEquals(
            setOf("BUILT_IN_MIC", "USB", "WIRED_HEADSET", "BLUETOOTH_SCO", "UNKNOWN"),
            AudioRouteKind.entries.map { it.name }.toSet(),
        )
    }
}
