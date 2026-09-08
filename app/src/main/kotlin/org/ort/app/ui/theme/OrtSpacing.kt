package org.ort.app.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing scale read off the canvas's own layout (`Menu.dc.html`'s 20px page margin, 12-13px row
 * gaps, `States.dc.html`'s 18px card padding). Centralised so P14-P17's screens inherit the same
 * rhythm rather than each screen picking its own margins.
 */
public object OrtSpacing {
    public val xs: Dp = 4.dp
    public val sm: Dp = 8.dp
    public val md: Dp = 12.dp
    public val lg: Dp = 20.dp
    public val xl: Dp = 30.dp
}
