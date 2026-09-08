package org.ort.lexicon.fake

import org.ort.lexicon.LatticeSource
import org.ort.lexicon.PhoneticLattice
import org.ort.lexicon.UnitSpotter

/**
 * The behavioural fake for [UnitSpotter] (constitution II). Scripted to a fixed [lattice]
 * regardless of the audio given — this session has no real acoustic spotter to fake a *model
 * of*; what it fakes is "an acoustic spotter exists and returns this", which is exactly what the
 * M4 comparison mechanism needs without a device.
 */
public class FakeUnitSpotter(private val lattice: PhoneticLattice) : UnitSpotter {
    override fun spot(audio: FloatArray): PhoneticLattice = lattice

    public companion object {
        /** A spotter that heard nothing — the honest stand-in until a real model exists. */
        public fun spotsNothing(): FakeUnitSpotter =
            FakeUnitSpotter(PhoneticLattice(emptyList(), LatticeSource.ACOUSTIC))
    }
}
