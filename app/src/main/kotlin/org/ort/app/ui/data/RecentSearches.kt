package org.ort.app.ui.data

import android.content.Context

/** One recorded search and how many overs it matched (`Search.dc.html`'s `RECENT` rows, R-063). */
public data class RecentSearchEntry(val label: String, val overCount: Int)

/**
 * An app-private store of the operator's last searches (R-063). Backed by
 * [android.content.SharedPreferences] rather than a `:data` Room table: this is reader-local UI
 * convenience state (what the operator typed, not a fact about captured radio traffic), so it
 * does not belong to the shared database `:data` owns, and it never leaves the device by any path
 * this reader has (constitution V) — a plain per-app preferences file is exactly as local as
 * everything else the reader keeps client-side.
 *
 * [record] keeps the most recent 5, most-recent-first, de-duplicated case-insensitively (a repeat
 * search moves to the front with its latest count rather than appending a second row). A blank
 * [RecentSearches.record] label is dropped — a filters-only browse with no callsign/text/frequency
 * has nothing meaningful to show as a one-line recent-search label (`Search.dc.html`'s rows are
 * all single terms).
 */
public object RecentSearches {
    private const val PREFS_NAME = "recent_searches"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 5
    private const val FIELD_SEPARATOR = ""
    private const val ENTRY_SEPARATOR = ""

    public fun list(context: Context): List<RecentSearchEntry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return decode(raw)
    }

    public fun record(context: Context, label: String, overCount: Int) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return
        val existing = list(context).filterNot { it.label.equals(trimmed, ignoreCase = true) }
        val updated = (listOf(RecentSearchEntry(trimmed, overCount)) + existing).take(MAX_ENTRIES)
        prefs(context).edit().putString(KEY_ENTRIES, encode(updated)).apply()
    }

    /** The single term worth remembering from [input] — the primary field the operator actually
     * typed, preferring an exact identity (callsign) over free text over a bare frequency. `null`
     * when the search was filters-only and has no one term to label a recent row with. */
    public fun primaryTermFor(input: SearchFilterInput): String? = when {
        input.callsign.isNotBlank() -> input.callsign.trim()
        input.text.isNotBlank() -> input.text.trim()
        input.frequencyMhz.isNotBlank() -> input.frequencyMhz.trim()
        else -> null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun encode(entries: List<RecentSearchEntry>): String =
        entries.joinToString(ENTRY_SEPARATOR) { "${it.label}$FIELD_SEPARATOR${it.overCount}" }

    private fun decode(raw: String): List<RecentSearchEntry> = raw.split(ENTRY_SEPARATOR)
        .mapNotNull { entry ->
            val parts = entry.split(FIELD_SEPARATOR)
            if (parts.size == 2) RecentSearchEntry(parts[0], parts[1].toIntOrNull() ?: 0) else null
        }
}
