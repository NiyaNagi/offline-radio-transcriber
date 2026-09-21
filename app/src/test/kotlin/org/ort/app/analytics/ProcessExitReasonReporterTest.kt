package org.ort.app.analytics

import android.app.ApplicationExitInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.app.analytics.ProcessExitReasonReporter.ExitReasonSample

/**
 * R-1123: the retrospective half of "native aborts, ANRs and OOM are unreportable" —
 * [ProcessExitReasonReporter] turns Android's own [android.app.ApplicationExitInfo] (API 30's
 * in-process, no-signal-handler answer to "why did my process die last time") into the same
 * tier-1 `Crash` shape [CrashPayloads] already produces. A plain JVM test: the real
 * `ApplicationExitInfo.REASON_*` constants are compile-time `int` constants inlined by the
 * compiler, so referencing them here needs no Robolectric and no device.
 */
class ProcessExitReasonReporterTest {

    @Test
    fun `R_1123_a native crash reason is classified and reported with a real, measured isAnr false`() {
        val classified = ProcessExitReasonReporter.buildCrashPayloads(
            samples = listOf(ExitReasonSample(ApplicationExitInfo.REASON_CRASH_NATIVE, 1_000L, "Native crash")),
            sinceMillisExclusive = 0L,
        )

        assertEquals(1, classified.size)
        val payload = classified.single().payload
        assertEquals(ExitReasonClass.NATIVE_CRASH, classified.single().kind)
        assertEquals(false, payload.isAnr, "a native crash is a measured fact, not an unmeasured null")
        assertEquals("PreviousProcessExit.NATIVE_CRASH", payload.exceptionClass)
        assertTrue(payload.stackTrace.contains("Native crash"))
    }

    @Test
    fun `R_1123_an ANR reason is classified with a genuine isAnr true`() {
        val classified = ProcessExitReasonReporter.buildCrashPayloads(
            samples = listOf(ExitReasonSample(ApplicationExitInfo.REASON_ANR, 1_000L, "ANR")),
            sinceMillisExclusive = 0L,
        )

        assertEquals(true, classified.single().payload.isAnr)
        assertEquals(ExitReasonClass.ANR, classified.single().kind)
    }

    @Test
    fun `R_1123_a low-memory kill reason is classified as the OOM-adjacent signal`() {
        val classified = ProcessExitReasonReporter.buildCrashPayloads(
            samples = listOf(ExitReasonSample(ApplicationExitInfo.REASON_LOW_MEMORY, 1_000L, "Low memory")),
            sinceMillisExclusive = 0L,
        )

        assertEquals(ExitReasonClass.LOW_MEMORY, classified.single().kind)
        assertEquals(false, classified.single().payload.isAnr)
    }

    @Test
    fun `R_1123_an uninteresting reason — the operator swiping the app away — is never reported`() {
        assertNull(ProcessExitReasonReporter.classify(ApplicationExitInfo.REASON_USER_REQUESTED))

        val classified = ProcessExitReasonReporter.buildCrashPayloads(
            samples = listOf(ExitReasonSample(ApplicationExitInfo.REASON_USER_REQUESTED, 1_000L, "user requested")),
            sinceMillisExclusive = 0L,
        )

        assertTrue(classified.isEmpty(), "routine process churn must never be reported as a finding")
    }

    @Test
    fun `R_1123_a sample at or before the last-processed watermark is never re-reported`() {
        val classified = ProcessExitReasonReporter.buildCrashPayloads(
            samples = listOf(ExitReasonSample(ApplicationExitInfo.REASON_CRASH_NATIVE, 1_000L, "Native crash")),
            sinceMillisExclusive = 1_000L, // already processed on a previous launch
        )

        assertTrue(classified.isEmpty(), "a sample already reported on a prior launch must not repeat forever")
    }

    @Test
    fun `R_1123_only samples newer than the watermark are reported, oldest first`() {
        val classified = ProcessExitReasonReporter.buildCrashPayloads(
            samples = listOf(
                ExitReasonSample(ApplicationExitInfo.REASON_ANR, 3_000L, "ANR 2"),
                ExitReasonSample(ApplicationExitInfo.REASON_CRASH_NATIVE, 2_000L, "Native crash"),
                ExitReasonSample(ApplicationExitInfo.REASON_ANR, 500L, "too old"),
            ),
            sinceMillisExclusive = 1_000L,
        )

        assertEquals(listOf(2_000L, 3_000L), classified.map { it.sample.timestampMillis })
    }
}
