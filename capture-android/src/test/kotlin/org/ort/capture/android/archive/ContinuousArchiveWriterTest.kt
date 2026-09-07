package org.ort.capture.android.archive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

class ContinuousArchiveWriterTest {

    @Test
    @Requirement("AC-96", "FR-SEG-9")
    fun `AC_96 the archived stream replays sample-for-sample in order`() {
        val dir = createTempDir("archive-test")
        val writer = ContinuousArchiveWriter(dir, chunkSamples = 100)
        val block1 = ShortArray(100) { it.toShort() }
        val block2 = ShortArray(60) { (it + 1000).toShort() }
        writer.append(block1, framePosition = 0)
        writer.append(block2, framePosition = 100)
        writer.finish()

        val reader = ArchiveReader(writer.chunkIndex())
        val replayed = reader.readAll().toList()
        val all = replayed.sortedBy { it.first }.flatMap { it.second.toList() }

        assertEquals((block1.toList() + block2.toList()), all)
    }
}
