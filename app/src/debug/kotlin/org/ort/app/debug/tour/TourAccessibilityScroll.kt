package org.ort.app.debug.tour

import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider

/**
 * spec/ui-conformance-plan.md WP12 v5, register R-460: scrolls a composed screen's own primary
 * vertical scroll container to its end, so a `@2x` capture shows the *bottom* of the screen —
 * proving "scrollable" rather than leaving a first frame that cannot be told apart from "clipped"
 * (R-360's own finding, closed on a real device only by hand-scrolling).
 *
 * **Why `AccessibilityNodeInfo`, not `SemanticsOwner`, despite the coordinator's own "semantics
 * owner" wording**: `androidx.compose.ui.semantics.SemanticsOwner` is real, public API — but every
 * way to *obtain* an instance of it from outside a composition (`ViewRootForTest`,
 * `SemanticsNodeInteractionsProvider`, `createComposeRule()`) lives in the `androidx.compose.ui:ui-test`
 * artifact, which this app only carries as a `testImplementation`/`androidTestImplementation`
 * dependency (checked `app/build.gradle.kts` before writing this — confirmed, not assumed) — never
 * shipped into the real `debug` variant APK [ScreenshotTourActivity] itself runs in. Adding a new
 * production dependency is `app/build.gradle.kts`, outside this package's own file row, so this
 * uses the one accessible, production-safe path instead: Compose translates its own semantics tree
 * into the *real*, standard Android accessibility tree unconditionally (the same bridge `TalkBack`/
 * `uiautomator` read from a different process) — `View.getAccessibilityNodeProvider()`,
 * `AccessibilityNodeProvider` and `AccessibilityNodeInfo` are core `android.view.accessibility`
 * framework classes, no extra dependency needed, and precisely what "any screen works, generic,
 * semantics-based" asks for: a scroll container is one whose `AccessibilityNodeInfo.isScrollable` is
 * `true`, exactly the property Compose's own `Modifier.verticalScroll`/`LazyColumn` semantics set.
 * See [scrollToEnd]'s own doc comment for why the actual scroll action is driven through
 * [AccessibilityNodeProvider.performAction] rather than `AccessibilityNodeInfo.performAction`.
 */
public object TourAccessibilityScroll {

    /**
     * What [scrollToEnd] actually did — a closed pair (constitution I), never a thrown exception for
     * the honest "this screen has nothing to scroll" case (coordinator round two, the seven-error
     * first-tour-run finding): a `scroll: "end"` step exists to *prove reachability*, and a screen
     * that already fits without scrolling, even at font scale 2.0, proves exactly that — it is
     * evidence the screen does not clip, not a failure to record as one.
     */
    public sealed interface ScrollOutcome {
        /** A real scrollable container was found and driven to its end. */
        public data object Scrolled : ScrollOutcome

        /** No vertically-scrollable container exists in this screen's accessibility tree — the
         * screen's whole content already fits on one page, even at this font scale. Never thrown:
         * [ScreenshotTourActivity] captures the screen exactly as it stands and records a note, not
         * an error (see that class's own call sites). */
        public data object NothingToScroll : ScrollOutcome
    }

