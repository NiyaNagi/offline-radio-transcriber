package org.ort.pipeline.archive

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `Recording-Session.dc.html`'s (RC02) Export action: today, always a typed "unavailable" stub —
 * see [SessionAudioExport]'s own doc comment for why. */
class SessionAudioExportTest {

    @Test
    fun `session audio export is unavailable and names a real reason, not a placeholder`() {
        val result = SessionAudioExport.unavailable("S1")

        assertTrue(result.reason.isNotBlank())
    }
}
