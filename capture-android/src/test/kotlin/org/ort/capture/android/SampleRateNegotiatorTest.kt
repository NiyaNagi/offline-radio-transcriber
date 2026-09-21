package org.ort.capture.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

/**
 * FR-CAP-2, FR-CAP-2a (register R-1114): the pure decision behind [AndroidAudioIo]'s sample-rate
 * negotiation. See that class's own kdoc for why most USB audio adapters do not offer 16 kHz —
 * and, just as relevantly here, why plenty do not offer the old fixed default (48 000 Hz) either.
 */
class SampleRateNegotiatorTest {

    @Test
    @Requirement("FR-CAP-2", "FR-CAP-2a")
    fun `FR_CAP_2 a device offering only 44_1kHz negotiates to 44_1kHz instead of failing to open`() {
        val negotiated = SampleRateNegotiator.negotiate(preferred = 48_000, supported = intArrayOf(44_100))

        assertEquals(44_100, negotiated, "an adapter that only offers 44.1kHz must be negotiated to, not rejected")
    }

    @Test
    @Requirement("FR-CAP-2")
    fun `the preferred rate is kept unchanged when the device actually offers it`() {
        val negotiated = SampleRateNegotiator.negotiate(preferred = 48_000, supported = intArrayOf(44_100, 48_000))

        assertEquals(48_000, negotiated, "the preferred rate must not be abandoned when the device supports it")
    }

    @Test
    @Requirement("FR-CAP-2")
    fun `an empty supported list means no restriction, so the preferred rate is kept`() {
        val negotiated = SampleRateNegotiator.negotiate(preferred = 48_000, supported = intArrayOf())

        assertEquals(
            48_000,
            negotiated,
            "an empty array is Android's own signal for 'no restriction' -- it must not be read as 'nothing works'",
        )
    }

    @Test
    @Requirement("FR-CAP-2")
    fun `when the preferred rate is unsupported the highest supported rate is chosen deterministically`() {
        val negotiated =
            SampleRateNegotiator.negotiate(preferred = 96_000, supported = intArrayOf(16_000, 44_100, 22_050))

        assertEquals(44_100, negotiated, "the highest offered rate must be picked, regardless of array order")
    }

    @Test
    @Requirement("FR-CAP-2")
    fun `the choice does not depend on the order the OS enumerates supported rates in`() {
        val ascending = SampleRateNegotiator.negotiate(preferred = 8_000, supported = intArrayOf(22_050, 44_100))
        val descending = SampleRateNegotiator.negotiate(preferred = 8_000, supported = intArrayOf(44_100, 22_050))

        assertEquals(
            ascending,
            descending,
            "the same supported set must negotiate to the same rate regardless of order",
        )
        assertEquals(44_100, ascending)
    }
}
