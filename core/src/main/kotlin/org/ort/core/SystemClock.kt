package org.ort.core

import java.util.TimeZone

/**
 * The production [Clock]. On Android the DI graph substitutes an implementation backed by
 * `SystemClock.elapsedRealtimeNanos()`; this JVM one uses [System.nanoTime], which has the
 * same monotonic contract. Kept in `:core` so desktop tools and the harness share it.
 */
public object SystemClock : Clock {
    override fun monotonicNanos(): Long = System.nanoTime()

    override fun wallMillis(): Long = System.currentTimeMillis()

    override fun utcOffsetMinutes(): Int {
        val now = System.currentTimeMillis()
        return TimeZone.getDefault().getOffset(now) / 60_000
    }
}
