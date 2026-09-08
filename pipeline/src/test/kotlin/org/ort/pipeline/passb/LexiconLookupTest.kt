package org.ort.pipeline.passb

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.lexicon.CallsignGrammar
import org.ort.lexicon.ConfusionCostMatrix
import org.ort.lexicon.ItuPrefixTable

/**
 * Audit F-018 (FR-UI-6, `spec/open-questions.md` Q8, constitution I): Q8's "search the lexicon"
 * correction tier needs a `:pipeline` call site over the bundled ITU table and callsign grammar
 * that `:app` cannot reach directly (module graph — `:app` has no edge to `:lexicon`). This proves
 * [RealLexiconLookup] itself, over the real bundled tables, exactly as
 * [PassBFactory]/[PassBTest] already do for Pass B's own resolution path.
 */
class LexiconLookupTest {

    private fun lookup(): RealLexiconLookup {
        val ituTable = ItuPrefixTable.bundled()
        return RealLexiconLookup(ituTable, CallsignGrammar(ituTable, ConfusionCostMatrix.bundled()))
    }

    @Test
    fun `AC_11 a callsign whose prefix is not ITU allocated is never a match`() = runTest {
        // "ZZ" is not in the bundled itu-prefixes.tsv (see the fixture) — a structurally
        // callsign-shaped string with an unallocated prefix must never surface, per AC-11.
        val results = lookup().search("ZZ9ABC", limit = 20)
        assertTrue(results.isEmpty(), "expected no match for an unallocated prefix, got $results")
    }

    @Test
    fun `AC_11 an unallocated fragment surfaces no growing-prefix suggestion either`() = runTest {
        val results = lookup().search("ZZ", limit = 20)
        assertTrue(results.isEmpty(), "expected no prefix suggestion for an unallocated fragment, got $results")
    }

    @Test
    fun `FR_UI_6 an exact grammar-valid callsign returns its ITU allocation`() = runTest {
        val results = lookup().search("K7ABC", limit = 20)
        val exact = results.singleOrNull { it.callsign == "K7ABC" }
        assertTrue(exact != null, "expected K7ABC among $results")
        assertEquals("K", exact!!.ituPrefix)
        assertEquals("United States", exact.ituCountry)
        assertEquals("US", exact.ituIso)
    }

    @Test
    fun `FR_UI_6 a partial fragment inside an allocated block surfaces the block it is in`() = runTest {
        // "K7" is not itself a table row (the table only carries "K"), but it lies inside the
        // "K" (United States) allocation — the live "still typing a callsign" case.
        val results = lookup().search("K7", limit = 20)
        val partial = results.singleOrNull { it.callsign == "K7" }
        assertTrue(partial != null, "expected K7 (as a live fragment) among $results")
        assertEquals("K", partial!!.ituPrefix)
        assertEquals("United States", partial.ituCountry)
    }

    @Test
    fun `FR_UI_6 a short fragment surfaces every allocated block it could grow into`() = runTest {
        val results = lookup().search("K", limit = 20)
        val prefixes = results.map { it.callsign }.toSet()
        assertTrue(prefixes.containsAll(setOf("K", "KH6", "KL7")), "expected K/KH6/KL7 among $results")
    }

    @Test
    fun `FR_UI_6 the limit is respected`() = runTest {
        val results = lookup().search("K", limit = 1)
        assertEquals(1, results.size)
    }

    @Test
    fun `FR_UI_6 a blank query returns no matches`() = runTest {
        assertTrue(lookup().search("   ", limit = 20).isEmpty())
    }

    @Test
    fun `FR_UI_6 an unknown-symbol fragment does not throw, just returns no exact match`() = runTest {
        // "@" is not a phonetic unit (PhoneticUnit.spell would throw) — the exact-callsign path
        // must swallow that internally, not propagate an exception to a live search box.
        val results = lookup().search("K7@BC", limit = 20)
        assertTrue(results.none { it.callsign == "K7@BC" })
    }
}
