package org.ort.app.debug.tour

import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider

/**
 * spec/e2e-capture-modes-plan.md WPW, register R-1007 follow-up, **R-1021**: taps a real node in
 * the composed screen's own accessibility tree — the mechanism `ScreenshotTourActivity`'s own
 * `tapLiveBar` drillIn uses to reach `LiveMonitorScreen`, since no `NavSeed` field exists for it
 * (`OrtNavHost.NavHostNavState.openCaptureLiveMonitor`'s own doc comment: "the pinned bar's own
 * tap", not a seed) and `ui/navigation` is outside this round's own file-ownership map.
 *
 * **R-1021 (the lead's own field report): locating the live bar by its rendered *label* was the
 * defect.** `"Live"` is only [org.ort.app.ui.data.LiveBarPolling.toneAndLabel]'s own `else`
 * branch — outranked by nine other real states (`"Gap"`, `"N behind"`, `"Tier N"`, `"Rig lost"`,
 * ...), several of which `overnight-live-monitor` (this package's own scenario) deliberately seeds,
 * because they are exactly the states `Live-Monitor.dc.html` exists to render. Matching on that
 * copy is constitution II's "never assert prose... that a designer may legitimately change
 * tomorrow" — arising here in the capture tooling rather than a test, which is worse, not better,
 * because a tooling failure reads identically to a feature failure. [tapNodeWithTestTag] (and its
 * own read, [hasNodeWithTestTag]) replace copy-matching with
 * [org.ort.app.ui.components.LiveBar]'s own stable `testTag("live-bar")`, present on every
 * tone/label/partial-text combination alike. **This round's own audit (reported to the lead,
 * `R-1021`) found no other `tour.json` step matching on rendered copy** except this one and
 * [org.ort.app.debug.tour.TourAccessibilityScroll]'s own `"loading"` placeholder-text check
 * (pre-existing, not this file's own row, reported rather than fixed) — so the text-matching
 * entry point this file used to keep "for a future step" was removed outright rather than left as
 * a standing invitation to reintroduce the identical defect: a public, unused function whose only
 * purpose was matching on a label is exactly the shape this fix exists to argue against.
 *
 * Shares [TourAccessibilityScroll]'s own bridge and reasoning (that class's doc comment has the
 * full account of why `AccessibilityNodeProvider`/`AccessibilityNodeInfo`, not `SemanticsOwner` or
 * a `getChild` walk): a virtual-view-id scan against the *provider* directly, driven through
 * [AccessibilityNodeProvider.performAction] — never [AccessibilityNodeInfo.performAction], which
 * throws on an unsealed instance the way [TourAccessibilityScroll]'s own doc comment already found.
 *
 * **Reading a `testTag`, found only by actually running two candidate mechanisms under Robolectric
 * before writing this, not assumed from either's own documentation.** Compose UI 1.7.3 also
 * exposes a `testTag` through the platform's "extra data" channel
 * (`AccessibilityNodeInfo.setAvailableExtraData`/`addExtraDataToAccessibilityNodeInfo`, the same
 * one `androidx.compose.ui.test.uiautomator`'s own matcher uses under the key
 * `"androidx.compose.ui.semantics.testTag"`, confirmed present by decompiling
 * `AndroidComposeViewAccessibilityDelegateCompat`) — a real diagnostic test built against it here
 * found the key correctly *declared* in `node.availableExtraData` but the requested value never
 * actually landed in `node.extras` after calling `addExtraDataToAccessibilityNodeInfo`, on this
 * Robolectric/Compose combination; not chased further; this file does not depend on it, so it
 * remains an open question rather than this fix's problem to solve. What **is** proven to work,
 * empirically, on this project's own Robolectric harness: [SemanticsPropertiesAndroid
 * .testTagsAsResourceId] set `true` on an ancestor (`ScreenshotTourActivity`'s own composition
 * root — a debug-tour-only opt-in, no production composable touched) makes every descendant's
 * `testTag` show up as the ordinary `AccessibilityNodeInfo.viewIdResourceName` — a plain field,
 * unsealed-safe for the identical reason [TourAccessibilityScroll]'s own `isScrollable`/bounds
 * reads already are.
 */
public object TourAccessibilityTap {

    /** What a tap actually did — a closed pair (constitution I), mirroring
     * [TourAccessibilityScroll.ScrollOutcome]'s own shape. */
    public sealed interface TapOutcome {
        /** A real, matching, clickable node was found and tapped. */
        public data object Tapped : TapOutcome

