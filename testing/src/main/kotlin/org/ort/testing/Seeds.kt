package org.ort.testing

import kotlin.random.Random

/**
 * Fixed seeds so a test that needs randomness is still byte-reproducible (constitution VI:
 * "determinism is bounded and the bound is stated"). Name a seed after what it seeds.
 */
public object Seeds {
    public const val ULID: Long = 0xA11CEL
    public const val EMBEDDING: Long = 0xE1BEDL
    public const val TRAFFIC: Long = 0x7A1FL
    public const val DEFAULT: Long = 0x0FF11EL

    public fun random(seed: Long = DEFAULT): Random = Random(seed)
}
