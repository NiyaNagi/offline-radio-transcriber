package org.ort.pipeline.alerts

/**
 * Build-plan P31 (FR-ALR-1): what the operator asked to be told about. A closed, sealed set —
 * constitution VII ("guarantees are expressed as types where possible") — so a watch can never
 * carry both a callsign and a frequency, or a frequency stored as free text: the three kinds
 * FR-ALR-1 names are the only three constructible shapes.
 *
 * [id] is a stable [org.ort.core.Ulid] value, minted once at [add][AlertWatchStore.add] time and
 * never reused — [AlertWatchStore.update]/[AlertWatchStore.remove] key on it, not on the watched
 * value itself, so editing "K7ABC" into "K7XYZ" does not read as deleting one watch and creating
 * an unrelated one.
 */
public sealed class AlertWatch {
    public abstract val id: String
    public abstract val enabled: Boolean

    /** FR-ALR-1: matched against the transmission's *resolved* station id (Pass B/D, never a Pass
     * A partial — [AlertMatcher]'s own doc comment). Case-insensitive, exact. */
    public data class Callsign(override val id: String, val callsign: String, override val enabled: Boolean = true) :
        AlertWatch()

    /** FR-ALR-1: matched against the accepted transcript text. Case-insensitive substring. */
    public data class Keyword(override val id: String, val keyword: String, override val enabled: Boolean = true) :
        AlertWatch()

    /** FR-ALR-1: matched against the transmission's own [org.ort.data.entity.TransmissionEntity.frequencyHz],
     * within [AlertMatcher.FREQUENCY_TOLERANCE_HZ] — "a frequency waking up" (functional spec
     * §7.19) tolerates ordinary rig retuning drift, not only a bit-exact reading. */
    public data class Frequency(override val id: String, val frequencyHz: Long, override val enabled: Boolean = true) :
        AlertWatch()

    /** A short, kind-labelled description for a notification title / a Settings row — never the
     * only place attribution state is stated (that is [AlertNotificationContentBuilder]'s job). */
    public fun displayValue(): String = when (this) {
        is Callsign -> callsign
        is Keyword -> keyword
        is Frequency -> "%.3f MHz".format(java.util.Locale.ROOT, frequencyHz / 1_000_000.0)
    }
}