        /** No accessibility bridge exists under the root view at all, no clickable node anywhere
         * matches, or the platform's own `performAction` reported the tap did not go through —
         * never thrown; the caller decides whether that is a genuine step failure. */
        public data object NotFound : TapOutcome
    }

    /**
     * R-1021: scans [rootView]'s own accessibility tree for the first clickable node whose real
     * Compose `testTag` equals [testTag] exactly (via [AccessibilityNodeInfo.getViewIdResourceName],
     * populated only while some ancestor has opted into `testTagsAsResourceId` — see this object's
     * own doc comment), and performs a standard `ACTION_CLICK` on it. Never matches on rendered
     * text/label — see this object's own doc comment for why that was the actual defect this
     * replaces.
     */
    public fun tapNodeWithTestTag(rootView: View, testTag: String): TapOutcome {
        val provider = TourAccessibilityScroll.findAccessibilityNodeProvider(rootView) ?: return TapOutcome.NotFound
        val targetId = findClickableVirtualViewIdWithTestTag(provider, testTag) ?: return TapOutcome.NotFound
        val tapped = provider.performAction(targetId, AccessibilityNodeInfo.ACTION_CLICK, null)
        return if (tapped) TapOutcome.Tapped else TapOutcome.NotFound
    }

    /** R-1021: `true` when some node anywhere in [rootView]'s own accessibility tree carries the
     * real Compose `testTag` [testTag] — clickable or not. Lets a caller confirm a screen has
     * actually composed by a stable tag (e.g. `LiveMonitorScreen`'s own `"live-monitor-top-bar"`)
     * instead of the same copy-matching mistake this object's own doc comment describes. */
    public fun hasNodeWithTestTag(rootView: View, testTag: String): Boolean {
        val provider = TourAccessibilityScroll.findAccessibilityNodeProvider(rootView) ?: return false
        return findAnyVirtualViewIdWithTestTag(provider, testTag) != null
    }

    /**
     * R-1021: locates the first *clickable* node whose real Compose `testTag` equals [testTag].
     * `LiveBar`'s own `testTag("live-bar")` sits on its outer, non-clickable `Column` (the
     * clickable node is a distinct, nested one a couple of `LayoutNode`s down, carrying whichever
     * screen-specific tag the caller supplied instead) — so this cannot simply act on the tagged
     * node's own id the way a node that is itself clickable could be acted on directly. Instead it
     * records the tagged node's own [Rect] and returns the first *clickable* node anywhere in the
     * scan whose bounds [boundsMatchTag] the tagged node's own — generic (no assumption about how
     * many `LayoutNode`s sit between the tag and the click, and no dependency on virtual-id ordering
     * reflecting tree structure, which it does not: ids are assigned in creation order, not
     * depth-first), and safe for the live bar specifically because its clickable row fills
     * essentially the whole tagged column (confirmed by reading `LiveBar.kt` before writing this:
     * the only sibling inside the tagged `Column` is a 1dp top-edge divider with no semantics of its
     * own, so no other clickable node could ever match the same bounds).
     */
    private fun findClickableVirtualViewIdWithTestTag(provider: AccessibilityNodeProvider, testTag: String): Int? {
        val taggedBounds = boundsOfFirstNodeWithTestTag(provider, testTag) ?: return null
        for (virtualViewId in AccessibilityNodeProvider.HOST_VIEW_ID..MAX_VIRTUAL_VIEW_ID) {
            val node = provider.createAccessibilityNodeInfo(virtualViewId) ?: continue
            if (!node.isClickable) continue
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (boundsMatchTag(taggedBounds, bounds)) return virtualViewId
        }
        return null
    }

