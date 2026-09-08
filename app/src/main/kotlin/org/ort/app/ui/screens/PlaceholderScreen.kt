package org.ort.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.ort.app.ui.theme.OrtSpacing

/**
 * A drawer destination the canvas specifies but no prompt has built a screen for yet
 * (build-plan P13's `ReaderDestination.hasScreen == false`). Reachable, not hidden — the
 * alternative (leaving it out of the drawer) would misrepresent `Menu.dc.html`, which lists it —
 * but honest that it is not built, rather than a fabricated screen standing in for one.
 */
@Composable
public fun PlaceholderScreen(destinationLabel: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(OrtSpacing.lg)) {
        Text(text = destinationLabel, style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Not built yet — see spec/build-plan.md.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .padding(top = OrtSpacing.sm)
                .semantics { contentDescription = "$destinationLabel is not built yet" },
        )
    }
}
