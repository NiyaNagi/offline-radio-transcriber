package org.ort.capture.android.service

/**
 * The notification's content — and nothing else. This type has no field capable of holding
 * transcript text; that is what makes AC-61 (FR-PLT-3) a structural guarantee rather than a
 * reviewer's promise (constitution VII: guarantees are expressed as types where possible).
 */
public data class CaptureNotificationContent(
    val stateLabel: String,
    val elapsedLabel: String,
    val transmissionCount: Int,
) {
    public val title: String = "Capturing"
    public val text: String = "$stateLabel · $elapsedLabel · $transmissionCount transmissions"
}

public object CaptureNotificationBuilder {

    /** Builds notification content from state/elapsed/count only — never a transcript (AC-61). */
    public fun build(state: String, elapsedMillis: Long, transmissionCount: Int): CaptureNotificationContent {
        val totalSeconds = elapsedMillis / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return CaptureNotificationContent(state, "%02d:%02d:%02d".format(h, m, s), transmissionCount)
    }
}
