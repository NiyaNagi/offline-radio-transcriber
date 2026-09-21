package org.ort.app.analytics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * FR-ANL-2/D42, constitution I: the instrumentation builder's report found `isAnr` hardcoded
 * `false` in the crash payload with no ANR-detection mechanism anywhere in the codebase — a field
 * that always reports `false` is "never measured" wearing a default, not "measured, and not an
 * ANR." [CrashPayloads.fromUncaughtException] is the one call site `OrtApplication` uses to build
 * that payload; this suite is the discriminating test the fix requires: it fails the moment
 * `isAnr` goes back to being a hardcoded `false` (or any other constant), because `Boolean?`
 * (see `AnalyticsPayload.kt`) makes `false` and "never measured" two different values that a test
 * can tell apart, not one value doing both jobs.
 */
class CrashPayloadsTest {

    @Test
    fun `FR_ANL_2_the payload never asserts isAnr — no detection mechanism has run`() {
        val payload = CrashPayloads.fromUncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        assertNull(payload.isAnr, "isAnr must be absent, not a default, when no ANR detection ran")
    }

    @Test
    fun `the payload still carries the real exception class, stack trace and thread name`() {
        val thread = Thread.currentThread()
        val throwable = IllegalArgumentException("bad arg")

        val payload = CrashPayloads.fromUncaughtException(thread, throwable)

        assertEquals("java.lang.IllegalArgumentException", payload.exceptionClass)
        assertTrue(payload.stackTrace.contains("IllegalArgumentException"))
        assertEquals(thread.name, payload.threadName)
    }

    @Test
    fun `a different throwable still never asserts isAnr — the absence is not tied to one exception type`() {
        val payload = CrashPayloads.fromUncaughtException(Thread.currentThread(), RuntimeException("other"))

        assertNull(payload.isAnr)
    }

    /**
     * R-1123: [CrashPayloads.fromMainThreadStall] is the one path where `isAnr` is a genuine,
     * measured `true` — a real stall was observed by [AnrWatchdog], not inferred from an
     * exception that was never thrown, which is why `exceptionClass` here is a synthetic, closed
     * label rather than a real `Throwable`'s class name.
     */
    @Test
    fun `R_1123_fromMainThreadStall reports a genuine isAnr true, never the unmeasured null`() {
        val stackTrace = listOf(
            StackTraceElement("org.ort.app.Foo", "bar", "Foo.kt", 42),
            StackTraceElement("org.ort.app.Foo", "baz", "Foo.kt", 10),
        )

        val payload = CrashPayloads.fromMainThreadStall(stackTrace)

        assertEquals(true, payload.isAnr)
        assertEquals("main", payload.threadName)
        assertEquals("MainThreadAnr", payload.exceptionClass)
        assertTrue(payload.stackTrace.contains("org.ort.app.Foo.bar(Foo.kt:42)"))
        assertTrue(payload.stackTrace.contains("org.ort.app.Foo.baz(Foo.kt:10)"))
    }
}
