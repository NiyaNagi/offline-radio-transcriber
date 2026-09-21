package org.ort.app.analytics

import org.ort.telemetry.AnalyticsTier1Payload

/**
 * FR-ANL-2/D42, constitution I. Extracted out of `OrtApplication.onCreate`'s own
 * `CrashCaptureHandler` wiring (that class body never runs under Robolectric —
 * `OrtApplication.isRunningUnderRobolectric` — so a plain, dependency-free function is the only
 * way this codebase can put a discriminating test on what that call site actually builds) so
 * [fromUncaughtException] itself is testable with no Android dependency and no device.
 *
 * **[AnalyticsTier1Payload.Crash.isAnr] is always `null` from [fromUncaughtException].**
 * `Thread.UncaughtExceptionHandler`, the one path that reaches that function, fires for an
 * uncaught exception, never for a hung main thread, so it can never truthfully answer "was this an
 * ANR." Asserting `false` there would claim "measured, and not an ANR" when the true fact is
 * "never measured" — the silent wrongness constitution I forbids. `null` is the honest state for
 * that path.
 *
 * **R-1123 gives this object a second, genuine source of `isAnr`.** [fromMainThreadStall] (backed
 * by [AnrWatchdog]) and [org.ort.app.analytics.ProcessExitReasonReporter] (backed by the OS's own
 * [android.app.ApplicationExitInfo] on the *next* launch) each report a real, measured `true` or
 * `false` — the first live main-thread-stall detector and the first native-crash/ANR/low-memory
 * visibility this codebase has ever had, neither needing a third-party crash SDK or a signal
 * handler. `fromUncaughtException`'s `null` is unchanged and still correct for what it measures;
 * these are additional, independent facts, not a replacement for it.
 */
public object CrashPayloads {
    public fun fromUncaughtException(thread: Thread, throwable: Throwable): AnalyticsTier1Payload.Crash =
        AnalyticsTier1Payload.Crash(
            exceptionClass = throwable.javaClass.name,
            stackTrace = throwable.stackTraceToString(),
            isAnr = null,
            threadName = thread.name,
        )

    /**
     * R-1123: [AnrWatchdog]'s own report — the one path in this codebase where [isAnr] is
     * genuinely `true` rather than the "never measured" `null` [fromUncaughtException] must
     * report, because a real main-thread stall was actually observed, not inferred. [stackTrace]
     * is the main thread's own frames at the moment the stall was detected ([Thread.getStackTrace]
     * called from the watchdog's background thread — code/line-number content only, the same
     * vocabulary rule [fromUncaughtException]'s `stackTrace` already follows), never a message a
     * user or a station typed. `"MainThreadAnr"` in [AnalyticsTier1Payload.Crash.exceptionClass]'s
     * place is a synthetic, closed label (there is no real `Throwable` here — nothing was thrown,
     * the thread was simply unresponsive) so a consumer of this event stream can tell a watchdog
     * report apart from a real uncaught exception without a separate field.
     */
    public fun fromMainThreadStall(mainThreadStackTrace: List<StackTraceElement>): AnalyticsTier1Payload.Crash =
        AnalyticsTier1Payload.Crash(
            exceptionClass = "MainThreadAnr",
            stackTrace = mainThreadStackTrace.joinToString(separator = "\n"),
            isAnr = true,
            threadName = "main",
        )
}
