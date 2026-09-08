package org.ort.data.dao

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ort.testing.Requirement

/**
 * Pure query-construction logic (FR-UI-3) — no database, no fts5 module required. Free text
 * typed by the user must never be interpreted as FTS5 query syntax (a hyphen, a colon, an
 * asterisk are all operators to MATCH) — every token is individually double-quoted so it is
 * matched literally, and multiple tokens are ANDed together.
 */
public class FtsMatchQueryTest {

    @Test
    @Requirement("FR-UI-3")
    public fun `a single word is quoted for a literal match`() {
        assertEquals("\"mayday\"", FtsMatchQuery.build("mayday"))
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `multiple words become an AND of individually-quoted tokens`() {
        assertEquals("\"roger\" AND \"that\"", FtsMatchQuery.build("roger that"))
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `surrounding and repeated whitespace does not produce empty tokens`() {
        assertEquals("\"roger\" AND \"that\"", FtsMatchQuery.build("  roger   that  "))
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `a token containing FTS5 operator characters is matched literally, not interpreted as syntax`() {
        // A bare `K7ABC-2` would parse as `K7ABC NOT 2` to FTS5's default syntax; quoting it
        // must prevent that.
        assertEquals("\"K7ABC-2\"", FtsMatchQuery.build("K7ABC-2"))
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `an embedded double quote is escaped by doubling, per SQLite string-literal rules`() {
        assertEquals("\"\"\"hi\"\"\"", FtsMatchQuery.build("\"hi\""))
    }
}
