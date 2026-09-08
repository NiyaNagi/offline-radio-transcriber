package org.ort.pipeline.passb.fake

import org.ort.pipeline.passb.LexiconLookup
import org.ort.pipeline.passb.LexiconMatch

/**
 * The behavioural fake for [LexiconLookup] (constitution II): scriptable to return a fixed set of
 * matches per query or throw, without the real ITU table/grammar behind it. Every call is
 * recorded so a caller (e.g. `:app`'s `CorrectionFlowTest`) can assert the query it was actually
 * asked to search, not just the result rendered.
 */
public class FakeLexiconLookup(private val behaviour: Behaviour = Behaviour.Returns(emptyMap())) : LexiconLookup {

    public val queries: MutableList<String> = mutableListOf()

    public sealed interface Behaviour {
        /** [byQuery] maps a normalised (trimmed, uppercased) query to the matches it returns. */
        public data class Returns(val byQuery: Map<String, List<LexiconMatch>>) : Behaviour
        public data class Throws(val error: Throwable) : Behaviour
    }

    override suspend fun search(prefixOrPartial: String, limit: Int): List<LexiconMatch> {
        queries += prefixOrPartial
        return when (behaviour) {
            is Behaviour.Throws -> throw behaviour.error
            is Behaviour.Returns ->
                behaviour.byQuery[prefixOrPartial.trim().uppercase()].orEmpty().take(limit)
        }
    }
}
