package org.ort.telemetry

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * FR-ANL-1/FR-ANL-9: whether each [AnalyticsTier] is currently on. Tier 1 defaults on and can be
 * turned off; tiers 2 and 3 default off and are opt-in. Settings' three toggles
 * (`SettingsAnalyticsScreen`) and the setup consent step both read and write through this.
 */
public interface AnalyticsTierPreferences {
    public fun isEnabled(tier: AnalyticsTier): Boolean
    public fun setEnabled(tier: AnalyticsTier, enabled: Boolean)
}

private fun defaultFor(tier: AnalyticsTier): Boolean = tier == AnalyticsTier.TIER_1

/** The behavioural fake (constitution II) — an in-memory [AnalyticsTierPreferences] for tests. */
public class InMemoryAnalyticsTierPreferences(initial: Map<AnalyticsTier, Boolean> = emptyMap()) :
    AnalyticsTierPreferences {
    private val state = AnalyticsTier.entries.associateWith { initial[it] ?: defaultFor(it) }.toMutableMap()

    override fun isEnabled(tier: AnalyticsTier): Boolean = state.getValue(tier)

    override fun setEnabled(tier: AnalyticsTier, enabled: Boolean) {
        state[tier] = enabled
    }
}

/** The real, `SharedPreferences`-backed [AnalyticsTierPreferences]. */
public class SharedPreferencesAnalyticsTierPreferences(private val prefs: SharedPreferences) :
    AnalyticsTierPreferences {

    override fun isEnabled(tier: AnalyticsTier): Boolean = prefs.getBoolean(keyFor(tier), defaultFor(tier))

    override fun setEnabled(tier: AnalyticsTier, enabled: Boolean) {
        prefs.edit { putBoolean(keyFor(tier), enabled) }
    }

    private fun keyFor(tier: AnalyticsTier): String = "tier_${tier.name.lowercase()}_enabled"

    public companion object {
        public const val PREFS_NAME: String = "org.ort.telemetry.tiers"
    }
}
