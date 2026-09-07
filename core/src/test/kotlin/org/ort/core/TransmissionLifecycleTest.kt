package org.ort.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TransmissionLifecycleTest {

    /** The whole legal-transition table, asserted exhaustively (technical design §7.2). */
    private val expectedLegal = setOf(
        TransmissionState.CAPTURED to TransmissionState.PROCESSING,
        TransmissionState.PROCESSING to TransmissionState.COMPLETE,
        TransmissionState.PROCESSING to TransmissionState.REJECTED,
        TransmissionState.PROCESSING to TransmissionState.FAILED,
        TransmissionState.PROCESSING to TransmissionState.CAPTURED,
        TransmissionState.FAILED to TransmissionState.PROCESSING,
        TransmissionState.COMPLETE to TransmissionState.PROCESSING,
        TransmissionState.REJECTED to TransmissionState.PROCESSING,
    )

    @Test
    fun `FR_RUN_7 the legal-transition table is exactly as specified`() {
        assertEquals(expectedLegal, TransmissionLifecycle.legalTransitions)
    }

    @Test
    fun `FR_RUN_7 every from-to pair is classified and no illegal pair is accepted`() {
        for (from in TransmissionState.entries) {
            for (to in TransmissionState.entries) {
                val legal = TransmissionLifecycle.isLegal(from, to)
                assertEquals((from to to) in expectedLegal, legal, "$from -> $to")
            }
        }
    }

    @Test
    fun `FR_RUN_8 a killed PROCESSING transmission may return to CAPTURED and be re-queued`() {
        assertTrue(TransmissionLifecycle.isLegal(TransmissionState.PROCESSING, TransmissionState.CAPTURED))
    }

    @Test
    fun `FR_RUN_10 a FAILED transmission may be retried`() {
        assertTrue(TransmissionLifecycle.isLegal(TransmissionState.FAILED, TransmissionState.PROCESSING))
    }

    @Test
    fun `FR_REP_9 a COMPLETE transmission may be reprocessed`() {
        assertTrue(TransmissionLifecycle.isLegal(TransmissionState.COMPLETE, TransmissionState.PROCESSING))
    }

    @Test
    fun `a transmission cannot skip PROCESSING`() {
        assertFalse(TransmissionLifecycle.isLegal(TransmissionState.CAPTURED, TransmissionState.COMPLETE))
        assertThrows(IllegalTransitionException::class.java) {
            TransmissionLifecycle.require(TransmissionState.CAPTURED, TransmissionState.COMPLETE)
        }
    }

    @Test
    fun `no transition leaves a terminal state except by reprocessing`() {
        assertEquals(
            setOf(TransmissionState.PROCESSING),
            TransmissionLifecycle.legalTargetsFrom(TransmissionState.COMPLETE),
        )
    }

    @Test
    fun `the initial state is CAPTURED`() {
        assertEquals(TransmissionState.CAPTURED, TransmissionLifecycle.initialState)
    }

    @Test
    fun `STALE is not a stored state`() {
        assertFalse(TransmissionState.entries.any { it.name == "STALE" })
    }
}
