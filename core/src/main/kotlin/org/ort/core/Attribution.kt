package org.ort.core

/**
 * The closed set of attribution states (constitution I; FR-SPK-10, D7, G4). Every attribution
 * carries exactly one. Nothing outside this enum is a valid state.
 */
public enum class AttributionState {
    /** Heard and resolved in *this* transmission. Never reached by a voice match or cross-session inference. */
    CONFIRMED,

    /** A single best station, but the callsign was not heard in this transmission — carried from context or voice. */
    INFERRED,

    /** Two or more candidates within the separation threshold; the system will not choose (FR-LEX-11). */
    AMBIGUOUS,

    /** No candidate the system is willing to assert. */
    UNKNOWN,
}

/**
 * An attribution of a transmission to a station. The [state] is **non-optional** — there is no
 * constructor that omits it (constitution I: this is a type-level obligation, enforced at the
 * data layer, not a UI convention).
 *
 * `CONFIRMED` additionally requires a resolved [stationId]; the factory functions make the
 * invalid combinations unrepresentable.
 */
public class Attribution private constructor(
    public val state: AttributionState,
    public val stationId: String?,
    public val confidence: Double?,
    /** The transmission a non-`CONFIRMED` attribution was carried from, if any (FR-SPK-4/7). */
    public val sourceTransmissionId: TransmissionId?,
    /** A user correction locks the attribution against re-propagation (FR-SPK-7). */
    public val corrected: Boolean,
) {
    public fun withCorrection(stationId: String): Attribution = Attribution(
        state = AttributionState.INFERRED,
        stationId = stationId,
        confidence = null,
        sourceTransmissionId = null,
        corrected = true,
    )

    override fun equals(other: Any?): Boolean = other is Attribution &&
        other.state == state &&
        other.stationId == stationId &&
        other.confidence == confidence &&
        other.sourceTransmissionId == sourceTransmissionId &&
        other.corrected == corrected

    override fun hashCode(): Int = listOf(state, stationId, confidence, sourceTransmissionId, corrected).hashCode()

    override fun toString(): String =
        "Attribution($state, station=$stationId, confidence=$confidence, corrected=$corrected)"

    public companion object {
        /** Heard and resolved in this transmission. Requires a station and a calibrated confidence. */
        public fun confirmed(stationId: String, confidence: Double): Attribution {
            require(stationId.isNotBlank()) { "CONFIRMED requires a resolved station" }
            require(confidence in 0.0..1.0) { "confidence must be a calibrated probability in [0,1]" }
            return Attribution(AttributionState.CONFIRMED, stationId, confidence, null, corrected = false)
        }

        /** A single best station carried from context or a voice match — never promoted to CONFIRMED. */
        public fun inferred(
            stationId: String,
            confidence: Double,
            sourceTransmissionId: TransmissionId? = null,
        ): Attribution {
            require(stationId.isNotBlank()) { "INFERRED requires a station" }
            require(confidence in 0.0..1.0) { "confidence must be a calibrated probability in [0,1]" }
            return Attribution(
                AttributionState.INFERRED,
                stationId,
                confidence,
                sourceTransmissionId,
                corrected = false,
            )
        }

        /** Top candidates too close to separate (FR-LEX-11). Precision outranks recall (NFR-1a). */
        public fun ambiguous(): Attribution =
            Attribution(AttributionState.AMBIGUOUS, null, null, null, corrected = false)

        /** Nothing worth asserting. */
        public fun unknown(): Attribution = Attribution(AttributionState.UNKNOWN, null, null, null, corrected = false)
    }
}
