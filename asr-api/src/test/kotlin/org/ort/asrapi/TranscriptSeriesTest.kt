package org.ort.asrapi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.AssetRef
import org.ort.core.TransmissionId
import org.ort.testing.Requirement

class TranscriptSeriesTest {

    @Test
    @Requirement("FR-REP-3", "AC-31")
    fun `superseding writes a new current version and retains the old one, not deleting it`() {
        val series = TranscriptSeries(TransmissionId.new())

        val v1 = series.supersede(TranscriptPass.A, "partial hypothesis", AssetRef("streaming-model", "1"))
        val v2 = series.supersede(TranscriptPass.B, "final accurate transcript", AssetRef("offline-model", "1"))

        assertEquals(2, series.all.size, "nothing is deleted (P9)")
        assertTrue(series.all.contains(v1.copy(isCurrent = false)))
        assertEquals(v2, series.current)
    }

    @Test
    @Requirement("AC-31")
    fun `exactly one version is current after any number of supersessions`() {
        val series = TranscriptSeries(TransmissionId.new())
        repeat(5) { i -> series.supersede(TranscriptPass.REPROCESS, "version $i", AssetRef("m", "1")) }

        assertEquals(1, series.all.count { it.isCurrent })
        assertEquals(5, series.all.size)
    }
}
