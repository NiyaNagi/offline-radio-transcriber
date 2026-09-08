package org.ort.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * WP11e (register R-137, FR-OBS-3, constitution V): `Settings-Diagnostics.dc.html`'s own scrubbing
 * example — a line reads `resolved [callsign] at 0.94`, never the callsign itself — is the
 * contract this pure function establishes. No log writer exists yet for the board-listed logs
 * (grepped before writing this: no `lifecycle.log`/`capture.log`/`pipeline.log`/`rig.log` producer
 * anywhere in the tree), so these cases are the scrubber's own guarantee, exercised directly
 * rather than through a real log line this build cannot yet produce.
 */
class CallsignScrubberTest {

    @Test
    fun `FR_OBS_3 board example line scrubs verbatim`() {
        val input = "resolved K7ABC at 0.94"
        assertEquals("resolved [callsign] at 0.94", CallsignScrubber.scrub(input))
    }

    @Test
    fun `FR_OBS_3 a line with no callsign is unchanged`() {
        val input = "route verified: usb-serial device attached, 48000 Hz native rate"
        assertEquals(input, CallsignScrubber.scrub(input))
    }

    @Test
    fun `FR_OBS_3 a portable modifier suffix is scrubbed as one token`() {
        val input = "heard K7ABC/P calling CQ"
        val scrubbed = CallsignScrubber.scrub(input)
        assertFalse(scrubbed.contains("K7ABC"))
        assertEquals("heard [callsign] calling CQ", scrubbed)
    }

    @Test
    fun `FR_OBS_3 a reciprocal secondary-prefix form is scrubbed as one token`() {
        val input = "worked VE7/K7ABC on 146.520"
        val scrubbed = CallsignScrubber.scrub(input)
        assertFalse(scrubbed.contains("K7ABC"))
        assertFalse(scrubbed.contains("VE7/K7ABC"))
    }

    @Test
    fun `FR_OBS_3 multiple callsigns on one line are all scrubbed`() {
        val input = "thread K7ABC and N7XYZ both active"
        val scrubbed = CallsignScrubber.scrub(input)
        assertFalse(scrubbed.contains("K7ABC"))
        assertFalse(scrubbed.contains("N7XYZ"))
        assertEquals(2, "\\[callsign]".toRegex().findAll(scrubbed).count())
    }

    @Test
    fun `FR_OBS_3 a rejection rule code with no digit is never mistaken for a callsign`() {
        val input = "VAD_NO_SPEECH: squelch tail, 0.4 s"
        assertEquals(input, CallsignScrubber.scrub(input))
    }

    @Test
    fun `FR_OBS_3 scrubbing is idempotent — a callsign inside the sentinel is not reachable to re-scrub`() {
        val once = CallsignScrubber.scrub("resolved K7ABC at 0.94")
        val twice = CallsignScrubber.scrub(once)
        assertEquals(once, twice)
    }

    @Test
    fun `FR_OBS_3 scrub applies line by line across a multi-line log`() {
        val input = "line one: nothing here\nresolved K7ABC at 0.94\nline three: also nothing"
        val expected = "line one: nothing here\nresolved [callsign] at 0.94\nline three: also nothing"
        assertEquals(expected, CallsignScrubber.scrub(input))
    }

    @Test
    fun `FR_OBS_3 empty input scrubs to empty output`() {
        assertEquals("", CallsignScrubber.scrub(""))
    }
}
