package org.ort.lexicon.fake

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.lexicon.LatticeSource
import org.ort.lexicon.PhoneticLattice
import org.ort.lexicon.PhoneticUnit

/**
 * The behavioural fake for [org.ort.lexicon.UnitSpotter] (constitution II): every module-bearing
 * interface ships one in the same change. Scriptable to a fixed lattice or to "spotted nothing",
 * which is what the M4 comparison mechanism needs to simulate an acoustic spotter without a real
 * model — see [org.ort.pipeline.passb.SpotterComparisonTest].
 */
class FakeUnitSpotterTest {

    @Test
    fun `returns the scripted lattice regardless of the audio given`() {
        val lattice = PhoneticLattice.ofUnits(PhoneticUnit.spell("K7ABC"), LatticeSource.ACOUSTIC)
        val spotter = FakeUnitSpotter(lattice)
        assertEquals(lattice, spotter.spot(FloatArray(1_000)))
        assertEquals(lattice, spotter.spot(FloatArray(0)))
    }

    @Test
    fun `spotsNothing produces an empty ACOUSTIC lattice`() {
        val spotter = FakeUnitSpotter.spotsNothing()
        assertEquals(true, spotter.spot(FloatArray(100)).isEmpty)
        assertEquals(LatticeSource.ACOUSTIC, spotter.spot(FloatArray(100)).source)
    }
}
