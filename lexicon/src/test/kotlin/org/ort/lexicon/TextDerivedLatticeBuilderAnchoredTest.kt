package org.ort.lexicon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R-182 (register, `Detail-Confirmed`/`Detail-Ambiguous`, FR-UI-4), R-320 (FR-UI-8): real
 * character spans out of [TextDerivedLatticeBuilder.buildAnchored], and how they reach
 * [CallsignGrammar.parse]'s [SlotDetail] output via [LatticeSlot.charStart]/[LatticeSlot.charEnd].
 *
 * **Fixture provenance (constitution VI: fold/machine/provider for any number these tests carry
 * or assert against).** The callsign these tests spell, `N7XYZ`, is drawn from
 * `corpus/manifest.json`'s **dev** fold — session `synth-callsigns/dev-01` (`source:
 * synth-callsigns`, `fold: dev`) lists `N7XYZ` among its stations — never `eval` (constitution
 * VI: "never read the eval fold, not to debug, not for a quick check"). These are pure JVM unit
 * tests of code, not a corpus-run accuracy measurement, so no score/accuracy number is reported
 * here; the fixture text itself is invented prose around the real dev-fold callsign, run on this
 * machine, JVM (no ASR/inference provider involved — [TextDerivedLatticeBuilder] is pure string
 * matching against [VariantTable], not a model).
 */
class TextDerivedLatticeBuilderAnchoredTest {

    private val builder = TextDerivedLatticeBuilder(VariantTable.bundled())
    private val grammar = bundledGrammar()

    /** "N7XYZ" spelled out, embedded in continuous speech — the same shape `PassB.resolveFromText`
     * handles in production, including words the [VariantTable] does not recognise. */
    private val transcript = "the weather here is clear this is november seven xray yankee zulu monitoring"

    @Test
    fun `R_182_char_span_locates_each_recognised_token_at_its_real_offset_in_the_source_text`() {
        val lattice = builder.buildAnchored(transcript)

        val spans = lattice.slots.map { transcript.substring(it.charStart!!, it.charEnd!!) }
        assertEquals(listOf("november", "seven", "xray", "yankee", "zulu"), spans)
    }

    @Test
    fun `R_182_char_span_skips_unrecognised_words_without_shifting_later_offsets`() {
        val lattice = builder.buildAnchored(transcript)

        // "the", "weather", "here", "is", "clear", "this", "is" are not phonetic-unit spoken
        // forms and must not become slots at all (same silent-exclusion PassB.kt documents).
        assertEquals(5, lattice.slots.size)
        // "november" is the 41st character of transcript (0-indexed) — confirms real offsets,
        // not slot-index-derived guesses.
        assertEquals(transcript.indexOf("november"), lattice.slots[0].charStart)
    }

    @Test
    fun `R_182_char_span_survives_into_the_resolved_candidates_slot_details`() {
        val lattice = builder.buildAnchored(transcript)

        val chosen = grammar.parse(lattice).first { it.text == "N7XYZ" }

        assertEquals(5, chosen.slotDetails.size)
        chosen.slotDetails.forEachIndexed { i, detail ->
            assertEquals(lattice.slots[i].charStart, detail.charStart, "slot $i")
            assertEquals(lattice.slots[i].charEnd, detail.charEnd, "slot $i")
        }
        val highlightStart = chosen.slotDetails.minOf { it.charStart!! }
        val highlightEnd = chosen.slotDetails.maxOf { it.charEnd!! }
        assertEquals("november seven xray yankee zulu", transcript.substring(highlightStart, highlightEnd))
    }

    @Test
    fun `R_182_char_span_is_a_half_open_range_end_exclusive`() {
        val lattice = builder.buildAnchored("kilo")
        val slot = lattice.slots.single()
        assertEquals(0, slot.charStart)
        assertEquals(4, slot.charEnd) // "kilo".length, not length - 1
    }

    @Test
    fun `an empty source text yields an empty lattice, not a fabricated slot`() {
        val lattice = builder.buildAnchored("   ")
        assertTrue(lattice.isEmpty)
    }

    @Test
    fun `buildAnchored is text-derived, same as build`() {
        val lattice = builder.buildAnchored(transcript)
        assertEquals(LatticeSource.TEXT_DERIVED, lattice.source)
    }

    @Test
    fun `build tokens still throws on an unrecognised word, unlike buildAnchored`() {
        // Confirms buildAnchored's silent-exclusion is a deliberate new behaviour, not a change
        // to build's own documented contract.
        val threw = try {
            builder.build(listOf("november", "weather", "seven"))
            false
        } catch (e: UnknownSpokenFormException) {
            true
        }
        assertTrue(threw)
        assertTrue(builder.buildAnchored("weather").isEmpty)
    }
}
