package org.ort.core.capture

/**
 * Where an [AudioRouteKind] selection came from (FR-CAP-9, FR-CAP-13): the mode that preset it,
 * and whether the operator then changed it. Both axes of a capture mode remain independently
 * overridable — a preset is a default the operator can ignore, never an enforcement — so this is
 * how a session's route can be attributed back to "the mode said so" versus "the operator chose
 * differently", without re-deriving it from the mode alone (a mode's preset can change in a later
 * release; what actually happened at onboarding cannot).
 */
public data class AudioRouteProvenance(public val presetByMode: CaptureMode, public val overridden: Boolean)
