package org.ort.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/** R-131 (ui-conformance-plan WP2): `NavRow` — `Settings.dc.html`/`Improve.dc.html`/
 * `Sessions.dc.html`'s shared destination row. Split out of `RowsTest.kt` (detekt's own
 * `LargeClass` finding, once that file grew past a reasonable size across every row family it
 * covers) rather than suppressed — `NavRow` is its own self-contained family of tests already,
 * not entangled with `LogRow`/`KeyValueRow`/the others `RowsTest.kt` still owns. */
@RunWith(RobolectricTestRunner::class)
class NavRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `R_131_nav_row carries its leading icon, title, sub-line and a trailing chevron`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    NavRow(
                        rowTitle = "Rig",
                        subLine = "TH-D75A · connected",
                        icon = OrtIcons.rig,
                        onClick = {},
                        modifier = Modifier.testTag("nav-rig"),
                    )
                    NavRow(rowTitle = "Improve", onClick = {}, modifier = Modifier.testTag("nav-bare"))
                }
            }
        }

        // R-380 correction (WP2, gate-blocking): `NavRow`'s own outer node now carries its
        // composed description as both `contentDescription` and `text` (this package's
        // `CHANGELOG.md`). For "Rig"/"TH-D75A · connected" (a row *with* a sub-line, whose own
        // composed text is "Rig. TH-D75A · connected" — never equal to either fragment alone),
        // this stays exactly what it always was: the inner `Text` is the only exact match, reached
        // with `useUnmergedTree = true` since `clearAndSetSemantics` keeps it off the merged tree.
        // "Improve" (no sub-line) is the different case: its own composed text *is* exactly
        // "Improve" too, so the *default* merged tree is what stays a single, unambiguous match —
        // `useUnmergedTree = true` there would also surface the still-present inner `Text`, two
        // matches instead of one.
        composeTestRule.onNodeWithText("Rig", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("TH-D75A · connected", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Improve").assertIsDisplayed()
    }

    @Test
    fun `R_131_nav_row is a real 44dp Role_Button target with a merged title-then-subLine description`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    NavRow(
                        rowTitle = "Tier",
                        subLine = "Tier 1 · this phone's best",
                        onClick = {},
                        modifier = Modifier.testTag("nav-tier"),
                    )
                    NavRow(rowTitle = "About", onClick = {}, modifier = Modifier.testTag("nav-about"))
                }
            }
        }

        val withSubLine = composeTestRule.onNodeWithTag("nav-tier")
        withSubLine.assertHeightIsAtLeast(44.dp)
        withSubLine.assert(hasContentDescription("Tier. Tier 1 · this phone's best"))

        // No sub-line: the bare title, never a dangling ". ".
        val bare = composeTestRule.onNodeWithTag("nav-about")
        bare.assertHeightIsAtLeast(44.dp)
        bare.assert(hasContentDescription("About"))
    }

    @Test
    fun `R_380_nav_row's own unmerged node carries both OnClick and the composed description`() {
        // The register's own real-device finding, `NavRow`'s own instance of it (WP2 next round):
        // a plain, trailing `semantics(mergeDescendants = true) { contentDescription = ... }` does
        // not reliably keep the description on the *clickable* node itself once real child content
        // (this row's own `Text`s) sits beneath it — confirmed on-device for `TextAction`/`LogRow`/
        // `FilterChip` in an earlier round, fixed here with the identical `clearAndSetSemantics`
        // shape. Checked on the unmerged tree specifically, so this is about the one physical node
        // `OnClick` lives on, not a descendant's merged-in text.
        composeTestRule.setContent {
            OrtTheme {
                NavRow(
                    rowTitle = "Rig",
                    subLine = "TH-D75A · connected",
                    onClick = {},
                    modifier = Modifier.testTag("nav-rig"),
                )
            }
        }

        val node = composeTestRule.onNodeWithTag("nav-rig", useUnmergedTree = true).fetchSemanticsNode()
        assert(node.config.getOrNull(SemanticsActions.OnClick) != null) {
            "expected NavRow's own node to carry OnClick"
        }
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        assert(description?.contains("Rig") == true && description.contains("TH-D75A")) {
            "expected NavRow's own node (carrying OnClick) to also carry a description with both " +
                "the title and the sub-line, got $description"
        }
    }

    @Test
    fun `R_131_nav_row fires onClick and renders an optional trailing slot beside the chevron`() {
        var tapped = false
        composeTestRule.setContent {
            OrtTheme {
                NavRow(
                    rowTitle = "Tier",
                    onClick = { tapped = true },
                    trailing = { Badge(text = "Tier 1", kind = BadgeKind.TIER) },
                    modifier = Modifier.testTag("nav-tier"),
                )
            }
        }

        composeTestRule.onNodeWithText("TIER 1", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav-tier").performClick()
        assert(tapped)
    }

    @Test
    fun `R_131_nav_row's sub-line renders in full at font scale 2 point 0, never truncated`() {
        // R-131 (`Settings.dc.html`): a real status sentence must be free to wrap rather than
        // being cut to one ellipsised line — `NavRow` sets no `maxLines`/`overflow` on its
        // sub-line `Text` at all (Compose's own default is unrestricted, soft-wrapping), so there
        // is no ceiling here to regress back to. A height-based "did it actually wrap onto more
        // lines" assertion was tried and dropped: Robolectric returns degenerate glyph metrics
        // for this codebase's custom `fontFamily`s (confirmed directly, same finding recorded
        // against R-152's fix elsewhere in this package's `CHANGELOG.md` — a 71-character sub-line
        // and a 9-character one measured the identical row height, 88px, at the same font scale),
        // so a rendered-pixel wrap can't be verified reliably on this host. What *is* real and
        // host-independent is that the full sentence survives verbatim into the semantics tree —
        // this catches a future regression that truncates/summarises the string before it ever
        // reaches `Text`, which no font metric is needed to detect.
        val longSubLine = "This phone's best · what it does not know about weak signals or noise"
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    NavRow(
                        rowTitle = "Tier",
                        subLine = longSubLine,
                        onClick = {},
                        modifier = Modifier.width(320.dp).testTag("nav-tier"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText(longSubLine, useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav-tier").assert(hasContentDescription(longSubLine, substring = true))
    }

    @Test
    fun `R_131_nav_row's NotBuilt tone dims the row without hiding it`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    NavRow(
                        rowTitle = "Rig",
                        icon = OrtIcons.rig,
                        onClick = {},
                        tone = NavRowTone.NotBuilt,
                        modifier = Modifier.testTag("nav-not-built"),
                    )
                }
            }
        }

        // Still present, still reachable (constitution III: never delete quietly) — only its
        // colour differs, which this test does not (and, per this package's prior findings on
        // Robolectric font/paint metrics, reliably cannot) assert directly; the structural claim
        // — the row still renders and is still a real target — is what is checked here.
        // R-380 correction (WP2, gate-blocking): see the note above — the default merged tree
        // finds the outer node's own `text` uniquely now.
        composeTestRule.onNodeWithText("Rig").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav-not-built").assertHeightIsAtLeast(44.dp)
    }
}
