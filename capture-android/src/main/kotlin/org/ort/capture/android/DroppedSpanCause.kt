package org.ort.capture.android

/**
 * The single encoding [AudioRecordSource] uses to report a span of audio it detected it could
 * not keep up with — a stalled downstream collector or an `AudioRecord` shortfall (AC-3,
 * constitution IV: "silence that was never listened to MUST be distinguishable from silence that
 * was"). It travels through [org.ort.captureapi.CaptureEvent.Interrupted]'s `cause` field rather
 * than a new [org.ort.captureapi.CaptureEvent] variant: `:pipeline`'s `RealCaptureService`
 * switches over that sealed interface exhaustively with no `else`, and `:capture-android` does
 * not own `:pipeline` — adding a case there is out of this fix's scope (see F-010's fix sketch).
 * Reusing `Interrupted`/[org.ort.captureapi.CaptureEvent.Resumed], which every existing consumer
 * already handles, keeps the whole span self-describing without crossing that boundary.
 *
 * [GapTracker] recognises this exact prefix and, unlike a genuine interruption, closes the gap
 * immediately from the encoded duration rather than waiting to observe a paired `Resumed` — the
 * whole span is already known at the moment it is detected, after the fact.
 */
internal object DroppedSpanCause {
    private const val PREFIX = "dropped samples:"
    private val DURATION_PATTERN = Regex("""over (\d+)ms""")

    /** Encodes [sampleCount] samples, lost over [durationMillis] of real time, as a cause string. */
    internal fun encode(sampleCount: Long, durationMillis: Long): String =
        "$PREFIX $sampleCount samples over ${durationMillis}ms (stalled consumer or read shortfall)"

    /** The encoded duration in milliseconds, or `null` if [cause] is not one of ours. */
    internal fun durationMillisOrNull(cause: String): Long? {
        if (!cause.startsWith(PREFIX)) return null
        return DURATION_PATTERN.find(cause)?.groupValues?.get(1)?.toLongOrNull()
    }
}
