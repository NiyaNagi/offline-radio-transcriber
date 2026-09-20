package org.ort.pipeline.alerts

/**
 * Build-plan P31: one watch's match against one transmission, already coalesced by
 * [AlertEvaluationCoordinator] — see that class's own doc comment for the coalescing rule.
 *
 * [occurrenceCount] is the running total of matches this watch has produced since the coordinator
 * started counting (a fresh process, or [AlertEvaluationCoordinator.DEFAULT_COALESCE_WINDOW_MILLIS]
 * having elapsed since the last one) — a notification for [occurrenceCount] > 1 says so ("×3"),
 * rather than silently overwriting a prior match's own count with a bare "1" again.
 *
 * [isRepeat] is `true` for every match after the first *within* the coalescing window — this is
 * what [AndroidAlertNotificationDispatcher] reads to decide whether this firing may re-alert
 * (sound/heads-up) or must only update the existing notification's content in place.
 */
public data class AlertFiring(
    val watch: AlertWatch,
    val input: AlertMatchInput,
    val occurrenceCount: Int,
    val isRepeat: Boolean,
)
