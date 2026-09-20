package org.ort.app.analytics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * FR-ANL-2/D42: crash capture without any third-party SDK (constitution VII: nothing outside
 * `:net` may link an HTTP client, and no crash/analytics SDK is used at all). [CrashCaptureHandler]
 * is plain `Thread.UncaughtExceptionHandler` chaining — records first, then always defers to
 * whatever handler was previously installed, so this never swallows or changes the app's own
 * crash behaviour (a debugger, `System.exit`, a crash dialog — whatever [delegate] would have
 * done still happens).
 */
class CrashCaptureHandlerTest {

    @Test
    fun `records the thread and throwable, then chains to the delegate`() {
        var recordedThread: Thread? = null
        var recordedThrowable: Throwable? = null
        var delegateCalledWith: Throwable? = null
        val delegate = Thread.UncaughtExceptionHandler { _, throwable -> delegateCalledWith = throwable }

        val handler = CrashCaptureHandler(delegate) { thread, throwable ->
            recordedThread = thread
            recordedThrowable = throwable
        }

        val thrown = IllegalStateException("boom")
        handler.uncaughtException(Thread.currentThread(), thrown)

        assertSame(Thread.currentThread(), recordedThread)
        assertSame(thrown, recordedThrowable)
        assertSame(thrown, delegateCalledWith)
    }

    @Test
    fun `a null delegate never throws — the handler still records`() {
        var recorded = false
        val handler = CrashCaptureHandler(null) { _, _ -> recorded = true }

        handler.uncaughtException(Thread.currentThread(), RuntimeException("x"))

        assertTrue(recorded)
    }

    @Test
    fun `a recording failure never prevents the delegate from running (never swallow the real crash)`() {
        var delegateRan = false
        val delegate = Thread.UncaughtExceptionHandler { _, _ -> delegateRan = true }
        val handler = CrashCaptureHandler(delegate) { _, _ -> error("recording itself failed") }

        handler.uncaughtException(Thread.currentThread(), RuntimeException("original"))

        assertTrue(delegateRan)
    }

    @Test
    fun `the recorded exception class name and stack trace come from the real throwable`() {
        var capturedClassName: String? = null
        var capturedStack: String? = null
        val handler = CrashCaptureHandler(null) { _, throwable ->
            capturedClassName = throwable.javaClass.name
            capturedStack = throwable.stackTraceToString()
        }

        handler.uncaughtException(Thread.currentThread(), IllegalArgumentException("bad arg"))

        assertEquals("java.lang.IllegalArgumentException", capturedClassName)
        assertTrue(capturedStack!!.contains("IllegalArgumentException"))
    }
}
