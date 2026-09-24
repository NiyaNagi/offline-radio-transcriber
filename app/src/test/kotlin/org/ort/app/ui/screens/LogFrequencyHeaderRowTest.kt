package org.ort.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.FakeLogFrequencyEditor
import org.ort.app.ui.data.LogFrequencyFacts
import org.ort.app.ui.data.LogFrequencyHeaderMapper
import org.ort.app.ui.data.LogPolling
import org.ort.app.ui.theme.OrtTheme
import org.ort.pipeline.capture.RigStatus
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * R-1167, D58, **AC-202**: the hand-entered frequency's new home, as the operator actually meets
 * it. Every assertion here keys on a stable test tag, never on rendered prose — **R-1160 and
 * R-1070**: this repository has twice shipped a UI check that passed by accidentally matching the
 * navigation drawer's own row list rather than the screen under test, and `LogFrequencyHeaderRow`'s
 * own copy ("Frequency", "Change") is exactly the kind of short string that could do it again.
 *
 * The write path is exercised through [LogContent] with a [FakeLogFrequencyEditor], not through
 * [LogScreen] with a hand-written lambda: what R-1167 is actually about is whether the operator's
 * entry reaches the store `RealCaptureService` reads, and a test that only proved a callback fired
 * would establish nothing about that.
 */
