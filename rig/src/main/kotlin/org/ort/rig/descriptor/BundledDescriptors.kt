package org.ort.rig.descriptor

/**
 * Loads the descriptors bundled as this module's own resources — `kenwood-thd75a.json`
 * (FR-RIG-3, verified per §9.3) and `generic-ascii-cat.json` (FR-RIG-14's second, unverified
 * row). These ship with the app and must always validate; a failure here is a bug in this
 * package, not a user-input error, so it throws rather than falling back to the null module —
 * that fallback is reserved for descriptors the operator supplies (FR-RIG-19).
 */
public object BundledDescriptors {
    public const val KENWOOD_TH_D75A_RESOURCE: String = "/descriptors/kenwood-thd75a.json"
    public const val GENERIC_ASCII_CAT_RESOURCE: String = "/descriptors/generic-ascii-cat.json"

    public fun load(resourcePath: String): RigDescriptor {
        val stream = requireNotNull(BundledDescriptors::class.java.getResourceAsStream(resourcePath)) {
            "bundled descriptor resource not found: $resourcePath"
        }
        val text = stream.bufferedReader().use { it.readText() }
        return when (val result = DescriptorLoader.load(text)) {
            is DescriptorLoadResult.Loaded -> result.descriptor
            is DescriptorLoadResult.Rejected ->
                error("bundled descriptor $resourcePath failed validation: ${result.errors}")
        }
    }

    /**
     * WPC3 (FR-RIG-3): `kenwood-thd75a.json`'s two transports both declare `lineTerminator = ";"`
     * (Kenwood convention, `docs/reference/th-d75a-cat.md`). Neither declares `usbVendorId`/
     * `usbProductId` — that same reference file marks both "still to verify" (finding #1), so this
     * descriptor leaves them absent rather than guessing (constitution I); H1 (the operator, with
     * the radio in hand over USB) fills them in, at which point
     * [org.ort.pipeline.rig.DefaultRigTransportFactory] picks them up automatically — the
     * connect-params fallback exists for exactly this "not yet declared" case.
     */
    public fun kenwoodThD75a(): RigDescriptor = load(KENWOOD_TH_D75A_RESOURCE)

    public fun genericAsciiCat(): RigDescriptor = load(GENERIC_ASCII_CAT_RESOURCE)
}
