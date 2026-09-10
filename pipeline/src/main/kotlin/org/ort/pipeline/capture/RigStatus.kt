package org.ort.pipeline.capture

import org.ort.rig.RigTransportKind

/**
 * F9 (register R-104): the rig's (radio's) CAT-connection state, readable by the status surface —
 * the same process-wide holder pattern [ShedStatus]/[CaptureState] use. Until WPC2, this object's
 * only real producer was [absent] (the rig module itself was unbuilt, register R-084); WPC2's
 * [org.ort.pipeline.rig.RigSupervisor] is now the real producer of [connected]/[stale], driven by
 * a live [org.ort.rig.RigModule]. The scenario simulator remains a producer for debug boards.
 *
 * `Stale` is distinct from `Absent`: a rig that *was* connected and dropped mid-session
 * ([Fail-Rig.dc.html] — "radio disconnected at 04:02 — frequency is stale") still carries its
 * [State.Connected.lastKnown] facts, marked stale from the moment it stopped reporting; a rig that
 * was never configured at all carries nothing to fall back on.
 *
 * [State.Connected.transportKind] and [State.Connected.descriptorId] (WPC2, E2-B09/E2-D08) name
 * which transport and which rig descriptor produced this reading, so the UI never re-derives
 * either fact from somewhere else (F9/CF06 name the transport directly). Both are trailing,
 * defaulted parameters — added to the existing shape rather than replacing it — so every call site
 * that predates WPC2 (the debug scenarios, screen-level tests across `:app`) keeps compiling
 * unchanged; a caller that does not know the transport yet simply does not pass it.
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
        /** No rig configured (the pre-WPC2 only real producer — see class kdoc). */
        public data object Absent : State

        public data class Connected(
            public val descriptor: String,
            public val bands: List<BandState>,
            /** `null` only for a caller that predates WPC2 and never supplied one (see class kdoc). */
            public val transportKind: RigTransportKind? = null,
            /** The [org.ort.rig.descriptor.RigDescriptor.id] (or [org.ort.rig.NullRigModule.ID])
             * this reading came from. */
            public val descriptorId: String? = null,
        ) : State

        /** The rig stopped reporting at [sinceMillis]; [lastKnown] is what it reported last — including
         * its own [Connected.transportKind]/[Connected.descriptorId], carried forward unchanged. */
        public data class Stale(public val lastKnown: Connected, public val sinceMillis: Long) : State
    }

    @Volatile
    public var state: State = State.Absent
        private set

    public fun absent() {
        state = State.Absent
    }

    public fun connected(
        descriptor: String,
        bands: List<BandState>,
        transportKind: RigTransportKind? = null,
        descriptorId: String? = null,
    ) {
        state = State.Connected(descriptor, bands, transportKind, descriptorId)
    }

    public fun stale(lastKnown: State.Connected, sinceMillis: Long) {
        state = State.Stale(lastKnown, sinceMillis)
    }

    public fun reset() {
        state = State.Absent
    }
}