@RunWith(RobolectricTestRunner::class)
class LogFrequencyHeaderRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun resetRigStatus() {
        RigStatus.reset()
    }

    @After
    fun clearRigStatus() {
        RigStatus.reset()
    }

    private fun editor(rigReportedHz: Long? = null, activeManualHz: Long? = null, isCapturing: Boolean = false) =
        FakeLogFrequencyEditor(
            LogFrequencyFacts(
                rigReportedHz = rigReportedHz,
                activeManualHz = activeManualHz,
                pendingManualHz = null,
                hasPendingConfiguration = false,
                isCapturing = isCapturing,
            ),
        )

    /** The whole real content composable, on the true first-launch path (`sessionId = null`, no
     * session has ever started) — which needs no database and is exactly the state D58 leaves a
     * fresh install in now that setup no longer asks. */
    private fun setContent(editor: FakeLogFrequencyEditor, fontScale: Float = 1.0f, widthDp: Int = 390) {
        composeTestRule.setContent {
            val realDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(realDensity, fontScale)) {
                Box(modifier = Modifier.width(widthDp.dp)) {
                    OrtTheme {
                        LogContent(context = context, sessionId = null, onOpen = {}, frequencyEditor = editor)
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Presence and absence.
    // -------------------------------------------------------------------------------------------

    @Test
    @Requirement("AC-202")
    fun `AC_202 the Log header carries the frequency row on a first launch with nothing entered`() {
        setContent(editor())

        composeTestRule.onNodeWithTag(LOG_FREQUENCY_HEADER_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(LOG_FREQUENCY_HEADER_VALUE_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(LOG_FREQUENCY_HEADER_ACTION_TEST_TAG).assertExists()
        // The editor is not open until the operator asks for it.
        composeTestRule.onNodeWithTag(LOG_FREQUENCY_EDITOR_TEST_TAG).assertDoesNotExist()
    }

    @Test
    @Requirement("AC-202", "FR-RIG-8")
    fun `AC_202 no frequency row is rendered at all while a rig is reporting and nothing overrides it`() {
        setContent(editor(rigReportedHz = 146_520_000L))

        composeTestRule.onNodeWithTag(LOG_FREQUENCY_HEADER_TEST_TAG).assertDoesNotExist()
    }

    // -------------------------------------------------------------------------------------------
    // The write path.
    // -------------------------------------------------------------------------------------------

    @Test
    @Requirement("AC-202", "R-1167")
    fun `AC_202 saving a real entry writes exactly what was typed, once, to the capture configuration`() {
        val fake = editor()
        setContent(fake)

        composeTestRule.onNodeWithTag(LOG_FREQUENCY_HEADER_ACTION_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(LOG_FREQUENCY_EDITOR_FIELD_TEST_TAG).performTextInput("145.230")
        composeTestRule.onNodeWithTag(LOG_FREQUENCY_EDITOR_SAVE_TEST_TAG).performClick()

        assertEquals(listOf<Long?>(145_230_000L), fake.saved)
        composeTestRule.onNodeWithTag(LOG_FREQUENCY_EDITOR_TEST_TAG).assertDoesNotExist()
    }

    @Test
    @Requirement("R-1170", "AC-201", "constitution I")
    fun `R_1170 Save stays lit and refuses an unparseable entry rather than writing a fabricated one`() {
        val fake = editor()
        setContent(fake)

        composeTestRule.onNodeWithTag(LOG_FREQUENCY_HEADER_ACTION_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(LOG_FREQUENCY_EDITOR_FIELD_TEST_TAG).performTextInput("one four five")
        // The control is live — clicking it is what produces the refusal, which is the strictly
        // stronger assertion R-1170 asked every inverted `assertIsNotEnabled` to become.
        composeTestRule.onNodeWithTag(LOG_FREQUENCY_EDITOR_SAVE_TEST_TAG).performClick()

        assertTrue("nothing may reach the store from an unparseable entry: ${fake.saved}", fake.saved.isEmpty())
        composeTestRule.onNodeWithTag(LOG_FREQUENCY_EDITOR_VALIDATION_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(LOG_FREQUENCY_EDITOR_TEST_TAG).assertExists()
    }

    @Test
    @Requirement("AC-202", "constitution III")
    fun `AC_202 clearing is its own labelled action and writes a real null`() {
        val fake = editor(activeManualHz = 145_230_000L)
        setContent(fake)

        composeTestRule.onNodeWithTag(LOG_FREQUENCY_HEADER_ACTION_TEST_TAG).performClick()
        // The field opens seeded with what is actually stored, so an untouched save is a no-op.
        composeTestRule.onNodeWithTag(LOG_FREQUENCY_EDITOR_FIELD_TEST_TAG).performTextClearance()
        composeTestRule.onNodeWithTag(LOG_FREQUENCY_EDITOR_CLEAR_TEST_TAG).performClick()

        assertEquals(listOf<Long?>(null), fake.saved)
    }

    @Test
    @Requirement("R-1167", "FR-RIG-8", "constitution I")
    fun `R_1167 with a rig reporting, the only action clears the override and never opens an editor`() {
        val fake = editor(rigReportedHz = 146_520_000L, activeManualHz = 145_230_000L)
        setContent(fake)

        composeTestRule.onNodeWithTag(LOG_FREQUENCY_HEADER_NOTE_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(LOG_FREQUENCY_HEADER_ACTION_TEST_TAG).performClick()

        assertEquals("the override is cleared, never replaced by a typed one", listOf<Long?>(null), fake.saved)
        composeTestRule.onNodeWithTag(LOG_FREQUENCY_EDITOR_TEST_TAG).assertDoesNotExist()
    }

    @Test
    @Requirement("AC-131")
    fun `AC_131 the editor states when a save takes effect, on both the idle and the live path`() {
        setContent(editor(isCapturing = true))
        composeTestRule.onNodeWithTag(LOG_FREQUENCY_HEADER_ACTION_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(LOG_FREQUENCY_EDITOR_NOTE_TEST_TAG).assertExists()
    }

    // -------------------------------------------------------------------------------------------
    // Layout and touch targets (constitution VIII; R-1176).
    // -------------------------------------------------------------------------------------------

    private fun boundsOf(tag: String) = composeTestRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun assertClears44dp(tag: String) {
        val rect = boundsOf(tag)
        val height = rect.bottom - rect.top
        // The same sub-pixel tolerance `CaptureScreenBoundsTest` documents — 420 dpi's px-per-dp
        // ratio does not divide evenly.
        assertTrue("expected $tag to clear the 44dp floor; got $height", height >= 44.dp - 0.5.dp)
    }

    @Test
    @Requirement("constitution VIII", "FR-A11Y-1")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `constitution_VIII at the tour AVD's width the header, chips and editor stack without overlap`() {
        setContent(editor(activeManualHz = 145_230_000L))
        composeTestRule.onNodeWithTag(LOG_FREQUENCY_HEADER_ACTION_TEST_TAG).performClick()

        val header = boundsOf(LOG_FREQUENCY_HEADER_TEST_TAG)
        val editorBounds = boundsOf(LOG_FREQUENCY_EDITOR_TEST_TAG)
        assertTrue("the editor sits inside the header block: $header vs $editorBounds", editorBounds.top >= header.top)
        assertTrue("the header has real height", header.bottom - header.top > 0.dp)
        assertTrue("the header starts inside the viewport", header.left >= 0.dp)
        assertTrue("the header does not run past 390dp: $header", header.right <= 390.dp + 0.5.dp)
    }

    @Test
    @Requirement("constitution VIII", "FR-A11Y-1", "R-1176")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `R_1176 every interactive control in the header clears the 44dp floor at font scale 2_0`() {
        setContent(editor(activeManualHz = 145_230_000L), fontScale = 2.0f)
        composeTestRule.onNodeWithTag(LOG_FREQUENCY_HEADER_ACTION_TEST_TAG).performClick()

        assertClears44dp(LOG_FREQUENCY_HEADER_ACTION_TEST_TAG)
        assertClears44dp(LOG_FREQUENCY_EDITOR_SAVE_TEST_TAG)
        assertClears44dp(LOG_FREQUENCY_EDITOR_CANCEL_TEST_TAG)
        assertClears44dp(LOG_FREQUENCY_EDITOR_CLEAR_TEST_TAG)
        assertClears44dp(LOG_FREQUENCY_EDITOR_FIELD_TEST_TAG)
        val header = boundsOf(LOG_FREQUENCY_HEADER_TEST_TAG)
        assertTrue("the header does not run past 390dp at 2.0: $header", header.right <= 390.dp + 0.5.dp)
    }

    @Test
    @Requirement("AC-202", "R-1051")
    fun `AC_202 the first-launch Log state carries the header only when an editor is supplied`() {
        // `LogPolling.noSessionState()`'s own pre-existing, editor-less contract is unchanged —
        // this is what keeps every caller that predates R-1167 rendering exactly as before.
        assertEquals(null, LogPolling.noSessionState().frequencyHeader)
        assertEquals(
            LogFrequencyHeaderMapper.from(editor().facts()),
            LogPolling.noSessionState(editor()).frequencyHeader,
        )
    }
}
