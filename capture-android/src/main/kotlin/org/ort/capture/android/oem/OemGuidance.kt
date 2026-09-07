package org.ort.capture.android.oem

/** One onboarding step. [deepLinkAction] is an Intent action, resolved (or dropped) by the caller. */
public data class OemStep(val title: String, val detail: String, val deepLinkAction: String? = null)

public data class OemGuidance(val manufacturer: String, val steps: List<OemStep>)

/**
 * technical design §5.6's OEM guidance table (FR-SVC-5a → AC-66), keyed on
 * `Build.MANUFACTURER`/`Build.BRAND`. Kept as a pure table, with no `PackageManager` dependency,
 * so it is unit-testable without a device; [OemGuidanceResolver] does the Android-side intent
 * resolution against this table's output.
 */
public object OemGuidanceTable {

    /** The four ColorOS steps this build-plan session names explicitly (Oppo/Realme/OnePlus post-merge). */
    public val coloros: List<OemStep> = listOf(
        OemStep(
            "Disable battery optimisation",
            "Settings > Battery > App Battery Management > Offline Radio Transcriber > Allow background activity",
            "oppo.intent.action.battery_saver_appwhitelist",
        ),
        OemStep("Lock the app in Recents", "Open Recents, long-press the app card, tap Lock"),
        OemStep(
            "Allow auto-start",
            "Settings > App Management > Offline Radio Transcriber > Enable Autostart",
            "com.coloros.safecenter.startupapp.StartupAppListActivity",
        ),
        OemStep(
            "Exempt from background power management",
            "Settings > Battery > More Battery Settings > High Background Power Consumption App Management",
        ),
    )

    public val generic: List<OemStep> = listOf(
        OemStep(
            "Disable battery optimisation",
            "Settings > Apps > Offline Radio Transcriber > Battery > Unrestricted",
        ),
        OemStep(
            "Allow background activity",
            "Settings > Apps > Offline Radio Transcriber > Battery > Allow background activity",
        ),
    )

    private val colorOsManufacturers = listOf("oppo", "realme", "oneplus")

    public fun forDevice(manufacturer: String, brand: String): OemGuidance {
        val m = manufacturer.lowercase()
        val b = brand.lowercase()
        val isColorOsFamily = colorOsManufacturers.any { m.contains(it) || b.contains(it) }
        return OemGuidance(manufacturer, if (isColorOsFamily) coloros else generic)
    }
}
