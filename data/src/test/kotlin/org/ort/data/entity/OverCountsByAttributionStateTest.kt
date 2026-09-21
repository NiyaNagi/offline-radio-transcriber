package org.ort.data.entity

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ort.core.AttributionState

/**
 * Register R-1132, D56 (FR-DIG-7, constitution I): the wire format
 * [StationEntity.overCountsByAttributionState] uses, exercised as a plain value type — no
 * database needed, since [OverCountsByAttributionState] has no Room dependency of its own.
 */
public class OverCountsByAttributionStateTest {

    @Test
    public fun a_fresh_count_set_serializes_every_state_at_zero_in_enum_order() {
        assertEquals(
            "CONFIRMED=0,INFERRED=0,AMBIGUOUS=0,UNKNOWN=0",
            OverCountsByAttributionState.EMPTY.serialize(),
        )
    }

    @Test
    public fun increment_adds_one_to_only_the_named_state() {
        val counts = OverCountsByAttributionState.EMPTY.increment(AttributionState.AMBIGUOUS)

        assertEquals(1, counts.countFor(AttributionState.AMBIGUOUS))
        assertEquals(0, counts.countFor(AttributionState.CONFIRMED))
        assertEquals("CONFIRMED=0,INFERRED=0,AMBIGUOUS=1,UNKNOWN=0", counts.serialize())
    }

    @Test
    public fun repeated_increments_accumulate_rather_than_overwrite() {
        val counts = OverCountsByAttributionState.EMPTY
            .increment(AttributionState.AMBIGUOUS)
            .increment(AttributionState.AMBIGUOUS)
            .increment(AttributionState.CONFIRMED)

        assertEquals(2, counts.countFor(AttributionState.AMBIGUOUS))
        assertEquals(1, counts.countFor(AttributionState.CONFIRMED))
    }

    @Test
    public fun parse_is_the_exact_inverse_of_serialize() {
        val counts = OverCountsByAttributionState.EMPTY
            .increment(AttributionState.CONFIRMED)
            .increment(AttributionState.AMBIGUOUS)
            .increment(AttributionState.AMBIGUOUS)

        val roundTripped = OverCountsByAttributionState.parse(counts.serialize())

        assertEquals(counts.serialize(), roundTripped.serialize())
    }

    @Test
    public fun a_null_or_blank_column_parses_to_empty_rather_than_throwing() {
        val empty = OverCountsByAttributionState.EMPTY.serialize()
        assertEquals(empty, OverCountsByAttributionState.parse(null).serialize())
        assertEquals(empty, OverCountsByAttributionState.parse("").serialize())
    }

    @Test
    public fun an_unparseable_pair_is_dropped_rather_than_thrown_on() {
        val parsed = OverCountsByAttributionState.parse("CONFIRMED=1,garbage,AMBIGUOUS=2")

        assertEquals(1, parsed.countFor(AttributionState.CONFIRMED))
        assertEquals(2, parsed.countFor(AttributionState.AMBIGUOUS))
    }
}
