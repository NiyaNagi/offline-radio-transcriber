package org.ort.pipeline.archive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.ort.capture.android.archive.ContinuousArchiveWriter
import org.ort.segment.SegmentConfig
import org.ort.segment.fake.ScriptedVad
import org.ort.testing.Requirement

class ReSegmenterTest {

    @Test
    @Requirement("AC-96", "FR-SEG-9")
    fun `AC_96 the same archived stream re-segments differently under different VAD parameters`() {
        val dir = createTempDir("resegment-test")
        val writer = ContinuousArchiveWriter(dir, chunkSamples = 100_000)
        // 2.5 s of dummy audio — the scripted VAD, not the sample values, drives segmentation.
        writer.append(ShortArray(40_000), framePosition = 0)
        writer.finish()
        val chunks = writer.chunkIndex()

        // Speech 0-1000ms, silence 1000-1500ms (500ms gap), speech 1500-2500ms.
        val vadFor = { ScriptedVad.fromRegions(2_500, listOf(0..999, 1_500..2_499)) }

        val mergedConfig = SegmentConfig(minSilenceMs = 600) // 500ms gap does not reach the floor: merges into one
        val splitConfig = SegmentConfig(minSilenceMs = 300) // 500ms gap exceeds the floor: splits into two

        val merged = ReSegmenter.reSegment(chunks, mergedConfig, vadFor())
        val split = ReSegmenter.reSegment(chunks, splitConfig, vadFor())

        assertEquals(1, merged.size, "a 500ms gap under a 600ms floor must merge into one transmission")
        assertEquals(2, split.size, "the same gap under a 300ms floor must split into two")
        assertNotEquals(merged.size, split.size, "re-segmentation must produce a different, valid set (AC-96)")
    }
}
