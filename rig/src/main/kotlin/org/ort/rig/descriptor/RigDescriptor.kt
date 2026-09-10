package org.ort.rig.descriptor

import kotlinx.serialization.Serializable

/** The schema version this build understands (FR-AST-7). A descriptor declaring a newer one is
 * rejected before anything in it is applied. */
public const val SUPPORTED_DESCRIPTOR_SCHEMA_VERSION: Int = 1

/**
 * A declarative rig descriptor (FR-RIG-4, functional spec §9.2). JSON, not YAML — no extra
 * parser needed, `kotlinx.serialization` is already in the version catalog (technical design
 * §11) — and versioned (FR-AST-7) via [schemaVersion].
 */
@Serializable
public data class RigDescriptor(
    public val schemaVersion: Int,
    public val id: String,
    public val displayName: String,
    public val transports: List<TransportSpec>,
    /** Band indices this radio exposes state per (D23) — empty for a single-receiver rig. */
    public val bands: List<Int> = emptyList(),
    public val poll: PollSpec? = null,
    public val unsolicited: UnsolicitedSpec? = null,
    /** §9.3: whether this descriptor's command set has been checked against real hardware. */
    public val verified: Boolean = false,
)

/** One transport this radio is reachable over, and what it yields there (FR-RIG-14/FR-RIG-17). */
@Serializable
public data class TransportSpec(
    public val kind: String,
    public val serial: SerialParams? = null,
    public val capabilities: List<String> = emptyList(),
)

@Serializable
public data class SerialParams(
    public val baud: Int,
    public val dataBits: Int = 8,
    public val stopBits: Int = 1,
    public val parity: String = "none",
)

@Serializable
public data class PollSpec(
    public val intervalMs: Long,
    /** Commands sent once per poll cycle, unscoped to a band. */
    public val commands: List<CommandSpec> = emptyList(),
    /**
     * Commands sent once per poll cycle for EACH of [RigDescriptor.bands], with `{band}`
     * substituted into [CommandSpec.send]. The band itself is recovered from the *response*, not
     * the request — see `docs/reference/th-d75a-cat.md`.
     */
    public val perBand: List<CommandSpec> = emptyList(),
)

@Serializable
public data class CommandSpec(
    public val send: String,
    public val expect: String,
    /** field name -> `"$N"` capture-group reference into [expect]. */
    public val map: Map<String, String> = emptyMap(),
    /** field name -> (raw captured value -> translated value), applied after [map]. */
    public val lookup: Map<String, Map<String, String>> = emptyMap(),
)

/** Same shape as [CommandSpec] minus `send` — nothing is transmitted to receive a push line. */
@Serializable
public data class PatternSpec(
    public val expect: String,
    public val map: Map<String, String> = emptyMap(),
    public val lookup: Map<String, Map<String, String>> = emptyMap(),
)

/**
 * `AI`-style unsolicited push (`docs/reference/th-d75a-cat.md`, finding 1): [enable] is sent
 * once on connect, after which the radio pushes state changes with no further polling needed.
 */
@Serializable
public data class UnsolicitedSpec(public val enable: String, public val patterns: List<PatternSpec> = emptyList())
