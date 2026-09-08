package org.ort.data

/**
 * The standard amateur-radio band allocations (functional spec §8's "Band plan tables", FR-UI-3),
 * expressed as inclusive `[minHz, maxHz]` ranges so a [org.ort.data.dao.SearchDao] filter and any
 * future caller can derive "which band is this frequency in" without re-deriving the table.
 *
 * Region-2 (ITU) HF/VHF/UHF amateur allocations — the bands the product's reference rig
 * (TH-D75A, VHF/UHF) and its HF-capable siblings cover. Deliberately amateur-only: this is the
 * "amateur band table" FR-UI-3 asks for, not a full scanner service table (public safety, marine,
 * aviation, …), which the spec does not ask this filter to cover.
 *
 * Kept here, at the `:data` package root beside [Converters], rather than under `dao/`, because
 * it is a plain domain table with no Room annotations of its own — reusable by any `:data` caller,
 * not just [org.ort.data.dao.SearchDao].
 */
public enum class Band(public val minHz: Long, public val maxHz: Long) {
    HF_160M(1_800_000L, 2_000_000L),
    HF_80M(3_500_000L, 4_000_000L),
    HF_60M(5_330_500L, 5_403_500L),
    HF_40M(7_000_000L, 7_300_000L),
    HF_30M(10_100_000L, 10_150_000L),
    HF_20M(14_000_000L, 14_350_000L),
    HF_17M(18_068_000L, 18_168_000L),
    HF_15M(21_000_000L, 21_450_000L),
    HF_12M(24_890_000L, 24_990_000L),
    HF_10M(28_000_000L, 29_700_000L),
    VHF_6M(50_000_000L, 54_000_000L),
    VHF_2M(144_000_000L, 148_000_000L),
    VHF_1_25M(222_000_000L, 225_000_000L),
    UHF_70CM(420_000_000L, 450_000_000L),
    UHF_33CM(902_000_000L, 928_000_000L),
    UHF_23CM(1_240_000_000L, 1_300_000_000L),
    ;

    /** Whether [frequencyHz] falls within this band's allocation, inclusive of both edges. */
    public fun contains(frequencyHz: Long): Boolean = frequencyHz in minHz..maxHz

    public companion object {
        /** The band [frequencyHz] falls in, or `null` if it matches none of the table above. */
        public fun of(frequencyHz: Long): Band? = entries.firstOrNull { it.contains(frequencyHz) }
    }
}
