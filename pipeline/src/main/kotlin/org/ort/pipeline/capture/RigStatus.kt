package org.ort.pipeline.capture

import org.ort.rig.RigCapability
import org.ort.rig.RigHealthIssue
import org.ort.rig.RigTransportKind

/**
 * R-1019 (WPRIG2): whether every capability the connected rig's descriptor declares for its
 * transport has actually been *observed*, not merely that a link opened — the same fact
 * [org.ort.pipeline.rig.RigLinkProbeState.Verified]/[org.ort.pipeline.rig.RigLinkProbeState
 * .VerifyTimedOut] already establish at setup time (R-1013/R-1014), now carried into
 * session-lifetime [RigStatus] too, because nothing did before this: `ReadyScreen` (S12) derived
 * its wording from [RigStatus.State.Connected] alone, which had no field for the partial fact at
 * all, so the last screen of setup said "verified" unconditionally — the same defect class as an
 * attribution without its confidence state (constitution I).
 *
 * A closed set, not a boolean, for the same reason [org.ort.pipeline.rig.RigLinkProbeState] is:
 * [Partial] must *name* what was never observed (S10b/S11's own wording — "identified, command
 * set partially confirmed" — reused here, not reinvented), and [Unknown] is the honest state for
 * a caller that never populated this at all, distinct from a caller that positively knows every
 * capability was seen ([Full]). [RigStatus.State.Connected.verification] defaults to [Unknown]
 * rather than [Full] specifically so a caller that forgets to state it can never silently claim
 * "verified" by omission — the one direction constitution I forbids.
 *
 * **This is deliberately a data fact, not a rendering guarantee**: Kotlin cannot force a consumer
 * that pattern-matches on [RigStatus.State.Connected] to also inspect [RigStatus.State.Connected
 * .verification] before choosing its wording — that exhaustiveness has to live in the `:app` code
 * that renders it (see this package's own report on `ReadyScreen`/`radioRowForCatRig`, the exact
 * call site this fixes the data for). Making [RigStatus.State.Connected.verification] itself
 * non-optional is what constitution I actually requires at the data layer; whether every consumer
 * remembers to read it is the follow-up this report names precisely rather than assumes.
 */
public sealed interface RigVerification {
    /** Every capability [org.ort.rig.RigModule.capabilities] declares for the active transport has
     * been observed at least once this connection — the only state that may render "verified". */
    public data object Full : RigVerification

    /** [missingCapabilities] is exactly what has never been observed yet — never empty (an empty
     * set is [Full], not [Partial]; enforced by [org.ort.pipeline.rig.RigSupervisor]'s own
     * construction, not by this type, since a closed sealed interface cannot itself forbid an
     * empty set without an `init` block this shared value type has no need to carry). */
    public data class Partial(public val missingCapabilities: Set<RigCapability>) : RigVerification

    /** No caller has stated this fact — the default, and the only honest reading for anything that
     * predates R-1019. Never renders as "verified". */
    public data object Unknown : RigVerification
}

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
            /** R-1019 (WPRIG2): see [RigVerification]'s own kdoc for the fact this carries and why
             * it defaults to [RigVerification.Unknown] rather than [RigVerification.Full]. */
            public val verification: RigVerification = RigVerification.Unknown,
        ) : State

        /**
         * The rig stopped reporting at [sinceMillis]; [lastKnown] is what it reported last —
         * including its own [Connected.transportKind]/[Connected.descriptorId], carried forward
         * unchanged. [attempt]/[ofTotal]/[nextRetryInMillis] (F9, WPC3) are
         * [org.ort.pipeline.ReconnectLadderPosition]'s fields, computed from the real
         * `UsbReconnectBackoff`/`BluetoothReconnectBackoff` this transport retries on — trailing,
         * defaulted `null` so every pre-existing caller of [RigStatus.stale] keeps compiling.
         */
        public data class Stale(
            public val lastKnown: Connected,
            public val sinceMillis: Long,
            public val attempt: Int? = null,
            public val ofTotal: Int? = null,
            public val nextRetryInMillis: Long? = null,
            /** R-1015 (WPRIG2): which [RigHealthIssue] this staleness reflects —
             * [RigHealthIssue.TRANSPORT_LOST] when the transport itself reported the link gone
             * (the original case, carrying [attempt]/[ofTotal]/[nextRetryInMillis] from the real
             * reconnect ladder the transport is retrying on) and [RigHealthIssue.TIMEOUT] when the
             * transport never reported anything wrong at all — it still believes it is open — but
             * the rig has answered nothing for [org.ort.pipeline.rig.RigSupervisor]'s own bounded
             * wait. No reconnect ladder runs underneath that second case (neither
             * `UsbSerialTransport` nor `BluetoothSppTransport` has any reason to retry a link it
             * still believes is open), so [attempt]/[ofTotal]/[nextRetryInMillis] stay `null`
             * there rather than reporting a countdown that does not exist (constitution I: never
             * assert more than is known). `null` only for a caller that predates R-1015 and never
             * supplied one — reused vocabulary, not a parallel one: this is exactly
             * [org.ort.rig.RigHealth.Degraded.issue], the signal
             * [org.ort.rig.descriptor.DescriptorRigModule.health] already emitted and nothing read
             * before this fix (see this package's own report).
             */
            public val issue: RigHealthIssue? = null,
        ) : State
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
        verification: RigVerification = RigVerification.Unknown,
    ) {
        state = State.Connected(descriptor, bands, transportKind, descriptorId, verification)
    }

    public fun stale(
        lastKnown: State.Connected,
        sinceMillis: Long,
        attempt: Int? = null,
        ofTotal: Int? = null,
        nextRetryInMillis: Long? = null,
        issue: RigHealthIssue? = null,
    ) {
        state = State.Stale(lastKnown, sinceMillis, attempt, ofTotal, nextRetryInMillis, issue)
    }

    public fun reset() {
        state = State.Absent
    }
}
