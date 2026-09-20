package org.ort.app.analytics

import org.ort.telemetry.AnalyticsTier1Payload

/**
 * FR-ANL-2/D42, constitution I. Extracted out of `OrtApplication.onCreate`'s own
 * `CrashCaptureHandler` wiring (that class body never runs under Robolectric —
 * `OrtApplication.isRunningUnderRobolectric` — so a plain, dependency-free function is the only
 * way this codebase can put a discriminating test on what that call site actually builds) so
 * [fromUncaughtException] itself is testable with no Android dependency and no device.
 *
 * **[AnalyticsTier1Payload.Crash.isAnr] is always `null` here.** No ANR-detection mechanism exists
 * anywhere in this codebase (confirmed before this change; see this file's own CHANGELOG entry) —
 * `Thread.UncaughtExceptionHandler`, the one path that reaches this function, fires for an
 * uncaught exception, never for a hung main thread, so it can never truthfully answer "was this an
 * ANR." Asserting `false` here would claim "measured, and not an ANR" when the true fact is "never
 * measured" — the silent wrongness constitution I forbids. `null` is the honest state until a real
 * watchdog exists to set this field for real.
 */
public object CrashPayloads {
    public fun fromUncaughtException(thread: Thread, throwable: Throwable): AnalyticsTier1Payload.Crash =
        AnalyticsTier1Payload.Crash(
            exceptionClass = throwable.javaClass.name,
            stackTrace = throwable.stackTraceToString(),
            isAnr = null,
            threadName = thread.name,
        )
}
