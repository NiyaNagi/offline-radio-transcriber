package org.ort.app.ui.improve

import android.content.Context

/**
 * Now's "Can get better" row (WP4, `NowViewState.CanGetBetterRow`) is meant to route to the
 * `IMPROVE` destination and needs a count to show — this is that hook, named exactly as this
 * package's WP10 row promised. WP4's own `ReaderPolling.canGetBetterRow` (`ui/data/ReaderPolling.kt`,
 * WP4's file, not edited here) computes an equivalent count independently today; once WP4 switches
 * its row to call this function instead, both numbers are guaranteed to agree, since both would
 * then read the exact same qualifying-session rule ([ImprovePolling.root]).
 */
public object ImproveCounts {
    public suspend fun canGetBetterCount(context: Context): Int = ImprovePolling.root(context).totalOverCount
}