    /**
     * Scrolls [rootView]'s screen to the end of its primary vertical scroll container, by driving
     * repeated `ACTION_SCROLL_FORWARD` accessibility actions against the accessibility root node,
     * stopping once an action reports it moved nothing further (never a fixed step count guessing
     * "the end"). Returns [ScrollOutcome.NothingToScroll] rather than throwing when the screen has no
     * scrollable container at all — a real, common case (a short screen at 2x font scale can still
     * fit on one page) distinct from [error]'s own genuine-defect case below (no accessibility
     * bridge present at all, which would mean nothing on this screen — scrollable or not — could ever
     * be found this way).
     *
     * **Why this walks the *View* hierarchy first, found only by actually running this on a real
     * device, not by inspection**: `View.getAccessibilityNodeProvider()` is a plain per-View getter,
     * never aggregated from descendants — passing `window.decorView` (the outer `DecorView`) returns
     * null even though the screen's real content is fully scrollable, because the provider Compose
     * installs lives on the specific `AndroidComposeView` several levels *inside* the decor view (a
     * `setContent { }` activity's own content hierarchy), not on the decor view itself. This BFS-
     * walks the plain [ViewGroup] tree from [rootView] down, taking the *first* descendant whose own
     * `accessibilityNodeProvider` is non-null as the provider — generic (works whether the real
     * Compose view sits one level down or several, and needs no reference to any Compose-specific
     * type), and exactly matches how a real accessibility service (TalkBack, `uiautomator`) locates
     * the same bridge in production.
     *
     * **Why the action is driven through [AccessibilityNodeProvider.performAction] and never through
     * `AccessibilityNodeInfo.performAction`/`getChild`, found only by actually running this on a real
     * device, not by inspection**: an `AccessibilityNodeInfo` obtained by calling
     * `provider.createAccessibilityNodeInfo(...)` directly, in-process, is an *unsealed* instance —
     * sealing (and the live `mConnectionId` that `performAction`/`getChild` need) is only ever
     * assigned by the OS-mediated `AccessibilityInteractionClient` round trip a genuine bound
     * `AccessibilityService` (TalkBack) or `UiAutomation` (an *instrumented* test process — this
     * plain `am start`-launched activity has no `Instrumentation` to obtain one from) goes through;
     * calling either method on such an instance throws `IllegalStateException: Cannot perform this
     * action on a not sealed instance.` (reproduced directly). `AccessibilityNodeProvider.performAction
     * (virtualViewId, action, arguments)`, called on the *provider* itself rather than on a node
     * object, carries none of that requirement — it is the same entry point the OS calls into on the
     * client's behalf, just invoked directly.
     *
     * **Why a virtual-view-id scan, not a [AccessibilityNodeInfo.getChild] tree walk, found only by
     * actually running this against every board this tour captures, not by inspection**: the same
     * unsealed-instance limitation above means `getChild(...)` cannot be used to descend either — it
     * calls the identical `enforceSealed()` guard `performAction` does, reproduced directly (the
     * *first* build of this function tried exactly that walk and hit the same "not sealed" exception
     * from inside it, not from `performAction`, once the root-cause was isolated by testing the host
     * node's own `isScrollable` in isolation and finding it `false` on every one of these screens —
     * their scroll region is a genuine descendant, not the merged root). No public API exposes a
     * virtual child's own id from a parent [AccessibilityNodeInfo] either, so there is no sealed-free
     * way to *navigate* to it. What *does* stay unsealed-safe, because it never calls `enforceSealed()`
     * (confirmed by reading `AccessibilityNodeInfo`'s own source and by the host-node check above
     * itself never throwing): a plain field read (`isScrollable`, `getBoundsInScreen`) on a node
     * `provider.createAccessibilityNodeInfo(id)` returns for any id, existing or not, always
     * returning `null` for one the provider does not recognise rather than throwing. This scans the
     * id space directly — inelegant, but generic (no Compose-specific type, no assumption about
     * *where* in the tree the container sits) and entirely public API — collecting the *tallest*
     * scrollable node found (the same "biggest region, not a narrow chip row" heuristic an earlier
     * draft used for a proper tree walk), then drives *that* id's scrolling through
     * [AccessibilityNodeProvider.performAction] exactly as the host-level case above already does.
     */
    public fun scrollToEnd(rootView: View): ScrollOutcome {
        val provider = findAccessibilityNodeProvider(rootView)
            ?: error("no AccessibilityNodeProvider anywhere under the root view — nothing to scroll")
        val targetId = findTallestScrollableVirtualViewId(provider) ?: return ScrollOutcome.NothingToScroll
        var steps = 0
        while (steps < MAX_SCROLL_ACTIONS) {
            val moved = provider.performAction(targetId, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
            if (!moved) break
            steps++
        }
        return ScrollOutcome.Scrolled
    }

    /** Breadth-first over the plain [View]/[ViewGroup] tree (not the accessibility tree — that
     * search only becomes possible once a provider is already in hand) for the first descendant
     * (self included) whose own [View.getAccessibilityNodeProvider] is non-null. */
    private fun findAccessibilityNodeProvider(rootView: View): AccessibilityNodeProvider? {
        val queue = ArrayDeque<View>()
        queue.addLast(rootView)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            current.accessibilityNodeProvider?.let { return it }
            if (current is ViewGroup) {
                for (i in 0 until current.childCount) {
                    queue.addLast(current.getChildAt(i))
                }
            }
        }
        return null
    }

