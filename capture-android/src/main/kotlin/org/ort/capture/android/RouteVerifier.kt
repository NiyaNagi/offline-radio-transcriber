package org.ort.capture.android

/**
 * technical design §5.2 — route verification (FR-CAP-3, FR-CAP-3a → AC-2, AC-98). Recording the
 * room instead of the radio is the highest-consequence silent failure in the system (constitution
 * IV), so this comparison is the one thing standing between "the user selected a USB adapter" and
 * "the built-in mic quietly took over": a mismatch is reported, **never** silently substituted.
 *
 * A deliberate selection of the built-in mic is legal — [RouteVerdict.Ok.isBuiltInMic] carries
 * that fact forward so the session can be persistently labelled (FR-CAP-3a).
 */
public sealed interface RouteVerdict {
    public data class Ok(val routed: AudioDeviceDescriptor) : RouteVerdict {
        public val isBuiltInMic: Boolean get() = routed.kind == AudioDeviceKind.BUILT_IN_MIC
    }

    public data class Mismatch(
        val selected: AudioDeviceDescriptor,
        val routed: AudioDeviceDescriptor?,
        val reason: String,
    ) : RouteVerdict
}

public object RouteVerifier {

    /** Compares what the OS actually routed to against what was selected. Pure; no I/O. */
    public fun verify(selected: AudioDeviceDescriptor, routed: AudioDeviceDescriptor?): RouteVerdict {
        if (routed == null) return RouteVerdict.Mismatch(selected, null, "no device routed")
        if (routed.id == selected.id) return RouteVerdict.Ok(routed)
        return RouteVerdict.Mismatch(
            selected,
            routed,
            "routed to '${routed.label}' (${routed.kind}); selected '${selected.label}' (${selected.kind})",
        )
    }
}
