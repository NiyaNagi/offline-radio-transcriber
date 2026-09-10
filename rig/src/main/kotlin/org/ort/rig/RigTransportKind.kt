package org.ort.rig

/**
 * The transport a [RigModule] speaks over (FR-RIG-1, FR-RIG-14).
 *
 * `BLUETOOTH_SPP` and `BLE` are separate values, not one "Bluetooth" value, because they are
 * different Android APIs with different capability sets and different failure modes — the
 * TH-D75A module (FR-RIG-14) is Bluetooth **Classic SPP**, not BLE. `NONE` is the null module's
 * transport (FR-RIG-2). `NETWORK` describes a rig reachable over a local socket (e.g. a
 * `rigctld`-style daemon on the same LAN) — no module in this package implements it, and any
 * future one must still honour constitution V / NFR-6 (only `:net` may link an HTTP client): a
 * `NETWORK` rig transport is a plain socket, never an HTTP client, and does not live in `:net`.
 */
public enum class RigTransportKind {
    USB_SERIAL,
    BLUETOOTH_SPP,
    BLE,
    NETWORK,
    NONE,
}
