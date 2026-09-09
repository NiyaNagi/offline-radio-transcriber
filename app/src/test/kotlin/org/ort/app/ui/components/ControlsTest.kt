package org.ort.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * R-023/R-024 (ui-conformance-plan WP2): the shared control set. None of these existed before —
 * every screen hand-rolled `Text` + `clickable` with no pressed state and the text's own height
 * as the hit area (R-024's `halt` finding).
 */
@RunWith(RobolectricTestRunner::class)
class ControlsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `FR_A11Y_2 every interactive component has a 44dp target and a role`() {
        // R-510-class (`Controls.kt`'s own `TextAction` doc comment): this passed in Robolectric
        // both before and after that fix — a plain `testTag` modifier here never reproduced the
        // real device's own finding (`Sheet`'s "Clear all", no caller modifier at all, still
        // measured under the floor) — Robolectric's own layout pass is not the host that caught or
        // confirmed this defect; `requiredHeightIn` is pinned by the doc comment and the device
        // dump in this round's own CHANGELOG entry, not by a new assertion here.
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    TextAction(text = "Filter", onClick = {}, modifier = Modifier.testTag("text-action"))
                    PrimaryButton(text = "Start capture", onClick = {}, modifier = Modifier.testTag("primary"))
                    SecondaryButton(text = "Not now", onClick = {}, modifier = Modifier.testTag("secondary"))
                    DestructiveButton(text = "Delete audio", onClick = {}, modifier = Modifier.testTag("destructive"))
                    FilterChip(label = "All", selected = true, onClick = {}, modifier = Modifier.testTag("chip"))
                    RadioRow(label = "All bands", selected = true, onClick = {}, modifier = Modifier.testTag("radio"))
                    CheckboxRow(
                        label = "Confirmed",
                        checked = true,
                        onCheckedChange = {},
                        modifier = Modifier.testTag("checkbox"),
                    )
                    ToggleRow(
                        label = "Retain audio",
                        checked = true,
                        onCheckedChange = {},
                        modifier = Modifier.testTag("toggle"),
                    )
                }
            }
        }

        listOf(
            "text-action",
            "primary",
            "secondary",
            "destructive",
            "chip",
            "radio",
            "checkbox",
            "toggle",
        ).forEach { tag ->
            val node = composeTestRule.onNodeWithTag(tag)
            node.assertHeightIsAtLeast(44.dp)
            node.assertHasClickAction()
        }
    }

    @Test
    fun `R_380_the same node that carries OnClick also carries a real, non-empty description`() {
        // The register's own real-device finding (S12's `Fix`, dumped as `<node text="" content-
        // desc="" clickable="true" focusable="true"><node text="Fix" focusable="false"/></node>`):
        // an *empty* `semantics(mergeDescendants = true) {}` (or no `semantics` at all) depending on
        // merge-from-descendants to carry a child `Text`'s label up onto the clickable node itself
        // is unreliable on a real device, even though Robolectric's own merged-tree test model
        // let every one of these pass before this fix (Robolectric's own merge simulation is not
        // what a real device's AccessibilityNodeInfo tree actually does here). Every one of these
        // composables now composes its own description *explicitly*, on the same node `OnClick`
        // lives on — checked here on the *unmerged* tree specifically, so this assertion is about
        // that one physical node's own semantics config, not whatever the merged-tree view of a
        // descendant would report. `useUnmergedTree = true` also matters for `KeyValueRow`'s own
        // inner `Row`'s children never independently carrying `OnClick` themselves — only the row's
        // one outer node should.
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    TextAction(text = "Filter", onClick = {}, modifier = Modifier.testTag("text-action"))
                    PrimaryButton(text = "Start capture", onClick = {}, modifier = Modifier.testTag("primary"))
                    SecondaryButton(text = "Not now", onClick = {}, modifier = Modifier.testTag("secondary"))
                    DestructiveButton(text = "Delete audio", onClick = {}, modifier = Modifier.testTag("destructive"))
                    FilterChip(label = "All", selected = true, onClick = {}, modifier = Modifier.testTag("chip"))
                    KeyValueRow(
                        key = "Level",
                        value = "Not measured",
                        onClick = {},
                        onClickLabel = "Open level meter",
                        modifier = Modifier.testTag("kv"),
                    )
                }
            }
        }

        fun descriptionOf(tag: String) = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString()

        fun hasOnClick(tag: String) = composeTestRule.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.OnClick) != null

        listOf(
            "text-action" to "Filter",
            "primary" to "Start capture",
            "secondary" to "Not now",
        ).forEach { (tag, label) ->
            assert(hasOnClick(tag)) { "expected node '$tag' to carry OnClick" }
            assert(descriptionOf(tag)?.contains(label) == true) {
                "expected node '$tag' (carrying OnClick) to also carry a description containing " +
                    "'$label', got ${descriptionOf(tag)}"
            }
        }

        assert(descriptionOf("destructive")?.contains("Delete audio") == true) {
            "expected 'destructive' to carry a description containing 'Delete audio', got " +
                descriptionOf("destructive")
        }

        assert(descriptionOf("chip")?.contains("All") == true) {
            "expected the filter chip's own clickable node to carry a description containing 'All', " +
                "got ${descriptionOf("chip")}"
        }

        assert(hasOnClick("kv")) { "expected KeyValueRow to carry OnClick" }
        val kvDescription = descriptionOf("kv")
        assert(kvDescription?.contains("Level") == true && kvDescription.contains("Open level meter")) {
            "expected KeyValueRow's own clickable node to carry both the fact ('Level, Not " +
                "measured') and the action hint ('Open level meter'), got $kvDescription"
        }
    }

    @Test
    fun `a disabled text action carries text_disabled and does not fire its click`() {
        var clicked = false
        composeTestRule.setContent {
            OrtTheme {
                TextAction(
                    text = "Filter",
                    onClick = { clicked = true },
                    enabled = false,
                    modifier = Modifier.testTag("t"),
                )
            }
        }

        composeTestRule.onNodeWithTag("t").performClick()
        assert(!clicked) { "a disabled TextAction must not fire onClick" }
    }

    @Test
    fun `a filter chip is selectable and its dismiss affordance is a real icon, never text`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    FilterChip(
                        label = "Confirmed",
                        selected = true,
                        onClick = {},
                        modifier = Modifier.testTag("selected"),
                    )
                    FilterChip(
                        label = "Named",
                        selected = false,
                        onClick = {},
                        modifier = Modifier.testTag("unselected"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("selected").assertIsSelected()
        composeTestRule.onNodeWithTag("unselected").assertIsNotSelected()
        // Guide §7: never a font glyph for the dismiss affordance — proven by its absence as text.
        composeTestRule.onNodeWithText("×").assertDoesNotExist()
    }

    @Test
    fun `R_543_a dismissable chip exposes Remove as a custom accessibility action on its own node`() {
        // A real device confirmed `clearAndSetSemantics` (R-380/R-381's own fix) genuinely removes
        // every descendant from the *exported* accessibility tree — including the dismiss icon's
        // own, previously independently-reachable node. Robolectric's own `SemanticsNode` model
        // does not delete descendants the same way (`useUnmergedTree = true` still finds the icon's
        // own node directly, confirmed by this file's other filter-chip tests, unaffected by this
        // fix) — this test is instead checking the *mechanism* this fix actually adds: a
        // `CustomAccessibilityAction` on the chip's own node, the one thing Robolectric's model
        // *can* verify here, and the one thing a real device's TalkBack reads from without needing
        // a second, independently-reachable node at all.
        var dismissed = false
        composeTestRule.setContent {
            OrtTheme {
                FilterChip(
                    label = "W7NPC",
                    selected = true,
                    onClick = {},
                    onDismiss = { dismissed = true },
                    modifier = Modifier.testTag("chip"),
                )
            }
        }

        val node = composeTestRule.onNodeWithTag("chip").fetchSemanticsNode()
        val actions = node.config.getOrNull(SemanticsActions.CustomActions)
        assert(actions != null && actions.size == 1) {
            "expected the chip's own node to carry exactly one custom accessibility action, got $actions"
        }
        val removeAction = actions!!.first()
        assert(removeAction.label == "Remove W7NPC filter") {
            "expected the custom action's own label to name the filter, got '${removeAction.label}'"
        }
        removeAction.action.invoke()
        assert(dismissed) { "expected invoking the custom action to reach the caller's onDismiss" }
    }

    @Test
    fun `R_211_filter_chip_row scrolls so an overflowing last chip is reachable at font scale 2`() {
        // Station-Pattern.dc.html: at font scale 2.0 a third mode chip ("Change over time") was
        // clipped off-screen with no way to reach it — a narrow, fixed-width container here
        // stands in for the same overflow, real content and real scale, not a synthetic prop.
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    FilterChipRow(modifier = Modifier.width(200.dp).testTag("chips")) {
                        FilterChip(label = "By night", selected = false, onClick = {})
                        FilterChip(label = "By day of week", selected = false, onClick = {})
                        FilterChip(label = "Change over time", selected = true, onClick = {})
                    }
                }
            }
        }

        // Reachable only by scrolling the row — if the row clipped instead of scrolling,
        // `performScrollTo` would find no scrollable ancestor able to bring it into view and this
        // would fail, exactly the defect the register caught on a real device. R-380 correction
        // (WP2, gate-blocking): `FilterChip`'s own outer node now carries its label as both
        // `contentDescription` and `text` (`CHANGELOG.md`), so the default merged tree finds it
        // directly and uniquely.
        composeTestRule.onNodeWithText("Change over time").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `R_023 badges render the guide's complete set as distinct visible labels`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    Badge(text = "new", kind = BadgeKind.NEW)
                    Badge(text = "revised", kind = BadgeKind.REVISED)
                    Badge(text = "corrected", kind = BadgeKind.CORRECTED)
                    Badge(text = "tier 1", kind = BadgeKind.TIER)
                    Badge(text = "3", kind = BadgeKind.COUNT)
                }
            }
        }

        composeTestRule.onNodeWithText("NEW").assertIsDisplayed()
        composeTestRule.onNodeWithText("REVISED").assertIsDisplayed()
        composeTestRule.onNodeWithText("CORRECTED").assertIsDisplayed()
        composeTestRule.onNodeWithText("TIER 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun `R_023 a halted step indicator differs from a done or upcoming one`() {
        composeTestRule.setContent {
            OrtTheme { StepIndicator(steps = 7, currentStep = 3, haltedStep = 3, modifier = Modifier.testTag("steps")) }
        }

        composeTestRule.onNode(hasContentDescription("halted", substring = true, ignoreCase = true)).assertIsDisplayed()
        composeTestRule.onNodeWithText("3 of 7").assertIsDisplayed()
    }

    @Test
    fun `a progress bar reports its percentage rather than spinning indeterminately`() {
        composeTestRule.setContent {
            OrtTheme { ProgressBar(progress = 0.42f, modifier = Modifier.testTag("progress")) }
        }

        composeTestRule.onNodeWithTag("progress").assert(hasContentDescription("42 percent"))
    }

    @Test
    fun `R_081_a warning-tone radio row shows an amber subtitle, per Setup-Input_dc_html's refused mic`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    RadioRow(
                        label = "USB Audio Device",
                        selected = true,
                        onClick = {},
                        subtitle = "USB · 48 kHz native · resampled to 16 kHz",
                        modifier = Modifier.testTag("usb"),
                    )
                    RadioRow(
                        label = "Built-in microphone",
                        selected = false,
                        onClick = {},
                        subtitle = "Not a radio — capture will refuse this route",
                        tone = RowTone.Warning,
                        modifier = Modifier.testTag("built-in"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("USB · 48 kHz native · resampled to 16 kHz").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not a radio — capture will refuse this route").assertIsDisplayed()
    }

    @Test
    fun `a radio row with no subtitle renders exactly as before`() {
        composeTestRule.setContent {
            OrtTheme {
                RadioRow(label = "All bands", selected = true, onClick = {}, modifier = Modifier.testTag("plain"))
            }
        }

        composeTestRule.onNodeWithText("All bands").assertIsDisplayed()
        composeTestRule.onNodeWithTag("plain").assertHeightIsAtLeast(44.dp)
    }

    @Test
    fun `R_061_a filter chip's leadingIcon renders beside the label, never replacing it`() {
        composeTestRule.setContent {
            OrtTheme {
                FilterChip(
                    label = "Filters",
                    selected = false,
                    onClick = {},
                    leadingIcon = OrtIcons.filters,
                    modifier = Modifier.testTag("filters-chip"),
                )
            }
        }

        // R-380 correction (WP2, gate-blocking): see the note above.
        composeTestRule.onNodeWithText("Filters").assertIsDisplayed()
        composeTestRule.onNodeWithTag("filters-chip").assertHeightIsAtLeast(44.dp)
    }

    @Test
    fun `R_383_a filter chip in a real FilterChipRow still meets the 44dp floor, no leading icon`() {
        // L02 `Log-Filter`'s own repro: the frequency row's chips (plain text, no leading icon,
        // several siblings in one `FilterChipRow`) measured 98-103px on the register's own device
        // dump, under the 116px/44dp floor, while a differently-built chip row on the same sheet
        // met it — the shape this test renders is the frequency row's own, not the single, icon-
        // carrying chip `R_211`'s neighbour test above already covers.
        composeTestRule.setContent {
            OrtTheme {
                FilterChipRow {
                    FilterChip(
                        label = "All",
                        selected = true,
                        onClick = {},
                        modifier = Modifier.testTag("all"),
                    )
                    FilterChip(label = "145.230", selected = false, onClick = {})
                    FilterChip(label = "146.960", selected = false, onClick = {})
                }
            }
        }

        composeTestRule.onNodeWithTag("all").assertHeightIsAtLeast(44.dp)
    }

    @Test
    fun `R_510_filter_chip meets the 44dp floor even when a caller's own modifier tries to undercut it`() {
        // L02 `Log-Filter`'s own frequency chip row (`overnight/L02-filter-sheet.png`, `@2x`):
        // the register measured 24dp at 1.0 and 37.7dp at 2.0 — exactly the chip's own *content*
        // height (padding + one line of `OrtType.chip` text) with no 44dp floor applied at all,
        // not merely a floor squeezed narrower by real content the way R-383's own repro was. A
        // plain `heightIn(min = 44.dp)`, deep in this composable's own modifier chain, only ever
        // *raises* the incoming constraints' floor — it cannot violate a *tighter* constraint a
        // caller's own modifier already fixed further out in the chain (`modifier` is this
        // composable's own leftmost/outermost parameter, ahead of everything this file adds), so
        // a caller able to pass e.g. `Modifier.height(24.dp)` genuinely undercuts it. This is
        // exactly what "enforce the 44dp floor inside the component so no caller can undercut it"
        // means: `requiredHeightIn(min = 44.dp)` — the fix — ignores the incoming constraint
        // rather than merely widening it, the same defensive-minimum-touch-target technique used
        // wherever a component must guarantee its own floor regardless of its caller.
        composeTestRule.setContent {
            OrtTheme {
                FilterChip(
                    label = "145.230",
                    selected = true,
                    onClick = {},
                    modifier = Modifier.testTag("undercut").height(24.dp),
                )
            }
        }

        composeTestRule.onNodeWithTag("undercut").assertHeightIsAtLeast(44.dp)
    }

    @Test
    fun `R_565_filter_chip's fill and border are drawn on the same node the 44dp floor is measured on`() {
        // Reviewer B's own re-measurement of `overnight/L02-filter-sheet.png`/`@2x`: R-510's
        // `requiredHeightIn` grew the chip's own *touch target* to 48dp, but `.background()`/
        // `.border()` still lived on the *inner* `Row`, which only ever wrapped its own, much
        // shorter content — the *painted* pill stayed ~24dp at 1.0/~32dp at 2.0, invisible padding
        // around a genuinely undersized visible chip. `.background()`/`.border()` are draw-only
        // modifiers — neither creates its own semantics node, with or without this fix — so there
        // is no separate "pill" node this host's own layout pass could ever measure independently
        // of the touch target, before or after: this fact is real only in the rendered pixels, and
        // this file's own `OrtThemeTest.kt` already found `captureToImage()` unusable in this
        // sandbox (hangs resolving native graphics with no network egress). What this test pins
        // instead, host-independently: moving the fill/border onto the same outer node
        // `requiredHeightIn` already lives on does not reintroduce the floor's own regression (a
        // draw-only modifier's presence must never coerce the node's own measured size) — checked
        // for both the `selected` (fill) and unselected (border) branches, since they take
        // different paths through `.then(...)`. The *painted* pill itself now filling 44dp is
        // confirmed by this round's own device screenshot (`emulator-5554`, this entry's own
        // `CHANGELOG.md`), not by this test.
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    FilterChip(
                        label = "Selected",
                        selected = true,
                        onClick = {},
                        modifier = Modifier.testTag("selected-chip"),
                    )
                    FilterChip(
                        label = "Unselected",
                        selected = false,
                        onClick = {},
                        modifier = Modifier.testTag("unselected-chip"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("selected-chip").assertHeightIsAtLeast(44.dp)
        composeTestRule.onNodeWithTag("unselected-chip").assertHeightIsAtLeast(44.dp)
    }

    @Test
    fun `a text field shows its label, placeholder, error text and reports edits`() {
        var value = ""
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    TextField(
                        value = value,
                        onValueChange = { value = it },
                        label = "Callsign",
                        placeholder = "W7NPC",
                        mono = true,
                        contentDescriptionText = "Typed callsign",
                        modifier = Modifier.testTag("field"),
                    )
                    TextField(
                        value = "bad",
                        onValueChange = {},
                        errorText = "Not a known callsign",
                        modifier = Modifier.testTag("field-error"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Callsign").assertIsDisplayed()
        composeTestRule.onNodeWithText("W7NPC").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not a known callsign").assertIsDisplayed()
        composeTestRule.onNodeWithTag("field").assertHeightIsAtLeast(44.dp)

        // `modifier` (carrying the testTag) lands on TextField's outer wrapper, so `weight` works
        // in a Row (see the dedicated test below); `contentDescriptionText` and the real
        // `RequestFocus`/`SetText` actions live together on the inner BasicTextField, so callers
        // drive the field the same simple way every other content-description query does.
        composeTestRule.onNodeWithContentDescription("Typed callsign").performTextInput("K7LWH")
        assert(value == "K7LWH") { "expected onValueChange to report the typed text, got '$value'" }
    }

    @Test
    fun `R_060_text_field_carries_a_leading_icon_and_trailing_clear`() {
        var cleared = false
        composeTestRule.setContent {
            OrtTheme {
                TextField(
                    value = "park activation",
                    onValueChange = {},
                    leadingIcon = OrtIcons.search,
                    trailingAction = {
                        Icon(
                            imageVector = OrtIcons.dismiss,
                            contentDescription = "Clear",
                            tint = OrtColors.textChipX,
                            modifier = Modifier
                                .size(14.dp)
                                .clickable(role = Role.Button, onClickLabel = "Clear", onClick = { cleared = true }),
                        )
                    },
                    modifier = Modifier.testTag("search-field"),
                )
            }
        }

        // The leading icon and trailing clear both sit inside the bordered box, per Search.dc.html
        // — proven here by the field still rendering its value plus a working trailing action.
        composeTestRule.onNodeWithText("park activation").assertIsDisplayed()
        composeTestRule.onNode(hasContentDescription("Clear")).assertIsDisplayed()
        composeTestRule.onNode(hasContentDescription("Clear")).performClick()
        assert(cleared) { "expected the trailing action's click to reach the caller" }
    }

    @Test
    fun `R_060_keyboard_search_action_reaches_the_caller`() {
        var searched = false
        composeTestRule.setContent {
            OrtTheme {
                TextField(
                    value = "",
                    onValueChange = {},
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { searched = true }),
                    contentDescriptionText = "Search text",
                    modifier = Modifier.testTag("search-field"),
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Search text").performImeAction()
        assert(searched) { "expected the keyboard's Search IME action to reach the caller's onSearch" }
    }

    @Test
    fun `R_063_degraded_error_tone_is_amber_not_halt`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    TextField(
                        value = "",
                        onValueChange = {},
                        errorText = "Text not applied",
                        errorTone = FieldTone.Degraded,
                        modifier = Modifier.testTag("degraded"),
                    )
                    TextField(
                        value = "",
                        onValueChange = {},
                        errorText = "Not a known callsign",
                        modifier = Modifier.testTag("halt"),
                    )
                }
            }
        }

        // guide §3: `halt` is reserved for capture stopped — a degraded field error is a distinct,
        // named tone, not the default. Both render their own text; the default stays FieldTone.Halt
        // so every caller from before this parameter existed is unaffected.
        composeTestRule.onNodeWithText("Text not applied").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not a known callsign").assertIsDisplayed()
    }

    @Test
    fun `a_weight_modifier_on_the_field_is_honoured_in_a_row`() {
        composeTestRule.setContent {
            OrtTheme {
                Row(modifier = Modifier.width(300.dp)) {
                    Box(modifier = Modifier.width(100.dp).testTag("sibling"))
                    TextField(
                        value = "",
                        onValueChange = {},
                        modifier = Modifier.weight(1f).testTag("weighted-field"),
                    )
                }
            }
        }

        // Before this fix, `modifier` (carrying `weight`) landed on an inner grandchild rather
        // than the outer node TextField emits as a direct Row child, so `weight` had no effect and
        // the field either filled the whole row or collapsed — neither is the ~200dp (300 - 100)
        // remaining share weight correctly produces.
        val width = composeTestRule.onNodeWithTag("weighted-field").fetchSemanticsNode().size.width
        assert(width in 150..250) {
            "expected the field to take its weighted share of the row (~200dp of 300dp), got ${width}px"
        }
    }
}
