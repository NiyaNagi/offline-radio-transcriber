package org.ort.rig

/**
 * What a [RigModule] can report over a given transport (FR-RIG-1). Declared **per transport**
 * (FR-RIG-17), because they genuinely differ — a rig may report squelch over USB and not
 * Bluetooth, or vice versa, and the operator is shown the difference rather than discovering it
 * after a night's capture.
 */
public enum class RigCapability {
    FREQUENCY,
    MODE,
    SQUELCH_STATE,
    SIGNAL_STRENGTH,
    MEMORY_CHANNEL,
    CHANNEL_NAME,
    SUB_BAND,
    TIME,

    /** The TH-D75A has GPS for APRS (FR-LEX-22). */
    POSITION,
}
