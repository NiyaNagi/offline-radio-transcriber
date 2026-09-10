package org.ort.core.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** FR-CAP-9, FR-CAP-13: a route's provenance names the mode that preset it and whether it was overridden. */
class AudioRouteProvenanceTest {

    @Test
    fun `records which mode preset the route and that it was not overridden`() {
        val provenance = AudioRouteProvenance(presetByMode = CaptureMode.LOCAL_MICROPHONE, overridden = false)
        assertEquals(CaptureMode.LOCAL_MICROPHONE, provenance.presetByMode)
        assertFalse(provenance.overridden)
    }

    @Test
    fun `records an operator override distinctly from the preset`() {
        val provenance = AudioRouteProvenance(presetByMode = CaptureMode.BLUETOOTH_RADIO, overridden = true)
        assertEquals(CaptureMode.BLUETOOTH_RADIO, provenance.presetByMode)
        assertEquals(true, provenance.overridden)
    }
}
