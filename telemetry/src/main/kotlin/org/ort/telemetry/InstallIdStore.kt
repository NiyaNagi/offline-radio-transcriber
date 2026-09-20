package org.ort.telemetry

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.UUID

/** FR-ANL-11: [previousId] is what the destination must purge every row of; [newId] is what
 * every event carries from this point on. */
public data class InstallIdReset(val previousId: String, val newId: String)

/**
 * FR-ANL-8/FR-ANL-11: a random, resettable id — never the device's hardware identity — that
 * every [AnalyticsProvenance] carries. [reset] only changes what this store hands out; the
 * caller (`:app`'s composition root) is responsible for telling the configured destination to
 * purge every row associated with the previous id (via `org.ort.core.analytics.AnalyticsUploadClient.purge`).
 */
public interface InstallIdStore {
    public fun currentId(): String
    public fun reset(): InstallIdReset
}

/** The behavioural fake (constitution II) — deterministic unless [nextId] is overridden. */
public class InMemoryInstallIdStore(
    seed: String = UUID.randomUUID().toString(),
    private val nextId: () -> String = { UUID.randomUUID().toString() },
) : InstallIdStore {
    private var id: String = seed

    override fun currentId(): String = id

    override fun reset(): InstallIdReset {
        val previous = id
        id = nextId()
        return InstallIdReset(previous, id)
    }
}

/** The real, `SharedPreferences`-backed [InstallIdStore] — mints an id on first read and persists
 * it, so it survives process death but not a deliberate [reset] or an uninstall. */
public class SharedPreferencesInstallIdStore(private val prefs: SharedPreferences) : InstallIdStore {

    override fun currentId(): String {
        prefs.getString(KEY_INSTALL_ID, null)?.let { return it }
        val minted = UUID.randomUUID().toString()
        prefs.edit { putString(KEY_INSTALL_ID, minted) }
        return minted
    }

    override fun reset(): InstallIdReset {
        val previous = currentId()
        val newId = UUID.randomUUID().toString()
        prefs.edit { putString(KEY_INSTALL_ID, newId) }
        return InstallIdReset(previous, newId)
    }

    public companion object {
        public const val PREFS_NAME: String = "org.ort.telemetry.install_id"
        public const val KEY_INSTALL_ID: String = "install_id"
    }
}
