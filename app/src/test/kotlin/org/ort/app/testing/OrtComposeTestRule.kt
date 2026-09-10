package org.ort.app.testing

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.ort.app.ui.data.DebugLexiconImportOverride
import org.ort.app.ui.data.DebugSearchOverride
import org.ort.app.ui.data.ModelsController

/**
 * idle-root task (2026-09-10, CHANGELOG's own entry has the full account): root-causes the
 * `AppNotIdleException` wedge two earlier sessions ("poison hunt", cb1d8cd; "poison hunt 2",
 * 7ac846b) worked around with `forkEvery` tuning and per-class JVM isolation, never a single
 * confirmed line. CI evidence (run 34444706036: `FrequencyDetailContentTest` PASSED, then
 * *every* test in `LogFilterSheetTest`/`LogScreenTest` — neither of which touches a `Context` or a
 * database at all — failed with the identical `Compose did not get idle` symptom) pinned the
 * mechanism down for the first time: a plain `@After fun closeDatabase() { db.close() }`, on a
 * class that also hosts a `ComposeContentTestRule`, runs **before** that rule's own teardown.
 * JUnit4's `@Rule`s wrap the *entire* `@Before`/`@Test`/`@After` sequence as one `Statement` — the
 * rule's `apply()` sets up before that whole sequence runs and tears down only after it returns —
 * so a bare `@After` always executes while the composition the rule owns is still alive. Closing
 * the database out from under a still-live composition, in the narrow window between the test
 * method returning and the rule's own disposal, is exactly the kind of use-after-close race that
 * can leave Compose's `Recomposer` for that composition in a state it never finishes tearing down
 * — and `ComposeIdlingResource`'s idle check (`RobolectricIdlingStrategy.runUntilIdle`, what both
 * earlier sessions' `jstack` captures caught spinning) is global across every Compose test sharing
 * this JVM fork, not scoped to the test that caused it: once one composition's `Recomposer` is
 * stuck, *every* later Compose test in the same fork — including one that never opens a database,
 * like `LogFilterSheetTest` — can never observe "idle" again.
 *
 * **The fix is ordering, not a new resource.** [ortComposeTestRule] wraps [compose] in a
 * [RuleChain] with the actual database close (and every other process-lifetime reset this module's
 * own tests need — see below) as the *outer* rule, so the sequence becomes: `@Before` opens the
 * database, the test runs, `@After` (if any) runs, [compose]'s own teardown disposes the
 * composition cleanly while the database is still open, and only *then* does this rule's own
 * `after()` close it. A `TestRule` field is easy to add and easy to wire in the wrong order by
 * hand — every Compose test in `:app` that owns a real `OrtDatabase` should build its
 * `@get:Rule` from this function instead of declaring `composeTestRule` and `db.close()`
 * separately, so the ordering is structural (constitution VII) rather than a convention to
 * remember per file.
 *
 * Also resets every other process-lifetime `ui/data` object this module's own tests are known to
 * read or write, for the same reason `RigStatus.reset()` already has to be called by hand in
 * several files: a plain Kotlin `object` is one instance for the life of the JVM fork, not
 * sandboxed per test the way a fresh Robolectric `Application` is, and an unreset value read by an
 * unrelated later test is a real bug independent of whether it happens to wedge Compose's own idle
 * check. [ModelsController.resetForTest] / [DebugSearchOverride.clear] /
 * [DebugLexiconImportOverride.clear] are all safe to call unconditionally, whether or not this
 * test happened to touch them.
 *
 * @param closeDb closes whatever real [org.ort.data.OrtDatabase] this test opened, or `null` for a
 *   class that never opens one — called first, before the process-lifetime resets, so a database
 *   error during close is never masked by an unrelated reset having already run.
 */
public fun <R : ComposeContentTestRule> ortComposeTestRule(compose: R, closeDb: (() -> Unit)? = null): TestRule =
    RuleChain.outerRule(OrtTestStateResetRule(closeDb)).around(compose)

private class OrtTestStateResetRule(private val closeDb: (() -> Unit)?) : ExternalResource() {
    override fun after() {
        closeDb?.invoke()
        ModelsController.resetForTest()
        DebugSearchOverride.clear()
        DebugLexiconImportOverride.clear()
    }
}
