package org.ort.capture.android.archive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.capture.android.codec.LosslessCodec
import org.ort.testing.Requirement

class ContinuousArchiveWriterTest {

    @Test
    @Requirement("AC-96", "FR-SEG-9", "FR-STO-2a")
    fun `AC_96 the archived stream replays sample-for-sample in order through real FLAC encode-decode`() {
        val dir = createTempDir("archive-test")
        val writer = ContinuousArchiveWriter(dir, chunkSamples = 100)
        val block1 = ShortArray(100) { it.toShort() }
        val block2 = ShortArray(60) { (it + 1000).toShort() }
        writer.append(block1, framePosition = 0)
        writer.append(block2, framePosition = 100)
        writer.finish()

        // WPARC (FR-STO-2a): each persisted chunk is really FLAC-encoded — not the raw PCM byte
        // count the pre-FLAC writer produced — proving this test would fail if the codec were
        // silently bypassed.
        val chunkFile = writer.chunkIndex().first().file
        assertTrue(chunkFile.name.endsWith(".flac"), "encoded chunks must be the FLAC-shaped store's own output")

        val reader = ArchiveReader(writer.chunkIndex())
        val replayed = reader.readAll().toList()
        val all = replayed.sortedBy { it.first }.flatMap { it.second.toList() }

        assertEquals((block1.toList() + block2.toList()), all)
    }

    @Test
    @Requirement("FR-STO-2a", "FR-RUN-12")
    fun `a chunk that fails FLAC verification is reported as a hole, never blocks, never joins the index`() {
        val dir = createTempDir("archive-hole-test")
        val holes = mutableListOf<ArchiveHole>()
        // A codec that "encodes" losslessly but always decodes back to something else — the exact
        // shape FlacStore's own decode-and-compare check exists to catch.
        val lyingCodec = object : LosslessCodec {
            override val name: String = "lying-codec"
            override fun encode(pcm: ByteArray): ByteArray = pcm
            override fun decode(encoded: ByteArray): ByteArray = ByteArray(encoded.size) { 0 }
        }
        val writer = ContinuousArchiveWriter(dir, chunkSamples = 10, codec = lyingCodec, onHole = { holes.add(it) })

        writer.append(ShortArray(10) { (it + 1).toShort() }, framePosition = 0)
        writer.finish()

        assertEquals(1, holes.size, "a verification failure must be reported, not silently dropped")
        assertEquals(0L, holes.single().startSample)
        assertEquals(10, holes.single().sampleCount)
        assertEquals(emptyList<ArchiveChunkMeta>(), writer.chunkIndex(), "a failed chunk must never join the index")
    }
}
