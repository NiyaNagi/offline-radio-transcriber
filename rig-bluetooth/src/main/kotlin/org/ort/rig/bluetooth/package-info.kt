/*
 * :rig-bluetooth — the Bluetooth SPP/BLE rig transport (FR-RIG-14, FR-RIG-15, D34), behind the
 * :rig transport contract so the rig catalogue and CAT command layer are transport-agnostic.
 * A disconnection over Bluetooth is treated exactly as any other rig disconnection (FR-RIG-15,
 * FR-RIG-7 semantics): capture is never gated on the transport being up. A behavioural fake
 * ships alongside the real transport (constitution II) — one that can be told to fail, hang or
 * drop mid-session, since a link that drops is the case that matters most here.
 *
 * Module :rig-bluetooth is scaffolded per technical design section 2: wired into the build
 * graph with its permitted dependencies (see build.gradle.kts) but not yet implemented.
 * The build-plan wave that owns it is in spec/build-plan.md (Wave F, P19).
 */
package org.ort.rig.bluetooth
