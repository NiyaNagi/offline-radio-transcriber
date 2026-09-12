package org.ort.app.fieldreport.recorder

import androidx.activity.ComponentActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * WPW (register, WPR1 flagged the leak, WPR2 could only bound it): [RealScreenFrameCapturer] used
 * to start its own [android.os.HandlerThread] lazily and never quit it. Proves [RealScreenFrameCapturer.close]
 * actually quits the thread it started, and is a safe no-op when nothing was ever started —
 * discriminating for exactly the leak this closes: before this fix there was no `close()` to call at
 * all, so the second test below could not have compiled, let alone passed.
 */
@RunWith(RobolectricTestRunner::class)
class RealScreenFrameCapturerCloseTest {

    @Test
    fun `close before any capture is a safe no-op that never starts a thread`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val capturer = RealScreenFrameCapturer(activity.window)

        capturer.close()

        assertNull(
            "close() must never force-start a HandlerThread just to quit it",
            capturer.handlerThreadForTest,
        )
    }

    @Test
    fun `close quits the real HandlerThread capture would have started`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val capturer = RealScreenFrameCapturer(activity.window)
        val thread = capturer.startHandlerThreadForTest()
        assertTrue("the thread must actually be running before close() is exercised", thread.isAlive)

        capturer.close()

        assertNull("close() must drop its own reference to the thread it quit", capturer.handlerThreadForTest)
        // HandlerThread.quitSafely() stops the thread's own Looper asynchronously — join with a
        // short, bounded wait rather than asserting isAlive immediately after the call returns.
        thread.join(1_000)
        assertFalse("close() must actually quit the HandlerThread, not just forget it", thread.isAlive)
    }

    @Test
    fun `close is idempotent — calling it twice never throws`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val capturer = RealScreenFrameCapturer(activity.window)
        capturer.startHandlerThreadForTest()

        capturer.close()
        capturer.close()

        assertNotNull("this call itself must not have thrown", capturer)
    }
}
