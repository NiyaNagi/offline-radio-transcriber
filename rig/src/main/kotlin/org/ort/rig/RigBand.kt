package org.ort.rig

/**
 * A receiver band on a dual-receive rig.
 *
 * Not part of functional spec §9.1's original `RigState` sketch, which predates the finding
 * recorded in `docs/reference/th-d75a-cat.md`: the TH-D75A **receives on two bands at once and
 * mixes the audio**, and every CAT command that matters (`FQ`, `BY`, `FO`, `MR`) is band-scoped
 * (D23). `RigState` therefore needs to be able to say *which* band a reading describes; `null`
 * on [RigState.band] means the module has no band concept (the null module, a single-receiver
 * generic CAT rig).
 */
public enum class RigBand {
    /** Kenwood's band selector value `0`. */
    A,

    /** Kenwood's band selector value `1`. */
    B,
}
