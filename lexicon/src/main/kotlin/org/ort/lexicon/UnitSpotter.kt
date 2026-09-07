package org.ort.lexicon

/**
 * Technical design §9.7 (M4): the acoustic spotting interface, specified narrowly on purpose —
 * M4 decides whether Pass C exists at all (R3), so nothing downstream may depend on more than
 * "audio in, a [PhoneticLattice] out". Two candidate implementations are raced against
 * [TextDerivedUnitSpotter] on the dev fold: sherpa-onnx open-vocabulary KWS over the ~36-unit
 * vocabulary, and CB-Whisper-style encoder-similarity spotting (gated on the TD2 probe — see
 * CHANGELOG for this session's finding, which was negative for the public sherpa-onnx API).
 */
public fun interface UnitSpotter {
    public fun spot(audio: FloatArray): PhoneticLattice
}

/**
 * M4.1's baseline: "the `TEXT_DERIVED` path is already its baseline implementation" (build-plan
 * P11). This is the number every acoustic spotter must beat (R3) — the same T0 degraded lattice
 * [TextDerivedLatticeBuilder] already produces from Pass B's transcript, wrapped behind
 * [UnitSpotter] so the M4 comparison can drive every candidate spotter, including this one,
 * through one uniform interface and the same downstream grammar/resolver.
 *
 * [audio] is deliberately ignored — a text-derived spotter has no acoustic model — which is
 * exactly the point of the comparison: does spending a model on [audio] ever beat not doing so.
 * [transcript] is a function rather than a fixed string so one spotter instance can be reused
 * across many segments in a harness loop.
 *
 * Per-token, not whole-transcript: an unrecognised word is excluded from the lattice rather than
 * aborting the whole spot (unlike [TextDerivedLatticeBuilder.build], which throws) — see
 * [org.ort.pipeline.passb.PassB]'s doc for why a real transcript of continuous speech needs this.
 */
public class TextDerivedUnitSpotter(private val variants: VariantTable, private val transcript: () -> String) :
    UnitSpotter {
    override fun spot(audio: FloatArray): PhoneticLattice {
        val units = transcript().trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .mapNotNull { variants.resolve(it) }
        return PhoneticLattice.ofUnits(units, LatticeSource.TEXT_DERIVED)
    }
}
