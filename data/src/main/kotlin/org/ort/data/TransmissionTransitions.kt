package org.ort.data

import org.ort.core.IllegalTransitionException
import org.ort.core.TransmissionLifecycle
import org.ort.core.TransmissionState
import org.ort.data.dao.TransmissionDao

/**
 * Asserts and applies one FR-RUN-7 transition (technical design §7.2). This is the only path
 * [org.ort.data.WorkQueue] uses to move a transmission's `processingState` — every write goes
 * through [TransmissionLifecycle.require] first, so an illegal transition is a thrown bug, not
 * a silently-written wrong state.
 */
public suspend fun TransmissionDao.requireLegalTransition(transmissionId: String, to: TransmissionState) {
    val current = getById(transmissionId) ?: error("no transmission $transmissionId")
    TransmissionLifecycle.require(current.processingState, to)
    setProcessingState(transmissionId, to)
}

/** True if [to] is a legal transition from [transmissionId]'s current state; does not throw. */
public suspend fun TransmissionDao.canTransition(transmissionId: String, to: TransmissionState): Boolean {
    val current = getById(transmissionId) ?: return false
    return TransmissionLifecycle.isLegal(current.processingState, to)
}

/** Re-throws with the transmission id attached, for callers that want to log it. */
public class TransmissionTransitionException(transmissionId: String, cause: IllegalTransitionException) :
    IllegalStateException("transmission $transmissionId: ${cause.message}", cause)
