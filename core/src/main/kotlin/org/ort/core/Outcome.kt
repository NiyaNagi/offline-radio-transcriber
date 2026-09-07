package org.ort.core

/**
 * A minimal result type for operations that fail in ways the caller must handle explicitly
 * (asset verification, descriptor parsing, config loading). Distinct from a pass's
 * `PassOutcome`, which lives in `:pipeline` and carries the Rejected/Failed distinction.
 */
public sealed interface Outcome<out T> {

    public data class Ok<out T>(val value: T) : Outcome<T>

    public data class Err(val reason: String, val cause: Throwable? = null) : Outcome<Nothing>

    public fun getOrNull(): T? = (this as? Ok)?.value

    public fun <R> map(transform: (T) -> R): Outcome<R> = when (this) {
        is Ok -> Ok(transform(value))
        is Err -> this
    }

    public fun <R> flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
        is Ok -> transform(value)
        is Err -> this
    }

    public companion object {
        public inline fun <T> catching(reason: String, block: () -> T): Outcome<T> = try {
            Ok(block())
        } catch (t: Throwable) {
            Err(reason, t)
        }
    }
}
