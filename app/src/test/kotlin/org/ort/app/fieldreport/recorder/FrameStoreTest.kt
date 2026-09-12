package org.ort.app.fieldreport.recorder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement
import java.nio.file.Files

/**
 * AC-142: "Screen frames captured by the recorder are stored app-private, bounded in count and in
 * total bytes, and the oldest frame is dropped once a bound is reached rather than the bound being
 * silently exceeded." Every test here proves the bound *by exceeding it*, never by asserting the
 * configured constant — the discipline this package's brief calls out by name.
 */
class FrameStoreTest {

    private fun tempDir() = Files.createTempDirectory("frame-store-test").toFile()

    @Test
    @Requirement("AC-142")
    fun `AC_142_storing fewer frames than the count bound keeps every one of them`() {
        val store = FrameStore(tempDir(), maxFrames = 5, maxTotalBytes = 1_000_000)

        store.store(byteArrayOf(1))
        store.store(byteArrayOf(2))
        store.store(byteArrayOf(3))

        assertEquals(3, store.frames().size)
    }

    @Test
    @Requirement("AC-142")
    fun `AC_142_storing past the count bound drops the oldest frame, keeps the newest, never grows past it`() {
        val store = FrameStore(tempDir(), maxFrames = 2, maxTotalBytes = 1_000_000)

        store.store(byteArrayOf(1))
        store.store(byteArrayOf(2))
        store.store(byteArrayOf(3))

        val frames = store.frames()
        assertEquals(2, frames.size, "must never hold more than the configured count bound")
        assertEquals(listOf(byteArrayOf(2).toList(), byteArrayOf(3).toList()), frames.map { it.readBytes().toList() })
    }

    @Test
    @Requirement("AC-142")
    fun `AC_142_storing past the byte bound drops the oldest frames until back under budget`() {
        // Each frame is 10 bytes; a 25-byte budget holds at most 2.
        val store = FrameStore(tempDir(), maxFrames = 100, maxTotalBytes = 25)
        val frameA = ByteArray(10) { 1 }
        val frameB = ByteArray(10) { 2 }
        val frameC = ByteArray(10) { 3 }

        store.store(frameA)
        store.store(frameB)
        store.store(frameC)

        val frames = store.frames()
        assertTrue(store.totalBytes() <= 25, "total bytes (${store.totalBytes()}) must never exceed the bound")
        assertEquals(2, frames.size, "the oldest (frameA) must be dropped to stay under the byte bound")
        assertEquals(frameB.toList(), frames[0].readBytes().toList())
        assertEquals(frameC.toList(), frames[1].readBytes().toList())
    }

    @Test
    @Requirement("AC-142")
    fun `AC_142_a single frame larger than the byte bound is not silently retained past it`() {
        val store = FrameStore(tempDir(), maxFrames = 100, maxTotalBytes = 5)

        store.store(ByteArray(20) { 7 })

        assertTrue(store.totalBytes() <= 5, "an over-budget frame must not be retained past the bound")
        assertTrue(store.frames().isEmpty())
    }

    /**
     * AC-148: "A screen frame reaches an uploaded bundle only when the operator has explicitly
     * turned that category on for that upload ... even though the recorder held one locally."
     * [FrameStore] is the "held it locally" half of that claim — this test proves it has **no
     * concept of an upload-time category toggle at all**: [FrameStore.store]/[FrameStore.frames]
     * never consult one, so a frame is always held locally regardless of any consent state. What
     * it does **not** prove: that the field-report bundle builder (outside this package's
     * ownership — WPR2's own package, per this round's brief) actually gates reading from
     * [FrameStore.frames] behind the FR-OBS-9 per-upload toggle before assembling an upload. That
     * half of AC-148 needs its own test in whichever package builds the bundle.
     */
    @Test
    @Requirement("AC-148")
    fun `AC_148_the frame store holds a frame locally with no awareness of any upload-time consent state`() {
        val store = FrameStore(tempDir(), maxFrames = 5, maxTotalBytes = 1_000_000)

        // No parameter here can express "the operator turned screen frames off for this upload" —
        // store() takes only the bytes. That is the structural proof: this class cannot possibly
        // gate on a toggle it has no way to be told about.
        store.store(byteArrayOf(42))

        assertEquals(1, store.frames().size, "the frame is held locally unconditionally")
    }
}
