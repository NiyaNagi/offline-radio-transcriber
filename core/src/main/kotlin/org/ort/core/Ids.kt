package org.ort.core

/**
 * Typed wrappers over a [Ulid] so a `SessionId` can never be passed where a `TransmissionId`
 * is expected. All are value classes — zero allocation over the underlying string.
 */
@JvmInline
public value class SessionId(public val ulid: Ulid) {
    override fun toString(): String = ulid.value

    public companion object {
        public fun new(clock: Clock = SystemClock): SessionId = SessionId(Ulid.generate(clock))
        public fun parse(text: String): SessionId = SessionId(Ulid.parse(text))
    }
}

@JvmInline
public value class TransmissionId(public val ulid: Ulid) {
    override fun toString(): String = ulid.value

    public companion object {
        public fun new(clock: Clock = SystemClock): TransmissionId = TransmissionId(Ulid.generate(clock))
        public fun parse(text: String): TransmissionId = TransmissionId(Ulid.parse(text))
    }
}

@JvmInline
public value class PassResultId(public val ulid: Ulid) {
    override fun toString(): String = ulid.value

    public companion object {
        public fun new(clock: Clock = SystemClock): PassResultId = PassResultId(Ulid.generate(clock))
        public fun parse(text: String): PassResultId = PassResultId(Ulid.parse(text))
    }
}
