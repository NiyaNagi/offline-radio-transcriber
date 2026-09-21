package org.ort.app.analytics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build
import org.ort.telemetry.AnalyticsTier1Payload

/**
 * R-1123: the retrospective half of "native aborts, ANRs and OOM are unreportable." A native
 * abort (an uncaught `Ort::Exception` from the sherpa JNI reaching `std::terminate`) is a
 * `SIGABRT`, not a Java exception — `Thread.UncaughtExceptionHandler` ([CrashCaptureHandler]) never
 * fires for it, and nothing else in this codebase ever has. Writing a native signal handler is
 * genuinely out of scope for this change (constitution VII's "guarantees are expressed as types
 * where possible" does not reach into libsherpa's C++), but Android has had an in-process, no-SDK
 * answer to exactly this since API 30: `ActivityManager.getHistoricalProcessExitReasons` records
 * why the OS believes the *previous* process instance ended, including
 * [ApplicationExitInfo.REASON_CRASH_NATIVE], [ApplicationExitInfo.REASON_ANR] and
 * [ApplicationExitInfo.REASON_LOW_MEMORY] — this is the same mechanism Play Console's own vitals
 * are built on, so reading it here rather than leaving the gap silent is the "make the absence
 * visible" this row asks for, applied on the very next launch instead of never.
 *
 * [ExitReasonSample] is a plain, closed projection of one [ApplicationExitInfo] (never the
 * platform type itself) so [classify] and [buildCrashPayloads] — the actual logic — are testable
 * on a plain JVM, with no Robolectric and no device; only the thin Android-facing read in
 * `org.ort.app.OrtApplication` touches [ApplicationExitInfo] or requires API 30.
 */
public object ProcessExitReasonReporter {

    /**
     * A plain projection of one [ApplicationExitInfo]. [description] is Android's own short,
     * system-generated diagnostic string (e.g. `"Native crash"`) — the same "code/diagnostic
     * content, never operator or third-party text" category [AnalyticsTier1Payload.Crash
     * .stackTrace] already carries elsewhere in this file, never anything the operator or a
     * station typed.
     */
    public data class ExitReasonSample(val reasonCode: Int, val timestampMillis: Long, val description: String?)

    /** One classified, reported exit — [kind] is why this was interesting enough to report,
     * [payload] is the tier-1 event [org.ort.app.OrtApplication] submits for it. */
    public data class ClassifiedExit(
        val sample: ExitReasonSample,
        val kind: ExitReasonClass,
        val payload: AnalyticsTier1Payload.Crash,
    )

    /**
     * The three [ApplicationExitInfo.getReason] values worth a tier-1 event. Everything else
     * (`REASON_USER_REQUESTED`, `REASON_EXIT_SELF`, an ordinary `REASON_SIGNALED` from the OS
     * reclaiming a backgrounded process, ...) is routine process churn on Android, not evidence —
     * reporting every process death would be noise dressed up as a finding, exactly what
     * constitution I's "precision outranks recall" warns against.
     */
    public fun classify(reasonCode: Int): ExitReasonClass? = when (reasonCode) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> ExitReasonClass.NATIVE_CRASH
        ApplicationExitInfo.REASON_ANR -> ExitReasonClass.ANR
        ApplicationExitInfo.REASON_LOW_MEMORY -> ExitReasonClass.LOW_MEMORY
        else -> null
    }

    /**
     * Only [samples] strictly newer than [sinceMillisExclusive] are ever reported. The OS keeps a
     * rolling window of past exits that survives across restarts (and reboots), so without this
     * bound the same historical native crash would be re-submitted on every subsequent launch
     * forever — the caller is responsible for persisting the newest
     * [ExitReasonSample.timestampMillis] this returns as the next launch's own
     * [sinceMillisExclusive] (see `org.ort.app.OrtApplication`'s own wiring).
     *
     * **[AnalyticsTier1Payload.Crash.isAnr] is a genuine, measured `true` or `false` here — the
     * first time anywhere in this codebase this field is not the "never measured" `null`
     * [CrashPayloads.fromUncaughtException] must report** — because [ApplicationExitInfo
     * .getReason] is an OS-level fact about what already happened, not an inference.
     */
    public fun buildCrashPayloads(samples: List<ExitReasonSample>, sinceMillisExclusive: Long): List<ClassifiedExit> =
        samples
            .filter { it.timestampMillis > sinceMillisExclusive }
            .sortedBy { it.timestampMillis }
            .mapNotNull { sample ->
                val kind = classify(sample.reasonCode) ?: return@mapNotNull null
                ClassifiedExit(sample, kind, toCrash(sample, kind))
            }

    private fun toCrash(sample: ExitReasonSample, kind: ExitReasonClass): AnalyticsTier1Payload.Crash =
        AnalyticsTier1Payload.Crash(
            exceptionClass = "PreviousProcessExit.${kind.name}",
            stackTrace = sample.description.orEmpty(),
            isAnr = kind == ExitReasonClass.ANR,
            threadName = "process",
        )

    /**
     * The one Android-facing read in this file — everything else above is plain-JVM-testable.
     * Self-guarded rather than `@RequiresApi`-annotated (avoiding a new `androidx.annotation`
     * dependency for one call site): `ActivityManager.getHistoricalProcessExitReasons` is API 30+,
     * and this app's minSdk is 26, so a device below API 30 gets an empty list — genuinely no
     * native-abort/ANR/low-memory visibility on those OS versions, a real, documented limitation
     * rather than a crash. [maxCount] bounds how far back into the OS's own rolling history a
     * single launch reads; [buildCrashPayloads]'s watermark keeps every later launch cheap
     * regardless.
     */
    public fun readExitReasonSamples(activityManager: ActivityManager, maxCount: Int = 16): List<ExitReasonSample> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return activityManager.getHistoricalProcessExitReasons(null, 0, maxCount)
            .map { info -> ExitReasonSample(info.reason, info.timestamp, info.description) }
    }
}

/** The closed set of [android.app.ApplicationExitInfo] reasons [ProcessExitReasonReporter.classify]
 * ever turns into a tier-1 event. */
public enum class ExitReasonClass { NATIVE_CRASH, ANR, LOW_MEMORY }