    /** [AccessibilityNodeProvider.HOST_VIEW_ID] (`-1`) first (the root itself, on a screen whose
     * whole content is one scrollable region), then every ordinary virtual id up to
     * [MAX_VIRTUAL_VIEW_ID] — Compose's own semantics ids are small, process-lifetime-monotonic
     * integers (a `LayoutNode`-scoped counter, never reset), so even deep into a many-step tour run
     * this scan stays a bounded, sub-second number of cheap in-process map lookups on the provider,
     * never an IPC round trip — confirmed by running the real tour end to end (this package's own
     * report has the timing). */
    private fun findTallestScrollableVirtualViewId(provider: AccessibilityNodeProvider): Int? {
        var bestId: Int? = null
        var bestHeight = 0
        val bounds = android.graphics.Rect()
        for (virtualViewId in AccessibilityNodeProvider.HOST_VIEW_ID..MAX_VIRTUAL_VIEW_ID) {
            val node = provider.createAccessibilityNodeInfo(virtualViewId) ?: continue
            if (node.isScrollable) {
                node.getBoundsInScreen(bounds)
                if (bounds.height() > bestHeight) {
                    bestHeight = bounds.height()
                    bestId = virtualViewId
                }
            }
        }
        return bestId
    }

    /** A generous cap, not a guess at "the end" — the loop above already stops the moment a scroll
     * action reports no further movement; this only bounds a screen whose content genuinely never
     * settles (a defect elsewhere, not this function's to paper over). */
    private const val MAX_SCROLL_ACTIONS = 20

    /** See [findTallestScrollableVirtualViewId]'s own doc comment for why this bound is safe. */
    private const val MAX_VIRTUAL_VIEW_ID = 50_000

    /**
     * R-973 (generalises R-971): a full-tree text/`contentDescription` reading, plus whether any node
     * carries a known placeholder marker — [ScreenshotTourActivity]'s settle compares two of these
     * taken ≥500ms apart, capturing only once they agree and no placeholder is present, rather than
     * trusting the destination/drawer/live-bar/link-state waits alone to prove a screen's own
     * asynchronous data load (`ModelsController`'s own state, named by WPE's own R-973 report, with
     * no "Loading…" text to key on) has actually finished landing. [text] deliberately excludes node
     * bounds — a settled layout's bounds do not move between two checks, so including them would only
     * add noise a real content change does not need to be caught by.
     */
    public data class SemanticsSnapshot(val text: String, val hasPlaceholder: Boolean)

    /** Known placeholder copy this codebase's own screens use while data is still loading (checked
     * across `SessionsContent.kt`/`DigestContent.kt`/`SettingsContent.kt`/others before writing this)
     * — the one textual signal available; there is no structural placeholder marker (a testTag or
     * semantics property) anywhere in this codebase to key on instead. */
    private const val PLACEHOLDER_TEXT_MARKER = "loading"

    /** A separator no real screen's own text is expected to contain, so two genuinely different
     * strings can never collide into the same [SemanticsSnapshot.text] by concatenation alone. */
    private const val SNAPSHOT_SEPARATOR = '\u0001'

    /** Walks the same accessibility bridge [scrollToEnd] does (see this object's own doc comment for
     * why), collecting every node's own `text`/`contentDescription` into one order-stable string —
     * cheap, in-process, bounded the same way [findTallestScrollableVirtualViewId] already is. */
    public fun snapshot(rootView: View): SemanticsSnapshot {
        val provider = findAccessibilityNodeProvider(rootView) ?: return SemanticsSnapshot("", false)
        val builder = StringBuilder()
        var placeholder = false
        for (virtualViewId in AccessibilityNodeProvider.HOST_VIEW_ID..MAX_VIRTUAL_VIEW_ID) {
            val node = provider.createAccessibilityNodeInfo(virtualViewId) ?: continue
            val text = node.text?.toString()
            val description = node.contentDescription?.toString()
            if (text != null) {
                builder.append(text).append(SNAPSHOT_SEPARATOR)
                if (text.contains(PLACEHOLDER_TEXT_MARKER, ignoreCase = true)) placeholder = true
            }
            if (description != null) {
                builder.append(description).append(SNAPSHOT_SEPARATOR)
                if (description.contains(PLACEHOLDER_TEXT_MARKER, ignoreCase = true)) placeholder = true
            }
        }
        return SemanticsSnapshot(builder.toString(), placeholder)
    }
}