    /**
     * **R-1025** (register): the real predicate behind "this clickable node belongs to that tagged
     * one" — **not** [Rect.contains], the previous check, which a real device run
     * (`overnight-live-monitor/N07-live-monitor`, font scale 1.0) proved wrong: `live-bar`'s own
     * tagged Column measured `Rect(0, 2564, 1260, 2709)` while its own clickable Row
     * (`capture-status-livebar`) measured `Rect(0, 2560, 1260, 2709)` — 4px *taller at its own top
     * edge* than the Column that visually contains it. That is not a layout bug: Android's
     * accessibility delegate pads a clickable node's *reported* bounds up to the platform's 48dp
     * minimum touch target when its real measured height is smaller
     * ([org.ort.app.ui.components.LiveBar]'s own Row is a deliberate 44dp,
     * `LiveBar.kt`'s `heightIn(min = 44.dp)`), and the live bar sits flush against the screen's own
     * bottom edge with no room to expand the touch target downward — so the whole deficit is pushed
     * upward, past the tagged Column's own top edge. `Rect.contains()` requires the candidate to sit
     * entirely inside the tagged bounds and therefore rejects this real, correct geometry. At font
     * scale 2.0 the Row's own real text/line height already clears 48dp, no padding is added, and
     * `contains()` happened to hold — the reason this defect was font-scale-dependent, not an
     * Activity recreation or a settle-timing race (both already ruled out — see the register row).
     * [Rect.intersects] — real, non-trivial overlap, not merely touching edges — is the correct
     * check: this project's own accessibility-padding case always overlaps the vast majority of the
     * tagged bounds, and per [findClickableVirtualViewIdWithTestTag]'s own doc comment no other,
     * unrelated clickable node ever shares that same screen region for this tag.
     */
    internal fun boundsMatchTag(taggedBounds: Rect, candidateBounds: Rect): Boolean =
        Rect.intersects(taggedBounds, candidateBounds)

    private fun findAnyVirtualViewIdWithTestTag(provider: AccessibilityNodeProvider, testTag: String): Int? {
        for (virtualViewId in AccessibilityNodeProvider.HOST_VIEW_ID..MAX_VIRTUAL_VIEW_ID) {
            val node = provider.createAccessibilityNodeInfo(virtualViewId) ?: continue
            if (node.viewIdResourceName == testTag) return virtualViewId
        }
        return null
    }

    private fun boundsOfFirstNodeWithTestTag(provider: AccessibilityNodeProvider, testTag: String): Rect? {
        val virtualViewId = findAnyVirtualViewIdWithTestTag(provider, testTag) ?: return null
        val node = provider.createAccessibilityNodeInfo(virtualViewId) ?: return null
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return bounds
    }

    /**
     * **R-1025** (register): `overnight-live-monitor/N07-live-monitor`'s own 1.0 step fails to find
     * a clickable node inside [testTag]'s own bounds while the identical scenario at 2.0 succeeds
     * every time — and [tapNodeWithTestTag] itself is already proven, in isolation, to tap a real
     * [org.ort.app.ui.components.LiveBar] correctly at the default font scale
     * ([TourAccessibilityTapTest]'s own first case). The gap is therefore something the *real,
     * composed* screen does differently that no bare-component test reproduces — evidence this
     * function exists to carry off a real device rather than guess at: [testTag]'s own resolved
     * bounds (or `"NOT FOUND"`, honestly, rather than throwing — the same closed-outcome discipline
     * [tapNodeWithTestTag] itself follows), then one line per node that is either resource-named
     * (carries a `testTag`) or clickable — its virtual id, resource name, clickability and bounds,
     * and whether those bounds fall inside [testTag]'s own (the exact fact
     * [findClickableVirtualViewIdWithTestTag] tests). Wired into [ScreenshotTourActivity]'s own
     * tap-failure error message so the evidence lands in the tour's own manifest.json, next to the
     * failure it explains, with no separate logcat capture needed.
     */
    public fun describeNodes(rootView: View, testTag: String): String {
        val provider = TourAccessibilityScroll.findAccessibilityNodeProvider(rootView)
            ?: return "no AccessibilityNodeProvider found under the root view"
        val taggedBounds = boundsOfFirstNodeWithTestTag(provider, testTag)
        val lines = mutableListOf("tagged('$testTag') bounds=${taggedBounds ?: "NOT FOUND"}")
        for (virtualViewId in AccessibilityNodeProvider.HOST_VIEW_ID..MAX_VIRTUAL_VIEW_ID) {
            val node = provider.createAccessibilityNodeInfo(virtualViewId) ?: continue
            val resourceName = node.viewIdResourceName
            if (resourceName == null && !node.isClickable) continue
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val insideTag = taggedBounds?.contains(bounds) == true
            lines += "id=$virtualViewId resourceName=$resourceName clickable=${node.isClickable} " +
                "bounds=$bounds insideTag=$insideTag"
        }
        return lines.joinToString("\n")
    }

    /** Matches [TourAccessibilityScroll]'s own bound — Compose's semantics ids are small,
     * process-lifetime-monotonic integers, so this stays a cheap, bounded, in-process scan. */
    private const val MAX_VIRTUAL_VIEW_ID = 50_000
}
