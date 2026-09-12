package org.ort.app.fieldreport.recorder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

/**
 * Constitution II: "a fake that cannot be told to fail, hang or return a hallucination is a stub,
 * and stubs test nothing that matters." [FakeScreenFrameCapturer] is FR-OBS-7's required
 * behavioural fake for [ScreenFrameCapturer] (real capture needs a live `Window` `PixelCopy` has
 * no JVM/Robolectric equivalent for — see this package's own report on what could not be
 * exercised off-device); this proves all three of its modes actually behave as advertised.
 */
class FakeScreenFrameCapturerTest {

    @Test
    @Requirement("FR-OBS-7")
    fun `FR_OBS_7_success mode returns the configured bytes instantly and counts the call`() {
        val capturer = FakeScreenFrameCapturer(FakeScreenFrameCapturer.Mode.Success(byteArrayOf(1, 2, 3)))

        val result = runBlocking { capturer.capture() }

        assertEquals(listOf<Byte>(1, 2, 3), result?.toList())
        assertEquals(1, capturer.callCount)
    }

    @Test
    @Requirement("FR-OBS-7")
    fun `FR_OBS_7_failure mode returns null rather than throwing, and still counts the call`() {
        val capturer = FakeScreenFrameCapturer(FakeScreenFrameCapturer.Mode.Failure)

        val result = runBlocking { capturer.capture() }

        assertNull(result)
        assertEquals(1, capturer.callCount)
    }

    @Test
    @Requirement("FR-OBS-7")
    fun `FR_OBS_7_hang mode never completes on its own, and is cancellable`() {
        val capturer = FakeScreenFrameCapturer(FakeScreenFrameCapturer.Mode.Hang)

        val timedOut = runBlocking {
            withTimeoutOrNull(200) { capturer.capture() }
        }

        assertNull(timedOut, "a hung capture must not spuriously complete within the timeout")
        assertEquals(1, capturer.callCount)
    }

    @Test
    @Requirement("FR-OBS-7")
    fun `FR_OBS_7_a hung capture can be cancelled out from under a caller`() {
        val capturer = FakeScreenFrameCapturer(FakeScreenFrameCapturer.Mode.Hang)

        runBlocking {
            val deferred = async { capturer.capture() }
            delay(20)
            deferred.cancel()
            val threw = try {
                deferred.await()
                false
            } catch (_: CancellationException) {
                true
            }
            assertTrue(threw, "cancelling the caller must actually cancel the hung capture")
        }
    }
}
