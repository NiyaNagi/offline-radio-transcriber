package org.ort.app.analytics

/**
 * FR-ANL-2/D42: the crash-and-ANR half of tier 1, without any third-party crash-reporting SDK
 * (constitution VII: only `:net` may link an HTTP client, and no analytics SDK exists anywhere in
 * this codebase — D48). Wraps whatever [delegate] `Thread.setDefaultUncaughtExceptionHandler` had
 * already installed (the platform's own, or none) — [record] always runs first and is always
 * wrapped in [runCatching], so a failure while recording can never suppress the app's real crash
 * behaviour, and [delegate] is always invoked afterwards regardless of whether recording
 * succeeded.
 */
public class CrashCaptureHandler(
    private val delegate: Thread.UncaughtExceptionHandler?,
    private val record: (Thread, Throwable) -> Unit,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching { record(thread, throwable) }
        delegate?.uncaughtException(thread, throwable)
    }
}
