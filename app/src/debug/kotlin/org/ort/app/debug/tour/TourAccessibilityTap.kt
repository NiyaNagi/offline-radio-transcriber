package org.ort.app.debug.tour

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider

/**
 * spec/e2e-capture-modes-plan.md WPW, register R-1007 follow-up: taps a real, clickable node in the
 * composed screen's own accessibility tree — the mechanism `ScreenshotTourActivity`'s own
 * `tapLiveBar` drillIn uses to reach `LiveMonitorScreen`, since no `NavSeed` field exists for it
 * (`OrtNavHost.NavHostNavState.openCaptureLiveMonitor`'s own doc comment: "the pinned bar's own
 * tap", not a seed) and `ui/navigation` is outside this round's own file-ownership map.
 *
 * Shares [TourAccessibilityScroll]'s own bridge and reasoning (that class's doc comment has the
 * full account of why `AccessibilityNodeProvider`/`AccessibilityNodeInfo`, not `SemanticsOwner` or
 * a `getChild` walk): a virtual-view-id scan against the *provider* directly, driven through
 * [AccessibilityNodeProvider.performAction] — never [AccessibilityNodeInfo.performAction], which
 * throws on an unsealed instance the way [TourAccessibilityScroll]'s own doc comment already found.
 */
public object TourAccessibilityTap {

    /** What [tapClickableNodeWithText] actually did — a closed pair (constitution I), mirroring
     * [TourAccessibilityScroll.ScrollOutcome]'s own shape. */
    public sealed interface TapOutcome {
        /** A real, clickable node carrying [text] (as its own `text` or `contentDescription`) was
         * found and tapped. */
        public data object Tapped : TapOutcome

        /** No accessibility bridge exists under the root view at all, no clickable node anywhere
         * carries [text], or the platform's own `performAction` reported the tap did not go
         * through — never thrown; the caller decides whether that is a genuine step failure. */
        public data object NotFound : TapOutcome
    }

    /**
     * Scans [rootView]'s own accessibility tree for the first clickable node whose `text` or
     * `contentDescription` equals [text] exactly (never a substring match — `LiveBar`'s own
     * composed description is exactly `"Live"` for a nominal, no-partial, no-room-audio session,
     * `LiveBarPolling.toneAndLabel`'s own real label; a substring match risks a false hit against
     * some other node's longer, unrelated copy), and performs a standard `ACTION_CLICK` on it —
     * the identical action a real accessibility service (TalkBack) or a tap on the rendered pixel
     * would ultimately invoke, since [org.ort.app.ui.components.LiveBar]'s own `.clickable(...)`
     * modifier is what makes this node clickable in the first place.
     */
    public fun tapClickableNodeWithText(rootView: View, text: String): TapOutcome {
        val provider = TourAccessibilityScroll.findAccessibilityNodeProvider(rootView) ?: return TapOutcome.NotFound
        val targetId = findClickableVirtualViewIdWithText(provider, text) ?: return TapOutcome.NotFound
        val tapped = provider.performAction(targetId, AccessibilityNodeInfo.ACTION_CLICK, null)
        return if (tapped) TapOutcome.Tapped else TapOutcome.NotFound
    }

    /** The same "scan every virtual id, read one plain field, never `getChild`" shape
     * [TourAccessibilityScroll]'s own `findTallestScrollableVirtualViewId` uses, and unsealed-safe
     * for the identical reason (`isClickable`/`getText`/`getContentDescription` are all plain field
     * reads, never `enforceSealed()`-guarded). */
    private fun findClickableVirtualViewIdWithText(provider: AccessibilityNodeProvider, text: String): Int? {
        for (virtualViewId in AccessibilityNodeProvider.HOST_VIEW_ID..MAX_VIRTUAL_VIEW_ID) {
            val node = provider.createAccessibilityNodeInfo(virtualViewId) ?: continue
            if (!node.isClickable) continue
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return virtualViewId
        }
        return null
    }

    /** Matches [TourAccessibilityScroll]'s own bound — Compose's semantics ids are small,
     * process-lifetime-monotonic integers, so this stays a cheap, bounded, in-process scan. */
    private const val MAX_VIRTUAL_VIEW_ID = 50_000
}
