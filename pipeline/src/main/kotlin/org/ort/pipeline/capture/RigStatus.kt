package org.ort.pipeline.capture

/**
 * F9 (register R-104): the rig's (radio's) CAT-connection state, readable by the status surface —
 * the same process-wide holder pattern [ShedStatus]/[CaptureState] use. The rig module itself
 * (FR-RIG) is unbuilt (register R-084), so this object's only real producer today is [absent] —
 * [RealCaptureService] calls it once per session, honestly, because there genuinely is no rig
 * connected. The scenario simulator ([connected]/[stale]) is the only other producer until FR-RIG
 * lands; this is deliberately the small, complete API surface that work will fill in.
 *
 * `Stale` is distinct from `Absent`: a rig that *was* connected and dropped mid-session
 * ([Fail-Rig.dc.html] — "radio disconnected at 04:02 — frequency is stale") still carries its
 * [State.Connected.lastKnown] facts, marked stale from the moment it stopped reporting; a rig that
 * was never configured at all carries nothing to fall back on.
 */
public object RigStatus {

    /** One band's last-reported facts. `squelchOpen` is a live fact, not carried across a [State.Stale]. */
    public data class BandState(
        public val band: String,
        public val frequencyHz: Long?,
        public val mode: String?,
        public val squelchOpen: Boolean,
    )

    public sealed interface State {
        /** No rig configured (today's only real producer — see class kdoc). */
        public data object Absent : State

        public data class Connected(public val descriptor: String, public val bands: List<BandState>) : State

        /** The rig stopped reporting at [sinceMillis]; [lastKnown] is what it reported last. */
        public data class Stale(public val lastKnown: Connected, public val sinceMillis: Long) : State
    }

    @Volatile
    public var state: State = State.Absent
        private set

    public fun absent() {
        state = State.Absent
    }

    public fun connected(descriptor: String, bands: List<BandState>) {
        state = State.Connected(descriptor, bands)
    }

    public fun stale(lastKnown: State.Connected, sinceMillis: Long) {
        state = State.Stale(lastKnown, sinceMillis)
    }

    public fun reset() {
        state = State.Absent
    }
}
