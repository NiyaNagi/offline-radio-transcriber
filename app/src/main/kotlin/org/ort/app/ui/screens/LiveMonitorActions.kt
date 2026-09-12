package org.ort.app.ui.screens

/**
 * [LiveMonitorScreen]'s own callbacks, bundled purely to keep that composable under detekt's
 * `LongParameterList` limit — the same reason `OrtNavHost.kt`'s own `NavHostCallbacks` exists.
 * Every field defaults to a no-op so a caller that only cares about one (this file's own tests
 * included) never has to name the rest.
 */
public data class LiveMonitorActions(
    val onBack: () -> Unit = {},
    val onOpenOver: (String) -> Unit = {},
    val onOpenFullLog: () -> Unit = {},
    val onStop: () -> Unit = {},
)
